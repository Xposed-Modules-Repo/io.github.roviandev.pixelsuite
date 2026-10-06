package io.github.pixelsuite;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


/**
 * Feature 5: Files by Google opens folders sorted newest-first instead of by name.
 *
 * Files by Google is closed-source and obfuscated, so there is no stable class to hook by
 * name. Instead this works by behaviour: it wraps the comparator passed to
 * Collections.sort(List, Comparator) and List.sort(Comparator) inside the Files process, and
 * when the list being sorted is made of file-like entries (java.io.File, or an object that
 * exposes a last-modified timestamp), it sorts those newest-first. Any other list is sorted
 * exactly as the app asked, so nothing else in the app is affected.
 *
 * Because it is heuristic, a future Files update could change what gets sorted. It is scoped
 * to the Files app alone. On/off: Settings.Secure Feature.FILES_SORT.
 */
final class FilesSort {

    private static volatile Context sContext;

    private FilesSort() {}

    static void install(ClassLoader cl) {
        try {
            Xp.Callback collectionsHook = new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (!active()) return;
                    Object list = param.args[0];
                    Comparator<Object> replacement = replacementFor(list);
                    if (replacement != null) param.args[1] = replacement;
                }
            };
            Xp.findAndHookMethod(Collections.class, "sort",
                    List.class, Comparator.class, collectionsHook);

            Xp.findAndHookMethod(List.class, "sort", Comparator.class,
                    new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            if (!active()) return;
                            Comparator<Object> replacement = replacementFor(param.thisObject);
                            if (replacement != null) param.args[0] = replacement;
                        }
                    });

            Xp.findAndHookMethod(java.util.ArrayList.class, "sort", Comparator.class,
                    new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            if (!active()) return;
                            Comparator<Object> replacement = replacementFor(param.thisObject);
                            if (replacement != null) param.args[0] = replacement;
                        }
                    });

            Module.log("files-sort hooks installed");
        } catch (Throwable t) {
            Module.log("files-sort hook failed", t);
        }
    }

    // ------------------------------------------------------------------ logic

    private static final Feature.Cached sOn = new Feature.Cached(Feature.FILES_SORT);

    private static boolean active() {
        Context ctx = appContext();
        return ctx != null && sOn.on(ctx);
    }

    /**
     * @return a newest-first comparator if {@code collection} is a non-empty list of file-like
     *         entries that expose a last-modified time, else null (leave the sort alone).
     */
    private static Comparator<Object> replacementFor(Object collection) {
        if (!(collection instanceof List)) return null;
        List<?> list = (List<?>) collection;
        if (list.size() < 2) return null;

        Object sample = null;
        for (Object o : list) {
            if (o != null) { sample = o; break; }
        }
        if (sample == null) return null;

        final Accessor accessor = accessorFor(sample.getClass());
        if (accessor == null) return null;

        return new Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                long ta = accessor.lastModified(a);
                long tb = accessor.lastModified(b);
                return Long.compare(tb, ta);   // newest first
            }
        };
    }

    /** How to read a last-modified time from an element of this type, or null if there's none. */
    private static Accessor accessorFor(Class<?> type) {
        if (File.class.isAssignableFrom(type)) {
            return new Accessor() {
                @Override
                public long lastModified(Object o) {
                    try {
                        return ((File) o).lastModified();
                    } catch (Throwable t) {
                        return 0L;
                    }
                }
            };
        }
        // Obfuscated model objects: look for a zero-arg method returning a plausible epoch-ms
        // long, named like a timestamp getter. Cache the first match per class.
        final Method m = timestampMethod(type);
        if (m != null) {
            return new Accessor() {
                @Override
                public long lastModified(Object o) {
                    try {
                        Object v = m.invoke(o);
                        return v instanceof Long ? (Long) v : 0L;
                    } catch (Throwable t) {
                        return 0L;
                    }
                }
            };
        }
        return null;
    }

    private static Method timestampMethod(Class<?> type) {
        Method best = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() != long.class && m.getReturnType() != Long.class) continue;
                String n = m.getName().toLowerCase();
                boolean looksLikeTime = n.contains("lastmodified") || n.contains("modified")
                        || n.contains("datemodified") || n.contains("timestamp")
                        || n.contains("datetaken") || n.contains("lastmodifiedtime");
                if (!looksLikeTime) continue;
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) { }
                // Prefer the most specific "last modified" name.
                if (n.contains("lastmodified")) return m;
                if (best == null) best = m;
            }
        }
        return best;
    }

    private interface Accessor {
        long lastModified(Object o);
    }

    private static Context appContext() {
        if (sContext == null) {
            try {
                Object app = Xp.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication");
                if (app instanceof Context) sContext = (Context) app;
            } catch (Throwable ignored) { }
        }
        return sContext;
    }
}
