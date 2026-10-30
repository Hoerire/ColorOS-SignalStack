package io.github.hoerire.stacksig;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * ColorOS 17 双排信号（C16 上的实现）。
 *
 * 原则：
 *  1. 不往系统容器里塞任何 view（StatusIconContainer 会把每个子 view 强转成
 *     StatusIconDisplayable，塞进去必崩）；
 *  2. 只替换 mobile_signal 这个 ImageView 的 drawable，画面全在自己的 drawable 里画；
 *  3. 制式标（2G/3G/4G/5G）、主卡大信号、副卡小信号都在同一张 drawable 里，
 *     所以永远同高、永远可见；WiFi 连上时制式标不画（宽度自动回收）。
 */
public final class Engine {

    private static final String KEY = "mobile_signal";

    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
    private static final WeakHashMap<ImageView, Drawable> ORIG = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, String> ICON = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, String> LAST = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, Boolean> SITES = new WeakHashMap<>();
    private static final WeakHashMap<Object, Integer> LEVELS = new WeakHashMap<>();
    private static final java.util.HashSet<Class<?>> LEVEL_HOOKED = new java.util.HashSet<>();

    private static XposedModule MODULE;
    private static boolean netCallbackInstalled;

    private Engine() {}

    public static int install(XposedModule m, ClassLoader cl) {
        MODULE = m;
        Res17.init(m);
        int ok = 0;
        ok += hook(m, "setImageDrawable", new Class<?>[]{Drawable.class});
        ok += hook(m, "setImageResource", new Class<?>[]{int.class});
        ok += hook(m, "setImageBitmap", new Class<?>[]{Bitmap.class});
        ok += hookClockColor(m);
        ok += hookDarkChanged(m, cl);
        ok += hookVisibility(m);
        // 颜色交给系统（见 VStack 注释），这里不再干预
        ok += hookNetSpeedInterval(m);
        return ok;
    }

    /**
     * 系统真正给状态栏图标上色走的是 StatusBarIconView.onDarkChanged(area, darkIntensity, ...)。
     * 在这之后立刻把我们的 drawable 重建，颜色就跟系统完全同步了。
     */
    private static int hookDarkChanged(XposedModule m, ClassLoader cl) {
        try {
            Class<?> c = null;
            String[] cands = {
                    "com.android.systemui.statusbar.StatusBarIconView",
                    "com.android.systemui.statusbar.phone.StatusBarIconView",
            };
            for (String cn : cands) {
                try { c = Class.forName(cn, false, cl != null ? cl : Engine.class.getClassLoader()); break; }
                catch (Throwable ignore) {}
            }
            if (c == null) return 0;
            Method md = null;
            for (Method m2 : c.getDeclaredMethods()) {
                if (m2.getName().equals("onDarkChanged") && m2.getParameterTypes().length == 3) { md = m2; break; }
            }
            if (md == null) return 0;
            m.hook(md).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain ch) throws Throwable {
                    Object r = ch.proceed();
                    try {
                        Object d0 = ch.getArg(1);
                        if (d0 instanceof Float) applyFallbackDark((Float) d0);
                    } catch (Throwable ignore) {}
                    try {
                        Object t = ch.getThisObject();
                        if (t instanceof ImageView) {
                            ImageView iv = (ImageView) t;
                            if (SITES.containsKey(iv)) {
                                Drawable d = iv.getDrawable();
                                if (d != null) d.invalidateSelf();
                                iv.post(new Runnable() {
                                    @Override public void run() { stack((ImageView) iv); }
                                });
                            }
                        }
                    } catch (Throwable ignore) {}
                    return r;
                }
            });
            Log.d("hook OK onDarkChanged");
            return 1;
        } catch (Throwable t) {
            Log.d("hook FAIL onDarkChanged " + t);
            return 0;
        }
    }

    /**
     * 系统给状态栏图标重新上色（切主题、换壁纸、进锁屏）时会调用 setImageTintList /
     * setColorFilter。挂在这里就能在系统上色的同一帧把我们的 drawable 也重绘一次，
     * 不用等兜底 ticker —— 颜色跟系统同步，没有半拍延迟。
     */
        /** 系统给了明确颜色：直接用它，并等下一帧再重绘（读时钟色更准） */
            /** 时钟文字色一变（状态栏图标换色），立刻重算我们的图标 */
    private static int hookClockColor(XposedModule m) {
        try {
            Method md = android.widget.TextView.class.getDeclaredMethod("setTextColor", int.class);
            m.hook(md).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain c) throws Throwable {
                    Object r = c.proceed();
                    try {
                        Object t = c.getThisObject();
                        if (t instanceof android.widget.TextView) {
                            String n = entryName((View) t);
                            if (n != null && n.contains("clock")) {
                                refreshAll();
                            }
                        }
                    } catch (Throwable ignore) {}
                    return r;
                }
            });
            Log.d("hook OK TextView.setTextColor");
            return 1;
        } catch (Throwable t) {
            Log.d("hook FAIL setTextColor " + t);
            return 0;
        }
    }

    /**
     * 全局 hook View.setVisibility。
     *
     * 收起副卡那一份时，系统（尤其在重建状态栏、展开控制中心时）会把它的可见性改回
     * VISIBLE，光靠 setImageDrawable 那条 hook 拦不住 —— 结果就是两个信号图标并排。
     * 这里在每次可见性变化后检查祖先是否被标记隐藏，是就按回去。
     */
    /**
     * 系统给状态栏图标重设 tint 时（收起通知面板、回桌面、切深浅色）立刻重绘我们自己的
     * drawable —— 只借这个时机刷新，不采纳它的颜色值（颜色源仍然是网速文字色）。
     * 没有这条，就要等兜底 ticker，肉眼就是「白了一下才变黑」。
     */
        /**
     * 原厂换色链路（反射出来的真实签名）：
     *   StatusBarIconView.setIconColor(int, boolean)
     *   StatusBarIconView.setColorInternal(int)
     *   StatusBarIconView.setDecorColor(int)
     *   StatusBarIconView.onDarkChanged(ArrayList, float, int)
     * 真正把颜色落到图标上的就是 setIconColor / setColorInternal。
     * 挂在这里 = 和系统同一调用栈里换色，不存在「晚一拍」。
     */
    private static volatile int SYSTEM_ICON_COLOR = 0;

            /** 系统刚给的图标色：记下来，并立刻刷到我们自己的 drawable 上 */
        /** 把原厂相关类的真实方法签名 dump 出来，照着它 hook，别再猜 */
    private static int hookVisibility(XposedModule m) {
        try {
            Method md = View.class.getDeclaredMethod("setVisibility", int.class);
            m.hook(md).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain c) throws Throwable {
                    Object r = c.proceed();
                    try {
                        if (Boolean.TRUE.equals(BUSY.get())) return r;
                        Object t = c.getThisObject();
                        if (!(t instanceof View)) return r;
                        if (HIDDEN.isEmpty()) return r;
                        View hid = hiddenAncestor((View) t);
                        if (hid != null && hid.getVisibility() != View.GONE) {
                            BUSY.set(Boolean.TRUE);
                            try { forceHidden(hid); } finally { BUSY.set(Boolean.FALSE); }
                        }
                    } catch (Throwable ignore) {}
                    return r;
                }
            });
            Log.d("hook OK setVisibility");
            return 1;
        } catch (Throwable t) {
            Log.d("hook FAIL setVisibility " + t);
            return 0;
        }
    }

    private static int hook(XposedModule m, String name, Class<?>[] sig) {
        try {
            Method md = ImageView.class.getDeclaredMethod(name, sig);
            m.hook(md).intercept(new H());
            Log.d("hook OK " + name);
            return 1;
        } catch (Throwable t) {
            Log.d("hook FAIL " + name + " -> " + t);
            return 0;
        }
    }

    static final class H implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain c) throws Throwable {
            Object r = c.proceed();
            if (Boolean.TRUE.equals(BUSY.get())) return r;
            try {
                Object t = c.getThisObject();
                if (!(t instanceof View)) return r;
                View hid = hiddenAncestor((View) t);
                if (hid != null) { forceHidden(hid); return r; }
                if (!(t instanceof ImageView)) return r;
                ImageView iv = (ImageView) t;
                String n = entryName(iv);
                if (n == null) return r;

                if (SEEN.size() < 40 && n.contains("signal") && SEEN.add(n)) {
                    Log.d("seen view id-name=" + n + " cls=" + iv.getClass().getSimpleName());
                }
                if (n.contains(KEY)) {
                    if ("setImageResource".equals(c.getExecutable().getName())) {
                        Object arg = c.getArg(0);
                        if (arg instanceof Integer) {
                            try { ICON.put(iv, iv.getResources().getResourceEntryName((Integer) arg)); }
                            catch (Throwable ignore) {}
                        }
                    }
                    Drawable d = iv.getDrawable();
                    if (d != null && !(d instanceof VStack)) ORIG.put(iv, d);
                    SITES.put(iv, Boolean.TRUE);
                    ensureNetworkCallback(iv.getContext());
                    final ImageView ivf = iv;
                    iv.post(new Runnable() {
                        @Override public void run() {
                            try {
                                stack(ivf);
                                if (!(ivf.getDrawable() instanceof VStack)) {
                                    // 容器还没量好：布局完成的瞬间再试一次，不必等定时器
                                    ivf.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                                        @Override public void onLayoutChange(View v, int l, int t, int rr, int b,
                                                                             int ol, int ot, int or, int ob) {
                                            v.removeOnLayoutChangeListener(this);
                                            try {
                                                if (v instanceof ImageView
                                                        && !(((ImageView) v).getDrawable() instanceof VStack)) {
                                                    stack((ImageView) v);
                                                }
                                            } catch (Throwable ignore) {}
                                        }
                                    });
                                    retry(ivf, 3);
                                }
                            } catch (Throwable ignore) {}
                        }
                    });
                } else if (n.contains("inout")) {
                    // 上传/下载箭头一律撤掉（制式标已画进信号图标）
                    hideHard(iv);
                } else if (n.contains("wifi")) {
                    // 断/连 WiFi 时系统会刷新 wifi 图标，顺势让信号图标重算
                    refreshAll();
                }
            } catch (Throwable ignore) {}
            return r;
        }
    }

    /**
     * 重启后系统很早就把图标设上来了，那时容器还没量好尺寸，stack() 会失败一次，
     * 之后要等网络变化才有下一次机会 —— 表现就是「重启后好久才变成堆叠」。
     * 这里做有限次重试，最多 10 次、间隔 400ms。
     */
    private static final int[] RETRY_DELAYS = {0, 32, 64, 120, 200, 320, 500, 800, 1200, 1800, 2500};

    private static void retry(final ImageView iv, final int n) {
        if (n >= RETRY_DELAYS.length || iv == null) return;
        iv.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (iv.getDrawable() instanceof VStack) return;
                    stack(iv);
                    if (!(iv.getDrawable() instanceof VStack)) retry(iv, n + 1);
                } catch (Throwable ignore) {}
            }
        }, RETRY_DELAYS[n]);
    }

    /**
     * 网速指示器的刷新周期默认 1000ms（OplusNetworkSpeedControllerExImpl 用 Handler 自己排）。
     * 把来自 netspeed 包的定时调用改成 500ms。
     *
     * 只对 delay 落在 900~1100ms 的调用做处理，其它一律原样放行，开销可以忽略。
     */
    // 500ms 采样会让数字在 0 与真实值之间来回跳（原厂 4 秒窗口是为了平滑），
    // 取 1 秒：比原厂灵敏 4 倍，同时不至于读到瞬时 0
    private static final long NETSPEED_INTERVAL = 1000L;

    /** 调用栈里有没有 netspeed 包（只认它的排期，别的 4 秒定时不动） */
    private static boolean fromNetSpeed() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (int i = 1; i < st.length && i < 24; i++) {
                if (st[i].getClassName().contains("netspeed")) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

        private static int hookNetSpeedInterval(XposedModule m) {
        // Handler 的所有排期最终都汇到 sendMessageAtTime(Message, long)。
        // 只拦这一处，就能覆盖 postDelayed / sendEmptyMessageDelayed / postAtTime 全部写法。
        try {
            Method md = android.os.Handler.class.getDeclaredMethod(
                    "sendMessageAtTime", android.os.Message.class, long.class);
            m.hook(md).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain c) throws Throwable {
                    try {
                        Object w = c.getArg(1);
                        if (w instanceof Long) {
                            long when = (Long) w;
                            long d = when - android.os.SystemClock.uptimeMillis();
                            // 实测原厂采样周期是 4 秒（getTotalByte dt≈4000），不是 1 秒；
                            // 两档都接住，统一改成 500ms
                            boolean hit = (d >= 800 && d <= 1200) || (d >= 3500 && d <= 4500);
                            if (hit && fromNetSpeed()) {
                                android.os.Handler h = (android.os.Handler) c.getThisObject();
                                android.os.Message msg = (android.os.Message) c.getArg(0);
                                if (DBG.size() < 400 && DBG.add("nd" + d)) {
                                    Log.d("netspeed reschedule " + d + " -> " + NETSPEED_INTERVAL
                                            + " " + callerHint());
                                }
                                return h.sendMessageAtTime(msg,
                                        android.os.SystemClock.uptimeMillis() + NETSPEED_INTERVAL);
                            }
                        }
                    } catch (Throwable ignore) {}
                    return c.proceed();
                }
            });
            Log.d("hook OK Handler.sendMessageAtTime(netspeed)");
            return 1;
        } catch (Throwable t) {
            Log.d("hook FAIL sendMessageAtTime " + t);
            return 0;
        }
    }

        private static String callerHint() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (int i = 2; i < st.length && i < 8; i++) {
                if (st[i].getClassName().contains("netspeed")) return st[i].getClassName() + "." + st[i].getMethodName();
            }
        } catch (Throwable ignore) {}
        return "?";
    }

    /** 主入口：找容器 → 取主/副卡 → 合成 */
    private static void stack(ImageView iv) {
        ViewGroup container = null;
        View node = iv;
        for (int depth = 0; depth < 8; depth++) {
            ViewParent p = node.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup par = (ViewGroup) p;
            int cnt = 0;
            for (int i = 0; i < par.getChildCount(); i++) {
                if (findSignal(par.getChildAt(i)) != null) cnt++;
            }
            if (cnt >= 2) { container = par; break; }
            node = par;
        }
        // 单卡：没有"含 2 个信号"的容器，退而找含 1 个信号的（就是信号组本身）
        if (container == null) {
            node = iv;
            for (int depth = 0; depth < 8; depth++) {
                ViewParent p = node.getParent();
                if (!(p instanceof ViewGroup)) break;
                ViewGroup par = (ViewGroup) p;
                if (findSignal(par) != null) { container = par; break; }
                node = par;
            }
        }
        if (container == null) { Log.d("skip: no container"); retry(iv, 1); return; }

        View primaryRoot = null;
        View secondRoot = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View ch = container.getChildAt(i);
            if (findSignal(ch) == null) continue;
            if (primaryRoot == null) primaryRoot = ch;
            else if (secondRoot == null) { secondRoot = ch; break; }
        }
        if (primaryRoot == null) { Log.d("skip: no primaryRoot"); retry(iv, 1); return; }
        // 双卡时只有"主卡那一层"负责合成；单卡（secondRoot==null）不需要这个限制，
        // 否则下拉通知栏里的那份实例会被误判掉。
        if (secondRoot != null && !isSelfOrDescendant(primaryRoot, iv)) return;

        ImageView main = findSignal(primaryRoot);
        ImageView sec = secondRoot == null ? null : findSignal(secondRoot);
        if (main == null) { Log.d("skip: main null"); return; }

        applyWifiRule(main);

        Drawable a = ORIG.get(main);
        if (a == null) a = main.getDrawable();
        if (a instanceof VStack) a = ORIG.get(main);
        Drawable b = null;
        if (sec != null) {
            b = sec.getDrawable();
            if (b instanceof VStack) b = ORIG.get(sec);
        }
        if (a == null || (sec != null && b == null)) {
            Log.d("skip: a=" + (a != null) + " b=" + (b != null) + " sec=" + (sec != null));
            return;
        }

        boolean wifi = isWifiConnected(main.getContext());
        int lp = levelOfView(main, a);
        int ls = sec == null ? -1 : levelOfView(sec, b);
        // 下拉栏那份实例的图标不是用 setImageResource 设的，取不到资源名 → 等级算成 0。
        // 用上一次已知等级兜底（信号强度与展开/收起无关）。
        if (lp == 0 && lastKnownLevel > 0) lp = lastKnownLevel;
        else if (lp > 0) lastKnownLevel = lp;
        // 副卡那一支被 GONE 之后，某些路径（下拉控制中心重建状态栏）在树里找不到它，
        // 单卡判定会让图标退回满高四格。这里用 TelephonyManager 兜底，双卡状态不丢。
        android.content.Context ctx0 = main.getContext();
        boolean dual = simCount(ctx0) >= 2;
        if (dual) {
            // 副卡那一支被 GONE 之后系统不再给它更新 drawable，view 里的等级会一直停在旧值
            // （表现为卡二永远是「空信号」）。按 subId 直接问基带要真实等级。
            int tl = secondaryLevelBySub(ctx0);
            if (tl >= 0) ls = tl;
        } else {
            ls = -1;
        }
        String sig = lp + "/" + ls + "/" + (wifi ? "w" : "m");
        if (DBG.size() < 300) {
            String hv = Integer.toHexString(System.identityHashCode(iv));
            String hc = Integer.toHexString(System.identityHashCode(container));
            String hp = Integer.toHexString(System.identityHashCode(primaryRoot));
            int hs = secondRoot == null ? 0 : System.identityHashCode(secondRoot);
            if (DBG.add("x" + hv + hp + hs)) {
            }
        }

        String idHash = Integer.toHexString(System.identityHashCode(main));
        if (SEEN.size() < 60 && SEEN.add("chain:" + idHash)) {
            StringBuilder sb = new StringBuilder();
            View cn = main;
            for (int i = 0; i < 5; i++) {
                sb.append('[').append(cn.getClass().getSimpleName())
                  .append(' ').append(entryName(cn))
                  .append(" w=").append(cn.getWidth())
                  .append(" v=").append(cn.getVisibility()).append(']');
                ViewParent p2 = cn.getParent();
                if (!(p2 instanceof View)) break;
                cn = (View) p2;
            }
            Log.d("chain " + idHash + " attach=" + main.isAttachedToWindow() + " " + sb);
        }
        Log.d("compose " + idHash + " sig=" + sig + " w=" + main.getWidth() + " h=" + main.getHeight()
                + " already=" + (main.getDrawable() instanceof VStack));
        boolean already = main.getDrawable() instanceof VStack;
        if (already && sig.equals(LAST.get(main))) {
            if (secondRoot != null) collapse(secondRoot);
            collapseSiblings(container, primaryRoot);
            collapseInnerSignals(primaryRoot, main);
            return;
        }
        // 重建太快会闪：网络类型在 NR/LTE 之间抖动时，250ms 内只重建一次
        long nowMs = android.os.SystemClock.uptimeMillis();
        // 已经有图了才节流（防抖动）；首次合成必须马上做，否则就是肉眼可见的延迟
        if (already && nowMs - lastBuildMs < 250) return;
        lastBuildMs = nowMs;

        LAST.put(main, sig);

        Drawable composed = build(a, b, lp, ls, main, wifi);
        if (composed == null) return;

        BUSY.set(Boolean.TRUE);
        try {
            int padL = Math.round(4 * main.getResources().getDisplayMetrics().density);
            if (main.getPaddingLeft() != padL) {
                main.setPadding(padL, main.getPaddingTop(), main.getPaddingRight(), main.getPaddingBottom());
            }
            MAIN = new java.lang.ref.WeakReference<>(main);
            attachLayoutWatcher(main);
            // 颜色由系统 tint 驱动，不需要逐帧比对
            startColorTicker(main);
            main.setImageDrawable(composed);
            if (secondRoot != null) collapse(secondRoot);
            // 兜底：这个容器里凡是还带信号的兄弟节点（副卡另一份、制式图那一份）全收掉，
            // 免得出现两个信号图标并排
            collapseSiblings(container, primaryRoot);
            // 同一个 combo 内部还有第二份 mobile_signal（副卡），外层容器收不掉它
            collapseInnerSignals(primaryRoot, main);
            tighten(main);
        } finally {
            BUSY.set(Boolean.FALSE);
        }
    }

    /** 用 C17 原素材合成：可选制式标 + 主卡 + 副卡 */
    private static Drawable build(Drawable a, Drawable b, int lp, int ls, ImageView main, boolean wifi) {
        String family = "lte";
        Drawable pa;
        if (ls < 0) {
            // 单卡：用 C17 的 single 素材（整格满高），而不是 stacked 的上半部分
            pa = Res17.get("ic_s17_single_" + Math.max(0, Math.min(4, lp)));
            if (pa == null) pa = Res17.get(Res17.primaryName(family, lp));
        } else {
            pa = Res17.get(Res17.primaryName(family, lp));
        }
        Drawable pb = ls < 0 ? null : Res17.get(Res17.secondaryName(family, ls));
        if (pa == null || (ls >= 0 && pb == null)) return new StackDraw(a, b);

        String badgeName = wifi ? null : badgeNameFor(main.getContext());
        if (badgeName != null) Log.d("badge type -> " + badgeName);
        Drawable badge = badgeName == null ? null : Res17.get(badgeName);

        VStack v = new VStack(pa, pb, main.getImageTintList(), badge);
        CUR_VS = new java.lang.ref.WeakReference<>(v);
        v.setHost(main);
        if (FALLBACK_DARK >= 0f) v.setFallbackDark(FALLBACK_DARK);
        try {
            // 高度参照网速那一格（两行数字+单位的高度），视觉上跟网速齐平；
            // 拿不到再退回电池图标
        } catch (Throwable ignore) {}

        return v;
    }

    /** 永久隐藏信号旁边的小制式图（制式标已画进图标） */
    /** 按当前数据网络制式选制式标（2G/3G/4G/5G/5GA） */
    private static String badgeNameFor(android.content.Context ctx) {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            if (tm != null) {
                int t = tm.getDataNetworkType();
                switch (t) {
                    case 20:  // NR
                        return "ic_s17_5g";
                    case 13:  // LTE
                        return "ic_s17_4g";
                    case 3: case 9: case 10: case 11: case 15: case 17:
                        return "ic_s17_3g";
                    case 1: case 2: case 4: case 7: case 16:
                        return "ic_s17_2g";
                    default:
                        break;
                }
            }
        } catch (Throwable ignore) {}
        return "ic_s17_5g";
    }

    private static void applyWifiRule(ImageView signal) {
        try {
            // 从信号 view 往上 3 层，把这一子树里所有制式小图/箭头都隐藏
            View node = signal;
            for (int i = 0; i < 4; i++) {
                hideTypeAndInout(node, 0);
                ViewParent p = node.getParent();
                if (!(p instanceof ViewGroup)) break;
                node = (View) p;
            }
        } catch (Throwable ignore) {}
    }

    private static void hideTypeAndInout(View v, int depth) {
        if (depth > 6 || v == null) return;
        String n = v.getId() != View.NO_ID ? entryName(v) : null;
        if (n != null && (n.contains("mobile_type") || n.contains("inout"))) {
            hideHard(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) hideTypeAndInout(g.getChildAt(i), depth + 1);
        }
    }

    /**
     * 彻底不占位地藏掉一个 view。
     *
     * 不能用 GONE：数据活动时系统会把 inout 箭头重新设成 VISIBLE，一占位就把左边的
     * 网速顶出去，下一次刷新再收回来 —— 观感就是「顶一下又回弹」。这里把宽高压成 0
     * 并保持 INVISIBLE，系统再怎么改可见性也不会占任何空间。
     */
    static void hideHard(View v) {
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && (lp.width != 0 || lp.height != 0)) {
                lp.width = 0;
                lp.height = 0;
                v.setLayoutParams(lp);
            }
            if (v.getVisibility() != View.INVISIBLE) v.setVisibility(View.INVISIBLE);
        } catch (Throwable ignore) {}
    }

    private static int lastKnownLevel = -1;
    private static final java.util.HashSet<String> SEEN = new java.util.HashSet<>();
    private static final java.util.HashSet<String> DBG = new java.util.HashSet<>();
    private static boolean tickerStarted;

    /**
     * 状态栏换色时系统不一定通知到我们（时钟会变但我们收不到回调），
     * 所以起一个轻量 ticker：只做 invalidateSelf，让 draw() 里的 syncTint 去读时钟色。
     * 不重建 drawable、不请求布局，成本很低。
     */
    private static void startColorTicker(final View any) {
        if (tickerStarted) return;
        tickerStarted = true;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    for (ImageView v : SITES.keySet()) {
                        if (v == null) continue;
                        Drawable d = v.getDrawable();
                        if (d != null) d.invalidateSelf();
                    }
                } catch (Throwable ignore) {}
                h.postDelayed(this, 1000);
            }
        }, 1500);
        Log.d("color ticker started 150ms");
    }   // 强引用，抓到就记住
    private static java.lang.ref.WeakReference<VStack> CUR_VS;
    static final float BADGE_X_FRAC = 0.105f;      // 5G 左边界（与 VStack 默认一致）
    private static java.lang.ref.WeakReference<ImageView> MAIN;

    // ---------- 视图工具 ----------

    static String entryName(View v) {
        try {
            if (v.getId() == View.NO_ID) return null;
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return null;
        }
    }

    private static ImageView findSignal(View v) {
        if (v instanceof ImageView) {
            String n = entryName(v);
            if (n != null && (n.contains(KEY) || n.contains("fiveg"))) return (ImageView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ImageView r = findSignal(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isSelfOrDescendant(View root, View target) {
        if (root == target) return true;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (isSelfOrDescendant(g.getChildAt(i), target)) return true;
            }
        }
        return false;
    }

    /** 副卡那组压成 0，容器才会按内容重算间距 */
    private static final java.util.WeakHashMap<View, Boolean> WATCHED = new java.util.WeakHashMap<>();
    private static long lastLayoutPass = 0;
    private static long lastBuildMs = 0;
    private static final java.util.WeakHashMap<ImageView, Long> POSTED = new java.util.WeakHashMap<>();

    /**
     * 让「网速 ↔ 信号」的间距等于「信号 ↔ 电池」的间距：
     * 先量出两个间距，再把网速整体平移差值。
     */
    /**
     * WiFi 的槽位复用：
     *   有 WiFi → 让系统画 WiFi（外观已换成 C17 素材）
     *   没 WiFi → 在同一个 ImageView 上画 5G，并把它所在的 combo 打开
     * 不新增任何 view，只改已有 view 的 drawable / 可见性，所以不会打乱布局。
     */
    /** 从主信号 view 往上找到状态栏容器，再递归找 wifi 图标 */
    private static java.lang.ref.WeakReference<android.widget.TextView> SPEED_TXT;

    /**
     * 网速那一格「视觉高度」：数字行 + 单位行的实际墨迹跨度（不是 View 高度，
     * View 高度含 padding，会比看到的高一倍多）。信号图标按这个值绘制，
     * 才能和网速逐像素齐平；分辨率/显示大小一变，这里跟着变，比例不会跑。
     */
    /** 状态栏网速那块里的 TextView（母容器 id = network_speed） */
    /** 状态栏在屏幕最上面，控制中心里若也有网速副本，取 y 最小的那个 */
    /** 这个 TextView 是不是站在 network_speed 那格里 */
    private static ImageView findByKey(View v, String key, int depth) {
        if (depth > 14 || v == null) return null;
        if (v instanceof ImageView) {
            String n = entryName(v);
            if (n != null && n.contains(key)) return (ImageView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ImageView r = findByKey(g.getChildAt(i), key, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 把网速文字往右推，使「网速↔信号」间距等于 12dp */
    /**
     * 网速文字宽度会随数字变化（1.00 / 34.0 / 367），导致整组图标左右跳。
     * 把它的宽度锁定成第一次测到的值并右对齐，位置就固定了。
     */
    /**
     * 逐帧盯着网速文字的颜色。
     *
     * 收起控制中心时，系统换色的方式各版本不一样（setImageTintList / setColorFilter /
     * 直接改 StatusBarIconView 内部字段），只 hook 那几种方法总会漏掉一条路径，
     * 表现出来就是「白一会儿才变黑」。这里在状态栏的 preDraw 里直接读一次网速文字色，
     * 和上次不同就重绘 —— 最多晚一帧（16ms），与系统换色同步。
     */
    private static final java.util.WeakHashMap<ImageView, Boolean> CWATCH = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ImageView, Integer> LASTC = new java.util.WeakHashMap<>();

    /** 系统下发的状态栏明暗强度（0=深色图标，1=浅色图标） */
    private static volatile float DARK_INTENSITY = -1f;

        /** 原厂的状态栏明暗强度：只作为「宿主没有 tint」时的兜底 */
    private static volatile float FALLBACK_DARK = -1f;

    private static void applyFallbackDark(float d) {
        if (d < 0f) return;
        FALLBACK_DARK = d;
        try {
            for (ImageView v : SITES.keySet()) {
                if (v == null) continue;
                Drawable dv = v.getDrawable();
                if (dv instanceof VStack) ((VStack) dv).setFallbackDark(d);
            }
            ImageView m = MAIN == null ? null : MAIN.get();
            if (m != null) {
                Drawable dv = m.getDrawable();
                if (dv instanceof VStack) ((VStack) dv).setFallbackDark(d);
            }
        } catch (Throwable ignore) {}
    }

    private static final java.util.HashMap<Class<?>, java.lang.reflect.Field> ICON_COLOR_FIELDS = new java.util.HashMap<>();

    /**
     * 直接读原厂 StatusBarIconView 内部的 mIconColor —— 这就是系统给这个图标设的颜色，
     * 和同排其它图标是同一个值，不依赖任何回调时机。
     */
    private static final java.util.WeakHashMap<ImageView, android.widget.TextView> SPEED_BY_MAIN = new java.util.WeakHashMap<>();

    /**
     * 只取「跟这个信号图标同一排」的网速文字。
     *
     * 全屏 App 里下拉控制中心时，窗口树里同时有多份 network_speed（App 自己的状态栏那份
     * 是黑字，控制中心那份是白字），之前按"屏幕最上面"挑，会挑到 App 那份，
     * 结果就是「App 里下拉变黑、桌面下拉正常」。
     * 从 main 往上找兄弟容器，拿到的必然是同一条状态栏里的那一格。
     */
    private static java.lang.ref.WeakReference<ImageView> BAT_REF;

    /**
     * 同一排电池图标当前的 tint —— 这就是系统给整排状态栏图标上的颜色。
     * 逐帧读它比等 setIconColor 回调可靠：展开控制中心时系统未必会重新下发颜色，
     * 但电池图标本身一定会跟着背景翻色。
     */
        /** 系统每次重新布局都会把 translation 冲掉，这里挂个监听重压一次 */
    private static void attachLayoutWatcher(final View main) {
        try {
            if (Boolean.TRUE.equals(WATCHED.get(main))) return;
            WATCHED.put(main, Boolean.TRUE);
            main.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - lastLayoutPass < 90) return;
                            lastLayoutPass = now;
                            try {
                                applyWifiRule((ImageView) main);
                                // 布局完成后重跑一次合成（下拉栏那份实例首帧可能是 0 宽，
                                // 首帧失败后就再没人调用了，这里兜住）
                                if (main instanceof ImageView) stack((ImageView) main);
                            } catch (Throwable ignore) {}
                        }
                    });
        } catch (Throwable ignore) {}
    }

    private static int visibleKids(ViewGroup g) {
        int n = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c != null && c.getVisibility() == View.VISIBLE) n++;
        }
        return n;
    }

    /** 收掉同一 combo 内部除 keep 以外的 signal view（副卡那一份） */
    private static void collapseInnerSignals(View root, ImageView keep) {
        try {
            java.util.ArrayList<ImageView> list = new java.util.ArrayList<>();
            collectSignals(root, list, 0);
            for (int i = 0; i < list.size(); i++) {
                ImageView iv = list.get(i);
                if (iv == keep) continue;
                ViewParent p = iv.getParent();
                collapse(p instanceof View ? (View) p : iv);
            }
        } catch (Throwable ignore) {}
    }

    private static void collectSignals(View v, java.util.List<ImageView> out, int depth) {
        if (depth > 8 || v == null) return;
        if (v instanceof ImageView) {
            String n = entryName(v);
            if (n != null && (n.contains("signal") || n.contains("fiveg"))) {
                out.add((ImageView) v);
                return;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectSignals(g.getChildAt(i), out, depth + 1);
        }
    }

    /** 把容器里除主卡那份以外、还带信号的节点全部收掉 */
    private static void collapseSiblings(ViewGroup container, View primaryRoot) {
        try {
            StringBuilder sb = new StringBuilder("container " + container.getClass().getSimpleName()
                    + " kids=" + container.getChildCount());
            for (int i = 0; i < container.getChildCount(); i++) {
                View ch = container.getChildAt(i);
                boolean hasSig = findSignal(ch) != null;
                sb.append(" [").append(i).append(':').append(entryName(ch))
                  .append(hasSig ? " SIG" : "")
                  .append(" v=").append(ch.getVisibility())
                  .append(" w=").append(ch.getWidth()).append(']');
                if (ch == primaryRoot) continue;
                if (hasSig) collapse(ch);
            }
            if (SEEN.size() < 40 && SEEN.add("kids:" + Integer.toHexString(System.identityHashCode(container)))) {
                Log.d(sb.toString());
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 收起副卡那一路容器：尺寸压 0 + INVISIBLE，但**不能 GONE**。
     *
     * GONE 之后有些路径下系统会把它从树里摘掉（下拉控制中心重建状态栏时尤其明显），
     * 下一次 stack() 就只找到一个 signal，判定成单卡，于是画成满高四格 ——
     * 5G 徽标和副卡小点都没了。留成 INVISIBLE，节点仍在树里，双卡判定才稳定。
     */
    /** 被我们收起来的节点（副卡的 combo / mobile_group）。系统每次刷图标都会改可见性，
     *  所以要在 hook 里持续把它们按回去，光收一次是不够的。 */
    private static final java.util.WeakHashMap<View, Boolean> HIDDEN = new java.util.WeakHashMap<>();

    private static void collapse(View v) {
        if (v == null) return;
        HIDDEN.put(v, Boolean.TRUE);
        forceHidden(v);
    }

    static void forceHidden(View v) {
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && (lp.width != 0 || lp.height != 0)) {
                lp.width = 0;
                lp.height = 0;
                v.setLayoutParams(lp);
            }
        } catch (Throwable ignore) {}
        try {
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        } catch (Throwable ignore) {}
    }

    /** 自己或最近 4 层祖先被标记为隐藏，就返回那个祖先 */
    private static View hiddenAncestor(View v) {
        View cur = v;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (HIDDEN.containsKey(cur)) return cur;
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return null;
    }

    /** 链路改 wrap_content，去掉撑宽度的固定留白 */
    private static void tighten(View v) {
        View cur = v;
        for (int depth = 0; depth < 2; depth++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) p;
            if (g.getClass().getSimpleName().contains("StatusIconContainer")) {
                g.requestLayout();
                return;
            }
            // 只收窄"只装信号图标"的槽位层；一旦这层还装着别的图标（网速/电池/WiFi），
            // 它就是我们和系统共用的容器，改它的宽度会让整排图标跟着重排。
            if (visibleKids(g) > 1) return;
            try {
                ViewGroup.LayoutParams lp = cur.getLayoutParams();
                if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    cur.setLayoutParams(lp);
                }
            } catch (Throwable ignore) {}
            cur = g;
        }
    }

    // ---------- 等级 / 颜色 / 网络 ----------

    /** 活动 SIM 卡数量（双卡布局判定用，不依赖 view 树） */
    private static int simCount(android.content.Context ctx) {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            if (tm != null) {
                int n = tm.getPhoneCount();
                if (n > 0) return n;
            }
        } catch (Throwable ignore) {}
        return 1;
    }

    /** 按订阅号精确取第二张卡的信号等级（0..4）；拿不到返回 -1 */
    private static int secondaryLevelBySub(android.content.Context ctx) {
        try {
            android.telephony.SubscriptionManager sm =
                    android.telephony.SubscriptionManager.from(ctx);
            java.util.List<android.telephony.SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null || list.size() < 2) return -1;
            int subId = list.get(1).getSubscriptionId();
            android.telephony.TelephonyManager tm = ((android.telephony.TelephonyManager)
                    ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE))
                    .createForSubscriptionId(subId);
            android.telephony.SignalStrength ss = tm.getSignalStrength();
            if (ss != null) return Math.max(0, Math.min(4, ss.getLevel()));
        } catch (Throwable ignore) {}
        return -1;
    }

    /** 副卡信号等级（0..4）；拿不到返回 -1 */
    private static int levelOfView(ImageView v, Drawable fallback) {
        String name = ICON.get(v);
        if (name != null) {
            int i = name.length();
            while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
            if (i < name.length()) {
                try { return Math.min(4, Integer.parseInt(name.substring(i))); } catch (Throwable ignore) {}
            }
        }
        Integer lv = LEVELS.get(fallback);
        if (lv != null) return Math.max(0, Math.min(4, lv));
        try { return Math.max(0, Math.min(4, fallback.getLevel())); } catch (Throwable ignore) {}
        return 0;
    }

    /**
     * 状态栏当前的图标颜色。
     *
     * 第一优先是同一排的电池图标 —— 系统给所有状态栏图标设的就是它身上的 tint，
     * 永远和邻居一致。以前只读「clock」文字色，下拉面板展开后最容易被找到的是面板里
     * 那个大时钟（主题红），信号图标就会被染红，跟白电池对不上。
     */
    private static boolean isWifiConnected(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            if (n != null) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (Throwable ignore) {}
        try {
            // 优先看 WifiManager 的连接信息：断连时 rssi 会掉到 -127 左右
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    int rssi = info.getRssi();
                    String ssid = info.getSSID();
                    boolean ssidOk = ssid != null && !ssid.contains("<unknown");
                    if (rssi > -100 && ssidOk) return true;
                    if (rssi <= -100 || !ssidOk) return false;
                }
            }
        } catch (Throwable ignore) {}
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void ensureNetworkCallback(Context ctx) {
        if (netCallbackInstalled || ctx == null) return;
        netCallbackInstalled = true;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerNetworkCallback(new android.net.NetworkRequest.Builder().build(),
                    new android.net.ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(android.net.Network n) { refreshAll(); }
                        @Override public void onLost(android.net.Network n) { refreshAll(); }
                        @Override public void onCapabilitiesChanged(android.net.Network n,
                                android.net.NetworkCapabilities c) { refreshAll(); }
                    });
            Log.d("net callback installed");
        } catch (Throwable t) {
            Log.d("net callback FAIL " + t);
        }
    }

    private static void refreshAll() {
        try {
            // 重绘一次就够：颜色是 draw() 里 syncTint 现读时钟色算出来的，
            // 不需要重建 drawable，也就不会触发布局。
            for (ImageView v : SITES.keySet()) {
                if (v == null) continue;
                Drawable d = v.getDrawable();
                if (d != null) d.invalidateSelf();
            }
            // 不要 LAST.clear()：清了就会无条件重建 drawable 并 requestLayout，
            // 网络能力每秒回调多次时状态栏会被反复重排（网速那格跟着左右顶）。
            // 需要重建的情形（等级/制式/WiFi 变化）在 stack() 里由 sig 自己判断。
            for (final ImageView v : SITES.keySet()) {
                if (v == null) continue;
                v.post(new Runnable() {
                    @Override public void run() {
                        try { stack(v); } catch (Throwable ignore) {}
                    }
                });
            }
        } catch (Throwable ignore) {}
    }
}