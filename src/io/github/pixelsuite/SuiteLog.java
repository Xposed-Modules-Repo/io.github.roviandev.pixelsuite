package io.github.pixelsuite;

final class SuiteLog {
    static volatile boolean hooked;

    private SuiteLog() {}

    static void log(String message) { log(message, null); }

    static void log(String message, Throwable error) {
        if (!hooked) {
            if (error == null) android.util.Log.i(Module.TAG, message);
            else android.util.Log.w(Module.TAG, message, error);
            return;
        }
        if (error == null) Module.log(message); else Module.log(message, error);
    }
}
