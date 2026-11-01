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
    /** 我们自己合成、当前挂在这个信号 view 上的 drawable：系统把它顶掉时用来当帧还原 */
    private static final WeakHashMap<ImageView, Drawable> OURS = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, String> ICON = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, String> LAST = new WeakHashMap<>();
    private static final WeakHashMap<ImageView, Boolean> SITES = new WeakHashMap<>();
    // LEVELS / LEVEL_HOOKED 已删：前者只 get 不 put（永远 null），后者完全没人引用

    static XposedModule MODULE;
    private static boolean netCallbackInstalled;

    // ==================== 调试日志开关 ====================
    // 定位「制式标消失」这类看不见的竞态时，把它改成 true 重新构建即可：
    // 每 3 秒往 LSPosed 日志打一行 BADGE（label / wifi / 每个 combo 的 tv 状态）。
    static final boolean DBG = false;
    private static long lastDbgMs = 0;

    static void dbg(String msg) {
        if (!DBG) return;
        try { MODULE.log(android.util.Log.INFO, "StackSig", msg); } catch (Throwable ignore) {}
    }

    private static void dbgBadge(final String phase, final android.content.Context ctx,
                                 final String label, final View primaryHost) {
        if (!DBG) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastDbgMs < 3000) return;
        lastDbgMs = now;
        try {
            StringBuilder sb = new StringBuilder("BADGE " + phase);
            sb.append(" label='").append(label).append('\'');
            sb.append(" combos=").append(BADGE_COMBOS.size());
            sb.append(" wifi=").append(isWifiConnected(ctx));
            sb.append(" name=").append(badgeNameFor(ctx));
            sb.append(" main=").append(MAIN == null ? null : MAIN.get());
            sb.append(" host=").append(primaryHost);
            for (ViewGroup g : BADGE_COMBOS.keySet()) {
                if (g == null) continue;
                sb.append(" [").append(entryName(g)).append(' ').append(g.getWidth()).append('x').append(g.getHeight())
                        .append(" v=").append(g.getVisibility())
                        .append(" kids=").append(g.getChildCount());
                android.widget.TextView t = BADGE_VIEWS.get(g);
                if (t == null) {
                    sb.append(" tv=null");
                } else {
                    sb.append(" tv{vis=").append(t.getVisibility())
                            .append(" text='").append(t.getText()).append('\'')
                            .append(" color=#").append(Integer.toHexString(t.getCurrentTextColor()))
                            .append(" size=").append(t.getWidth()).append('x').append(t.getHeight())
                            .append(" at=").append(t.getLeft()).append(',').append(t.getTop())
                            .append(" inCombo=").append(t.getParent() == g)
                            .append(" attached=").append(t.isAttachedToWindow());
                    if (t.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams mlp =
                                (android.view.ViewGroup.MarginLayoutParams) t.getLayoutParams();
                        sb.append(" lp=").append(mlp.width).append('x').append(mlp.height)
                                .append(" m=").append(mlp.leftMargin).append('/').append(mlp.topMargin);
                    }
                    sb.append('}');
                }
                sb.append(" tint:");
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (!(c instanceof ImageView)) continue;
                    android.content.res.ColorStateList cs = ((ImageView) c).getImageTintList();
                    sb.append(' ').append(entryName(c)).append('=')
                            .append(cs == null ? "null" : "#" + Integer.toHexString(cs.getDefaultColor()));
                }
                sb.append(']');
            }
            dbg(sb.toString());
        } catch (Throwable t) {
            dbg("BADGE dump failed: " + t);
        }
    }

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
        ok += hookAddView(m);
        ok += hookSetFlags(m);
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

    /**
     * 移动数据那一格的上下行箭头（data_inout）在数据断开、切换制式时会被系统设成 GONE。
     * GONE 不占位，这一格立刻从 91 px 塌到 67 px，右侧整排跟着重排 ——
     * 这就是「关 5G 时状态栏往右顶一下」的真正原因（实测 data_inout=91/8 时 mobile_combo 91→70）。
     *
     * 处理：没连 WiFi 时把它改成 INVISIBLE —— 一样不绘制，但保留占位，宽度恒定，不再横移。
     * 连着 WiFi 时不动：那时 WiFi 图标进出本身就会重排，让它 GONE 反而不会多出空白。
     */
    /** 箭头（data_inout）实测到的自然宽度。新建的 combo 也按这个值占位，避免第一帧偏窄。 */
    private static volatile int DATA_INOUT_W = 0;

    private static void keepDataInoutWidth(View v) {
        if (v == null) return;
        String n = entryName(v);
        if (!"data_inout".equals(n)) return;
        try {
            // 可见时记录自然宽度（同一台机器上是固定的，91 px @560dpi）
            int w = v.getWidth();
            if (w > DATA_INOUT_W) DATA_INOUT_W = w;
            if (isWifiConnected(v.getContext())) return;
            int vis = v.getVisibility();
            if (vis == View.GONE || vis == View.INVISIBLE) {
                // 隐藏态：宽度按已知自然宽度钉死。切卡时新 combo 是新建的，
                // 这一刻箭头还没量出宽度，不钉的话它先按 70 铺一帧，数据恢复后跳回 91 —— 整排 +21px。
                int want = DATA_INOUT_W;
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (want > 0 && lp != null && lp.width != want) {
                    lp.width = want;
                    v.setLayoutParams(lp);
                }
                if (vis == View.GONE) {
                    BUSY.set(Boolean.TRUE);
                    v.setVisibility(View.INVISIBLE);
                    dbg("data_inout GONE→INVISIBLE，占位保留 " + want + "px");
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (Boolean.TRUE.equals(BUSY.get())) BUSY.set(Boolean.FALSE);
        }
    }

    /**
     * 系统那一格在切制式时可能是**新建**一个 data_inout（带着 GONE 一起 inflate 出来），
     * 那就不会走 setVisibility，只有 addView 能拦到。这里在挂上去的瞬间就把它改成 INVISIBLE，
     * 让第一次 layout 就按「保留占位」算，宽度不会先塌再回来。
     */
    private static int hookAddView(XposedModule m) {
        try {
            Method md = ViewGroup.class.getDeclaredMethod("addView", View.class, int.class);
            m.hook(md).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain c) throws Throwable {
                    Object r = c.proceed();
                    try {
                        if (Boolean.TRUE.equals(BUSY.get())) return r;
                        Object child = c.getArg(0);
                        if (child instanceof View && ((View) child).getVisibility() == View.GONE) {
                            keepDataInoutWidth((View) child);
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

    /** View.VISIBILITY_MASK 是隐藏常量，写字面量 */
    private static final int VISIBILITY_MASK = 0x0000000c;

    /**
     * 实测：切制式时系统把 data_inout 设成 GONE 走的是 View.setFlags(flags, mask)，
     * 不是 setVisibility —— 挂在 setVisibility 上的 hook 一次都没被调用（诊断日志为空）。
     * 这里补上 setFlags，同样把 GONE 改成 INVISIBLE，保留占位。
     */
    private static int hookSetFlags(XposedModule m) {
        try {
            Method md = View.class.getDeclaredMethod("setFlags", int.class, int.class);
            m.hook(md).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain c) throws Throwable {
                    Object r = c.proceed();
                    try {
                        if (Boolean.TRUE.equals(BUSY.get())) return r;
                        Object msk = c.getArg(1);
                        Object flg = c.getArg(0);
                        if (!(msk instanceof Integer) || !(flg instanceof Integer)) return r;
                        if ((((Integer) msk) & VISIBILITY_MASK) == 0) return r;
                        if ((((Integer) flg) & VISIBILITY_MASK) != View.GONE) return r;
                        if (c.getThisObject() instanceof View) {
                            keepDataInoutWidth((View) c.getThisObject());
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

    private static int hookVisibility(XposedModule m) {
        try {
            Method md = View.class.getDeclaredMethod("setVisibility", int.class);
            m.hook(md).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain c) throws Throwable {
                    Object r = c.proceed();
                    try {
                        if (Boolean.TRUE.equals(BUSY.get())) return r;
                        // 只有「刚被设成 GONE」才需要看一眼，其余调用直接放过（这条 hook 每帧都会跑到）
                        Object arg = c.getArg(0);
                        if (arg instanceof Integer && (Integer) arg == View.GONE
                                && c.getThisObject() instanceof View) {
                            keepDataInoutWidth((View) c.getThisObject());
                        }
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

                // 下拉栏里那份完全交还系统
                if (isInShade(iv)) return r;
                if (n.contains(KEY)) {
                    if ("setImageResource".equals(c.getExecutable().getName())) {
                        Object arg = c.getArg(0);
                        if (arg instanceof Integer) {
                            try { ICON.put(iv, iv.getResources().getResourceEntryName((Integer) arg)); }
                            catch (Throwable ignore) {}
                        }
                    }
                    Drawable d = iv.getDrawable();
                    if (d != null && !(d instanceof VStack)) {
                        ORIG.put(iv, d);
                        // 切 5G/4G 时系统会用自己的素材重设信号图标。只 post(stack()) 的话，
                        // 中间会有一帧露出系统图标：它的宽高和我们的不同，
                        // StatusIconContainer 按它重排一次，右侧整排就「顶一下」（关 5G 时最明显）。
                        // 这里在同一帧直接还原成我们的 drawable，测量发生在还原之后，位移不会出现。
                        Drawable ours = OURS.get(iv);
                        if (ours != null && ours != d && !isInShade(iv)) {
                            try {
                                BUSY.set(Boolean.TRUE);
                                iv.setImageDrawable(ours);
                                dbg("系统顶掉信号图标，已同帧还原");
                            } catch (Throwable ignore) {
                            } finally {
                                BUSY.set(Boolean.FALSE);
                            }
                        }
                    }
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
        // 下拉通知栏里那份图标保持原厂行为，模块一概不碰。
        // 判断方式是看它是否挂在 NotificationShadeWindowView 这棵子树下 ——
        // 那是下拉面板的根视图，状态栏本身不在里面。
        if (isInShade(iv)) return;
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

        // 主卡判定：必须按「哪张卡在走流量」定，不能按子视图顺序。
        // 系统在切数据卡时会重排 container 的子视图，按顺序取第一项会在那一刻画错
        // （或把画错的那支 hide 掉，表现为信号消失）。ColorOS 17 用的是
        // TelephonyCallback.onActiveDataSubscriptionIdChanged 推来的 activeSubId
        // 做 sortedByDescending 排序，这里取同样的判据。
        // 容器的子视图里，同一个 subId 可能出现多份（状态栏一份、另一个 StatusIconContainer
        // 的副本一份），而且**同一张卡的两份实例尺寸不同**：可见的 91×70，另一份
        // 0×0 / GONE。只按 subId 选会挑中 0×0 那一份，然后把真正的图标当兄弟收掉 ——
        // 表现就是切卡后信号整块消失。
        // 所以主卡候选 = subId 命中 activeSub **且**有实际尺寸的那一份，优先 VISIBLE。
        int activeSub = activeDataSubId(iv.getContext());
        // 先把容器里所有「被我们收过」的候选全部放开再挑主卡。
        // 否则会自锁：上一轮收掉的那一支现在是 0×0 / GONE，score=0，
        // 挑不出来 → 只能退回去用另一张卡 → 继续收错的那一支，永远回不来。
        for (int i = 0; i < container.getChildCount(); i++) {
            View ch = container.getChildAt(i);
            if (findSignal(ch) == null) continue;
            // 只放开「当前上网卡」那一支。放开别的卡等于把它重新显示出来，
            // 状态栏会并排出现两个信号图标。
            if (activeSub > 0 && subIdOfRoot(ch) != activeSub) continue;
            if (HIDDEN.containsKey(ch)) revive(ch);
        }
        java.util.ArrayList<View> cands = new java.util.ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View ch = container.getChildAt(i);
            if (findSignal(ch) == null) continue;
            cands.add(ch);
        }
        if (cands.isEmpty()) { retry(iv, 1); return; }

        View primaryRoot = null;
        // 1) 正在走流量那张卡。这里**不看尺寸** —— 刚被 revive 放开的 view
        //    要等下一帧 layout 才有宽高，此刻看尺寸会把它误判成空壳。
        //    subId 是硬判据，尺寸只用来在同 subId 的多份副本之间去重。
        if (activeSub > 0) {
            for (View c : cands) {
                if (subIdOfRoot(c) == activeSub) { primaryRoot = c; break; }
            }
        }
        // 2) 退而求其次：有尺寸的里面挑最像「当前这份」的那一支
        if (primaryRoot == null) {
            for (View c : cands) {
                if (isSelfOrDescendant(c, iv) && score(c) > 0) { primaryRoot = c; break; }
            }
        }
        if (primaryRoot == null) {
            for (View c : cands) {
                if (score(c) > 0) { primaryRoot = c; break; }
            }
        }
        if (primaryRoot == null) primaryRoot = cands.get(0);

        // 副卡 = 另一张卡的那一份。同一张卡的另一份副本不算副卡（它要么是 0×0
        // 的空壳、要么属于别的容器），绝不能拿来当「第二张卡」参与堆叠。
        View secondRoot = null;
        int pSub = subIdOfRoot(primaryRoot);
        for (View c : cands) {
            if (c == primaryRoot) continue;
            int s = subIdOfRoot(c);
            if (s == pSub) continue;   // 同一张卡的副本，跳过
            if (s <= 0) continue;      // subId 未知，不能当第二张卡
            secondRoot = c;
            break;
        }
        // 双卡时只有"主卡那一层"负责合成；单卡（secondRoot==null）不需要这个限制，
        // 否则下拉通知栏里的那份实例会被误判掉。
        if (secondRoot != null && !isSelfOrDescendant(primaryRoot, iv)) return;

        ImageView main = findSignal(primaryRoot);
        ImageView sec = secondRoot == null ? null : findSignal(secondRoot);
        if (main == null) return;
        // MAIN 只认「挂在窗口上的那一份」。走「状态没变」的快速路径时也要更新，
        // 否则它会长期停在下拉栏 / 锁屏那份副本上，制式标就会被画到屏幕外那一份上。
        try {
            if (main.isAttachedToWindow()) MAIN = new java.lang.ref.WeakReference<>(main);
        } catch (Throwable ignore) {}

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
                if (DBG && fresh) dbg("BADGE register combo=" + entryName(g) + " " + g.getWidth() + "x"
                        + g.getHeight() + " kids=" + g.getChildCount());
                BADGE_COMBOS.put(g, Boolean.TRUE);
                if (fresh) watchComboWidth(g);
                pinDataInoutIn(g);
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
        // sig 必须带上「这张 combo 属于哪张卡」。切数据卡时等级/制式/WiFi 都可能
        // 一模一样，只比 sig 会走「状态没变」的快速路径 —— 而那时 drawable 里
        // 装的还是切换前的素材，主副卡角色已经换了，画出来的高低就不对。
        String sig = subIdOfRoot(primaryRoot) + ":" + lp + "/" + ls + "/" + (wifi ? "w" : "m");

        boolean already = main.getDrawable() instanceof VStack;
        if (already && !sig.equals(LAST.get(main))) {
            // 只有这一处会真正 setImageDrawable 重建图标；打出来方便对齐「闪一下」的时间点
            dbg("重建 drawable " + LAST.get(main) + " -> " + sig);
        }
        if (already && sig.equals(LAST.get(main))) {
            // 先还原主卡那一支（切卡后它可能上一轮被压成了 0×0 / GONE），再收副卡。
            // 顺序不能反：先收后还原会让还原把副卡一起放开。
            reviveChain(primaryRoot, primaryRoot, 0);
            if (secondRoot != null) collapse(secondRoot);
            collapseSiblings(container, primaryRoot);
            collapseInnerSignals(primaryRoot, main);
            return;
        }
        // 重建太快会闪：网络类型在 NR/LTE 之间抖动时，250ms 内只重建一次。
        // 但主卡变了必须立刻重建（不走节流）—— 否则这一帧画的还是旧角色。
        boolean roleChanged = !sig.equals(LAST.get(main));
        long nowMs = android.os.SystemClock.uptimeMillis();
        // 已经有图了才节流（防抖动）；首次合成必须马上做，否则就是肉眼可见的延迟
        if (already && !roleChanged && nowMs - lastBuildMs < 250) return;
        lastBuildMs = nowMs;

        LAST.put(main, sig);

        Drawable composed = build(a, b, lp, ls, main);
        if (composed == null) return;

        BUSY.set(Boolean.TRUE);
        try {
            // 左右不留额外留白：状态栏靠容器统一间距，自带 padding 会把这一格撑宽、WiFi 被推歪
            if (main.getPaddingLeft() != 0 || main.getPaddingRight() != 0) {
                main.setPadding(0, main.getPaddingTop(), 0, main.getPaddingBottom());
            }
            attachLayoutWatcher(main);
            // 颜色由系统 tint 驱动，不需要逐帧比对
            startColorTicker(main);
            startBadgeTicker(main.getContext());
            Speed.startSpeedTicker(main.getContext());
            OURS.put(main, composed);
            main.setImageDrawable(composed);
            // 还原主卡那一支：切数据卡后它可能还带着上一轮的 0×0 / GONE
            reviveChain(primaryRoot, primaryRoot, 0);
            if (secondRoot != null) collapse(secondRoot);
            // 兜底：这个容器里凡是还带信号的兄弟节点（副卡另一份、制式图那一份）全收掉，
            // 免得出现两个信号图标并排
            collapseSiblings(container, primaryRoot);
            // 同一个 combo 内部还有第二份 mobile_signal（副卡），外层容器收不掉它
            collapseInnerSignals(primaryRoot, main);
            // 箭头占位：切卡时新 combo 是新建的，这一帧就把它的宽度按已知自然宽度钉住，
            // 免得先按窄的那一版铺出来、数据恢复后再跳宽（整排 +21px）
            pinDataInoutIn(container);
            tighten(main);
            // 状态已确定，再补一次箭头位置：WiFi↔移动数据切换时
            // 前面 applyWifiRule 读到的 wifi 标志可能还是旧值，箭头会漏掉一次下移。
            try { dropDataInout(main); } catch (Throwable ignore) {}
        } finally {
            BUSY.set(Boolean.FALSE);
        }
    }

    /** 用 C17 原素材合成：可选制式标 + 主卡 + 副卡 */
    private static Drawable build(Drawable a, Drawable b, int lp, int ls, ImageView main) {
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

    /** 挂过宽度看门狗的 combo */
    private static final java.util.WeakHashMap<ViewGroup, Boolean> WIDTH_WATCHED =
            new java.util.WeakHashMap<>();

    /** 在 combo 子树里找 data_inout，按已知自然宽度钉住占位 */
    private static void pinDataInoutIn(View v) {
        if (v == null) return;
        if ("data_inout".equals(entryName(v))) {
            keepDataInoutWidth(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) pinDataInoutIn(g.getChildAt(i));
        }
    }

    /** 递归打子 view 的名字/类名/宽度/可见性，用来定位宽度变化来自哪一层 */
    private static void dumpKids(StringBuilder sb, ViewGroup g, int depth) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            sb.append(' ').append(entryName(c)).append('(').append(c.getClass().getSimpleName()).append(')')
                    .append('=').append(c.getWidth()).append('/').append(c.getVisibility())
                    .append('@').append(Integer.toHexString(System.identityHashCode(c)));
            if (depth > 0 && c instanceof ViewGroup) dumpKids(sb, (ViewGroup) c, depth - 1);
        }
    }

    /**
     * 宽度看门狗：这一格宽度一变，右侧整排就会横移（用户看到的「顶一下」）。
     * 变化时把每个子 view 的宽度与可见性打进日志，直接指认是哪个子 view 干的。
     */
    private static void watchComboWidth(final ViewGroup combo) {
        if (combo == null) return;
        if (WIDTH_WATCHED.containsKey(combo)) return;
        WIDTH_WATCHED.put(combo, Boolean.TRUE);
        combo.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                int w = r - l, ow = or - ol;
                if (w == ow || (w <= 0 && ow <= 0)) return;
                try {
                    StringBuilder sb = new StringBuilder("combo 宽度 " + ow + " -> " + w
                            + " left " + ol + " -> " + l
                            + " parentW=" + (v.getParent() instanceof View
                                    ? ((View) v.getParent()).getWidth() : -1)
                            + " kids:");
                    dumpKids(sb, combo, 2);
                    dbg(sb.toString());
                } catch (Throwable ignore) {}
            }
        });
    }

    /**
     * 制式 → 显示文字。
     *
     * 顺序要紧：先判 5ga 再判 5g，否则 "ic_s17_5ga" 里也含 "5g"，5GA 会被吞成 5G。
     * WiFi 连接时移动数据不跑流量，制式标直接收掉（返回空串）。
    // ==================== 制式判定：与系统同源（TelephonyDisplayInfo） ====================

    /**
     * 系统状态栏判 5G / 5GA 用的不是 getDataNetworkType()，而是 TelephonyDisplayInfo：
     *   - overrideNetworkType == NR_NSA / NR_NSA_MMWAVE → 显示 5G
     *   - overrideNetworkType == NR_ADVANCED          → 显示 5GA
     *   - override == NONE 时再看 networkType（NR → 5G，LTE → 4G）
     *
     * 只认 getDataNetworkType() == NR(20) 的做法有两个坑：
     *   1) NSA 组网（LTE 锚点）下 getDataNetworkType() 仍报 LTE，5G/5GA 分支根本进不去；
     *   2) 5GA 的判据 getTelephonyDisplayInfo() 是**隐藏 API**，android.jar 里没有，
     *      全靠反射，失败一次就静默返回 false —— 表现就是「永远只有 5G」。
     *
     * 这里改成用公开的 TelephonyCallback.DisplayInfoListener 订阅（系统就是推这个），
     * 反射只作兜底，并且把读到的值打到日志里，方便在没有 5GA 的地方也能核对。
     */
    private static volatile int DISPLAY_OVERRIDE = -1;   // OVERRIDE_NETWORK_TYPE_*
    private static volatile int DISPLAY_NETTYPE = -1;    // TelephonyManager.NETWORK_TYPE_*
    private static volatile int DISPLAY_SUB = -1;
    private static android.telephony.TelephonyCallback DISPLAY_CALLBACK;
    private static boolean displayCbWarned;
    private static boolean displayLogOnce;

    /** 独立成类：老系统上没有 TelephonyCallback 时，只有真正实例化这里才会报错并被 catch 住 */
    private static final class DisplayCb extends android.telephony.TelephonyCallback
            implements android.telephony.TelephonyCallback.DisplayInfoListener {
        @Override public void onDisplayInfoChanged(android.telephony.TelephonyDisplayInfo info) {
            try {
                int o = info.getOverrideNetworkType();
                int n = info.getNetworkType();
                if (o == DISPLAY_OVERRIDE && n == DISPLAY_NETTYPE) return;
                DISPLAY_OVERRIDE = o;
                DISPLAY_NETTYPE = n;
                markLabelDirty();
                dbg("制式来源 netType=" + n + " override=" + o + " sub=" + DISPLAY_SUB);
            } catch (Throwable ignore) {}
        }
    }

    /** 订阅「正在跑数据那张卡」的 DisplayInfo；切卡时重订阅 */
    private static synchronized void ensureDisplayCallback(android.content.Context ctx) {
        if (ctx == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT < 31) return;
            int sub = activeDataSubId(ctx);
            if (sub <= 0) return;
            if (DISPLAY_CALLBACK != null && sub == DISPLAY_SUB) return;
            android.telephony.TelephonyManager base = (android.telephony.TelephonyManager)
                    ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            if (base == null) return;
            if (DISPLAY_CALLBACK != null && DISPLAY_SUB > 0) {
                try {
                    base.createForSubscriptionId(DISPLAY_SUB)
                            .unregisterTelephonyCallback(DISPLAY_CALLBACK);
                } catch (Throwable ignore) {}
            }
            android.telephony.TelephonyCallback cb = new DisplayCb();
            base.createForSubscriptionId(sub).registerTelephonyCallback(ctx.getMainExecutor(), cb);
            DISPLAY_CALLBACK = cb;
            DISPLAY_SUB = sub;
            // 等回调推送第一帧；这期间走反射兜底
            if (DISPLAY_OVERRIDE < 0) DISPLAY_NETTYPE = -1;
            dbg("已订阅 DisplayInfoListener sub=" + sub);
        } catch (Throwable t) {
            if (!displayCbWarned) {
                displayCbWarned = true;
                try {
                    MODULE.log(android.util.Log.WARN, "StackSig",
                            "DisplayInfoListener 注册失败，退回反射：" + t);
                } catch (Throwable ignore) {}
            }
        }
    }

    /** 兜底：反射读隐藏的 getTelephonyDisplayInfo()，返回 {netType, override}，失败返回 null */
    private static int[] displayInfoReflect(android.content.Context ctx) {
        try {
            android.telephony.TelephonyManager tm = dataTelephonyManager(ctx);
            if (tm == null) return null;
            Object di = null;
            try {
                java.lang.reflect.Method m = tm.getClass().getMethod("getTelephonyDisplayInfo");
                m.setAccessible(true);
                di = m.invoke(tm);
            } catch (Throwable ignore) {}
            if (di == null) return null;
            int netType = -1, override = -1;
            for (java.lang.reflect.Field f : di.getClass().getDeclaredFields()) {
                String n = f.getName();
                if (!n.contains("NetworkType")) continue;
                f.setAccessible(true);
                Object v = f.get(di);
                if (!(v instanceof Integer)) continue;
                if (n.contains("override")) override = (Integer) v;
                else netType = (Integer) v;
            }
            if (netType < 0 && override < 0) return null;
            if (!displayLogOnce) {
                displayLogOnce = true;
                dbg("制式来源(反射) netType=" + netType + " override=" + override);
            }
            return new int[]{netType, override};
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 上一次判定出的制式素材名：网络类型拿不到时沿用它，避免 5G/4G 之间乱跳 */
    private static volatile String LAST_BADGE_NAME;

    // ==================== 省电：把轮询降级成「事件驱动 + 缓存」 ====================
    //
    // 800ms ticker 原本每轮都要问：
    //   SubscriptionManager.getDefaultDataSubscriptionId() / getAllNetworks() / getDataNetworkType()
    // 三个 binder，只为确认「有没有变」。现在这些输入全都已经有事件源：
    //   - 制式：TelephonyCallback.DisplayInfoListener（系统推送）
    //   - Wi-Fi 上下线：ConnectivityManager.NetworkCallback
    //   - 切卡：ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED 广播
    // 所以 ticker 只负责「脏了才重算」，其余时候零 binder 调用。

    private static volatile boolean WIFI_CACHED;
    private static volatile long WIFI_CACHED_MS = -100000L;
    private static volatile String CACHED_LABEL = "";
    private static volatile boolean LABEL_DIRTY = true;

    private static volatile android.content.Context APP_CTX;
    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * 输入变了：标记脏 + **立刻推一次**，不等 ticker。
     * 这样制式标反而比以前更快（以前最坏等 800ms，现在同一帧内）。
     */
    static void markLabelDirty() {
        LABEL_DIRTY = true;
        final android.content.Context c = APP_CTX;
        if (c == null) return;
        UI.post(new Runnable() {
            @Override public void run() {
                try { pushBadgeLabel(c); } catch (Throwable ignore) {}
            }
        });
    }

    /** Wi-Fi 状态：5 秒内复用上次结果，上下线事件会主动把它置脏 */
    private static boolean wifiCached(android.content.Context ctx) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - WIFI_CACHED_MS > 5000L) {
            WIFI_CACHED = isWifiConnected(ctx);
            WIFI_CACHED_MS = now;
        }
        return WIFI_CACHED;
    }

    /** 按当前数据网络制式选制式标（2G/3G/4G/5G/5GA） */
    private static String badgeNameFor(final android.content.Context ctx) {
        String name = badgeNameFor0(ctx);
        if (name != null) {
            LAST_BADGE_NAME = name;
            return name;
        }
        // 完全判不出来（网络类型取不到）：沿用上一次，没记录过才退回 4G
        String last = LAST_BADGE_NAME;
        return last != null ? last : "ic_s17_4g";
    }

    /** 判定主体；判不出来返回 null（由 badgeNameFor 兜底） */
    private static String badgeNameFor0(final android.content.Context ctx) {
        ensureDisplayCallback(ctx);
        int override = DISPLAY_OVERRIDE;
        int netType = DISPLAY_NETTYPE;
        if (override < 0 && netType < 0) {          // 回调还没到：反射兜底
            int[] di = displayInfoReflect(ctx);
            if (di != null) {
                netType = di[0];
                override = di[1];
            }
        }
        // ① override 优先：NSA 与 5GA 都属于「5G 系」，此时 data network type 往往还是 LTE
        if (override == android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
            return "ic_s17_5ga";
        }
        if (override == android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
                || override == android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE) {
            return "ic_s17_5g";
        }
        // ② 4G 的两种 override（LTE_CA / LTE_ADVANCED_PRO）也归 4G，别落进网络类型分支
        if (override == android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA
                || override == android.telephony.TelephonyDisplayInfo
                        .OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO) {
            return "ic_s17_4g";
        }
        // ③ 最后看网络类型（display info 的不行就问 getDataNetworkType）
        int t = netType > 0 ? netType : dataNetworkType(ctx);
        switch (t) {
            case android.telephony.TelephonyManager.NETWORK_TYPE_NR:      // 20
                return "ic_s17_5g";
            case android.telephony.TelephonyManager.NETWORK_TYPE_LTE:     // 13
            case 19:                                                      // LTE_CA（隐藏常量，写字面量）
                return "ic_s17_4g";
            case android.telephony.TelephonyManager.NETWORK_TYPE_UMTS:    // 3
            case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0:  // 5
            case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A:  // 6
            case android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA:   // 8
            case android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA:   // 9
            case android.telephony.TelephonyManager.NETWORK_TYPE_HSPA:    // 10
            case android.telephony.TelephonyManager.NETWORK_TYPE_IDEN:    // 11
            case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_B:  // 12
            case android.telephony.TelephonyManager.NETWORK_TYPE_EHRPD:   // 14
            case android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP:   // 15
            case android.telephony.TelephonyManager.NETWORK_TYPE_TD_SCDMA: // 17
                return "ic_s17_3g";
            case android.telephony.TelephonyManager.NETWORK_TYPE_GPRS:    // 1
            case android.telephony.TelephonyManager.NETWORK_TYPE_EDGE:    // 2
            case android.telephony.TelephonyManager.NETWORK_TYPE_CDMA:    // 4
            case android.telephony.TelephonyManager.NETWORK_TYPE_1xRTT:   // 7
            case android.telephony.TelephonyManager.NETWORK_TYPE_GSM:     // 16
                return "ic_s17_2g";
            default:
                break;
        }
        // 判不出来就交给上层沿用上一次（不能硬说 5G，也不能硬说 4G）
        return null;
    }

    /** 数据卡的 getDataNetworkType()，取不到返回 -1 */
    private static int dataNetworkType(android.content.Context ctx) {
        try {
            android.telephony.TelephonyManager tm = dataTelephonyManager(ctx);
            if (tm != null) return tm.getDataNetworkType();
        } catch (Throwable ignore) {}
        return -1;
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

    static String badgeLabel(android.content.Context ctx) {
        if (ctx == null) return "";
        // 没脏就直接用上次的结果：这条路径每 ticker 都会走到，不能有 binder 调用
        if (!LABEL_DIRTY) return CACHED_LABEL;
        LABEL_DIRTY = false;
        if (wifiCached(ctx)) {
            CACHED_LABEL = "";
            return "";
        }
        String n = badgeNameFor(ctx);
        String label;
        if (n == null) label = "";
        else if (n.contains("5ga")) label = "5GA";
        else if (n.contains("5g")) label = "5G";
        else if (n.contains("4g")) label = "4G";
        else if (n.contains("3g")) label = "3G";
        else label = "2G";
        CACHED_LABEL = label;
        return label;
    }

    /** 制式变化时直接把 label 推给已经挂上的 TextView，绕过 stack() 的去重 */
    private static void pushBadgeLabel(android.content.Context ctx) {
        if (BADGE_COMBOS.isEmpty()) return;
        startBadgeColorTicker();
        String label = badgeLabel(ctx);
        // 只有「当前主卡」那一个 combo 上的制式标该显示。
        // 切数据卡后两张卡各有一个 combo，副卡那个虽然被压成 0×0，
        // 挂在它身上的 TextView 仍然会被绘制 —— 表现为画面上并排两枚
        // 制式标叠在一起（5G 和 5G! 重合）。
        View primaryCombo = MAIN == null ? null : MAIN.get();
        View primaryHost = null;
        if (primaryCombo != null) {
            ViewParent cp = primaryCombo.getParent();
            while (cp instanceof View && !"mobile_combo".equals(entryName((View) cp))) {
                ViewParent up = ((View) cp).getParent();
                if (!(up instanceof View)) { cp = null; break; }
                cp = up;
            }
            if (cp instanceof View) primaryHost = (View) cp;
        }
        // 制式标只能画在「屏幕上真正在显示的那一个 combo」上。
        // 光看 MAIN 不够：MAIN 指向的可能是下拉栏 / 锁屏 / 已被系统丢弃的那一份，
        // 一旦它占了主位，状态栏这个 combo 就会被判成非主卡 → 制式标 INVISIBLE，
        // 而唯一被显示的那一份在屏幕外 —— 表现就是 5G 偶尔整块消失。
        // 所以：MAIN 指向的那份必须「活着」才算数，否则退回活着的 combo 里面积最大的那个。
        ViewGroup target = null;
        if (primaryHost instanceof ViewGroup && isLiveCombo((ViewGroup) primaryHost)) {
            target = (ViewGroup) primaryHost;
        }
        if (target == null) {
            int best = 0;
            for (ViewGroup g : BADGE_COMBOS.keySet()) {
                if (!isLiveCombo(g)) continue;
                int area = g.getWidth() * g.getHeight();
                if (area > best) { best = area; target = g; }
            }
        }
        dbgBadge("push", ctx, label, primaryHost);
        for (final ViewGroup g : BADGE_COMBOS.keySet()) {
            if (g == null) continue;
            boolean isPrimary = (target == null) || (g == target);
            android.widget.TextView gtv = BADGE_VIEWS.get(g);
            if (gtv != null && !isPrimary) {
                // 副卡的制式标：藏起来但不清文本，切回来时能立刻显示
                if (gtv.getVisibility() != View.INVISIBLE) gtv.setVisibility(View.INVISIBLE);
                continue;
            }
            if (gtv != null && isPrimary && gtv.getVisibility() != View.VISIBLE) {
                gtv.setVisibility(View.VISIBLE);
            }
            // 写文本 —— 这一行是整个制式刷新的核心。
            attachBadgeText(g, label);
            // 看门狗：主 combo 上的制式标必须是「可见 + 有文本 + 有尺寸 + 还在这个 combo 里」。
            // WiFi 切换、combo 重建、父容器回收等竞态都可能把它弄丢一次，
            // 这里 800ms 内发现不对就原地重建，不留「消失到重启」的死角。
            if (isPrimary && !label.isEmpty()) {
                android.widget.TextView fix = BADGE_VIEWS.get(g);
                if (fix != null && !badgeOk(fix, g)) rebadge(g, fix);
            }
            // 边距校正只在「刚创建/刚复用」时做一次（attachBadgeText 内部已经做过），
            // 不再每 tick 往队列里塞一个 Runnable —— 14 个站点时那是 17 次/秒的分配。
        }
    }

    /** 制式标当前是否「真的画得出来」 */
    private static boolean badgeOk(android.widget.TextView tv, ViewGroup combo) {
        try {
            return tv.getParent() == combo
                    && tv.isAttachedToWindow()
                    && tv.getVisibility() == View.VISIBLE
                    && tv.getWidth() > 0 && tv.getHeight() > 0
                    && tv.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int badgeFixCount = 0;

    /**
     * 制式标丢了就地重建。
     *
     * 只做「摘掉旧的、按当前尺寸重新挂一个」，不改任何布局参数，
     * combo 尺寸没变时重建一次就回到原样。
     */
    private static void rebadge(ViewGroup combo, android.widget.TextView tv) {
        if (combo == null || tv == null) return;
        try {
            BADGE_VIEWS.remove(combo);
            try { combo.removeView(tv); } catch (Throwable ignore) {}
            attachBadgeWhenMeasured(combo, combo.getContext());
            badgeFixCount++;
            if (badgeFixCount <= 20) {
                try {
                    MODULE.log(android.util.Log.WARN, "StackSig",
                            "制式标丢失，已重建（第 " + badgeFixCount + " 次）");
                } catch (Throwable ignore) {}
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

    /**
     * 这个 view 是不是在下拉通知栏里。
     *
     * 下拉面板有自己的根视图 NotificationShadeWindowView，状态栏那一份在它的外部，
     * 所以按祖先链上有无这个类名就能区分。宁可在拿不到类名时返回 false（照常处理
     * 状态栏那一份），也不要误判导致状态栏失去效果。
     */
    private static boolean isInShade(View v) {
        try {
            View cur = v;
            for (int i = 0; i < 24 && cur != null; i++) {
                String cn = cur.getClass().getName();
                if (cn.contains("NotificationShadeWindowView")
                        || cn.contains("NotificationPanelView")
                        || cn.contains("NotificationsQuickSettingsContainer")) {
                    return true;
                }
                ViewParent p = cur.getParent();
                cur = (p instanceof View) ? (View) p : null;
            }
        } catch (Throwable ignore) {}
        return false;
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
        APP_CTX = ctx;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // 无条件推一次。attachBadgeText 内部会比对文本，值没变时不会 setText，
                    // 所以这里不缓存、不比对 —— 缓存一旦和屏幕脱节就再也推不动了。
                    pushBadgeLabel(ctx);
                } catch (Throwable ignore) {}
                // 真正的刷新已经全部是事件驱动（DisplayInfoCallback / NetworkCallback / 广播），
                // 这里只是漏事件时的兜底，所以放到 2500ms —— 常驻唤醒越少越好。
                h.postDelayed(this, 2500);
            }
        }, 800);
    }

    /**
     * 状态栏换色时系统不一定通知到我们（时钟会变但我们收不到回调），
     * 所以起一个轻量 ticker：只做 invalidateSelf，让 draw() 里的 syncTint 去读时钟色。
     * 不重建 drawable、不请求布局，成本很低。
     */
    private static void startColorTicker(final View any) {
        if (tickerStarted) return;
        tickerStarted = true;
        // 事件驱动优先：切卡广播一到就重算，不等轮询
        try { startSlotReceiver(any.getContext()); } catch (Throwable ignore) {}
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    boolean anyVisible = false;
                    for (ImageView v : SITES.keySet()) {
                        if (v == null) continue;
                        boolean live = v.isAttachedToWindow() && v.getVisibility() == View.VISIBLE;
                        if (live) anyVisible = true;
                        // 只有挂在窗口上、真在显示的站点才重绘；detach 的直接跳过
                        if (!live) continue;
                        Drawable d = v.getDrawable();
                        if (d != null) d.invalidateSelf();
                    }
                    // 兜底：切数据卡时系统会重排/重建整条链路，可能一路下来没有任何
                    // 图标回调触发 stack()，结果上轮被压成 0×0 的那一支就一直藏着，
                    // 状态栏上信号整个消失。这里每秒探一次：只要一个可见的信号 view
                    // 都没有，就按当前 activeSubId 重跑一遍判定。
                    // 切数据卡后系统会重建/替换图标链路，但可能一路下来没有任何
                    // setImageDrawable 回调，stack() 就不会被叫起来 —— 于是主副卡
                    // 判定永远停在旧值，画出来的高低还是切换前的。
                    // 这里盯住 activeDataSubId：它一变就把所有已知站点重跑一遍。
                    int act = -1;
                    for (ImageView v : SITES.keySet()) {
                        if (v == null || !v.isAttachedToWindow()) continue;
                        act = activeDataSubId(v.getContext());
                        break;
                    }
                    if (act > 0 && act != lastActiveSub) {
                        lastActiveSub = act;
                        refreshAllSitesNow();
                    }
                    if (!anyVisible) refreshAllSitesNow();
                } catch (Throwable ignore) {}
                // 主通道是 DisplayInfoCallback / NetworkCallback / 系统广播，这里只是兜底。
                // 1500ms 足够（真有变化都是推过来的），也把常驻唤醒压到最低。
                h.postDelayed(this, 1500);
            }
        }, 1500);
    }   // 强引用，抓到就记住
    private static java.lang.ref.WeakReference<ImageView> MAIN;
    /** 上一次看到的 activeDataSubId；变化即视为「切数据卡」，强制所有站点重算 */
    private static int lastActiveSub = -1;

    private static boolean slotReceiverStarted = false;

    /**
     * 盯住「默认数据卡」广播，切换那一刻立刻重算，不再等 ticker 轮询。
     *
     * 之前只有 1 秒一次的 activeSubId 比对，最坏要等整整一秒才开始恢复，
     * 连切几次就会明显感觉「卡半天才回来」。广播是系统同步发出的，
     * 收到就重排，恢复时间从 ~1s 降到一帧。
     */
    private static void startSlotReceiver(android.content.Context ctx) {
        if (slotReceiverStarted) return;
        slotReceiverStarted = true;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            // 这是 TelephonyManager 的隐藏常量，编译期拿不到，直接写字面量
            f.addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
            f.addAction("android.intent.action.ACTION_SUBINFO_RECORD_UPDATED");
            f.addAction("android.intent.action.SERVICE_STATE");
            final android.content.BroadcastReceiver rev = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    String action = i == null ? null : i.getAction();
                    // 只有「数据卡/订阅真的变了」才值得清缓存强制重建（主副卡角色可能互换）。
                    // SERVICE_STATE 每次制式变化（5G↔4G）都会来一发，那时角色没变，
                    // 强制重建只会让图标白闪一下 —— 交给 sig 比对 + 节流去判断。
                    boolean force = "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"
                            .equals(action)
                            || "android.intent.action.ACTION_SUBINFO_RECORD_UPDATED".equals(action);
                    markLabelDirty();
                    WIFI_CACHED_MS = -100000L;      // 切卡后 Wi-Fi 判定也重新问一次
                    dbg("广播 " + action + " force=" + force);
                    refreshAllSitesNow(force);
                }
            };
            android.content.Context app = ctx.getApplicationContext();
            // targetSdk 34+ 必须带 flags，否则 registerReceiver 直接抛 SecurityException（老代码把异常吞了，
            // 于是「广播驱动切卡恢复」从来没生效过，全靠 400ms 轮询兜底）
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(rev, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(rev, f);
            }
            dbg("已注册切卡/服务状态广播");
        } catch (Throwable t) {
            try {
                MODULE.log(android.util.Log.WARN, "StackSig", "注册广播失败：" + t);
            } catch (Throwable ignore) {}
        }
    }

    /** 立刻把所有已知站点重跑一遍判定（清掉 sig 缓存，强制重建） */
    static void refreshAllSitesNow() {
        refreshAllSitesNow(true);
    }

    /**
     * @param force true  = 清掉 sig 缓存，无条件重建（切数据卡、兜底自愈时用，角色可能已互换）
     *              false = 只重跑判定，交回 sig 比对决定要不要重建（制式变化等服务状态事件用，
     *                      避免 5G↔4G 这种与信号图无关的变化把图标白闪一下）
     */
    static void refreshAllSitesNow(final boolean force) {
        dbg("refreshAllSitesNow 站点=" + SITES.size() + " force=" + force);
        try {
            android.content.Context ctx = null;
            for (ImageView v : SITES.keySet()) {
                if (v == null) continue;
                if (ctx == null) ctx = v.getContext();
            }
            final android.content.Context c0 = ctx;
            for (final ImageView v : SITES.keySet()) {
                if (v == null) continue;
                v.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (force) LAST.remove(v);
                            stack(v);
                        } catch (Throwable ignore) {}
                    }
                });
            }
            // 制式标走独立通道，跟着一起立刻刷，不等 ticker
            if (c0 != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try { pushBadgeLabel(c0); } catch (Throwable ignore) {}
                    }
                }, 60);
            }
        } catch (Throwable ignore) {}
    }

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
                // 这一支和主卡信号共用一个 wrap_content 的 FrameLayout。
                // collapseKeepSpace 只把被收那一支的宽度压 0、高度保持原样，
                // 否则父容器高度会跟着塌（70→56），主卡信号矮 7px。
                collapseKeepSpace(p instanceof View ? (View) p : iv);
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
            for (int i = 0; i < container.getChildCount(); i++) {
                View ch = container.getChildAt(i);
                if (findSignal(ch) == null) continue;
                if (ch == primaryRoot) continue;
                // 同一张卡的另一份副本（0×0 或属于别的容器）不能收：它可能就是
                // 屏幕上真正显示的那一份，收掉等于把主卡信号一起干掉。
                if (subIdOfRoot(ch) == subIdOfRoot(primaryRoot)) continue;
                collapse(ch);
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 被我们收起来的节点（副卡的 combo / mobile_group）。系统每次刷图标都会改可见性，
     * 所以要在 hook 里持续把它们按回去，光收一次是不够的。
     */
    private static final java.util.WeakHashMap<View, Boolean> HIDDEN = new java.util.WeakHashMap<>();

    /**
     * 收掉一个「不该显示」的信号节点。
     *
     * 注意：**不能把尺寸压成 0**。这些节点和主卡信号共用同一个 FrameLayout，
     * 父容器是 wrap_content 的，把它压成 0 会让父容器高度一起塌下来
     * （实测 70 → 56），主卡信号跟着变矮 7px，表现为「切卡后信号整体上移」。
     * 正确做法：只设 INVISIBLE，保留它原本的占位尺寸。
     */
    private static void collapseKeepSpace(View v) {
        if (v == null) return;
        // 只隐藏「信号图标」这一种节点。combo 里还有 data_inout 箭头、制式图等，
        // 一刀切全设 INVISIBLE 会把箭头一起藏掉（实测切卡后箭头变灰消失）。
        if (!isSignalNode(v)) return;
        HIDDEN.put(v, Boolean.TRUE);
        try {
            if (v.getVisibility() != View.INVISIBLE) v.setVisibility(View.INVISIBLE);
        } catch (Throwable ignore) {}
    }

    /** 这个节点是不是「信号图标」本身（mobile_signal / fiveg 这一类） */
    private static boolean isSignalNode(View v) {
        if (v == null) return false;
        String n = entryName(v);
        if (n != null && (n.contains("signal") || n.contains("fiveg"))) return true;
        if (v instanceof android.widget.ImageView) {
            String desc = String.valueOf(v.getContentDescription());
            if (desc.contains("信号") || desc.toLowerCase().contains("signal")) return true;
        }
        return false;
    }

    private static void collapse(View v) {
        if (v == null) return;
        HIDDEN.put(v, Boolean.TRUE);
        forceHidden(v);
    }

    /**
     * 把上轮判定为「副卡」而现在应当显示的那一支还原。
     *
     * collapse() 会把 view 压成 0×0 + GONE 并记进 HIDDEN。切数据卡后系统会把原来
     * 那一支重新启用（visibility 变回 VISIBLE、尺寸重算），但我们记的还是旧实例，
     * 只要主副卡角色一换，被压成 0×0 的就不再是副卡 —— 结果是两支都被压掉，
     * 状态栏上信号整个消失。每次判完主卡都要把不该藏的还原回来。
     */
    private static void revive(View v) {
        if (v == null) return;
        if (!HIDDEN.containsKey(v)) return;
        if (DBG) dbg("revive " + entryName(v) + " w=" + v.getWidth() + " x=" + v.getX());
        HIDDEN.remove(v);
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.width == 0) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(lp);
            }
        } catch (Throwable ignore) {}
        try {
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        } catch (Throwable ignore) {}
    }

    /**
     * 还原主卡这一支上被 collapse 过的节点。
     *
     * 只还原「位于主卡这条链上的祖先/自身」，**不下钻到兄弟子树**。
     * 早先这里是整棵子树递归 revive，会把别的卡（副卡）那个实例一起放出来，
     * 于是状态栏出现两个信号图标并排 —— 副卡那个本来该收掉的。
     * 判据：节点本身，或者从它往上走能走到主卡根，才还原。
     */
    private static void reviveChain(View node, View root, int depth) {
        if (node == null || root == null || depth > 10) return;
        // 非信号节点（箭头 data_inout、制式图等）一律恢复可见 ——
        // 它们不该被 HIDDEN 影响，之前整支加起来会让箭头消失。
        if (HIDDEN.containsKey(node) && !isSignalNode(node)) { revive(node); }
        View cur = node;
        boolean onChain = false;
        for (int i = 0; i < 10 && cur != null; i++) {
            if (cur == root) { onChain = true; break; }
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        if (!onChain) return;
        if (HIDDEN.containsKey(node)) revive(node);
        if (node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0; i < g.getChildCount(); i++) reviveChain(g.getChildAt(i), root, depth + 1);
        }
    }

    /**
     * 收起副卡那一支。
     *
     * **绝不改 LayoutParams**。改动会触发重新 measure，而父容器是 wrap_content 的，
     * 被压成 0 之后整条链路的高度基准就变了（实测 70 → 56），主卡信号跟着矮 7px ——
     * 这正是「切数据卡后信号整体上移、只有重启状态栏才恢复」的成因。
     * ColorOS 17 之所以没这毛病，是因为它只有一个 View，压根没有「收起另一个」这回事。
     *
     * 这里只改可见性：INVISIBLE 保留占位尺寸，不参与绘制，父容器高度不受影响。
     */
    static void forceHidden(View v) {
        try {
            if (DBG) dbg("collapse " + entryName(v) + " w=" + v.getWidth() + " x=" + v.getX());
            if (v.getVisibility() != View.INVISIBLE) v.setVisibility(View.INVISIBLE);
        } catch (Throwable ignore) {}
        // 只压宽度、**不动高度**。
        //   - 高度不能动：动高度会让 wrap_content 的父链路重新测量，高度基准从 70
        //     掉到 56，主卡信号跟着矮 7px，这就是「切卡后信号整体上移」的成因。
        //   - 宽度必须压：INVISIBLE 仍然占着 74px，状态栏中间会空出一大块
        //     （实测闹钟到信号之间空了 94px）。
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.width != 0) {
                lp.width = 0;
                v.setLayoutParams(lp);
            }
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

    /**
     * 当前走流量那张卡的 subId。取不到返回 -1。
     *
     * 这是 ColorOS 17 判主卡的同一判据：系统侧
     * MobileConnectionsRepositoryImpl.activeMobileDataSubscriptionId 就是由
     * TelephonyCallback.onActiveDataSubscriptionIdChanged 推来的。
     * 注意 getDefaultDataSubscriptionId() 在切卡瞬间会短暂不同步，
     * 所以先读 registry 里的 mActiveDataSubId，读不到再退回前者。
     */
    private static int activeDataSubId(android.content.Context ctx) {
        try {
            android.telephony.SubscriptionManager sm =
                    android.telephony.SubscriptionManager.from(ctx);
            if (sm != null) {
                int id = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
                if (id > 0) return id;
            }
        } catch (Throwable ignore) {}
        return -1;
    }

    /**
     * 从 container 的一个「含信号」子视图里问出它对应的 subId。
     * OplusModernStatusBarMobileView / ModernStatusBarMobileView 上有 public getSubId()，
     * 但它不一定就在这一层，所以往下一层找。找不到返回 -1。
     */
    private static int subIdOfRoot(View root) {
        if (root == null) return -1;
        int direct = invokeSubId(root);
        if (direct >= 0) return direct;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                int s = invokeSubId(g.getChildAt(i));
                if (s >= 0) return s;
            }
        }
        return -1;
    }

    /**
     * 候选「是不是真的画出来了」的分数。0 = 没画（0×0 或 GONE），越大越可信。
     * 同一个 subId 会有多份实例，只有真正有尺寸的那一份才是屏幕上的图标。
     */
    private static int score(View v) {
        if (v == null) return 0;
        if (v.getVisibility() == View.GONE) return 0;
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return 0;
        return v.getVisibility() == View.VISIBLE ? (w * h) : 1;
    }

    /** 反射调 getSubId()，失败返回 -1 */
    private static int invokeSubId(Object o) {
        if (o == null) return -1;
        try {
            java.lang.reflect.Method m = o.getClass().getMethod("getSubId");
            Object v = m.invoke(o);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignore) {}
        return -1;
    }

    /** 按订阅号精确取第二张卡的信号等级（0..4）；拿不到返回 -1 */
    private static int secondaryLevelBySub(android.content.Context ctx) {
        try {
            android.telephony.SubscriptionManager sm =
                    android.telephony.SubscriptionManager.from(ctx);
            java.util.List<android.telephony.SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null || list.size() < 2) return -1;
            // 取「不是当前数据卡」的那张。不能写 list.get(1) —— 列表顺序不保证
            // 就是卡一/卡二，切数据卡后还会变，会拿成同一张卡。
            int active = activeDataSubId(ctx);
            int subId = -1;
            for (android.telephony.SubscriptionInfo si : list) {
                int id = si.getSubscriptionId();
                if (active > 0 && id == active) continue;
                subId = id;
                break;
            }
            if (subId < 0) return -1;
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
        try { return Math.max(0, Math.min(4, fallback.getLevel())); } catch (Throwable ignore) {}
        return 0;
    }

    /**
     * 当前是不是「连着 WiFi」。
     *
     * 判据只有一条：ConnectivityManager 里此刻**存在一个已连接的 WiFi 网络**
     * （getAllNetworks + TRANSPORT_WIFI）。网络一断它就从这个列表里消失，
     * 不存在「缓存没刷新」的问题。
     *
     * 老实现先问 WifiManager.getConnectionInfo()，且只要 info 非 null 就 return 终局结论。
     * 实测 ColorOS 在「连着 WiFi → 走出范围断开」或「自动连上又被关掉」之后，
     * getConnectionInfo() 仍会返回上一次的 SSID 与 RSSI（既不是 <unknown ssid>，
     * 也不是 -127），于是这里长期返回 true → badgeLabel() 永远给空串 →
     * attachBadgeText() 把制式标设成 GONE 并清空文本，5G 再也不回来，
     * 只有重启 SystemUI 才恢复。这就是「WiFi 断开后制式标消失」的成因。
     *
     * 拿不到网络列表时才用 supplicant 状态兜底 —— 只看状态机，不看 SSID/RSSI。
     */
    private static boolean isWifiConnected(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network[] all = cm == null ? null : cm.getAllNetworks();
            if (all != null) {
                for (android.net.Network n : all) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null) continue;
                    if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return true;
                }
                // 列表拿到了、里面一个 WiFi 网络都没有 → 一定没连 WiFi，直接否掉。
                // 绝不能再去问 WifiManager：它的 SSID/RSSI 是缓存，会把「已断开」说成「还连着」。
                return false;
            }
        } catch (Throwable ignore) {}
        // 兜底：只看 supplicant 状态机（COMPLETED 才是真连上）
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null
                        && info.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED) {
                    return true;
                }
            }
        } catch (Throwable ignore) {}
        return false;
    }

    /** combo 是不是「真的在屏幕上」：挂在窗口上、有尺寸、没被我们收掉 */
    private static boolean isLiveCombo(ViewGroup g) {
        if (g == null) return false;
        try {
            if (!g.isAttachedToWindow()) return false;
            if (g.getVisibility() != View.VISIBLE) return false;
            return g.getWidth() > 0 && g.getHeight() > 0;
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
        // Wi-Fi 上下线：缓存立刻失效，由下一个 ticker 周期（最多 2.5s）刷。
        // 这里**不**直接 push：NetworkCallback 的能力变化可能很频繁（信号强度抖动），
        // 每次都立刻重算就等于把「事件风暴」搬进 UI 线程。
        WIFI_CACHED_MS = -100000L;
        LABEL_DIRTY = true;
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