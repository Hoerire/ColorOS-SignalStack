package io.github.hoerire.stacksig;

import android.util.Log;

import java.lang.reflect.Constructor;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 状态栏图标的排列顺序。
 *
 * StatusBarIconList 的构造函数直接吃 config_statusBarIcons 这个 String[]，
 * 数组顺序就是显示顺序 —— 改数组是纯数据操作，不碰视图树，零崩溃风险。
 *
 * 目标顺序：
 *   网速 → 蓝牙 → 闹钟 → 免打扰 → 月亮 → 信号 → WiFi → 电池
 * 网速顶在最左，数字变宽只会往左长，右侧图标永远不会被推动。
 *
 * 蓝牙排在网速后面（2026-11-02 用户要求）：蓝牙图标有三种宽度
 * （未连接 13dp / 已连接 17dp / 已连接并显示设备电量 18dp），排在最右边会紧贴
 * 信号格左上角的制式标；放到闹钟之前以后，它长宽变化只会推动闹钟/音量，
 * 与制式标再无关系，三种状态都不用单独处理。
 */
final class Order {

    // ==================== 图标顺序（对齐 ColorOS 17） ====================

    /**
     * ColorOS 16 的 config_statusBarIcons 里 wifi 排在 mobile 前面；
     * ColorOS 17 把 mobile 提到了 wifi 前面，于是变成「信号 → WiFi」。
     *
     * StatusBarIconList 的构造函数直接吃这个 String[]，数组顺序就是显示顺序。
     * 这里只在构造时交换 wifi / mobile 的下标，纯数据层操作，不碰视图树。
     */
    static int hookIconOrder(XposedModule m, ClassLoader cl) {
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
            try { Engine.MODULE.log(android.util.Log.WARN, "StackSig", "iconOrder hook failed: " + t); }
            catch (Throwable ignore) {}
            return 0;
        }
    }

    /**
     * 重排 icon 数组（数组顺序 = 显示顺序，从左到右）。
     *
     * 1. mobile 挪到 wifi 前（对齐 C17 的「信号 → WiFi」）；
     * 2. zen（月亮）挪到 wifi 前（和 mobile 一起，月亮在信号左侧）；
     * 3. network_speed 提到 logging 前——让网速成为这一簇最左的一格，
     *    数字宽度（115 / 1.20M / 12.3）一直变，它站在最左边就只会往左长，
     *    右边的图标永远不会被挤动；
     * 4. bluetooth 挪到 alarm_clock 前（紧跟网速之后，见类头注释）。
     */
    private static void reorder(String[] slots) {
        moveBefore(slots, "mobile", "wifi");
        moveBefore(slots, "zen", "wifi");
        moveBefore(slots, "network_speed", "logging");
        // 网速提到最左：越过 mute / ethernet / alarm_clock，成为整簇第一格

        // 蓝牙紧跟网速之后（锚点用 alarm_clock，不管中间那些偶尔出现的
        // nfc / hotspot / cast 开没开，蓝牙都稳定落在闹钟左边）
        moveBefore(slots, "bluetooth", "alarm_clock");
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

}
