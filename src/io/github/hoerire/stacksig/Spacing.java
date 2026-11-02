package io.github.hoerire.stacksig;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 状态栏那一排在布局时的挂点。
 *
 * **这里不改任何间距。** padding / margin / 宽度 / translationX 一律不碰，位置完全交给
 * 系统的 StatusIconContainer.updateStates()。2026-11-02 起用户明确要求间距走原厂，
 * 历史上那套「按 slot 配 padding」「给蓝牙单独留 1px」的代码已全部删除，不要再加回来。
 *
 * 现在只做两件事，都挂在 updateStates() 这次布局上：
 *   1. 登记窗口里的网速格，第一次见到时启动网速 ticker；
 *   2. DBG 打开时打一行 `bar:` 快照（各格宽度 / padding / x / 可见性 / 箭头位移）。
 *
 * 之所以挂 updateStates 而不是一次性的 setPadding：它每次布局都会跑，
 * 是「这一排刚排完」的最可靠时机，网速 ticker 也能跟着第一批图标一起起来。
 */
final class Spacing {

    private Spacing() {}

    static int hookBarLayout(XposedModule m, ClassLoader cl) {
        try {
            Class<?> sic = Class.forName(
                    "com.android.systemui.statusbar.phone.StatusIconContainer", false, cl);
            Method st = sic.getDeclaredMethod("updateStates");
            m.hook(st).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain ch) throws Throwable {
                    try { onLayout((ViewGroup) ch.getThisObject()); } catch (Throwable ignore) {}
                    return ch.proceed();
                }
            });
            return 1;
        } catch (Throwable t) {
            try { Engine.MODULE.log(android.util.Log.WARN, "StackSig", "iconGap hook failed: " + t); }
            catch (Throwable ignore) {}
            return 0;
        }
    }

    /** 模块编译期拿不到 SystemUI 的类，只能按类名判断（StatIconView / *StatusBarIconView / *combo） */
    private static boolean isStatusIcon(View v) {
        // 先按 id 名判断（mobile_combo / wifi_combo / network_speed 都在 id 上）
        String id = Engine.entryName(v);
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

    private static long lastDumpMs = 0;
    private static long lastBadMs = 0;

    private static void onLayout(ViewGroup g) {
        dumpBar(g);
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            if (!isStatusIcon(v)) continue;
            Speed.rememberSpeedView(v);
            // 网速 ticker 在这里启动：这一排每次布局都会经过，
            // 比挂在 stack() 里可靠（stack 只在有信号图标时才执行）。
            Speed.ensureTicker(v.getContext());
        }
    }

    /**
     * 诊断：把 StatusIconContainer 每个子 view 的宽度 / 可见性 / padding / x 打出来。
     *
     * 查「位置不对」先看这一行：`p=` 是格子自己的 padding，`x=` 是系统算出来的横坐标，
     * `pad=` 是容器内边距。三者都没被模块改过（模块不碰间距），
     * 所以任何异常都来自系统自身或 drawable 的内在留白。
     */
    private static void dumpBar(ViewGroup g) {
        if (!Engine.DBG) return;
        // 只打「真的显示在屏幕上」的那一个容器：下拉栏 / 锁屏里还有几份同结构的
        // StatusIconContainer，打错了会得出「inout 和网速都正常」的结论，
        // 而屏幕上那份其实是另一套状态。isShown() 把祖先可见性和窗口挂载都算进来。
        boolean shown;
        try { shown = g.isShown() && g.getWidth() > 0; } catch (Throwable t) { shown = false; }
        if (!shown) return;
        // 再按「屏幕绝对位置」筛一遍：状态栏那一排贴着屏幕顶部（y≈0）、靠右。
        // 下拉控制中心里还有一份同结构容器（w≈370~480、x 在屏幕中部），
        // 它也有 mobile_combo，光看 isShown + combo 会把它当成状态栏那排。
        int[] loc = new int[2];
        try { g.getLocationOnScreen(loc); } catch (Throwable t) { loc[0] = loc[1] = -1; }
        if (loc[1] < 0 || loc[1] > 150) return;
        long now = android.os.SystemClock.uptimeMillis();
        try {
            StringBuilder sb = new StringBuilder("bar: pad=")
                    .append(g.getPaddingLeft()).append('/').append(g.getPaddingRight())
                    .append(" w=").append(g.getWidth()).append(" x=").append(Math.round(g.getX()));
            // 「看起来缺了一块」的判据：网速格没了/塌了，或者箭头不可见/塌了。
            // 一旦命中就不受 2 秒节流，按 200ms 打一行 —— 要抓的是"坏掉的那几秒"。
            // 只对「含 mobile_combo 的容器」做判定：窗口里还有别的 StatusIconContainer
            // （锁屏 / 控制中心 / 转场动画里那份），它们本来就没有移动数据那一格。
            boolean hasCombo = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                String cn = Engine.entryName(g.getChildAt(i));
                if (cn != null && cn.contains("mobile_combo")) { hasCombo = true; break; }
            }
            if (!hasCombo) {
                if (now - lastDumpMs >= 2000) { lastDumpMs = now; Engine.dbg("bar(旁支): " + g.getWidth() + "x" + g.getHeight()); }
                return;
            }
            // Wi-Fi 下 data_inout 被系统设 GONE 是**正常**的（我们不钉占位），
            // 只有「没连 Wi-Fi 时箭头 / 网速不见」才是要抓的问题。
            final boolean wifi = Engine.wifiNow();
            boolean bad = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                String n = Engine.entryName(c);
                int w = c.getWidth();
                int v = c.getVisibility();
                if (w <= 0 && v != View.VISIBLE) continue;      // 只打有意义的
                if (n == null) n = c.getClass().getSimpleName();
                if (n.contains("network_speed") && (w < 10 || v != View.VISIBLE)) bad = true;
                sb.append(' ').append(n).append('=').append(w).append('/').append(v)
                        .append(",p=").append(c.getPaddingLeft()).append(',').append(c.getPaddingRight())
                        .append(",x=").append(Math.round(c.getX()));
                // 移动数据那一格内部：data_inout（上下行箭头）的可见性 / 宽度 / 位移。
                if (c instanceof ViewGroup && "mobile_combo".equals(n)) {
                    View di = Engine.findDataInout((ViewGroup) c, 0);
                    if (di != null) {
                        sb.append("[inout=").append(di.getWidth())
                                .append('/').append(di.getVisibility())
                                .append(",ty=").append(di.getTranslationY()).append(']');
                        if (!wifi && (di.getWidth() == 0 || di.getVisibility() != View.VISIBLE)) bad = true;
                    } else {
                        sb.append("[inout=null]");
                        if (!wifi) bad = true;
                    }
                }
            }
            if (bad) {
                if (now - lastBadMs >= 200) {
                    lastBadMs = now;
                    Engine.dbg("BAR!! " + sb + " || " + Engine.diagSummary());
                }
            } else if (now - lastDumpMs >= 2000) {
                lastDumpMs = now;
                Engine.dbg(sb.toString());
            }
        } catch (Throwable ignore) {}
    }
}
