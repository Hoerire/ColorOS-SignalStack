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
                    iv.post(new Runnable() {
                        @Override public void run() {
                            try { stack((ImageView) iv); } catch (Throwable ignore) {}
                        }
                    });
                } else if (n.contains("inout")) {
                    // 上传/下载箭头一律撤掉（制式标已画进信号图标）
                    if (iv.getVisibility() != View.GONE) iv.setVisibility(View.GONE);
                } else if (n.contains("wifi")) {
                    // 断/连 WiFi 时系统会刷新 wifi 图标，顺势让信号图标重算
                    refreshAll();
                }
            } catch (Throwable ignore) {}
            return r;
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
        if (container == null) { Log.d("skip: no container"); return; }

        View primaryRoot = null;
        View secondRoot = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View ch = container.getChildAt(i);
            if (findSignal(ch) == null) continue;
            if (primaryRoot == null) primaryRoot = ch;
            else if (secondRoot == null) { secondRoot = ch; break; }
        }
        if (primaryRoot == null) { Log.d("skip: no primaryRoot"); return; }
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
        String sig = lp + "/" + ls + "/" + (wifi ? "w" : "m");

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
            return;
        }
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
            startColorTicker(main);
            main.setImageDrawable(composed);
            if (secondRoot != null) collapse(secondRoot);
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

        VStack v = new VStack(pa, pb, tintListOf(main), badge);
        CUR_VS = new java.lang.ref.WeakReference<>(v);
        v.setHost(main);
        // 箭头不改自绘，改为直接挪系统那个 view
        Log.d("build family=" + family + " lp=" + lp + " ls=" + ls
                + (pb == null ? " single" : "") + " wifi=" + wifi
                + " badge=" + (badge != null) + " clockPx=" + clockTextPx(main));
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
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) hideTypeAndInout(g.getChildAt(i), depth + 1);
        }
    }

    private static int lastKnownLevel = -1;
    private static final java.util.HashSet<String> SEEN = new java.util.HashSet<>();
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
                h.postDelayed(this, 1500);
            }
        }, 1500);
        Log.d("color ticker started");
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
    private static ImageView findWifi(View from) {
        try {
            View v = from;
            for (int i = 0; i < 6; i++) {
                ViewParent p = v.getParent();
                if (!(p instanceof ViewGroup)) return null;
                v = (View) p;
                String n = entryName(v);
                if (n != null && n.contains("container") || n != null && n.contains("icons")) {
                    ImageView r = findWifiIn(v, 0);
                    if (r != null) return r;
                }
            }
            ImageView r = findWifiIn(from.getRootView(), 0);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ImageView findWifiIn(View v, int depth) {
        if (depth > 10 || v == null) return null;
        if (v instanceof ImageView) {
            String n = entryName(v);
            if (n != null && n.contains("wifi")) return (ImageView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ImageView r = findWifiIn(g.getChildAt(i), depth + 1);
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
    /** 系统每次重新布局都会把 translation 冲掉，这里挂个监听重压一次 */
    private static void attachLayoutWatcher(final View main) {
        try {
            if (Boolean.TRUE.equals(WATCHED.get(main))) return;
            WATCHED.put(main, Boolean.TRUE);
            main.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override public void onGlobalLayout() {
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

    private static void collapse(View v) {
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

    /** 链路改 wrap_content，去掉撑宽度的固定留白 */
    private static void tighten(View v) {
        View cur = v;
        for (int depth = 0; depth < 5; depth++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) p;
            if (g.getClass().getSimpleName().contains("StatusIconContainer")) {
                g.requestLayout();
                return;
            }
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

    private static ColorStateList tintListOf(ImageView v) {
        try {
            ColorStateList csl = v.getImageTintList();
            if (csl != null) return csl;
        } catch (Throwable ignore) {}
        // 身上没 tint：跟状态栏时钟同色
        try {
            int c = currentIconColor(v);
            if (c != 0) return ColorStateList.valueOf(c);
        } catch (Throwable ignore) {}
        return null;
    }

    /** 状态栏当前的图标颜色：优先时钟文字色，其次夜景兜底 */
    static int currentIconColor(View from) {
        try {
            android.widget.TextView clock = findClock(from.getRootView(), 0);
            if (clock != null) {
                int c = clock.getCurrentTextColor();
                if ((c >>> 24) != 0) return c;
            }
        } catch (Throwable ignore) {}
        try {
            boolean night = (from.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            return night ? 0xFFFFFFFF : 0xFF000000;
        } catch (Throwable ignore) {}
        return 0;
    }

    private static int clockTextPx(View from) {
        try {
            android.widget.TextView tv = findClock(from.getRootView(), 0);
            if (tv != null && tv.getTextSize() > 0) return Math.round(tv.getTextSize());
        } catch (Throwable ignore) {}
        return 0;
    }

    private static android.widget.TextView findClock(View v, int depth) {
        if (depth > 14 || v == null) return null;
        if (v instanceof android.widget.TextView && v.getVisibility() == View.VISIBLE) {
            String n = entryName(v);
            if (n != null && n.contains("clock")) {
                android.widget.TextView tv = (android.widget.TextView) v;
                if (tv.getTextSize() > 0) return tv;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.TextView r = findClock(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isWifiConnected(Context ctx) {
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
            LAST.clear();
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