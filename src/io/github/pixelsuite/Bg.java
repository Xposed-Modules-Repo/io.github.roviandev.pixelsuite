package io.github.pixelsuite;

import android.os.Handler;
import android.os.HandlerThread;

/** Background thread for system_server work that must stay off the input thread. */
final class Bg {
    private static Handler sHandler;

    private Bg() {}

    static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("PixelSuiteBg");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }
}
