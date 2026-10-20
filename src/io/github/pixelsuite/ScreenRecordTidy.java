package io.github.pixelsuite;

import java.util.ArrayList;
import java.util.List;

/**
 * SystemUI's screen record sheet (Android 17, Compose): blanks the "anything shown on your screen is
 * recorded" warning, drops its row, and places the floating X / Start pill under the options card.
 */
final class ScreenRecordTidy {
    static final String KEY = "pixelsuite_screen_record_tidy";
    private ScreenRecordTidy() {}

    static void install(ClassLoader cl) {
        try { composeHooks(cl); } catch (Throwable error) { Module.log("Screen record sheet hooks unavailable", error); }
        try {
            // The Android 17 sheet (window "ScreenCaptureUi") is drawn by Compose, so there is no TextView to
            // hide: blank the warning where its string is loaded instead.
            Xp.hookAllMethods(android.content.res.Resources.class, "getText", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 1) return;
                        Object r = p.getResult();
                        if (!(r instanceof CharSequence) || ((CharSequence) r).length() < 60 || !((CharSequence) r).toString().contains("careful with things like passwords")) return;
                        if (enabled()) p.setResult("");
                    } catch (Throwable ignored) { }
                }
            });
            // build 194 (owner): the recording sheet (options card, X / Start pill: Compose, window "ScreenCaptureUi") in the
            // folders' frost under Frosted Glass; stock otherwise (ComposeFrost).
            ComposeFrost.install("screen recording sheet", "ScreenCaptureUi");
            // 2.0.4 build 177 (owner): with Super Dark-Mode on, the recording preview's dialogs ("Delete this
            // recording?") are black with the Doodles menu's soft rim, like Pixel Studio's (DarkChips, only while that
            // screen is in front).
            Xp.hookAllMethods(android.app.Activity.class, "onResume", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    if (!p.thisObject.getClass().getName().endsWith("PostRecordingActivity")) return;
                    recordingFront = true;
                    if (SuperDark.inEffectNow()) DarkChips.on("screen recording");
                }
            });
            // build 191: the preview's window frosted from its first frame (onCreate), see WindowFrost
            Xp.hookAllMethods(android.app.Activity.class, "onCreate", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    // build 193 (owner: the recorded video came out blurred under Frosted Glass): the preview plays the video
                    // in a SurfaceView, which sits behind the window, where the window's blur region blurred it too. The
                    // preview's window therefore gets the solid tint (no blur, no see-through); its dialogs stay frosted.
                    if (p.thisObject.getClass().getName().endsWith("PostRecordingActivity")) WindowFrost.frost((android.app.Activity) p.thisObject, "recording preview", false);
                }
            });
            // build 190 (owner, light mode: the preview's "Delete this recording?" dialog white): outside Super Dark the
            // preview's dialogs (System UI dialogs, View-based) get the folders' frost: their card's background is the
            // pill drawable with the card's corners, its own window, so the preview behind the dialog is blurred.
            Xp.hookAllMethods(android.app.Dialog.class, "onStart", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (!recordingFront) return;
                        android.app.Dialog d = (android.app.Dialog) p.thisObject;
                        if (d.getWindow() == null || SuperDark.inEffect(d.getContext()) || !SurfaceStyle.restyled(d.getContext())) return;
                        // build 203 (owner's screenshot, light theme + Frosted Glass: a black slab around "Video saved"):
                        // the message pills are dialogs too; their window, bigger than the pill, got the frost, which
                        // the pill's colour-inverting layer then turned dark. Pills keep their own look (inverted) only.
                        synchronized (pills) { if (pills.containsKey(d)) return; }
                        frostDialog(d.getWindow().getDecorView(), d.getWindow());
                    } catch (Throwable ignored) { }
                }
            });
            // build 178 (owner: "Video saved" / "Video deleted · Undo" stayed light): those pills are System UI dialogs
            // (SnackbarDialogDelegate, Compose content, light surface and dark text, shown over any screen). Under
            // Super Dark their whole window is drawn through a colour-inverting layer: the light pill turns near-black,
            // its dark text and icon light; the rest of the window is transparent and stays so.
            try {
                Class<?> snack = Class.forName("com.android.systemui.screencapture.record.smallscreen.ui.SnackbarDialogDelegate", false, cl);
                Xp.hookAllMethods(snack, "onCreate", new Xp.Callback() {
                    protected void afterHookedMethod(Xp.Param p) {
                        try {
                            if (!(p.args[0] instanceof android.app.Dialog)) return;
                            synchronized (pills) { pills.put(p.args[0], Boolean.TRUE); }
                            android.view.Window w = ((android.app.Dialog) p.args[0]).getWindow(); if (w == null) return;
                            // build 183 (owner: build 182's frost view behind the pill came out as a big light slab): back
                            // to the colour-inverting layer, now in every mode: System UI draws these pills in the
                            // theme's inverse colours (light pill on a dark phone, dark on a light one), so inverting
                            // them gives a dark pill with white text in dark / Super Dark and a light pill with dark
                            // text in light mode, like the other pills. Gain 1.6 makes the text pure white / near black.
                            // build 184 (owner, light mode: white pill with light grey text): on a light phone the pill is
                            // dark with light-grey text, which needs a different mapping (dark -> the light tint, grey
                            // text -> near black: 255 - 1.42x); on a dark phone as before (1.6 * (255 - x)).
                            // build 193 (owner: "Video deleted" black on a light phone under Blur Off): in every style; the stock
                            // pill is the theme's inverse, which the owner wants to follow the theme in all three styles.
                            boolean dark = FrostedSurface.dark(w.getContext());
                            float k = dark ? -1.6f : -1.42f, o = dark ? 408 : 255;
                            android.graphics.Paint inv = new android.graphics.Paint();
                            inv.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{k,0,0,0,o, 0,k,0,0,o, 0,0,k,0,o, 0,0,0,1,0}));
                            w.getDecorView().setLayerType(android.view.View.LAYER_TYPE_HARDWARE, inv);
                        } catch (Throwable ignored) { }
                    }
                });
            } catch (Throwable error) { Module.log("Screen record: message pills unavailable", error); }
            Xp.hookAllMethods(android.app.Activity.class, "onPause", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    if (p.thisObject.getClass().getName().endsWith("PostRecordingActivity")) { recordingFront = false; DarkChips.off(); }
                }
            });
        } catch (Throwable error) { Module.log("Screen record tidy unavailable", error); }
    }


    static volatile boolean recordingFront;
    /** The message pills' dialogs ("Video saved", "Video deleted · Undo"): never frosted (build 203). */
    static final java.util.Map<Object, Boolean> pills = new java.util.WeakHashMap<Object, Boolean>();

    /** The dialog's card: the first view under the window's content with a background narrower than the window. */
    private static void frostDialog(android.view.View decor) { frostDialog(decor, null); }
    private static void frostDialog(android.view.View decor, android.view.Window window) {
        android.view.View content = decor.findViewById(android.R.id.content);
        android.view.View card = card(content == null ? decor : content, decor.getWidth() > 0 ? decor.getWidth() : decor.getResources().getDisplayMetrics().widthPixels);
        // build 194 (log of 193: "dialog card not found"): the dialog's window is the size of its card, and the card is the
        // window background itself (a rounded GradientDrawable on the decor view, the one DarkChips recolours).
        if (card == null && decor.getBackground() != null) card = decor;
        if (card == null) return;
        if (card.getBackground() instanceof PillFrost) return;
        float corner = FrostedSurface.dp(card.getContext(), 28);
        android.graphics.drawable.Drawable bg = card.getBackground();
        try {
            android.graphics.drawable.Drawable inner = bg;
            for (int i = 0; i < 3 && inner != null && !(inner instanceof android.graphics.drawable.GradientDrawable); i++) {
                if (inner instanceof android.graphics.drawable.InsetDrawable) inner = (android.graphics.drawable.Drawable) Xp.callMethod(inner, "getDrawable");
                else if (inner instanceof android.graphics.drawable.LayerDrawable && ((android.graphics.drawable.LayerDrawable) inner).getNumberOfLayers() > 0) inner = ((android.graphics.drawable.LayerDrawable) inner).getDrawable(0);
                else break;
            }
            if (inner instanceof android.graphics.drawable.GradientDrawable) { float r = (Float) Xp.callMethod(inner, "getCornerRadius"); if (r > 0) corner = r; }   // the card's own corners
        } catch (Throwable ignored) { }
        PillFrost d = PillFrost.frost(card, false, corner);
        if (card == decor && window != null) window.setBackgroundDrawable(d);
    }
    private static android.view.View card(android.view.View v, int windowW) {
        if (v.getBackground() != null && !(v.getBackground() instanceof android.graphics.drawable.ColorDrawable && ((android.graphics.drawable.ColorDrawable) v.getBackground()).getColor() == 0) && v.getWidth() > 0 && v.getWidth() < windowW) return v;
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { android.view.View f = card(g.getChildAt(i), windowW); if (f != null) return f; } }
        return null;
    }

    private static final ThreadLocal<Boolean> armed = new ThreadLocal<Boolean>();
    /** The X / Start tool bar's lowest bottom in the sheet's window (from its touch region), for the way out. */
    private static volatile int toolbarBottom;
    private static java.lang.ref.WeakReference<Object> toolbarRegion;
    private static java.lang.reflect.Field regionField;
    /** The sheet's content: the tool bar's touch region (filled with its bounds in the window after each layout). */
    private static void noteToolbar(Object content) {
        try {
            if (regionField == null) { java.lang.reflect.Field f = content.getClass().getDeclaredField("f$1"); f.setAccessible(true); regionField = f; }
            Object r = regionField.get(content);
            if (!(r instanceof android.graphics.Region)) return;
            if (toolbarRegion == null || toolbarRegion.get() != r) { toolbarRegion = new java.lang.ref.WeakReference<Object>(r); toolbarBottom = 0; }   // a new sheet
            android.graphics.Rect b = ((android.graphics.Region) r).getBounds();
            if (!b.isEmpty() && b.bottom > toolbarBottom) toolbarBottom = b.bottom;   // at rest (never lower while sliding out)
        } catch (Throwable ignored) { }
    }
    private static final java.util.Map<Object, Object> wrapped = new java.util.WeakHashMap<Object, Object>();
    private static boolean loggedSwap, loggedSkip;
    private static final String COMPOSE = "com.android.systemui.screencapture.record.smallscreen.ui.compose.";

    /**
     * The Android 17 sheet is Compose. Its content lambda is a Column of two children: the X / Start
     * toolbar, then the options card. The Column's measure policy is wrapped so the children are
     * placed in reverse (card first, toolbar under it), and the row that holds the screen-sharing
     * warning is not composed at all, so it leaves no gap.
     */
    private static void composeHooks(ClassLoader cl) throws Throwable {
        final Class<?> content = Xp.findClass(COMPOSE + "SmallScreenCaptureRecordContent$$ExternalSyntheticLambda4", cl);
        final Class<?> columnKt = Xp.findClass("androidx.compose.foundation.layout.ColumnKt", cl);
        final Class<?> policyType = Xp.findClass("androidx.compose.ui.layout.MeasurePolicy", cl);
        Xp.hookAllMethods(content, "invoke", new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) {
                try {
                    boolean on = enabled();
                    armed.set(on ? Boolean.TRUE : null);
                    if (on) noteToolbar(p.thisObject);
                } catch (Throwable ignored) { }
            }
            protected void afterHookedMethod(Xp.Param p) { armed.set(null); }
        });
        // build 213 (owner: after X, the X / Start pill stayed for a moment after the card had gone): the sheet leaves by
        // sliding up by the status bar and the tool bar's height, which is exactly what takes the tool bar off the top of
        // the screen where Android puts it. With the pill under the card, the sheet slides up past the pill's bottom
        // instead, so the pill leaves together with the card (same spring; the way in is unchanged).
        try {
            final Class<?> offset = Xp.findClass(COMPOSE + "SmallScreenCaptureRecordContent$$ExternalSyntheticLambda2", cl);
            final java.lang.reflect.Field kind = offset.getDeclaredField("$r8$classId"); kind.setAccessible(true);
            Xp.hookAllMethods(offset, "invoke", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (kind.getInt(p.thisObject) != 1 || !(p.getResult() instanceof Integer)) return;   // 1: the way out
                        int bottom = toolbarBottom;
                        if (bottom <= 0 || !enabled()) return;
                        int away = -(bottom + Math.round(8 * android.content.res.Resources.getSystem().getDisplayMetrics().density));
                        if (away < (Integer) p.getResult()) p.setResult(away);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable error) { Module.log("Screen record sheet exit unavailable", error); }
        Xp.hookAllMethods(columnKt, "columnMeasurePolicy", new Xp.Callback() {
            protected void afterHookedMethod(Xp.Param p) {
                try {
                    if (armed.get() == null) return;
                    armed.set(null);   // only the sheet's outer Column, which is the first one built
                    final Object original = p.getResult();
                    if (original == null) return;
                    Object proxy;
                    synchronized (wrapped) {
                        proxy = wrapped.get(original);
                        if (proxy == null) {
                            proxy = java.lang.reflect.Proxy.newProxyInstance(policyType.getClassLoader(), new Class<?>[] {policyType}, new java.lang.reflect.InvocationHandler() {
                                public Object invoke(Object self, java.lang.reflect.Method m, Object[] args) throws Throwable {
                                    if (args != null && args.length == 3 && m.getName().startsWith("measure") && args[1] instanceof List) {
                                        List<Object> reversed = new ArrayList<Object>((List<?>) args[1]);
                                        java.util.Collections.reverse(reversed);
                                        args = new Object[] {args[0], reversed, args[2]};
                                    }
                                    try { return m.invoke(original, args); }
                                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause() != null ? e.getCause() : e; }
                                }
                            });
                            wrapped.put(original, proxy);
                        }
                    }
                    p.setResult(proxy);
                } catch (Throwable error) { if (!loggedSwap) { loggedSwap = true; Module.log("Screen record sheet swap failed", error); } }
            }
        });
        final Class<?> settings = Xp.findClass(COMPOSE + "RecordDetailsSettingsKt", cl);
        Xp.hookAllMethods(settings, "SettingsRow", new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) {
                try {
                    if (!enabled()) return;
                    for (Object arg : p.args) {
                        // The content arrives wrapped by rememberComposableLambda (ComposableLambdaImpl._block).
                        if (arg != null && arg.getClass().getName().endsWith("ComposableLambdaImpl")) {
                            try { java.lang.reflect.Field b = arg.getClass().getDeclaredField("_block"); b.setAccessible(true); arg = b.get(arg); } catch (Throwable ignored) { }
                        }
                        if (arg == null || !arg.getClass().getName().endsWith("RecordDetailsSettingsKt$$ExternalSyntheticLambda5")) continue;
                        java.lang.reflect.Field f = arg.getClass().getDeclaredField("f$0"); f.setAccessible(true);
                        Object owner = f.get(arg);
                        if (owner != null && owner.getClass().getName().endsWith("RecordDetailsTargetViewModel")) {
                            p.setResult(null);
                            return;
                        }
                    }
                } catch (Throwable error) { if (!loggedSkip) { loggedSkip = true; Module.log("Screen record sheet row check failed", error); } }
            }
        });
        Module.log("Screen record sheet hooks installed");
    }

    private static final Feature.Cached switchCache = new Feature.Cached(KEY);
    private static android.app.Application systemUiApp;

    /** The feature switch, read from SystemUI's own application context (at most one lookup a second: composition calls this every frame). */
    private static boolean enabled() {
        try {
            if (systemUiApp == null) systemUiApp = (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return systemUiApp != null && switchCache.on(systemUiApp);
        } catch (Throwable error) { return false; }
    }
}
