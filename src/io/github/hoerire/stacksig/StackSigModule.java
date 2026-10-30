package io.github.hoerire.stacksig;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** 模块入口：把 Engine 的 hook 装到 SystemUI 进程里。 */
public final class StackSigModule extends XposedModule {

    public StackSigModule() {
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
        try {
            log("STACKSIG loaded proc=" + p.getProcessName());
        } catch (Throwable ignore) {}
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        String pkg;
        try {
            pkg = p.getPackageName();
        } catch (Throwable t) {
            return;
        }
        if (!"com.android.systemui".equals(pkg)) return;
        try {
            ClassLoader cl = null;
            try {
                cl = p.getClassLoader();
            } catch (Throwable ignore) {}
            if (cl == null) {
                try {
                    cl = p.getDefaultClassLoader();
                } catch (Throwable ignore) {}
            }
            int hooks = Engine.install(this, cl);
            try {
                log("STACKSIG hooks=" + hooks);
            } catch (Throwable ignore) {}
        } catch (Throwable t) {
            try {
                log("STACKSIG install failed: " + t);
            } catch (Throwable ignore) {}
        }
    }
}
