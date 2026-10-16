package io.github.pixelsuite;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Field;
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

            for (Method m : java.util.Arrays.class.getDeclaredMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (!m.getName().equals("sort") || t.length < 2 || t[0] != Object[].class || t[t.length - 1] != Comparator.class) continue;
                final int last = t.length - 1;
                Xp.hookMethod(m, new Xp.Callback() {
                    @Override
                    protected void beforeHookedMethod(Xp.Param p) {
                        if (p.args[last] instanceof NewestFirst || !(p.args[0] instanceof Object[]) || !active()) return;
                        Object[] array = (Object[]) p.args[0];
                        java.util.List<Object> view = java.util.Arrays.asList(array);
                        if (last == 3) view = view.subList((Integer) p.args[1], (Integer) p.args[2]);
                        Comparator<Object> replacement = replacementFor(view);
                        if (replacement != null) p.args[last] = replacement;
                    }
                });
            }
            installQueries();
            Module.log("files-sort hooks installed");
        } catch (Throwable t) {
            Module.log("files-sort hook failed", t);
        }
    }

    private static void installQueries() {
        int hooks = 0;
        for (Method m : android.content.ContentResolver.class.getDeclaredMethods()) {
            Class<?>[] t = m.getParameterTypes();
            if (!m.getName().equals("query") || t.length != 4 || t[2] != android.os.Bundle.class) continue;
            Xp.hookMethod(m, new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (!active() || !(p.args[0] instanceof android.net.Uri)) return;
                        android.os.Bundle out = newestFirst((android.net.Uri) p.args[0], (String[]) p.args[1], (android.os.Bundle) p.args[2]);
                        if (out != null) p.args[2] = out;
                    } catch (Throwable ignored) { }
                }
            });
            hooks++;
        }
        if (hooks == 0) Module.log("files-sort: ContentResolver.query(Bundle) not found");
    }

    private static final String SQL_ORDER = "android:query-arg-sql-sort-order";
    private static final String SORT_COLUMNS = "android:query-arg-sort-columns";
    private static final String SORT_DIRECTION = "android:query-arg-sort-direction";
    private static final String SORT_COLLATION = "android:query-arg-sort-collation";
    private static volatile boolean sLoggedQuery;

    static android.os.Bundle newestFirst(android.net.Uri uri, String[] projection, android.os.Bundle in) {
        if (in == null || !"media".equals(uri.getAuthority())) return null;
        if (!in.containsKey(SQL_ORDER) && !in.containsKey(SORT_COLUMNS)) return null;
        String requested=in.getString(SQL_ORDER);
        if(requested==null){String[] columns=in.getStringArray(SORT_COLUMNS);if(columns!=null&&columns.length>0)requested=java.util.Arrays.toString(columns);}
        String distinct=uri.getQueryParameter("distinct");
        String order=MediaSortPolicy.order(uri.getAuthority(),uri.getPathSegments(),projection,
                distinct!=null&&!"false".equalsIgnoreCase(distinct)&&!"0".equals(distinct),
                in.getString("android:query-arg-sql-selection"),
                in.containsKey("android:query-arg-group-columns")||in.containsKey("android:query-arg-sql-group-by")||in.containsKey("android:query-arg-sql-having")||in.containsKey("android:query-arg-page-token"),requested);
        if(order==null)return null;
        android.os.Bundle out = new android.os.Bundle(in);
        out.remove(SORT_COLUMNS); out.remove(SORT_DIRECTION); out.remove(SORT_COLLATION);
        out.remove("android:query-arg-sort-locale");
        out.putString(SQL_ORDER, order);
        if (!sLoggedQuery) { sLoggedQuery = true; Module.log("files-sort: supported media listings sorted newest first"); }
        return out;
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
        if (accessor == null) accessor = learned(list, sample.getClass());
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
            boolean da = folder(a), db = folder(b);
            if (da != db) return da ? -1 : 1;
            return Long.compare(time(b), time(a));
        }

        private final IdentityHashMap<Object, Boolean> mFolders = new IdentityHashMap<Object, Boolean>();

        private boolean folder(Object o) {
            if (o == null) return false;
            Boolean f = mFolders.get(o);
            if (f == null) {
                File file = o instanceof File ? (File) o : mAccessor.file(o);
                f = file != null && file.isDirectory(); mFolders.put(o, f);
            }
            return f;
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
                long lastModified(Object o) {
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
                long lastModified(Object o) {
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

    private abstract static class Accessor {
        abstract long lastModified(Object o);
        File file(Object o) { return null; }
    }

    private static final ConcurrentHashMap<Class<?>, Object> sLearned = new ConcurrentHashMap<Class<?>, Object>();

    private static Accessor learned(List<?> list, Class<?> type) {
        Object hit = sLearned.get(type);
        if (hit == NO_ACCESSOR) return null;
        if (hit instanceof Accessor) return (Accessor) hit;
        List<Object> sample = new java.util.ArrayList<Object>();
        for (Object o : list) { if (o != null && o.getClass() == type) sample.add(o); if (sample.size() >= 24) break; }
        if (sample.size() < 3) return null;
        Accessor a = null;
        try { a = learn(type, sample); } catch (Throwable ignored) { }
        sLearned.put(type, a != null ? a : NO_ACCESSOR);
        return a;
    }

    private static Accessor learn(Class<?> type, List<Object> sample) throws Exception {
        if (type.getName().startsWith("java.") || type.getName().startsWith("android.")) return null;
        java.util.List<Field> longs = new java.util.ArrayList<Field>(), strings = new java.util.ArrayList<Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == long.class) { f.setAccessible(true); longs.add(f); }
                else if (f.getType() == String.class) { f.setAccessible(true); strings.add(f); }
            }
        }
        Field path = null;
        for (Field f : strings) {
            Object v = f.get(sample.get(0));
            if (v instanceof String && ((String) v).startsWith("/") && ((String) v).length() > 1) { path = f; break; }
        }
        if (path == null) return null;
        long now = System.currentTimeMillis(), floor = 978307200000L, ceil = now + 172800000L;
        java.util.List<Field> ms = new java.util.ArrayList<Field>(), secs = new java.util.ArrayList<Field>();
        for (Field f : longs) {
            boolean inMs = true, inS = true; java.util.HashSet<Long> distinct = new java.util.HashSet<Long>();
            for (Object o : sample) {
                long v = f.getLong(o); distinct.add(v);
                if (v < floor || v > ceil) inMs = false;
                if (v < floor / 1000 || v > ceil / 1000) inS = false;
            }
            if (distinct.size() < 2) continue;
            if (inMs) ms.add(f); else if (inS) secs.add(f);
        }
        java.util.List<Field> all = new java.util.ArrayList<Field>(ms); all.addAll(secs);
        if (all.isEmpty()) return null;
        Field chosen = null;
        for (Field f : all) {
            int match = 0, seen = 0;
            for (int i = 0; i < Math.min(4, sample.size()); i++) {
                Object p = path.get(sample.get(i)); if (!(p instanceof String)) continue;
                long disk = new File((String) p).lastModified(); if (disk <= 0) continue;
                seen++; long v = f.getLong(sample.get(i)); if (secs.contains(f)) v *= 1000;
                if (Math.abs(v - disk) <= 2000) match++;
            }
            if (seen > 0 && match == seen) { chosen = f; break; }
        }
        if (chosen == null) for (Field f : all) if (f.getName().toLowerCase(java.util.Locale.ROOT).contains("modif")) { chosen = f; break; }
        if (chosen == null) return null;
        final Field time = chosen, file = path; final boolean seconds = secs.contains(chosen);
        Module.log("files-sort: learned " + type.getName() + "." + time.getName() + (seconds ? " (s)" : " (ms)") + " as modified time");
        return new Accessor() {
            long lastModified(Object o) { try { long v = time.getLong(o); return seconds ? v * 1000 : v; } catch (Throwable t) { return 0L; } }
            File file(Object o) { try { Object p = file.get(o); return p instanceof String ? new File((String) p) : null; } catch (Throwable t) { return null; } }
        };
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
