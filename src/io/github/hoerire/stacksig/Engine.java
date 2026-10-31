package io.github.hoerire.stacksig;

import android.content.Context;
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

    static XposedModule MODULE;
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
        ok += Speed.hookSpeedValue(m, cl);
        ok += hookSpeedAlign(m, cl);
        ok += Order.hookIconOrder(m, cl);
        ok += Spacing.hookIconGap(m, cl);
        return ok;
    }


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
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 网速数字变宽时整块往左推、右边界不动，这样位数变化不会左右蹦。
     * 容器宽度改 wrap_content 并靠右沉底；两个 TextView 都靠右，
     * 单位文字固定不变，所以看上去只有上面那行在往左长。
     */
    private static int hookSpeedAlign(XposedModule m, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.oplus.systemui.statusbar.phone.netspeed.widget.NetworkSpeedView",
                    false, cl != null ? cl : Engine.class.getClassLoader());
            Method target = null;
            for (Method x : c.getDeclaredMethods()) {
                if (x.getName().equals("applyNetworkState")) { target = x; break; }
            }
            if (target == null) return 0;
            m.hook(target).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain ch) throws Throwable {
                    Object r = ch.proceed();
                    try { alignSpeedCenter((View) ch.getThisObject()); } catch (Throwable ignore) {}
                    return r;
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 网速那一格恢复原厂居中。
     *
     * 原厂 network_speed_view.xml 里两个 TextView 都是 gravity=CENTER，
     * 后来为了让数字变宽时不左右跳，这里把整格改成 END 靠右，副作用是
     * 数字不再居中。现在取消这个改动：宽度仍走 wrap_content（防跳动），
     * 但 gravity 交还给 CENTER，数字便以整格中心为轴。
     */
    private static void alignSpeedCenter(View box) {
        try {
            final int g = android.view.Gravity.CENTER;
            ViewGroup.LayoutParams lp = box.getLayoutParams();
            if (lp != null) {
                boolean dirty = false;
                if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    dirty = true;
                }
                if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                    android.widget.LinearLayout.LayoutParams llp = (android.widget.LinearLayout.LayoutParams) lp;
                    if (llp.gravity != g) { llp.gravity = g; dirty = true; }
                } else if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                    android.widget.FrameLayout.LayoutParams flp = (android.widget.FrameLayout.LayoutParams) lp;
                    if (flp.gravity != g) { flp.gravity = g; dirty = true; }
                }
                if (dirty) box.setLayoutParams(lp);
            }
            if (box instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) box;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View c = vg.getChildAt(i);
                    if (c instanceof android.widget.TextView) {
                        android.widget.TextView tv = (android.widget.TextView) c;
                        if (tv.getGravity() != g) tv.setGravity(g);
                    }
                }
            }
        } catch (Throwable ignore) {}
    }

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
                                // 时钟换色 = 系统刚给状态栏下发新颜色。此刻紧跟在同一调用栈里
                                // 给制式标取色，才能和同排图标同时变色；等 ticker 就是慢半拍。
                                syncAllBadgeColors((android.widget.TextView) t);
                            }
                        }
                    } catch (Throwable ignore) {}
                    return r;
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 全局 hook View.setVisibility。
     *
     * 收起副卡那一份时，系统（尤其在重建状态栏、展开控制中心时）会把它的可见性改回
     * VISIBLE，光靠 setImageDrawable 那条 hook 拦不住 —— 结果就是两个信号图标并排。
     * 这里在每次可见性变化后检查祖先是否被标记隐藏，是就按回去。
    /**
     * 系统给状态栏图标重设 tint 时（收起通知面板、回桌面、切深浅色）立刻重绘我们自己的
     * drawable —— 只借这个时机刷新，不采纳它的颜色值（颜色源仍然是网速文字色）。
        /**
     * 原厂换色链路（反射出来的真实签名）：
     *   StatusBarIconView.setIconColor(int, boolean)
     *   StatusBarIconView.setColorInternal(int)
     *   StatusBarIconView.setDecorColor(int)
     *   StatusBarIconView.onDarkChanged(ArrayList, float, int)
     * 真正把颜色落到图标上的就是 setIconColor / setColorInternal。
     * 挂在这里 = 和系统同一调用栈里换色，不存在「晚一拍」。
     */

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
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hook(XposedModule m, String name, Class<?>[] sig) {
        try {
            Method md = ImageView.class.getDeclaredMethod(name, sig);
            m.hook(md).intercept(new H());
            return 1;
        } catch (Throwable t) {
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
                } else if (n.contains("wifi")) {
                    // 断/连 WiFi 时系统会刷新 wifi 图标，顺势让信号图标重算。
                    // 制式标（5G）要跟着立刻收/放，不能等 ticker 那 2.5 秒 ——
                    // 切 WiFi 时那几秒里 5G 会停在柱子上，看着就是「没反应过来」。
                    // 制式标立刻收/放，但不要 refreshAll：那会让信号图标重建，
                    // 和系统正在做的 WiFi 图标切换挤在同一帧，WiFi 会晚好几秒才出来。
                    pushBadgeLabel(iv.getContext());
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
        if (container == null) { retry(iv, 1); return; }

        View primaryRoot = null;
        View secondRoot = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View ch = container.getChildAt(i);
            if (findSignal(ch) == null) continue;
            if (primaryRoot == null) primaryRoot = ch;
            else if (secondRoot == null) { secondRoot = ch; break; }
        }
        if (primaryRoot == null) { retry(iv, 1); return; }
        // 双卡时只有"主卡那一层"负责合成；单卡（secondRoot==null）不需要这个限制，
        // 否则下拉通知栏里的那份实例会被误判掉。
        if (secondRoot != null && !isSelfOrDescendant(primaryRoot, iv)) return;

        ImageView main = findSignal(primaryRoot);
        ImageView sec = secondRoot == null ? null : findSignal(secondRoot);
        if (main == null) return;

        applyWifiRule(main);
        // 制式标挂在 mobile_combo 上。这里只负责「登记」这个容器，
        // 文本一律由 startBadgeTicker 那条唯一通道写入 —— 两处都写会出现两套刷新互相打架。
        {
            ViewParent cp = main.getParent();
            while (cp instanceof View && !"mobile_combo".equals(entryName((View) cp))) {
                ViewParent up = ((View) cp).getParent();
                if (!(up instanceof View)) { cp = null; break; }
                cp = up;
            }
            if (cp instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) cp;
                boolean fresh = !BADGE_COMBOS.containsKey(g);
                BADGE_COMBOS.put(g, Boolean.TRUE);
                // 新容器（状态栏重建／下拉栏首次出现）先填一次当前值，
                // 否则要等到制式下次变化才有文字。已登记过的完全不碰，避免两套写入口。
                if (fresh) attachBadgeWhenMeasured(g, main.getContext());
            }
        }

        Drawable a = ORIG.get(main);
        if (a == null) a = main.getDrawable();
        if (a instanceof VStack) a = ORIG.get(main);
        Drawable b = null;
        if (sec != null) {
            b = sec.getDrawable();
            if (b instanceof VStack) b = ORIG.get(sec);
        }
        if (a == null || (sec != null && b == null)) {
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
        // sig 只描述「信号本身」：等级、单双卡、WiFi 与否。
        // 制式不再进 sig —— 制式标已经是独立 TextView，由 startBadgeTicker 单独刷新。
        // 把制式塞进来只会让 drawable 因为一个跟它无关的变化白白重建（闪一下）。
        String sig = lp + "/" + ls + "/" + (wifi ? "w" : "m");

        String idHash = Integer.toHexString(System.identityHashCode(main));
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
            // 左右不留额外留白：状态栏靠容器统一间距，自带 padding 会把这一格撑宽、WiFi 被推歪
            if (main.getPaddingLeft() != 0 || main.getPaddingRight() != 0) {
                main.setPadding(0, main.getPaddingTop(), 0, main.getPaddingBottom());
            }
            MAIN = new java.lang.ref.WeakReference<>(main);
            attachLayoutWatcher(main);
            // 颜色由系统 tint 驱动，不需要逐帧比对
            startColorTicker(main);
            startBadgeTicker(main.getContext());
            Speed.startSpeedTicker(main.getContext());
            main.setImageDrawable(composed);
            if (secondRoot != null) collapse(secondRoot);
            // 兜底：这个容器里凡是还带信号的兄弟节点（副卡另一份、制式图那一份）全收掉，
            // 免得出现两个信号图标并排
            collapseSiblings(container, primaryRoot);
            // 同一个 combo 内部还有第二份 mobile_signal（副卡），外层容器收不掉它
            collapseInnerSignals(primaryRoot, main);
            tighten(main);
            // 状态已确定，再补一次箭头位置：WiFi↔移动数据切换时
            // 前面 applyWifiRule 读到的 wifi 标志可能还是旧值，箭头会漏掉一次下移。
            try { dropDataInout(main); } catch (Throwable ignore) {}
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

        // 制式标是独立 TextView（见 attachBadgeText），drawable 里不画
        Drawable badge = null;

        VStack v = new VStack(pa, pb, main.getImageTintList(), badge);
        v.setHost(main);
        if (FALLBACK_DARK >= 0f) v.setFallbackDark(FALLBACK_DARK);
        try {
            // 高度参照网速那一格（两行数字+单位的高度），视觉上跟网速齐平；
            // 拿不到再退回电池图标
        } catch (Throwable ignore) {}

        return v;
    }

    // ==================== 制式标独立 View ====================

    /**
     * 把制式标（5G/4G）做成独立 View，挂进 mobile_combo。
     *
     * 以前画在信号 drawable 的左上角，但 drawable 出不了 View 边界，
     * 5G 永远追不上左侧 24px 处的网络指示器箭头。
     * 现在改成独立 TextView 摆到箭头正上方，两者左缘就能真正对齐。
     *
     * 只往 mobile_combo（普通 ViewGroup）里加，**不碰 StatusIconContainer** ——
     * 那个容器 onMeasure 会把每个子 View 强转成 StatusIconDisplayable，加进去必崩。
    /**
     * 制式标的左偏移（dp）。
     *
     * 只调这一个数就能左右移动制式标，箭头位置完全不动 —— 单向调整才不会两边拉扯。
     * 560dpi 下 1dp = 3.5px。
     */
    private static final float BADGE_LEFT_DP = 2.05f;

    private static final java.util.WeakHashMap<ViewGroup, android.widget.TextView> BADGE_VIEWS =
            new java.util.WeakHashMap<>();

    /** 挂过制式标的 combo，制式一变直接刷它，不必等信号图标重画 */
    private static final java.util.WeakHashMap<ViewGroup, Boolean> BADGE_COMBOS =
            new java.util.WeakHashMap<>();

    /**
     * 制式 → 显示文字。
     *
     * 顺序要紧：先判 5ga 再判 5g，否则 "ic_s17_5ga" 里也含 "5g"，5GA 会被吞成 5G。
     * WiFi 连接时移动数据不跑流量，制式标直接收掉（返回空串）。
    /** 按当前数据网络制式选制式标（2G/3G/4G/5G/5GA） */
    private static String badgeNameFor(android.content.Context ctx) {
        try {
            android.telephony.TelephonyManager tm = dataTelephonyManager(ctx);
            if (tm != null) {
                int t = tm.getDataNetworkType();
                switch (t) {
                    case 20:  // NR
                        return isNrAdvanced(ctx) ? "ic_s17_5ga" : "ic_s17_5g";
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

    /**
     * 取「正在承载数据那张卡」的 TelephonyManager。
     */
    private static android.telephony.TelephonyManager dataTelephonyManager(
            android.content.Context ctx) {
        try {
            int subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            if (subId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                android.telephony.TelephonyManager base =
                        (android.telephony.TelephonyManager) ctx.getSystemService(
                                android.content.Context.TELEPHONY_SERVICE);
                if (base != null) return base.createForSubscriptionId(subId);
            }
        } catch (Throwable ignore) {}
        try {
            return (android.telephony.TelephonyManager) ctx.getSystemService(
                    android.content.Context.TELEPHONY_SERVICE);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 当前 NR 是否已升级到 5G-A（只看数据卡） */
    private static boolean isNrAdvanced(android.content.Context ctx) {
        int subId = -1;
        try {
            subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
        } catch (Throwable ignore) {}
        return overrideNrAdvanced(ctx, subId);
    }

    private static boolean overrideNrAdvanced(android.content.Context ctx, int subId) {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            if (tm == null) return false;
            if (subId > 0) {
                try { tm = tm.createForSubscriptionId(subId); }
                catch (Throwable ignore) {}
            }
            Object di = null;
            try {
                java.lang.reflect.Method m = tm.getClass().getMethod("getTelephonyDisplayInfo");
                di = m.invoke(tm);
            } catch (Throwable ignore) {}
            if (di == null) return false;
            for (java.lang.reflect.Field f : di.getClass().getDeclaredFields()) {
                if (!f.getName().contains("overrideNetworkType")) continue;
                f.setAccessible(true);
                Object v = f.get(di);
                if (!(v instanceof Integer)) continue;
                if ((Integer) v == 5) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    static String badgeLabel(android.content.Context ctx) {
        if (ctx == null) return "";
        if (isWifiConnected(ctx)) return "";
        String n = badgeNameFor(ctx);
        if (n == null) return "";
        if (n.contains("5ga")) return "5GA";
        if (n.contains("5g")) return "5G";
        if (n.contains("4g")) return "4G";
        if (n.contains("3g")) return "3G";
        return "2G";
    }

    /** 制式变化时直接把 label 推给已经挂上的 TextView，绕过 stack() 的去重 */
    private static void pushBadgeLabel(android.content.Context ctx) {
        if (BADGE_COMBOS.isEmpty()) return;
        startBadgeColorTicker();
        String label = badgeLabel(ctx);
        for (final ViewGroup g : BADGE_COMBOS.keySet()) {
            if (g == null) continue;
            // 写文本 —— 这一行是整个制式刷新的核心。
            attachBadgeText(g, label);
            // 系统重排这一格后可能改掉左边距，下一个周期校回来
            final android.widget.TextView tv = BADGE_VIEWS.get(g);
            if (tv != null) {
                tv.postDelayed(new Runnable() {
                    @Override public void run() { enforceBadgeMargin(g, tv); }
                }, 120);
            }
        }
    }

    /** 强制把 5G 的左边距设回目标值（系统重排后可能被改掉） */
    private static void enforceBadgeMargin(ViewGroup combo, android.widget.TextView tv) {
        try {
            ViewGroup.LayoutParams lp = tv.getLayoutParams();
            if (!(lp instanceof android.widget.FrameLayout.LayoutParams)) return;
            android.widget.FrameLayout.LayoutParams f =
                    (android.widget.FrameLayout.LayoutParams) lp;
            int want = Math.round(BADGE_LEFT_DP
                    * tv.getResources().getDisplayMetrics().density);
            if (f.leftMargin != want) {
                f.leftMargin = want;
                tv.setLayoutParams(f);
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 制式标取色。
     *
     * 不能只在创建时算一次：TextView 刚 new 出来时，同排 ImageView 的 tint 往往还是空的
     * （系统要等下一次 onDarkChanged 才刷），算出来就退到写死的黑，深色背景上直接看不见。
     * 这里返回 0 表示「还没拿到有效颜色」，由 ticker 过一会儿再试。
     */
    private static int resolveBadgeColor(ViewGroup combo) {
        try {
            for (int depth = 0; depth < 3; depth++) {
                if (combo == null) break;
                for (int i = 0; i < combo.getChildCount(); i++) {
                    View child = combo.getChildAt(i);
                    if (!(child instanceof ImageView)) continue;
                    android.content.res.ColorStateList t = ((ImageView) child).getImageTintList();
                    if (t != null) return t.getDefaultColor();
                }
                ViewParent up = combo.getParent();
                combo = (up instanceof ViewGroup) ? (ViewGroup) up : null;
            }
        } catch (Throwable ignore) {}
        // 兜底：信号图标自己的 tint（系统给整排设的就是它身上的值）
        try {
            ImageView sig = MAIN == null ? null : MAIN.get();
            if (sig != null) {
                android.content.res.ColorStateList t = sig.getImageTintList();
                if (t != null) return t.getDefaultColor();
            }
        } catch (Throwable ignore) {}
        return 0;
    }

    private static void syncBadgeColor(android.widget.TextView tv, ViewGroup combo) {
        try {
            int c = resolveBadgeColor(combo);
            if (c == 0) return;               // 还没算出来，等下一轮
            if (tv.getCurrentTextColor() != c) tv.setTextColor(c);
        } catch (Throwable ignore) {}
    }

    /**
     * 颜色跟随系统。状态栏换色（深/浅色切换、下拉面板遮罩）时系统只回调它自己的图标，
     * 我们的 TextView 收不到，所以每秒对一次，取到了就停（不重建 drawable，几乎零开销）。
     */
    private static void startBadgeColorTicker() {
        if (badgeColorTickerStarted) return;
        badgeColorTickerStarted = true;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    for (ViewGroup g : BADGE_COMBOS.keySet()) {
                        if (g == null) continue;
                        android.widget.TextView tv = BADGE_VIEWS.get(g);
                        if (tv != null) syncBadgeColor(tv, g);
                    }
                } catch (Throwable ignore) {}
                h.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private static boolean badgeColorTickerStarted;

    /**
     * 立刻按「此刻」的系统色刷一遍所有制式标。
     *
     * 取色源优先级：时钟文字色（系统刚 setTextColor 的那个值最好使）
     *   → 同排 ImageView 的 tint → 信号图标 tint。
     * 时钟是状态栏唯一每帧都跟着主题走的文字，拿它当颜色基准最稳。
     */
    private static void syncAllBadgeColors(android.widget.TextView clock) {
        if (BADGE_COMBOS.isEmpty()) return;
        int base = 0;
        try {
            if (clock != null) base = clock.getCurrentTextColor();
        } catch (Throwable ignore) {}
        for (ViewGroup g : BADGE_COMBOS.keySet()) {
            if (g == null) continue;
            android.widget.TextView tv = BADGE_VIEWS.get(g);
            if (tv == null) continue;
            try {
                int c = base != 0 ? base : resolveBadgeColor(g);
                if (c == 0) continue;
                if (tv.getCurrentTextColor() != c) tv.setTextColor(c);
            } catch (Throwable ignore) {}
        }
    }

    /**
     * 等 mobile_combo 完成一次 layout 再建制式标。
     *
     * 系统界面刚重启时，stack() 是在容器还没 measure 完就被调起来的：
     * 那会儿 combo 尺寸是 0，TextView 按 Gravity.TOP|START 落进去会堆在信号柱正上方，
     * 等下次布局才被推到正确位置 —— 表现为「刚重启时 5G 压在柱子上，过一会儿才归位」。
     * 量好了再建，第一帧就在正确位置。
     */
    private static void attachBadgeWhenMeasured(final ViewGroup combo,
                                               final android.content.Context ctx) {
        if (combo.getWidth() > 0 && combo.getHeight() > 0) {
            attachBadgeText(combo, badgeLabel(ctx));
            return;
        }
        combo.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
                v.removeOnLayoutChangeListener(this);
                try { attachBadgeText(combo, badgeLabel(ctx)); } catch (Throwable ignore) {}
            }
        });
    }

    private static void attachBadgeText(final ViewGroup combo, String text) {
        try {
            final android.widget.TextView tv;
            android.widget.TextView old = BADGE_VIEWS.get(combo);
            if (old != null && old.getParent() == combo) {
                tv = old;
                // 复用也要校一次边距：下拉控制中心重建后可能停在上一次的值
                ViewGroup.LayoutParams lp0 = tv.getLayoutParams();
                if (lp0 instanceof android.widget.FrameLayout.LayoutParams) {
                    android.widget.FrameLayout.LayoutParams f0 =
                            (android.widget.FrameLayout.LayoutParams) lp0;
                    int want = Math.round(BADGE_LEFT_DP
                            * tv.getResources().getDisplayMetrics().density);
                    if (f0.leftMargin != want) {
                        f0.leftMargin = want;
                        tv.setLayoutParams(f0);
                    }
                }
            } else {
                tv = new android.widget.TextView(combo.getContext());
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 6.5f);
                tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                tv.setIncludeFontPadding(false);
                tv.setId(View.generateViewId());
                ViewGroup.LayoutParams lp;
                if (combo instanceof android.widget.FrameLayout) {
                    android.widget.FrameLayout.LayoutParams flp =
                            new android.widget.FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    android.view.Gravity.TOP | android.view.Gravity.START);
                    // 与箭头左缘对齐：箭头墨迹从 combo 左缘 x+7px 起步，
                    // TextView 自带一点字形左留白，所以这里给 1.15dp 起步即可
                    flp.leftMargin = Math.round(BADGE_LEFT_DP * combo.getResources()
                            .getDisplayMetrics().density);
                    // 顶部与闹钟齐平，不压信号柱
                    flp.topMargin = Math.round(2 * combo.getResources()
                            .getDisplayMetrics().density);
                    lp = flp;
                } else if (combo instanceof android.widget.LinearLayout) {
                    lp = new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    ((android.widget.LinearLayout.LayoutParams) lp).gravity = android.view.Gravity.TOP;
                } else {
                    lp = new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                }
                tv.setLayoutParams(lp);
                // 让 TextView 不参与父容器测量 —— 它的宽度固定，不该影响 mobile_combo 的尺寸
                combo.setClipChildren(false);
                combo.setClipToPadding(false);
                combo.addView(tv);
                BADGE_VIEWS.put(combo, tv);
            }
            // 空串 = 该隐藏（WiFi 连着 / 制式未知）。不能直接 setText("") 完事，
            // 要让 view 真正退出布局，否则左边缘会留一截空白把箭头挤走。
            if (text.isEmpty()) {
                if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
                tv.setText("");
            } else {
                if (tv.getVisibility() != View.VISIBLE) tv.setVisibility(View.VISIBLE);
                // 布局可能已经变了（单/双卡切换、图标增减、WiFi 图标出现），
            // 挂一次 layout 监听：几何一变就重新校准左边距。

            if (!text.contentEquals(tv.getText())) tv.setText(text);
            }
            syncBadgeColor(tv, combo);
        } catch (Throwable ignore) {}
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

        // 网络指示器（上下行箭头）保留，只把它整体下移；5G 要占它上面那一条
        try { dropDataInout(signal); } catch (Throwable ignore) {}
    }

    /**
     * 把移动数据那一格的上传/下载箭头「放下来」—— 压到格子的底部。
     *
     * 只做 translationY 平移：箭头原样大小、原样宽度，整块往下挪。
     * 不用 margin / LayoutParams —— 那会让箭头重新走 measure + scaleType，
     * 素材被按新高度缩放，看起来就是「箭头变小了」。
     *
     * 只动「上面没有 WiFi 节点」的那一份（状态栏里 WiFi 与移动数据是两个独立分支），
     * 否则会把 WiFi 自己的箭头一起推下去。
     */
    private static void dropDataInout(View from) {
        try {
            // 不再按 isWifiConnected 分流：切制式的瞬间 ConnectivityManager 可能短暂
            // 报成「有 WiFi」，一走复位分支箭头就停在原位，叠在制式标上（看着像箭头消失）。
            // 这里只按「这一支里有没有移动数据图标」判断，不动 WiFi 那一支。
            View node = from;
            for (int i = 0; i < 5; i++) {
                ViewParent p = node.getParent();
                if (!(p instanceof ViewGroup)) break;
                node = (View) p;
                ImageView hit = findByKeyInDataBranch(node);
                if (hit != null) pushDown(hit);
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 在「不含 WiFi 节点」的子树里找 inout。
     *
     * 状态栏里 WiFi 与移动数据是两条独立分支，两边的 inout 都叫 data_inout / wifi_inout，
     * 只按名字找会命中错的那一支，把 WiFi 自己的箭头也推下去。
     */
    private static ImageView findByKeyInDataBranch(View v) {
        if (hasWifiBelow(v)) return null;
        return findByKey(v, "inout", 0);
    }

    /**
     * 箭头下移量（dp）。只平移，不改尺寸 ——
     * 用 LayoutParams 会触发重新 measure + scaleType，箭头被按新高度缩放，看着就是「变小了」。
     */
    private static final float INOUT_DROP_DP = 7f;

    /** 双卡布局下 mobile_signal 的高度基准（560dpi 实测 56px） */
    private static final int DUAL_COLUMN_H = 56;

    /**
     * 信号柱高度 / 双卡基准柱高。双卡时柱子只占上半排，单卡是整格满高；
     * 下移量按这个比例放大，单卡箭头才会落在同样的相对位置上。
     */
    private static float columnRatio() {
        try {
            ImageView sig = MAIN == null ? null : MAIN.get();
            if (sig == null || sig.getHeight() == 0) return 1f;
            float r = sig.getHeight() / (float) DUAL_COLUMN_H;
            if (r < 0.9f) r = 0.9f;
            if (r > 1.4f) r = 1.4f;
            return r;
        } catch (Throwable t) {
            return 1f;
        }
    }

    private static void pushDown(View v) {
        try {
            int px = Math.round(INOUT_DROP_DP * v.getResources().getDisplayMetrics().density
                    * columnRatio());
            v.setTranslationY(px);
        } catch (Throwable ignore) {}
    }

    private static boolean hasWifiBelow(View v) {
        String n = entryName(v);
        if (n != null && n.contains("wifi")) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasWifiBelow(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static void hideTypeAndInout(View v, int depth) {
        if (depth > 6 || v == null) return;
        String n = v.getId() != View.NO_ID ? entryName(v) : null;
        // 收掉制式小图（制式标已画进信号图标）。
        // 上传/下载箭头只在 WiFi 下保留：移动数据下显示会和制式标挤在一起，显得割裂。
        if (n != null && n.contains("mobile_type")) {
            hideHard(v);
        }
        // 网络指示器（上下行箭头）保留显示 —— C17 在移动数据下也显示它
        // 先让它露出来看位置和观感，不合适再换素材
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
    private static boolean tickerStarted;

    /**
     * 制式变化轮询。
     *
     * 切 4G/5G、开关 5G、换数据卡时，系统未必会重画信号图标，我们就没有机会重建制式标。
     * 这是制式标的**唯一**刷新通道：每 2.5 秒读一次当前制式，值一变就直接改 TextView 文本。
     * 不再调 refreshAll() —— 制式跟信号 drawable 已经没关系了，重建 drawable 只会白闪一下。
     * 只比一个字符串，开销可以忽略。
     */
    private static boolean badgeTickerStarted;

    private static void startBadgeTicker(final android.content.Context ctx) {
        if (badgeTickerStarted) return;
        badgeTickerStarted = true;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // 无条件推一次。attachBadgeText 内部会比对文本，值没变时不会 setText，
                    // 所以这里不缓存、不比对 —— 缓存一旦和屏幕脱节就再也推不动了。
                    pushBadgeLabel(ctx);
                } catch (Throwable ignore) {}
                h.postDelayed(this, 2500);
            }
        }, 2500);
    }

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
    }   // 强引用，抓到就记住
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

    /**
     * WiFi 的槽位复用：
     *   有 WiFi → 让系统画 WiFi（外观已换成 C17 素材）
     *   没 WiFi → 在同一个 ImageView 上画 5G，并把它所在的 combo 打开
     * 不新增任何 view，只改已有 view 的 drawable / 可见性，所以不会打乱布局。
     */

    /**
     * 网速那一格「视觉高度」：数字行 + 单位行的实际墨迹跨度（不是 View 高度，
     * View 高度含 padding，会比看到的高一倍多）。信号图标按这个值绘制，
     * 才能和网速逐像素齐平；分辨率/显示大小一变，这里跟着变，比例不会跑。
     */
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

    /**
     * 逐帧盯着网速文字的颜色。
     *
     * 收起控制中心时，系统换色的方式各版本不一样（setImageTintList / setColorFilter /
     * 直接改 StatusBarIconView 内部字段），只 hook 那几种方法总会漏掉一条路径，
     * 表现出来就是「白一会儿才变黑」。这里在状态栏的 preDraw 里直接读一次网速文字色，
     * 和上次不同就重绘 —— 最多晚一帧（16ms），与系统换色同步。
     */


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

    /**
     * 只取「跟这个信号图标同一排」的网速文字。
     *
     * 全屏 App 里下拉控制中心时，窗口树里同时有多份 network_speed（App 自己的状态栏那份
     * 是黑字，控制中心那份是白字），之前按"屏幕最上面"挑，会挑到 App 那份，
     * 结果就是「App 里下拉变黑、桌面下拉正常」。
     * 从 main 往上找兄弟容器，拿到的必然是同一条状态栏里的那一格。
     */

    /**
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
                                lockSpeedWidth(main);
                                tightenRightPadding(main);
                                alignEdges(main);
                            } catch (Throwable ignore) {}
                        }
                    });
        } catch (Throwable ignore) {}
    }

    // ---------- 状态栏两端边距 ----------

    private static final java.util.WeakHashMap<View, Integer> EDGE_BASE = new java.util.WeakHashMap<>();

    /** 按类名在子树里找第一个可见、有尺寸的控件 */
    private static View findByClass(View root, String key) {
        if (root == null) return null;
        if (root.getVisibility() == View.VISIBLE && root.getWidth() > 0
                && root.getClass().getSimpleName().contains(key)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findByClass(g.getChildAt(i), key);
                if (f != null) return f;
            }
        }
        return null;
    }

    /**
     * 时间与电池到屏幕两边的距离原本不等（左边宽右边窄）。
     * 这里量出两侧实际差值，把宽的那一侧压回去，让两端边距相等。
     */
    private static void alignEdges(View main) {
        try {
            int sw = main.getResources().getDisplayMetrics().widthPixels;
            // 向上找到「整条状态栏」那一层（宽度≈屏宽），别搜到下拉栏/锁屏里的副本
            View root = main;
            for (int i = 0; i < 12; i++) {
                ViewParent p = root.getParent();
                if (!(p instanceof View)) break;
                View up = (View) p;
                root = up;
                if (up.getWidth() >= sw * 0.9) break;
            }
            View clock = findByClass(root, "StatClock");
            View bat = findByClass(root, "Battery");
            if (clock == null || bat == null) return;
            int[] a = new int[2], b = new int[2];
            clock.getLocationOnScreen(a);
            bat.getLocationOnScreen(b);
            int lg = a[0];
            int rg = sw - (b[0] + bat.getWidth());
            if (lg <= 0 || rg <= 0 || lg == rg) return;
            // 只动时间那一侧：电池的位置由 nudgeBattery 负责，这里再碰它会来回打架
            ViewGroup.LayoutParams lp = clock.getLayoutParams();
            if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            Integer prev = EDGE_BASE.get(clock);
            int base = prev == null ? m.getMarginStart() : prev;
            Integer diff = EDGE_DIFF.get(clock);
            if (prev == null || diff == null) {
                // 差值只在首帧量一次。之后每帧重算会跟自己的修正互相追，时钟就会左右跳
                EDGE_BASE.put(clock, base);
                diff = lg - rg;
                EDGE_DIFF.put(clock, diff);
            }
            // 只补一半：整排往两侧扩得太多，时钟会被挤掉，这里往回收一档
            int want = base - Math.round(diff * EDGE_FILL);
            if (m.getMarginStart() == want) return;
            m.setMarginStart(want);
            clock.setLayoutParams(m);
        } catch (Throwable ignore) {}
    }




    /** 右侧容器内边距收回量（dp）。原为 8，整排外扩过头，时钟被挤掉，收到 4 */
    private static final float EXTEND_DP = 4f;
    /** 两端差值补齐比例。原为 1.0（完全拉平），太靠边，收到 0.5 */
    private static final float EDGE_FILL = 0.5f;

    private static final java.util.WeakHashMap<View, Integer> SPEED_MAX_W = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ViewGroup, Integer> PAD_BASE = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, Integer> EDGE_DIFF = new java.util.WeakHashMap<>();

    /**
     * 右侧那一排在容器里还剩一圈 padding。把它收掉，整排就会往右挪，
     * 两端边距一起变小；电池不会超出容器，尖头也就不会缺。
     */
    private static void tightenRightPadding(View main) {
        try {
            int sw = main.getResources().getDisplayMetrics().widthPixels;
            View root = main;
            for (int i = 0; i < 12; i++) {
                ViewParent p = root.getParent();
                if (!(p instanceof View)) break;
                View up = (View) p;
                root = up;
                if (up.getWidth() >= sw * 0.9) break;
            }
            View bat = findByClass(root, "Battery");
            if (bat == null) return;
            View cur = bat;
            int cut = Math.round(EXTEND_DP * main.getResources().getDisplayMetrics().density);
            for (int i = 0; i < 6; i++) {
                ViewParent p = cur.getParent();
                if (!(p instanceof ViewGroup)) return;
                ViewGroup g = (ViewGroup) p;
                int pad = g.getPaddingRight();
                if (pad > 0) {
                    Integer base = PAD_BASE.get(g);
                    if (base == null) {
                        base = pad;
                        PAD_BASE.put(g, base);
                    }
                    int want = Math.max(0, base - cut);
                    if (pad != want) {
                        g.setPadding(g.getPaddingLeft(), g.getPaddingTop(), want, g.getPaddingBottom());
                    }
                    return;
                }
                cur = g;
            }
        } catch (Throwable ignore) {}
    }


    /** 状态栏那一格网速里的 TextView（母容器名字里带 network_speed） */
    private static android.widget.TextView findSpeedText(View v, int depth) {
        if (v == null || depth > 14) return null;
        if (v instanceof android.widget.TextView && !(v instanceof android.widget.EditText)) {
            ViewParent p = v.getParent();
            if (p instanceof View) {
                String n = entryName((View) p);
                if (n != null && n.contains("network_speed")) return (android.widget.TextView) v;
            }
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null) {
                String t = cs.toString();
                if (t.contains("B/S") || t.contains("K/S") || t.contains("M/S")) return (android.widget.TextView) v;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.TextView r = findSpeedText(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * 网速文字宽度随数字变（101 / 1.2M / 999），一变整排就跟着挪，把电池挤窄。
     * 把这一格的宽度锁成观察到的最大值，数字再变也不再推动。
     */
    private static void lockSpeedWidth(View main) {
        try {
            int sw = main.getResources().getDisplayMetrics().widthPixels;
            View root = main;
            for (int i = 0; i < 12; i++) {
                ViewParent p = root.getParent();
                if (!(p instanceof View)) break;
                View up = (View) p;
                root = up;
                if (up.getWidth() >= sw * 0.9) break;
            }
            android.widget.TextView tv = findSpeedText(root, 0);
            if (tv == null || tv.getWidth() <= 0) return;
            Integer mx = SPEED_MAX_W.get(tv);
            if (mx == null || tv.getWidth() > mx) {
                mx = tv.getWidth();
                SPEED_MAX_W.put(tv, mx);
            }
            if (tv.getMinWidth() != mx) tv.setMinimumWidth(mx);
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
        } catch (Throwable ignore) {}
    }

    /**
     * 收起副卡那一路容器：尺寸压 0 + INVISIBLE，但**不能 GONE**。
     *
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
                        @Override public void onAvailable(android.net.Network n) { onNetChanged(ctx); }
                        @Override public void onLost(android.net.Network n) { onNetChanged(ctx); }
                        @Override public void onCapabilitiesChanged(android.net.Network n,
                                android.net.NetworkCapabilities c) { onNetChanged(ctx); }
                    });
        } catch (Throwable t) {
        }
    }

    /**
     * 网络能力变化（含 WiFi 上下线）的统一入口。
     *
     * 制式标要在这里立刻重算：WiFi 连上就该马上收起 5G，断开就该马上显示，
     * 靠 2.5 秒 ticker 会明显慢一拍，这里直接推一次。
     */
    private static void onNetChanged(android.content.Context ctx) {
        // 只重绘，不重建：WiFi 上下线时系统马上会重draw 自己的图标，
        // 我们在这里再 refreshAll() 等于和系统抢同一帧，WiFi 图标会被压到后面才出现。
        try {
            for (ImageView v : SITES.keySet()) {
                if (v == null) continue;
                Drawable d = v.getDrawable();
                if (d != null) d.invalidateSelf();
            }
        } catch (Throwable ignore) {}
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