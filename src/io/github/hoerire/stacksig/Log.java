package io.github.hoerire.stacksig;

import java.io.File;
import java.io.FileOutputStream;

/** 调试日志开关：出厂关闭，排查时把 ENABLED 改 true 即可 */
public final class Log {

    public static final boolean ENABLED = false;

    private static final String[] PATHS = {
            "/data/user/0/com.android.systemui/stacksig.log",
    };

    private Log() {}

    public static void d(String s) {
        if (!ENABLED) return;
        for (String path : PATHS) {
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
