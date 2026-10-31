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
    private static java.util.HashSet<String> netIfaces;
    private static boolean speedTickerStarted;

    /** 只在未启动时启动（Spacing 每帧都会调用） */
    static void ensureTicker(android.content.Context ctx) {
        if (!speedTickerStarted) startSpeedTicker(ctx);
    }

    static void startSpeedTicker(final android.content.Context ctx) {
        if (speedTickerStarted) return;
        speedTickerStarted = true;
        netIfaces = activeIfaces();
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
     * 要统计的接口：只算真正上网的那几张。
     *
     * 不能靠 /proc/net/route —— 里面只列了子网路由，默认路由那行不一定在，
     * 而且虚拟接口（dummy/gre/ifb/sit…）一大堆，全算进去数值会虚高。
     * 这里按名字白名单，并跳过隧道外的虚拟设备。
     */
    private static java.util.HashSet<String> activeIfaces() {
        java.util.HashSet<String> set = new java.util.HashSet<>();
        try {
            String[] names = new java.io.File("/sys/class/net").list();
            if (names == null) return set;
            for (String n : names) {
                if (n.equals("lo")) continue;
                if (n.startsWith("dummy") || n.startsWith("ifb") || n.startsWith("gre")
                        || n.startsWith("ip6") || n.startsWith("ip_vti") || n.startsWith("sit")
                        || n.startsWith("erspan") || n.startsWith("tunl") || n.startsWith("p2p")
                        || n.startsWith("wifi-aware") || n.startsWith("ovnet")
                        || n.startsWith("r_rmnet")) continue;
                if (n.startsWith("wlan") || n.startsWith("rmnet_data")
                        || n.startsWith("eth") || n.equals("tun0")) {
                    set.add(n);
                }
            }
        } catch (Throwable ignore) {}
        return set;
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

    /** 已捕获的网速 View（主状态栏 / 下拉栏各一份） */
    private static final java.util.WeakHashMap<Object, Boolean> SPEED_VIEWS =
            new java.util.WeakHashMap<>();


    /** 记住出现过的 NetworkSpeedView */
    static void rememberSpeedView(View v) {
        String id = Engine.entryName(v);
        if (id != null && id.toLowerCase().contains("network_speed")) {
            SPEED_VIEWS.put(v, Boolean.TRUE);
        }
    }

    /** 每秒把每份网速 View 的当前状态重新 apply 一次，触发重绘 */
    private static void repaintSpeedViews() {
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
            } catch (Throwable ignore) {}
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
