package io.github.hoerire.stacksig;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.File;
import java.io.FileOutputStream;

/** 入口：双通道探针（框架 log + 文件），用于确认到底有没有被加载。 */
public final class StackSigModule extends XposedModule {

    public StackSigModule() {
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
        String msg;
        try {
            msg = "LOADED proc=" + p.getProcessName() + " system=" + p.isSystemServer() + " api=" + getApiVersion();
        } catch (Throwable t) {
            msg = "LOADED (param err) " + t;
        }
        try { log("STACKSIG " + msg); } catch (Throwable ignore) {}
        dump("onModuleLoaded " + msg);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        String pkg;
        try { pkg = p.getPackageName(); } catch (Throwable t) { pkg = "?"; }
        try { log("STACKSIG pkg=" + pkg); } catch (Throwable ignore) {}
        dump("onPackageLoaded " + pkg);
        if (!"com.android.systemui".equals(pkg)) return;
        try {
            int n = Engine.install(this, null);
            try { log("STACKSIG Engine hooks=" + n); } catch (Throwable ignore) {}
            dump("Engine hooks=" + n);
        } catch (Throwable t) {
            dump("Engine EX " + t);
        }
    }

    /** 文件探针：SystemUI 只可写自己的目录，system_server 可写 /data/system */
    private static void dump(String s) {
        String[] paths = {
                "/data/local/tmp/stacksig.log",
                "/data/user/0/com.android.systemui/stacksig.log",
                "/data/user/0/com.tencent.mm/stacksig.log",
                "/data/system/stacksig.log",
        };
        for (String path : paths) {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(new File(path), true);
                fos.write((s + "\n").getBytes("UTF-8"));
                fos.flush();
            } catch (Throwable ignore) {
            } finally {
                if (fos != null) { try { fos.close(); } catch (Throwable ignore) {} }
            }
        }
    }
}
