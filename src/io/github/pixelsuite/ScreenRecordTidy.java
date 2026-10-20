package io.github.pixelsuite;

import java.util.ArrayList;
import java.util.List;

final class ScreenRecordTidy {
    static final String KEY = "pixelsuite_screen_record_tidy";
    private ScreenRecordTidy() {}

    static void install(ClassLoader cl) {
        try { composeHooks(cl); } catch (Throwable error) { Module.log("Screen record sheet hooks unavailable", error); }
        try {
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
            ComposeFrost.install("screen recording sheet", "ScreenCaptureUi");
            Xp.hookAllMethods(android.app.Activity.class, "onResume", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    if (!p.thisObject.getClass().getName().endsWith("PostRecordingActivity")) return;
                    recordingFront = true;
                    if (SuperDark.inEffectNow()) DarkChips.on("screen recording");
                }
            });
            Xp.hookAllMethods(android.app.Activity.class, "onCreate", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    if (p.thisObject.getClass().getName().endsWith("PostRecordingActivity")) WindowFrost.frost((android.app.Activity) p.thisObject, "recording preview", false);
                }
            });
            Xp.hookAllMethods(android.app.Dialog.class, "onStart", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (!recordingFront) return;
                        android.app.Dialog d = (android.app.Dialog) p.thisObject;
                        if (d.getWindow() == null || SuperDark.inEffect(d.getContext()) || !SurfaceStyle.restyled(d.getContext())) return;
                        synchronized (pills) { if (pills.containsKey(d)) return; }
                        frostDialog(d.getWindow().getDecorView(), d.getWindow());
                    } catch (Throwable ignored) { }
                }
            });
            try {
                Class<?> snack = Class.forName("com.android.systemui.screencapture.record.smallscreen.ui.SnackbarDialogDelegate", false, cl);
                Xp.hookAllMethods(snack, "onCreate", new Xp.Callback() {
                    protected void afterHookedMethod(Xp.Param p) {
                        try {
                            if (!(p.args[0] instanceof android.app.Dialog)) return;
                            synchronized (pills) { pills.put(p.args[0], Boolean.TRUE); }
                            android.view.Window w = ((android.app.Dialog) p.args[0]).getWindow(); if (w == null) return;
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
    static final java.util.Map<Object, Boolean> pills = new java.util.WeakHashMap<Object, Boolean>();

    private static void frostDialog(android.view.View decor) { frostDialog(decor, null); }
    private static void frostDialog(android.view.View decor, android.view.Window window) {
        android.view.View content = decor.findViewById(android.R.id.content);
        android.view.View card = card(content == null ? decor : content, decor.getWidth() > 0 ? decor.getWidth() : decor.getResources().getDisplayMetrics().widthPixels);
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
            if (inner instanceof android.graphics.drawable.GradientDrawable) { float r = (Float) Xp.callMethod(inner, "getCornerRadius"); if (r > 0) corner = r; }
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
    private static volatile int toolbarBottom;
    private static java.lang.ref.WeakReference<Object> toolbarRegion;
    private static java.lang.reflect.Field regionField;
    private static void noteToolbar(Object content) {
        try {
            if (regionField == null) { java.lang.reflect.Field f = content.getClass().getDeclaredField("f$1"); f.setAccessible(true); regionField = f; }
            Object r = regionField.get(content);
            if (!(r instanceof android.graphics.Region)) return;
            if (toolbarRegion == null || toolbarRegion.get() != r) { toolbarRegion = new java.lang.ref.WeakReference<Object>(r); toolbarBottom = 0; }
            android.graphics.Rect b = ((android.graphics.Region) r).getBounds();
            if (!b.isEmpty() && b.bottom > toolbarBottom) toolbarBottom = b.bottom;
        } catch (Throwable ignored) { }
    }
    private static final java.util.Map<Object, Object> wrapped = new java.util.WeakHashMap<Object, Object>();
    private static boolean loggedSwap, loggedSkip;
    private static final String COMPOSE = "com.android.systemui.screencapture.record.smallscreen.ui.compose.";

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
        try {
            final Class<?> offset = Xp.findClass(COMPOSE + "SmallScreenCaptureRecordContent$$ExternalSyntheticLambda2", cl);
            final java.lang.reflect.Field kind = offset.getDeclaredField("$r8$classId"); kind.setAccessible(true);
            Xp.hookAllMethods(offset, "invoke", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (kind.getInt(p.thisObject) != 1 || !(p.getResult() instanceof Integer)) return;
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
                    armed.set(null);
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

    private static boolean enabled() {
        try {
            if (systemUiApp == null) systemUiApp = (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return systemUiApp != null && switchCache.on(systemUiApp);
        } catch (Throwable error) { return false; }
    }
}
