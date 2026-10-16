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
            Module.log("Screen record tidy installed");
        } catch (Throwable error) { Module.log("Screen record tidy unavailable", error); }
    }

    private static final ThreadLocal<Boolean> armed = new ThreadLocal<Boolean>();
    private static final java.util.Map<Object, Object> wrapped = new java.util.WeakHashMap<Object, Object>();
    private static boolean loggedSwap, loggedSkip;
    private static final String COMPOSE = "com.android.systemui.screencapture.record.smallscreen.ui.compose.";

    private static void composeHooks(ClassLoader cl) throws Throwable {
        final Class<?> content = Xp.findClass(COMPOSE + "SmallScreenCaptureRecordContent$$ExternalSyntheticLambda4", cl);
        final Class<?> columnKt = Xp.findClass("androidx.compose.foundation.layout.ColumnKt", cl);
        final Class<?> policyType = Xp.findClass("androidx.compose.ui.layout.MeasurePolicy", cl);
        Xp.hookAllMethods(content, "invoke", new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) { try { armed.set(enabled() ? Boolean.TRUE : null); } catch (Throwable ignored) { } }
            protected void afterHookedMethod(Xp.Param p) { armed.set(null); }
        });
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
                    if (!loggedSwap) { loggedSwap = true; Module.log("Screen record sheet: toolbar moved under the card"); }
                    p.setResult(proxy);
                } catch (Throwable error) { Module.log("Screen record sheet swap failed", error); }
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
                            if (!loggedSkip) { loggedSkip = true; Module.log("Screen record sheet: warning row removed"); }
                            p.setResult(null);
                            return;
                        }
                    }
                } catch (Throwable error) { Module.log("Screen record sheet row check failed", error); }
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
