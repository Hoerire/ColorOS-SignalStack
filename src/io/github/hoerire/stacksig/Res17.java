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

    public static synchronized boolean init(XposedModule m) {
        if (res != null) return true;
        try {
            ApplicationInfo ai = m.getModuleApplicationInfo();
            String apk = ai.sourceDir != null ? ai.sourceDir : ai.publicSourceDir;
            pkg = ai.packageName;
            AssetManager am = AssetManager.class.newInstance();
            Method add = AssetManager.class.getMethod("addAssetPath", String.class);
            add.invoke(am, apk);
            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            res = new Resources(am, dm, Resources.getSystem().getConfiguration());
            Log.d("Res17 ok apk=" + apk + " pkg=" + pkg);
            return true;
        } catch (Throwable t) {
            Log.d("Res17 FAIL " + t);
            return false;
        }
    }

    /** 取一张 C17 矢量图（返回新实例，避免共享状态） */
    public static Drawable get(String name) {
        if (res == null) return null;
        try {
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) {
                Log.d("Res17 miss " + name);
                return null;
            }
            Drawable d = res.getDrawable(id, null);
            if (d == null) return null;
            return d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        } catch (Throwable t) {
            Log.d("Res17 err " + name + " " + t);
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
