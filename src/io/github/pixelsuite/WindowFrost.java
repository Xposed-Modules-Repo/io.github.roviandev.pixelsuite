package io.github.pixelsuite;

import android.app.Activity;
import android.view.View;

/**
 * 2.0.4 build 191 (owner's video of the screenshot editor: its frame came up white for a quarter of a second, then
 * its grey; the recording preview's frame white too): an activity's window in the folders' frost, from its first
 * frame. The activity is made see-through (Activity.convertToTranslucent, the framework's own way for an activity to
 * show what is behind it) and its window background becomes the frost with square corners: a blur of what was on the
 * screen before, under the folder tint (black under Super Dark-Mode). Done in onCreate, before the first frame, so the
 * stock white window background is never shown; what the app then draws over it (the editor's grey, the preview's
 * surface) is its own. Only under the Themes slider's styles that draw Pixel Suite's surfaces (Frosted Glass, Blur Off
 * in dark theme, Super Dark); Stock leaves the window alone.
 */
final class WindowFrost {
    private WindowFrost() {}

    private static final java.util.Set<String> logged = new java.util.HashSet<String>();

    static void frost(final Activity a, String what) { frost(a, what, true); }
    /** blur=false: the window stays opaque and gets the solid tint only (a window whose content a blur region would blur: the recording preview's video). */
    static void frost(final Activity a, String what, boolean blur) {
        try {
            if (!SurfaceStyle.restyled(a)) return;
            View decor = a.getWindow().getDecorView();
            if (decor.getBackground() instanceof PillFrost) return;
            if (!blur) {
                int colour = SuperDark.inEffect(a) ? FrostedSurface.SUPER_DARK_PANEL : FrostedSurface.tone(a) | 0xff000000;
                a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(colour));
                return;
            }
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method x : Activity.class.getDeclaredMethods()) if (x.getName().equals("convertToTranslucent") && x.getParameterTypes().length == 2) { m = x; break; }
            if (m == null) throw new NoSuchMethodException("convertToTranslucent");
            m.setAccessible(true);
            m.invoke(a, null, null);
            PillFrost d = PillFrost.frost(decor, false, 0f);
            a.getWindow().setBackgroundDrawable(d);
        } catch (Throwable error) { if (logged.add(what)) Module.log(what + ": window not frosted", error); }
    }
}
