package io.github.hoerire.stacksig;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 状态栏图标间距。
 *
 * 只调 padding，不动 View 的宽高，也不碰容器里的子 View 顺序。
 *
 * 历史教训（都踩过）：
 *   - 按「可见序号」绑定 padding：蓝牙排到第 3 位时被当成月亮，套错值后贴死 5G 徽标；
 *   - 给每个图标配独立的 padding：左右增减一个图标，所有缝就重排；
 *   - 实测墨迹宽度做自适应、固定 View 宽度：视觉上割裂。
 * 现在只按 slot 名给极少数特例（蓝牙右邻是制式标），其余一律同一个基准值。
 */
final class Spacing {

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
    static int hookIconGap(XposedModule m, ClassLoader cl) {
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
            try { Engine.MODULE.log(android.util.Log.WARN, "StackSig", "iconGap hook failed: " + t); }
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

    private static void tightenGap(ViewGroup g) {
        int nullRank = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            if (!isStatusIcon(v)) continue;
            Speed.rememberSpeedView(v);
            // 网速 ticker 在这里启动：tightenGap 每次布局都会跑，
            // 比挂在 stack() 里可靠（stack 只在有信号图标时才执行）。
            Speed.ensureTicker(v.getContext());
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
            String slot = Spacing.slotOf(v);
            if (slot != null && slot.contains("bluetooth")) {
                return new int[]{GAP_INSET_PX, GAP_INSET_PX + 1};
            }
        }
        return new int[]{GAP_INSET_PX, GAP_INSET_PX};
    }

    /** 反射调 StatusIconDisplayable.getSlot()，拿不到返回 null */
    static String slotOf(View v) {
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
}
