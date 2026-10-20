package io.github.pixelsuite;

import android.app.Activity;
import android.view.View;

final class WindowFrost {
    private WindowFrost() {}

    private static final java.util.Set<String> logged = new java.util.HashSet<String>();

    static void frost(final Activity a, String what) { frost(a, what, true); }
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
