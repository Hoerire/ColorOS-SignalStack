package io.github.hoerire.stacksig;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.lang.reflect.Method;
import java.util.HashMap;

import io.github.libxposed.api.XposedModule;

/** 用模块自身的资源表把 C17 的 40 个 stacked 矢量图实例化 */
public final class Res17 {

    private static Resources res;
    private static String pkg;
    private static final HashMap<String, Drawable> CACHE = new HashMap<>();

    private Res17() {}

    private static String apkPath;
    private static int lastDpi = -1;

    public static synchronized boolean init(XposedModule m) {
        if (res != null && apkPath != null) return true;
        try {
            ApplicationInfo ai = m.getModuleApplicationInfo();
            apkPath = ai.sourceDir != null ? ai.sourceDir : ai.publicSourceDir;
            pkg = ai.packageName;
            ensure();
            return res != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 资源表按当前 density 建。用户改「显示大小 / 分辨率」后 densityDpi 会变，
     * 矢量图的 intrinsic 是按 dp*density 算出来的，不重建就会一直用旧 dpi 的尺寸
     * —— 显示出来比邻居大一圈。
     */
    private static synchronized void ensure() {
        try {
            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            if (res != null && dm.densityDpi == lastDpi) return;
            if (apkPath == null) return;
            AssetManager am = AssetManager.class.newInstance();
            Method add = AssetManager.class.getMethod("addAssetPath", String.class);
            add.invoke(am, apkPath);
            res = new Resources(am, dm, Resources.getSystem().getConfiguration());
            lastDpi = dm.densityDpi;
            CACHE.clear();
        } catch (Throwable t) {
        }
    }

    /** 取一张 C17 矢量图（返回新实例，避免共享状态） */
    public static Drawable get(String name) {
        ensure();
        if (res == null) return null;
        try {
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) {
                return null;
            }
            Drawable d = res.getDrawable(id, null);
            if (d == null) return null;
            return d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** C17 的 stacked 图标名，level 0..4 */
    public static String primaryName(String family, int level) {
        return "ic_s17_" + family + "_signal_stacked_primary_" + clamp(level);
    }

    public static String secondaryName(String family, int level) {
        return "ic_s17_" + family + "_signal_stacked_secondary_" + clamp(level);
    }

    private static int clamp(int level) {
        if (level < 0) return 0;
        if (level > 4) return 4;
        return level;
    }
}
