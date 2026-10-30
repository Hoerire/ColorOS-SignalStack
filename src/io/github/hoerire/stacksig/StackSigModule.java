package io.github.hoerire.stacksig;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口：把 Engine 的 hook 装到 SystemUI 进程里。
 *
 * 注意：libxposed API 102 的日志方法是
 *     log(int priority, String tag, String msg)
 * 不是单参数的 log(String)。用错签名会在运行时抛 NoSuchMethodError，
 * 而且被 catch 吞掉后表现为"完全没有日志"。
 */
public final class StackSigModule extends XposedModule {

    static final String TAG = "StackSig";

    public StackSigModule() {
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
        try {
            log(Log.INFO, TAG, "loaded proc=" + p.getProcessName());
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        String pkg;
        try {
            pkg = p.getPackageName();
        } catch (Throwable t) {
            return;
        }
        if (!"com.android.systemui".equals(pkg)) {
            return;
        }
        try {
            ClassLoader cl = null;
            try {
                cl = p.getClassLoader();
            } catch (Throwable ignore) {
            }
            if (cl == null) {
                try {
                    cl = p.getDefaultClassLoader();
                } catch (Throwable ignore) {
                }
            }
            log(Log.INFO, TAG, "SystemUI 已加载，开始安装 hook");
            int hooks = Engine.install(this, cl);
            log(Log.INFO, TAG, "安装完成 hooks=" + hooks);
        } catch (Throwable t) {
            try {
                log(Log.ERROR, TAG, "安装失败", t);
            } catch (Throwable ignore) {
            }
        }
    }
}
