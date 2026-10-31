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
        ok += hookSpeedAlign(m, cl);
        ok += hookIconOrder(m, cl);
        ok += hookIconGap(m, cl);
        return ok;
    }

    // ==================== 图标间距 ====================

    /**
     * 所有普通格的左右 padding 基准。
     *
     * 整簇偏宽就往小调、偏窄就往大调 —— 只改这一个数。
     * 不要按图标身份分别配值：图标显隐一变，缝就重排（蓝牙曾被当成月亮套错值）。
     */
    private static final int GAP_INSET_PX = 0;

    /**
     * 只压缩「信号 → WiFi」之间的缝，电池位置不动。
     *
     * StatusIconContainer.updateStates() 从右往左累加
     *   child.getWidth() + paddingStart + paddingEnd
     * 得出每格的 translationX。给 mobile_combo 加负的 paddingEnd，它和它左边的东西
     * 都会整体右移，右侧的 WiFi / 电池纹丝不动 —— 这正是"只缩间距不挪电池"。
     *
     * updateStates 每次布局都会跑，所以挂在这里而不是一次性的 setPadding。
     */
    private static int hookIconGap(XposedModule m, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.android.systemui.statusbar.phone.StatusIconContainer", false, cl);
            int n = 0;
            Class<?> sic = Class.forName(
                    "com.android.systemui.statusbar.phone.StatusIconContainer", false, cl);
            Method st = sic.getDeclaredMethod("updateStates");
            m.hook(st).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain ch) throws Throwable {
                    try { tightenGap((ViewGroup) ch.getThisObject()); } catch (Throwable ignore) {}
                    return ch.proceed();
                }
            });
            n++;
            Method lo = sic.getDeclaredMethod("onLayout",
                    boolean.class, int.class, int.class, int.class, int.class);
            m.hook(lo).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain ch) throws Throwable {
                    return ch.proceed();
                }
            });
            n++;
            return n;
        } catch (Throwable t) {
            try { MODULE.log(android.util.Log.WARN, "StackSig", "iconGap hook failed: " + t); }
            catch (Throwable ignore) {}
            return 0;
        }
    }

    /**
     * 把所有可见图标的左右 padding 统一成同一个值，让相邻图标之间的缝隙恒定。
     *
     * 原厂各格的 padding 是各自不同的（网速 7px、信号 0、WiFi 0 …），所以图标多一个少一个
     * 缝隙就变样。这里统一成 GAP_INSET_PX：左右各一份，相邻两格合计就是一条恒定缝。
     * 只改 padding，不动任何 child 的顺序/尺寸，updateStates 照常算。
     */
    /** 模块编译期拿不到 SystemUI 的类，只能按类名判断（StatIconView / *StatusBarIconView / *combo） */
    private static boolean isStatusIcon(View v) {
        // 先按 id 名判断（mobile_combo / wifi_combo / network_speed 都在 id 上）
        String id = entryName(v);
        if (id != null) {
            String k = id.toLowerCase();
            if (k.contains("combo") || k.contains("network_speed")) return true;
        }
        try {
            for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass()) {
                String n = c.getSimpleName();
                if (n.contains("StatIconView") || n.contains("StatusBarIconView")) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private static void tightenGap(ViewGroup g) {
        int nullRank = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            if (!isStatusIcon(v)) continue;
            int[] pe = gapFor(v, nullRank++);
            if (v.getPaddingLeft() != pe[0] || v.getPaddingRight() != pe[1]) {
                v.setPadding(pe[0], v.getPaddingTop(), pe[1], v.getPaddingBottom());
            }

            // 早先的 equalize() 实验给网速留下过 translationX，会让它画到别处甚至看不见。
            // 这里统一清掉，位置完全交给系统的 updateStates。
            if (v.getTranslationX() != 0f) {
                v.setTranslationX(0f);
                v.invalidate();
            }
        }
    }

    /**
     * 每格的左右 padding。除蓝牙外全部相同。
     *
     * 这里刻意**不做按图标身份的区分**：曾经给 nfc / alarm / zen / bluetooth 各配一组值，
     * 结果左右增减一个图标（开蓝牙、开免打扰）缝就重排 —— 因为「某格到右邻的缝」
     * 取决于右邻是谁，而右邻随时会变。统一成一个值后，图标增删只改变总宽。
     */
    private static int[] gapFor(View v, int nullRank) {
        // 蓝牙右边紧挨信号格左上角的制式标，需要比其他格多留 1px
        if (v.getId() == View.NO_ID) {
            String slot = slotOf(v);
            if (slot != null && slot.contains("bluetooth")) {
                return new int[]{GAP_INSET_PX, GAP_INSET_PX + 1};
            }
        }
        return new int[]{GAP_INSET_PX, GAP_INSET_PX};
    }

    /** 反射调 StatusIconDisplayable.getSlot()，拿不到返回 null */
    private static String slotOf(View v) {
        try {
            Method md = null;
            for (Class<?> c = v.getClass(); c != null && md == null; c = c.getSuperclass()) {
                try { md = c.getDeclaredMethod("getSlot"); } catch (Throwable ignore) {}
            }
            // 多数实现把 getSlot() 声明在接口 StatusIconDisplayable 上，类里继承而来
            if (md == null) {
                for (Class<?> c = v.getClass(); c != null && md == null; c = c.getSuperclass()) {
                    for (Class<?> itf : c.getInterfaces()) {
                        try { md = itf.getMethod("getSlot"); break; } catch (Throwable ignore) {}
                    }
                }
            }
            if (md == null) return null;
            md.setAccessible(true);
            Object r = md.invoke(v);
            return r instanceof String ? (String) r : null;
        } catch (Throwable ignore) {
            return null;
        }
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
            return 1;
        } catch (Throwable t) {
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
                                return h.sendMessageAtTime(msg,
                                        android.os.SystemClock.uptimeMillis() + NETSPEED_INTERVAL);
                            }
                        }
                    } catch (Throwable ignore) {}
                    return c.proceed();
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
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
        // 重建条件必须带上「网络制式」：只比信号等级的话，切 4G/5G 时等级没变，
        // sig 相同就直接 return，制式标会永远停在旧值（下拉栏走系统 DisplayInfo 所以是对的）。
        String badgeKey = "";
        {
            android.content.Context bc = main.getContext();
            badgeKey = wifi ? "" : badgeNameFor(bc);
        }
        String sig = lp + "/" + ls + "/" + (wifi ? "w" : "m") + "/" + badgeKey;

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
        Drawable badge = badgeName == null ? null : Res17.get(badgeName);

        VStack v = new VStack(pa, pb, main.getImageTintList(), badge);
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
    /**
     * 取「正在承载数据那张卡」的 TelephonyManager。
     *
     * 双卡机器上 getDataNetworkType() 拿的是默认 subId 的类型，未必是真正跑数据的那张
     * —— 表现就是拉到 5G 却还显示 4G（下拉控制中心那份走的是系统 DisplayInfo，所以是对的）。
     * 这里先按 SubscriptionManager 的默认数据卡建实例，拿不到再退回整机那份。
     */
    private static android.telephony.TelephonyManager dataTelephonyManager(
            android.content.Context ctx) {
        try {
            android.telephony.SubscriptionManager sm =
                    android.telephony.SubscriptionManager.from(ctx);
            int subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            if (subId == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                java.util.List<android.telephony.SubscriptionInfo> subs =
                        sm.getActiveSubscriptionInfoList();
                if (subs != null && !subs.isEmpty()) {
                    subId = subs.get(0).getSubscriptionId();
                }
            }
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

    /**
     * 按当前数据网络制式选制式标。
     *
     * 5GA（5G-Advanced）在原厂是「NR 网络 + 已聚合多个 NR 载波」时才显示。
     * getDataNetworkType() 只给得出 NR，给不出载波数，所以要读 TelephonyDisplayInfo
     * 的 overrideNetworkType：系统把它算成 NR_ADVANCED 就说明要画 5GA。
     * 拿不到那块信息时退回 5G —— 宁可少画，不能错画。
     */
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
     * 当前 NR 是否已升级到 5G-A。
     *
     * 系统把这个结论放在 TelephonyDisplayInfo.overrideNetworkType 里
     * （OVERRIDE_NETWORK_TYPE_NR_ADVANCED = 5）。这里用反射读它：
     * 拿不到就返回 false，绝不影响普通 5G 的显示。
     */
    private static boolean isNrAdvanced(android.content.Context ctx) {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            if (tm == null) return false;
            Object di = null;
            try {
                java.lang.reflect.Method m = tm.getClass().getMethod("getTelephonyDisplayInfo");
                di = m.invoke(tm);
            } catch (Throwable ignore) {}
            if (di == null) {
                // 新接口：getNetworkType(boolean) 后接 TelephonyDisplayInfo 的路径不可靠，
                // 退而用隐藏字段
                java.lang.reflect.Field f = tm.getClass().getDeclaredField("mTelephonyDisplayInfo");
                f.setAccessible(true);
                di = f.get(tm);
            }
            if (di == null) return false;
            for (java.lang.reflect.Field f : di.getClass().getDeclaredFields()) {
                if (!f.getName().contains("overrideNetworkType")) continue;
                f.setAccessible(true);
                Object v = f.get(di);
                if (!(v instanceof Integer)) continue;
                int t = (Integer) v;
                // OVERRIDE_NETWORK_TYPE_NR_ADVANCED = 5
                if (t == 5) return true;
            }
        } catch (Throwable ignore) {}
        return false;
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
        // 收掉制式小图（制式标已画进信号图标）。
        // 上传/下载箭头只在 WiFi 下保留：移动数据下显示会和制式标挤在一起，显得割裂。
        if (n != null && n.contains("mobile_type")) {
            hideHard(v);
        }
        if (n != null && n.contains("inout") && !isWifiConnected(v.getContext())) {
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
    private static boolean tickerStarted;

    /**
     * 制式变化轮询。
     *
     * 切 4G/5G、开关 5G、换数据卡时，系统未必会重画信号图标，我们就没有机会重建制式标。
     * 这里每 2.5 秒读一次当前制式，值一变就 refreshAll()，重启一次合成。
     * 只比一个字符串，开销可以忽略。
     */
    private static String lastBadgeName;
    private static boolean badgeTickerStarted;

    private static void startBadgeTicker(final android.content.Context ctx) {
        if (badgeTickerStarted) return;
        badgeTickerStarted = true;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (!isWifiConnected(ctx)) {
                        String now = badgeNameFor(ctx);
                        if (now != null && !now.equals(lastBadgeName)) {
                            lastBadgeName = now;
                            refreshAll();
                        }
                    } else {
                        lastBadgeName = null;
                    }
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

    // ==================== 图标顺序（对齐 ColorOS 17） ====================

    /**
     * ColorOS 16 的 config_statusBarIcons 里 wifi 排在 mobile 前面；
     * ColorOS 17 把 mobile 提到了 wifi 前面，于是变成「信号 → WiFi」。
     *
     * StatusBarIconList 的构造函数直接吃这个 String[]，数组顺序就是显示顺序。
     * 这里只在构造时交换 wifi / mobile 的下标，纯数据层操作，不碰视图树。
     */
    private static int hookIconOrder(XposedModule m, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.android.systemui.statusbar.phone.ui.StatusBarIconList", false, cl);
            final java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor(String[].class);
            m.hook(ctor).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object arg = chain.getArg(0);
                        if (arg instanceof String[]) reorder((String[]) arg);
                    } catch (Throwable ignore) {}
                    return chain.proceed();
                }
            });
            return 1;
        } catch (Throwable t) {
            try { MODULE.log(android.util.Log.WARN, "StackSig", "iconOrder hook failed: " + t); }
            catch (Throwable ignore) {}
            return 0;
        }
    }

    /**
     * 重排 icon 数组。
     *
     * 1. mobile 挪到 wifi 前（对齐 C17 的「信号 → WiFi」）；
     * 2. zen（月亮）挪到 network_speed 前；
     * 3. network_speed 提到 zen 之前——让网速成为这一簇最左的一格。
     *    数字宽度（115 / 1.20M / 12.3）一直变，它站在最左边就只会往左长，
     *    右边的闹钟/铃铛/月亮永远不会被挤动，也就不需要额外预留缓冲。
     */
    private static void reorder(String[] slots) {
        moveBefore(slots, "mobile", "wifi");
        moveBefore(slots, "zen", "wifi");
        moveBefore(slots, "network_speed", "logging");
        // 网速提到最左：越过 mute / ethernet / alarm_clock，成为整簇第一格
    }

    /** 把 slot 名为 what 的元素挪到 slot 名为 before 的元素之前；任一个找不到就原样不动 */
    private static void moveBefore(String[] slots, String what, String before) {
        int iWhat = -1, iBefore = -1;
        for (int i = 0; i < slots.length; i++) {
            String s = slots[i];
            if (s == null) continue;
            if (s.equals(what)) iWhat = i;
            else if (s.equals(before)) iBefore = i;
        }
        if (iWhat < 0 || iBefore < 0) return;
        if (iWhat < iBefore) return;             // 已经在前面
        String v = slots[iWhat];
        // [before ... what] -> [what, before ...]
        for (int i = iWhat; i > iBefore; i--) slots[i] = slots[i - 1];
        slots[iBefore] = v;
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

    /** 系统下发的状态栏明暗强度（0=深色图标，1=浅色图标） */

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

    /**
     * 只取「跟这个信号图标同一排」的网速文字。
     *
     * 全屏 App 里下拉控制中心时，窗口树里同时有多份 network_speed（App 自己的状态栏那份
     * 是黑字，控制中心那份是白字），之前按"屏幕最上面"挑，会挑到 App 那份，
     * 结果就是「App 里下拉变黑、桌面下拉正常」。
     * 从 main 往上找兄弟容器，拿到的必然是同一条状态栏里的那一格。
     */

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
        } catch (Throwable t) {
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