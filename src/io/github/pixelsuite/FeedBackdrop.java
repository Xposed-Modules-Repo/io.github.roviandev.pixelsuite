package io.github.pixelsuite;

import java.util.HashMap;
import java.util.Map;

/**
 * Super Dark, Pixel Launcher: the blue-grey panel (#22242a) seen for a moment while the Google feed slides in.
 * Build 234's trace found it: a view inside Pixel Launcher (not the launcher's own code, a Google library loaded
 * into it) fills itself with that grey in its onDraw. Under Super Dark that one fill is black. Nothing else changes:
 * only an opaque, near-neutral dark grey painted with drawColor straight from a view's onDraw, by a class outside
 * Android, the launcher and Quickstep.
 */
final class FeedBackdrop {
    private FeedBackdrop() {}
    private static final Map<String, Boolean> callers = new HashMap<String, Boolean>();

    static void install() {
        Xp.Callback black = new Xp.Callback() {
            @Override protected void beforeHookedMethod(Xp.Param p) {
                try {
                    if (p.args.length != 1 || !SuperDark.inEffectNow()) return;
                    int c;
                    if (p.args[0] instanceof Integer) c = (Integer) p.args[0];
                    else if (p.args[0] instanceof Long) c = (Integer) android.graphics.Color.class.getMethod("toArgb", long.class).invoke(null, p.args[0]);
                    else return;
                    if (!panelGrey(c) || !fromForeignView()) return;
                    if (p.args[0] instanceof Integer) p.args[0] = 0xff000000;
                    else p.args[0] = 0xff000000L << 32;   // Color.pack(black): sRGB packs the ARGB in the top 32 bits
                } catch (Throwable ignored) { }
            }
        };
        int n = 0;
        try { n += Xp.hookAllMethods(Class.forName("android.graphics.BaseRecordingCanvas"), "drawColor", black).size(); } catch (Throwable ignored) { }
        Module.log("Super Dark: feed backdrop " + (n > 0 ? "ready" : "unavailable"));
    }

    /** Opaque and a dark, nearly neutral grey (the panel is #22242a). */
    static boolean panelGrey(int c) {
        if ((c >>> 24) != 0xff) return false;
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max >= 0x10 && max <= 0x30 && max - min <= 0x0c;
    }

    /** The colour comes straight from a view's onDraw in a class that is not Android's, the launcher's or Quickstep's. */
    private static boolean fromForeignView() {
        StackTraceElement[] s = new Throwable().getStackTrace();
        for (int i = 1; i < s.length && i < 14; i++) {
            if (!"onDraw".equals(s[i].getMethodName())) continue;
            String k = s[i].getClassName();
            synchronized (callers) {
                Boolean hit = callers.get(k);
                if (hit == null) {
                    hit = !(k.startsWith("android.") || k.startsWith("com.android.") || k.startsWith("androidx.") || k.startsWith("io.github.pixelsuite"));
                    callers.put(k, hit);
                    if (hit) Module.log("Super Dark: feed backdrop black for " + k);
                }
                return hit;
            }
        }
        return false;
    }
}
