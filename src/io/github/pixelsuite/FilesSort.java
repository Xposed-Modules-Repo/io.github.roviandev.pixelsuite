package io.github.pixelsuite;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class FilesSort {

    private static volatile Context sContext;

    private FilesSort() {}

    static void install(ClassLoader cl) {
        try {
            Xp.Callback collectionsHook = new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (param.args[1] instanceof NewestFirst || !active()) return;
                    Comparator<Object> replacement = replacementFor(param.args[0]);
                    if (replacement != null) param.args[1] = replacement;
                }
            };
            Xp.findAndHookMethod(Collections.class, "sort",
                    List.class, Comparator.class, collectionsHook);

            Xp.findAndHookMethod(List.class, "sort", Comparator.class,
                    new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            if (param.args[0] instanceof NewestFirst || !active()) return;
                            Comparator<Object> replacement = replacementFor(param.thisObject);
                            if (replacement != null) param.args[0] = replacement;
                        }
                    });

            Xp.findAndHookMethod(java.util.ArrayList.class, "sort", Comparator.class,
                    new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            if (param.args[0] instanceof NewestFirst || !active()) return;
                            Comparator<Object> replacement = replacementFor(param.thisObject);
                            if (replacement != null) param.args[0] = replacement;
                        }
                    });

            Module.log("files-sort hooks installed");
        } catch (Throwable t) {
            Module.log("files-sort hook failed", t);
        }
    }

    private static final Feature.Cached sOn = new Feature.Cached(Feature.FILES_SORT);

    private static boolean active() {
        Context ctx = appContext();
        return ctx != null && sOn.on(ctx);
    }

    private static Comparator<Object> replacementFor(Object collection) {
        if (!(collection instanceof List)) return null;
        List<?> list = (List<?>) collection;
        if (list.size() < 2) return null;

        Object sample = null;
        for (Object o : list) {
            if (o != null) { sample = o; break; }
        }
        if (sample == null) return null;

        Accessor accessor = accessorFor(sample.getClass());
        return accessor == null ? null : new NewestFirst(accessor);
    }

    private static final class NewestFirst implements Comparator<Object> {
        private final Accessor mAccessor;
        private final IdentityHashMap<Object, Long> mTimes = new IdentityHashMap<Object, Long>();

        NewestFirst(Accessor accessor) {
            mAccessor = accessor;
        }

        @Override
        public int compare(Object a, Object b) {
            return Long.compare(time(b), time(a));
        }

        private long time(Object o) {
            Long t = mTimes.get(o);
            if (t == null) {
                t = o == null ? 0L : mAccessor.lastModified(o);
                mTimes.put(o, t);
            }
            return t;
        }
    }

    private static final Object NO_ACCESSOR = new Object();
    private static final ConcurrentHashMap<Class<?>, Object> sAccessors =
            new ConcurrentHashMap<Class<?>, Object>();

    private static Accessor accessorFor(Class<?> type) {
        Object hit = sAccessors.get(type);
        if (hit == null) {
            Accessor a = findAccessor(type);
            hit = a != null ? a : NO_ACCESSOR;
            sAccessors.put(type, hit);
        }
        return hit == NO_ACCESSOR ? null : (Accessor) hit;
    }

    private static Accessor findAccessor(Class<?> type) {
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
