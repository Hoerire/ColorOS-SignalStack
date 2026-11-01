package io.github.hoerire.stacksig;

import android.content.Context;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 网速指示器：自己采样、自己刷新。
 *
 * 原厂网速不准的根因是「只压短了重发间隔，没改差分窗口」——
 * 分母忽大忽小，瞬时抖动被放大，实测 1.2 KB/s 会显示成 7.00。
 *
 * 这里不再碰它的定时器，而是接管数值与刷新：
 *   1. 每秒用 TrafficStats 读累计字节，按真实时间差算速率（B/s）；
 *   2. hook NetworkSpeedIconState.getSpeedText() 返回我们的值；
 *   3. 每秒主动调一次 NetworkSpeedView.applyNetworkState，让显示跟上真实值。
 *
 * 单位换算、KB/s 与 MB/s 切换、小数位、显隐仍交由原厂处理。
 */
final class Speed {

    private Speed() {}

    // ==================== 网速：自己算，接管数值 ====================

    /**
     * 原厂网速不准的原因：我们之前只把「重发消息的时间点」从 4 秒压到 1 秒，
     * 但没改差分窗口 —— 分母忽大忽小，瞬时抖动被放大，实测 1.2 KB/s 会显示成 7.00。
     *
     * 现在的做法：不再碰它的定时器，改为**直接替换数值来源**。
     * hook `NetworkSpeedIconState.getSpeedText()`（原厂返回 字节/秒），
     * 返回我们按真实时间差算出来的速率。单位换算、格式化、显示/隐藏全交给原厂。
     */
    private static volatile long OUR_SPEED_BPS = 0;
    private static long lastRx, lastTx, lastNetMs;
    private static boolean speedTickerStarted;

    /** 只在未启动时启动（Spacing 每帧都会调用） */
    static void ensureTicker(android.content.Context ctx) {
        if (!speedTickerStarted) startSpeedTicker(ctx);
    }

    static void startSpeedTicker(final android.content.Context ctx) {
        if (speedTickerStarted) return;
        speedTickerStarted = true;
        sampleSpeed(true);                       // 建立基线
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try { sampleSpeed(false); } catch (Throwable ignore) {}
                // 原厂 4 秒才刷一次 TextView，我们算得再快也看不出来。
                // 这里每秒主动把当前状态重新 apply 一次，显示就跟上真实值了。
                try { repaintSpeedViews(); } catch (Throwable ignore) {}
                h.postDelayed(this, 1000);
            }
        }, 1000);
    }

    /**
     * 读累计收发字节 —— **只统计物理出口接口**。
     *
     * 不能用 TrafficStats.getTotalRxBytes()：它把所有接口相加。
     * 走 VPN 或本地代理时，同一份数据会先经 tun0 再经 wlan0，被算两遍 ——
     * 实测下载 1.14 MB/s 时会读成 2.33 MB/s，正好翻倍。
     *
     * 这里遍历 /proc/net/dev，只累加真正跑在空口上的设备
     * （wlan0 / rmnet_data* / eth*），tun0、lo、ifb、dummy、gre、ovnet 一律跳过。
     * 隧道只是内部转发，数据最终都要从物理网卡出去，所以算物理口才不会重复计。
     */
    private static long[] readBytes() {
        long rx = 0, tx = 0;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/net/dev"));
            String line;
            br.readLine(); br.readLine();
            while ((line = br.readLine()) != null) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String name = line.substring(0, c).trim();
                if (!isPhysicalIface(name)) continue;
                String[] f = line.substring(c + 1).trim().split("\\s+");
                if (f.length < 9) continue;
                rx += Long.parseLong(f[0]);
                tx += Long.parseLong(f[8]);
            }
            br.close();
        } catch (Throwable ignore) {}
        return new long[]{rx, tx};
    }

    /** 物理出口接口：WiFi / 蜂窝 / 有线。其余（隧道、回环、虚拟网桥）都不算。 */
    private static boolean isPhysicalIface(String name) {
        if (name == null) return false;
        if (name.startsWith("wlan")) return true;
        if (name.startsWith("rmnet_data")) return true;   // 蜂窝数据（rmnet_ipa0 是汇聚口，不计）
        if (name.startsWith("eth")) return true;
        return false;
    }

    private static void sampleSpeed(boolean baseline) {
        long now = android.os.SystemClock.uptimeMillis();
        long[] b = readBytes();
        if (baseline || lastNetMs == 0) {
            lastRx = b[0]; lastTx = b[1]; lastNetMs = now;
            OUR_SPEED_BPS = 0;
            return;
        }
        long dt = now - lastNetMs;
        if (dt < 200) return;                    // 太短的分母不可信
        long d = (b[0] - lastRx) + (b[1] - lastTx);
        if (d < 0) d = 0;                        // 计数器回绕
        OUR_SPEED_BPS = d * 1000L / dt;          // B/s
        lastRx = b[0]; lastTx = b[1]; lastNetMs = now;
    }

    /** 见过的网速格最大宽度：宽度被系统算成 0 时用它钉回去 */
    private static volatile int SPEED_CELL_W = 0;

    static int cellWidth() { return SPEED_CELL_W; }

    /** 已捕获的网速 View（主状态栏 / 下拉栏各一份） */
    private static final java.util.WeakHashMap<Object, Boolean> SPEED_VIEWS =
            new java.util.WeakHashMap<>();

    /** 记住出现过的 NetworkSpeedView */
    static void rememberSpeedView(View v) {
        String id = Engine.entryName(v);
        if (id != null && id.toLowerCase().contains("network_speed")) {
            boolean isNew = !SPEED_VIEWS.containsKey(v);
            SPEED_VIEWS.put(v, Boolean.TRUE);
            if (isNew) Engine.dbg("speed register h" + Integer.toHexString(System.identityHashCode(v))
                    + " shown=" + v.isShown() + " w=" + v.getWidth());
        }
    }

    /** 清掉已经 detach 的旧实例，避免表越滚越大（实测切几次能从 4 涨到 20） */
    private static void pruneSpeedViews() {
        try {
            java.util.Iterator<Object> it = SPEED_VIEWS.keySet().iterator();
            while (it.hasNext()) {
                Object k = it.next();
                if (!(k instanceof View) || !((View) k).isAttachedToWindow()) it.remove();
            }
        } catch (Throwable ignore) {}
    }

    private static int countShown() {
        int n = 0;
        for (Object key : SPEED_VIEWS.keySet()) {
            if (key instanceof View && ((View) key).isShown()) n++;
        }
        return n;
    }

    /** 每秒把每份网速 View 的当前状态重新 apply 一次，触发重绘 */
    private static void repaintSpeedViews() {
        // 每秒都重新扫一遍窗口里的网速实例：切卡后系统会新建一份，
        // 只依赖“发现没有可见实例才扫”会漏掉「有可见实例但不是我们驱动的那份」。
        Engine.rescanSpeedViews();
        pruneSpeedViews();
        int visible = countShown();
        int applied = applyAll();
        // 一个可见的都没有 → 手里的都是旧实例：立刻重扫登记，并**在同一轮**把新实例补上，
        // 否则要等下一秒才亮，看起来就是「网速消失一会儿」。
        if (visible == 0) {
            Engine.rescanSpeedViews();
            applied += applyAll();
            visible = countShown();
        }
        Engine.dbg("speed repaint views=" + SPEED_VIEWS.size() + " shown=" + visible
                + " applied=" + applied + " bps=" + OUR_SPEED_BPS + " live=" + liveSpeedText());
    }

    /** 对表里所有可见实例 apply 一次当前状态，返回成功次数 */
    private static int applyAll() {
        int applied = 0;
        for (Object key : SPEED_VIEWS.keySet()) {
            if (!(key instanceof View)) continue;
            View v = (View) key;
            if (v.getVisibility() != View.VISIBLE) continue;
            try {
                java.lang.reflect.Field fm = null;
                for (Class<?> c = v.getClass(); c != null && fm == null; c = c.getSuperclass()) {
                    try { fm = c.getDeclaredField("mState"); } catch (Throwable ignore) {}
                }
                if (fm == null) continue;
                fm.setAccessible(true);
                Object state = fm.get(v);
                if (state == null) continue;
                java.lang.reflect.Method ap = null;
                for (Class<?> c = v.getClass(); c != null && ap == null; c = c.getSuperclass()) {
                    try { ap = c.getDeclaredMethod("applyNetworkState", state.getClass()); }
                    catch (Throwable ignore) {}
                }
                if (ap == null) continue;
                ap.setAccessible(true);
                ap.invoke(v, state);
                applied++;
                // 文本有了但宽度是 0：这一格被系统重新挂上去后没重新量过（实测 w=0）。
                // 先记下见过的最大宽度，然后直接把宽度钉成它 —— 光 requestLayout 不够，
                // 有些路径不会重走 measure，宽度就一直停在 0，屏幕上就是「网速消失」。
                int wv = v.getWidth();
                if (wv > SPEED_CELL_W) SPEED_CELL_W = wv;
                if (wv == 0 && SPEED_CELL_W > 0) {
                    // 宽度为 0：改 LayoutParams 在多数情况下能把这一格顶回去；
                    // 少数情况下父容器宽度也是 0，只能等系统下一次刷新（≤4s）。
                    android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                    if (lp != null && lp.width != SPEED_CELL_W) {
                        lp.width = SPEED_CELL_W;
                        v.setLayoutParams(lp);
                        Engine.dbg("speed 宽度归零，钉回 " + SPEED_CELL_W + "px");
                    }
                }
            } catch (Throwable ignore) {}
        }
        return applied;
    }

    private static void logRepaint(int total, int visible, int applied) {
        Engine.dbg("speed repaint views=" + total + " shown=" + visible + " applied=" + applied
                + " bps=" + OUR_SPEED_BPS + " live=" + liveSpeedText());
    }

    /** 诊断：把「当前挂着的」网速 view 的宽度/文本/是否在我们表里打出来 */
    private static String liveSpeedText() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Object key : SPEED_VIEWS.keySet()) {
                if (!(key instanceof View)) continue;
                View v = (View) key;
                if (!v.isShown()) continue;
                sb.append("[w=").append(v.getWidth()).append(" h").append(Integer.toHexString(System.identityHashCode(v)));
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c instanceof android.widget.TextView) {
                            sb.append(" '").append(((android.widget.TextView) c).getText()).append('\'');
                        }
                    }
                }
                sb.append(']');
            }
            return sb.length() == 0 ? "(none-shown)" : sb.toString();
        } catch (Throwable t) {
            return "err";
        }
    }

    static int hookSpeedValue(XposedModule m, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.oplus.systemui.statusbar.phone.netspeed.NetworkSpeedIconState",
                    false, cl);
            Method md = c.getDeclaredMethod("getSpeedText");
            m.hook(md).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain ch) throws Throwable {
                    Object r = ch.proceed();
                    try {
                        Engine.dbg("speed getSpeedText orig=" + r + (r == null ? "" : " (" + r.getClass().getSimpleName() + ")"));
                        long orig = (r instanceof Long) ? (Long) r : 0L;
                        // 原厂要隐藏时给负值，这种不要覆盖
                        if (orig < 0) return r;
                        return OUR_SPEED_BPS;
                    } catch (Throwable ignore) {}
                    return r;
                }
            });
            return 1;
        } catch (Throwable t) {
            try { Engine.MODULE.log(android.util.Log.WARN, "StackSig", "speedValue hook failed: " + t); }
            catch (Throwable ignore) {}
            return 0;
        }
    }

}