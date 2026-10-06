package io.github.pixelsuite;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * A thin compatibility layer so the feature code keeps the familiar hook/reflection shape
 * while actually running on the modern libxposed API 102.
 *
 * Modern API 102 has no static XposedBridge/XposedHelpers and no before/after split: a hook
 * is a {@link Hooker} whose {@code intercept(Chain)} wraps the original call. This shim maps
 * the old "before/after + setResult" model onto that:
 *   - a Callback with beforeHookedMethod / afterHookedMethod, and a Param carrying
 *     thisObject/args/result, as before;
 *   - hook(...) builds a Hooker that runs beforeHookedMethod; if the callback set a result or
 *     returned early it skips the original; otherwise it calls chain.proceed(), then
 *     afterHookedMethod, and returns the (possibly replaced) result.
 * Reflection helpers (callMethod, getObjectField, newInstance, additional instance fields)
 * are plain Java reflection and don't touch the framework.
 *
 * The XposedInterface (the module object itself) is set once, in Module.onModuleLoaded.
 */
final class Xp {

    private static volatile XposedInterface sX;

    private Xp() {}

    static void attach(XposedInterface x) { sX = x; }
    static XposedInterface x() { return sX; }

    // ------------------------------------------------------------------ logging

    static void log(String msg) {
        XposedInterface x = sX;
        if (x != null) x.log(4 /*INFO*/, Module.TAG, msg);
    }

    static void log(String msg, Throwable t) {
        XposedInterface x = sX;
        if (x != null) x.log(6 /*ERROR*/, Module.TAG, msg, t);
    }

    static void log(Throwable t) {
        XposedInterface x = sX;
        if (x != null) x.log(6, Module.TAG, "", t);
    }

    // ------------------------------------------------------------------ callback model

    /** Old-style hook callback. Override beforeHookedMethod / afterHookedMethod. */
    abstract static class Callback {
        protected void beforeHookedMethod(Param param) throws Throwable {}
        protected void afterHookedMethod(Param param) throws Throwable {}
    }

    /** Old-style MethodHookParam. */
    static final class Param {
        public Executable method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private boolean resultSet;   // setResult was called (skip original / replace)

        Object getResult() { return result; }
        void setResult(Object r) { result = r; resultSet = true; }
    }

    // ------------------------------------------------------------------ hooking

    static Set<Object> hookAllMethods(Class<?> clazz, String name, Callback cb) {
        Set<Object> handles = new HashSet<Object>();
        if (clazz == null) return handles;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Object h = hook(m, cb);
            if (h != null) handles.add(h);
        }
        return handles;
    }

    static Set<Object> hookAllConstructors(Class<?> clazz, Callback cb) {
        Set<Object> handles = new HashSet<Object>();
        if (clazz == null) return handles;
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            Object h = hook(c, cb);
            if (h != null) handles.add(h);
        }
        return handles;
    }

    /**
     * findAndHookMethod(clazz, name, type1, type2, ..., Callback): the last vararg is the
     * callback, the rest are parameter types (Class or class-name String).
     */
    static Object findAndHookMethod(Class<?> clazz, String name, Object... typesAndCallback) {
        if (clazz == null || typesAndCallback.length == 0) return null;
        Callback cb = (Callback) typesAndCallback[typesAndCallback.length - 1];
        Class<?>[] types = new Class<?>[typesAndCallback.length - 1];
        for (int i = 0; i < types.length; i++) types[i] = asClass(typesAndCallback[i], clazz);
        try {
            Method m = clazz.getDeclaredMethod(name, types);
            return hook(m, cb);
        } catch (Throwable t) {
            Xp.log("findAndHookMethod " + clazz.getName() + "#" + name + " failed", t);
            return null;
        }
    }

    static Object hookMethod(Executable m, Callback cb) {
        return hook(m, cb);
    }

    private static Object hook(Executable executable, final Callback cb) {
        XposedInterface x = sX;
        if (x == null || executable == null) return null;
        try {
            final Executable exec = executable;
            Hooker hooker = new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Param p = new Param();
                    p.method = exec;
                    p.thisObject = chain.getThisObject();
                    List<Object> a = chain.getArgs();
                    p.args = a == null ? new Object[0] : a.toArray();
                    try {
                        cb.beforeHookedMethod(p);
                    } catch (Throwable t) {
                        Xp.log(t);
                    }
                    if (!p.resultSet) {
                        Object res = chain.proceed(p.args);   // run the original with (maybe) edited args
                        p.result = res;
                    }
                    try {
                        cb.afterHookedMethod(p);
                    } catch (Throwable t) {
                        Xp.log(t);
                    }
                    return p.result;
                }
            };
            return x.hook(exec).intercept(hooker);
        } catch (Throwable t) {
            Xp.log("hook " + describe(executable) + " failed", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            throw new RuntimeException("class not found: " + name, t);
        }
    }

    static Method findMethodExactIfExists(Class<?> clazz, String name, Class<?>... types) {
        if (clazz == null) return null;
        try {
            Method m = clazz.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            // search supers
            for (Class<?> c = clazz.getSuperclass(); c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name, types);
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) { }
            }
            return null;
        }
    }

    static Object callMethod(Object obj, String name, Object... args) {
        try {
            Method m = bestMethod(obj.getClass(), name, args);
            if (m == null) throw new NoSuchMethodException(obj.getClass().getName() + "#" + name);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        try {
            Method m = bestMethod(clazz, name, args);
            if (m == null) throw new NoSuchMethodException(clazz.getName() + "#" + name);
            return m.invoke(null, args);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Object newInstance(Class<?> clazz, Object... args) {
        try {
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                if (accepts(c.getParameterTypes(), args)) {
                    c.setAccessible(true);
                    return c.newInstance(args);
                }
            }
            throw new NoSuchMethodException(clazz.getName() + ".<init> for given args");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static Object getObjectField(Object obj, String name) {
        try {
            Field f = findField(obj.getClass(), name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void setObjectField(Object obj, String name, Object value) {
        try {
            Field f = findField(obj.getClass(), name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean getBooleanField(Object obj, String name) {
        try {
            Field f = findField(obj.getClass(), name);
            f.setAccessible(true);
            return f.getBoolean(obj);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void setBooleanField(Object obj, String name, boolean value) {
        try {
            Field f = findField(obj.getClass(), name);
            f.setAccessible(true);
            f.setBoolean(obj, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // Additional instance fields: a side table keyed by object identity, like the original.
    private static final WeakHashMap<Object, java.util.HashMap<String, Object>> sExtra =
            new WeakHashMap<Object, java.util.HashMap<String, Object>>();

    static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        synchronized (sExtra) {
            java.util.HashMap<String, Object> m = sExtra.get(obj);
            if (m == null) { m = new java.util.HashMap<String, Object>(); sExtra.put(obj, m); }
            return m.put(key, value);
        }
    }

    static Object getAdditionalInstanceField(Object obj, String key) {
        synchronized (sExtra) {
            java.util.HashMap<String, Object> m = sExtra.get(obj);
            return m == null ? null : m.get(key);
        }
    }

    // ------------------------------------------------------------------ internals

    private static Class<?> asClass(Object o, Class<?> ctx) {
        if (o instanceof Class) return (Class<?>) o;
        if (o instanceof String) {
            try {
                return Class.forName((String) o, false, ctx.getClassLoader());
            } catch (Throwable t) {
                throw new RuntimeException("type not found: " + o, t);
            }
        }
        throw new IllegalArgumentException("not a type: " + o);
    }

    /**
     * Like XposedHelpers.findMethodBestMatch: the first method with this name whose parameter
     * types accept the actual arguments (boxing included), searching the class, its superclasses
     * and interfaces. Matching by type matters: setTitle(CharSequence) vs setTitle(int).
     */
    private static Method bestMethod(Class<?> clazz, String name, Object[] args) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && accepts(m.getParameterTypes(), args)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Class<?> itf : allInterfaces(clazz)) {
            for (Method m : itf.getMethods()) {
                if (m.getName().equals(name) && accepts(m.getParameterTypes(), args)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            Class<?> t = types[i];
            if (a == null) {
                if (t.isPrimitive()) return false;
                continue;
            }
            if (!box(t).isInstance(a)) return false;
        }
        return true;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == char.class) return Character.class;
        return t;
    }

    private static Set<Class<?>> allInterfaces(Class<?> clazz) {
        Set<Class<?>> out = new HashSet<Class<?>>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Collections.addAll(out, c.getInterfaces());
        }
        return out;
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static String describe(Executable e) {
        return e.getDeclaringClass().getName() + "#" + e.getName();
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof RuntimeException) return (RuntimeException) t;
        return new RuntimeException(t);
    }
}
