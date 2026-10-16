package io.github.pixelsuite;

/**
 * Logging that is safe in every process the shared UI code runs in.
 *
 * FrostedSurface and BackdropSurface draw the frosted panels both inside hooked apps (launcher,
 * screenshot editor) and inside Pixel Suite's own settings screen. Module extends the LSPosed
 * API class, which only exists in hooked processes: in the settings app, the first
 * Module.log(...) failed with NoClassDefFoundError and crashed Pixel Suite (preview39 report:
 * opening a choice dialog, FrostedSurface.frost line 58). Here, Module is only touched once
 * Module.onModuleLoaded has run in this process; otherwise the line goes to logcat.
 */
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
