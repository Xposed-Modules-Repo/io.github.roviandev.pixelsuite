package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PhotosSave {
    private PhotosSave() {}

    static final String ANCHOR = "SaveUiState(status=";
    static final String PARAMS = "com.google.android.apps.photos.photoeditor.api.parameters.PipelineParams";
    static final float STEP = 0.001f, LEAST = 0.004f;

    static Class<?> stateType, paramsType, flowType;
    static Field[] flowValue;
    static Field contrast;
    static Method flowSet, flowCas, copyFn;
    static int copyMask, copyCanSave, copyCanRevert = -1;
    static Constructor<?> paramsCopy;
    static volatile WeakReference<Object> stateFlow = new WeakReference<Object>(null);
    static volatile boolean dirty;

    static void install(final ClassLoader cl, final Context app) {
        new Thread(new Runnable() { public void run() {
            try {
                paramsType = Class.forName(PARAMS, false, cl);
                contrast = paramsType.getField("contrast");
                paramsCopy = paramsType.getConstructor(paramsType);
                String[] names = cached(app);
                if (names == null) {
                    names = scan(app.getApplicationInfo().sourceDir);
                    remember(app, names);
                }
                if (names == null || names == NONE) { PhotosEditor.log("save controller not found, Save appears only with Photos' own edits"); return; }
                stateType = Class.forName(names[0], false, cl);
                copyFn = copyFunction(stateType);
                if (copyFn == null) { PhotosEditor.log("save state has no copy function, Save appears only with Photos' own edits"); return; }
                Class<?> holder = Class.forName(names[1], false, cl);
                int n = Xp.hookAllConstructors(holder, new Xp.Callback() {
                    protected void afterHookedMethod(Xp.Param p) { onHolder(p.thisObject); }
                }).size();
                if (n == 0) PhotosEditor.log("save controller hooks unavailable, Save appears only with Photos' own edits");
            } catch (Throwable error) { PhotosEditor.logOnce("Save for doodles unavailable", error); }
        }}, "pixelsuite-photos-save").start();
    }

    static void onHolder(Object h) {
        try {
            for (Field f : fields(h.getClass())) {
                Object v = f.get(h);
                if (v == null || v == h) continue;
                Field[] path = valuePath(v, stateType, 2);
                if (path == null) continue;
                if (flowType == null) {
                    flowType = v.getClass(); flowValue = path; flowSet = setter(flowType); flowCas = cas(flowType);
                    if (flowSet == null) { PhotosEditor.logOnce("save state flow has no setter (" + flowType.getName() + ")", null); flowType = null; return; }
                    guard();
                }
                stateFlow = new WeakReference<Object>(v);
                return;
            }
            PhotosEditor.logOnce("save state flow not found in " + h.getClass().getName(), null);
        } catch (Throwable error) { PhotosEditor.logOnce("save state setup failed", error); }
    }

    static void reset() { dirty = false; }

    static void changed(Activity a) {
        if (dirty) return;
        dirty = true;
        force();
    }

    static boolean guarded;
    static void guard() {
        if (guarded || flowType == null) return;
        guarded = true;
        Xp.Callback fix = new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) {
                if (!dirty || p.args.length == 0 || p.thisObject != stateFlow.get()) return;
                Object v = p.args[p.args.length - 1];
                if (v == null || !stateType.isInstance(v)) return;
                String s = String.valueOf(v);
                if (s.contains("canSave=true") && !s.contains("canRevertToOriginal=true")) return;
                try { Object fixed = forced(v); if (fixed != null) p.args[p.args.length - 1] = fixed; }
                catch (Throwable error) { PhotosEditor.logOnce("save state could not be corrected", error); }
            }
        };
        int n = 0;
        for (Class<?> k = flowType; k != null && k != Object.class; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) continue;
                boolean set = p.length == 1 && p[0] == Object.class && m.getReturnType() == void.class;
                boolean cas = p.length == 2 && p[0] == Object.class && p[1] == Object.class && m.getReturnType() == boolean.class;
                if (!set && !cas) continue;
                try { Xp.hookMethod(m, fix); n++; }
                catch (Throwable error) { PhotosEditor.logOnce("save state hook failed", error); }
            }
        if (n == 0) PhotosEditor.logOnce("save state guard unavailable", null);
    }

    static Object forced(Object cur) throws Exception {
        Object[] args = new Object[copyFn.getParameterTypes().length];
        Class<?>[] p = copyFn.getParameterTypes();
        args[0] = cur;
        for (int i = 1; i < p.length - 1; i++) args[i] = blank(p[i]);
        args[copyCanSave] = Boolean.TRUE;
        if (copyCanRevert > 0) args[copyCanRevert] = Boolean.FALSE;
        args[p.length - 1] = Integer.valueOf(copyMask);
        Object forced = copyFn.invoke(null, args);
        if (forced == null || !String.valueOf(forced).contains("canSave=true") || String.valueOf(forced).contains("canRevertToOriginal=true")) return null;
        return forced;
    }

    static void force() {
        try {
            Object flow = stateFlow.get();
            if (flow == null || flowValue == null || flowSet == null || copyFn == null) { PhotosEditor.logOnce("no save state: Save appears only with Photos' own edits", null); return; }
            Object cur = read(flow);
            if (cur == null || !stateType.isInstance(cur)) return;
            String s = String.valueOf(cur);
            if (s.contains("canSave=true") && !s.contains("canRevertToOriginal=true")) return;
            Object forced = forced(cur);
            if (forced == null) { PhotosEditor.logOnce("save state copy did not switch canSave / canRevertToOriginal", null); return; }
            if (flowCas != null) { if (!Boolean.TRUE.equals(flowCas.invoke(flow, cur, forced))) return; }
            else flowSet.invoke(flow, forced);
        } catch (Throwable error) { PhotosEditor.logOnce("Save could not be shown for doodles", error); }
    }

    static Method getParams, setParams;
    static final ThreadLocal<Object> before = new ThreadLocal<Object>();

    static Boolean canSaveNow() {
        try {
            Object flow = stateFlow.get(); if (flow == null || flowValue == null) return null;
            Object cur = read(flow); if (cur == null || !stateType.isInstance(cur)) return null;
            String s = String.valueOf(cur);
            return s.contains("canSave=true") ? Boolean.TRUE : s.contains("canSave=false") ? Boolean.FALSE : null;
        } catch (Throwable t) { return null; }
    }

    static final ThreadLocal<Boolean> reverting = new ThreadLocal<Boolean>();
    static boolean revertRender() { return Boolean.TRUE.equals(reverting.get()); }

    static void beforeRender(Object processor, long handle) {
        reverting.set(!dirty && PhotosEditor.editing() && Boolean.FALSE.equals(canSaveNow()));
        if (!dirty || paramsType == null) return;
        try {
            if (getParams == null) { getParams = native1(processor.getClass(), "nativeGetPipelineParams", 1); setParams = native1(processor.getClass(), "nativeSetPipelineParams", 2); }
            Object cur = getParams.invoke(processor, handle);
            if (!paramsType.isInstance(cur)) return;
            Object copy = paramsCopy.newInstance(cur);
            float to = next(contrast.getFloat(cur));
            contrast.setFloat(copy, to);
            setParams.invoke(processor, handle, copy);
            before.set(cur);
        } catch (Throwable error) { PhotosEditor.logOnce("render-time edit failed", error); }
    }

    static void afterRender(Object processor, long handle) {
        Object cur = before.get(); if (cur == null) return;
        before.set(null);
        try { setParams.invoke(processor, handle, cur); } catch (Throwable error) { PhotosEditor.logOnce("render-time edit could not be put back", error); }
    }

    static Object blank(Class<?> t) {
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return Integer.valueOf(0);
        if (t == long.class) return Long.valueOf(0L);
        if (t == float.class) return Float.valueOf(0f);
        if (t == double.class) return Double.valueOf(0d);
        if (t == short.class) return Short.valueOf((short) 0);
        if (t == byte.class) return Byte.valueOf((byte) 0);
        if (t == char.class) return Character.valueOf((char) 0);
        return null;
    }

    static Method native1(Class<?> c, String name, int args) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == args) { m.setAccessible(true); return m; }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    static float next(float v) {
        int k = Math.round(v * 1000f);
        float out = (k % 2 == 0 ? k + 1 : k - 1) / 1000f;
        if (Math.abs(out) < LEAST - STEP / 2) out = LEAST;
        if (out > 1f) out = 1f - STEP; if (out < -1f) out = -1f + STEP;
        return out;
    }

    static List<Field> fields(Class<?> c) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) { if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue; try { f.setAccessible(true); out.add(f); } catch (Throwable t) { } }
        return out;
    }

    static Field[] valuePath(Object v, Class<?> type, int depth) {
        try {
            for (Field f : fields(v.getClass())) {
                Object x = f.get(v);
                if (x == null) continue;
                if (f.getType() == Object.class && type.isInstance(x)) return new Field[]{f};
                if (depth > 1 && !f.getType().isArray() && !(x instanceof java.util.Collection) && !x.getClass().getName().startsWith("java.")) {
                    Field[] more = valuePath(x, type, depth - 1);
                    if (more != null) { Field[] out = new Field[more.length + 1]; out[0] = f; System.arraycopy(more, 0, out, 1, more.length); return out; }
                }
            }
        } catch (Throwable t) { }
        return null;
    }
    static Object read(Object flow) throws Exception {
        Object o = flow;
        for (Field f : flowValue) { if (o == null) return null; o = f.get(o); }
        return o;
    }

    static Method setter(Class<?> c) {
        Method one = null;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) || p.length != 1 || p[0] != Object.class || m.getReturnType() != void.class) continue;
                if (one == null) one = m; else if (!one.getName().equals(m.getName())) return null;
            }
        if (one != null) one.setAccessible(true);
        return one;
    }

    static Method cas(Class<?> c) {
        Method one = null;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) || p.length != 2 || p[0] != Object.class || p[1] != Object.class || m.getReturnType() != boolean.class) continue;
                if (one == null) one = m; else if (!one.getName().equals(m.getName())) return null;
            }
        if (one != null) one.setAccessible(true);
        return one;
    }

    static Method copyFunction(Class<?> state) {
        for (Method m : state.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != state || p.length < 3 || p[0] != state || p[p.length - 1] != int.class) continue;
            int firstBoolean = -1, secondBoolean = -1;
            for (int i = 1; i < p.length - 1; i++) if (p[i] == boolean.class) { if (firstBoolean < 0) firstBoolean = i; else if (secondBoolean < 0) secondBoolean = i; }
            if (firstBoolean < 0) continue;
            m.setAccessible(true);
            copyCanSave = firstBoolean; copyCanRevert = secondBoolean;
            copyMask = ((1 << (p.length - 2)) - 1) & ~(1 << (firstBoolean - 1));
            if (secondBoolean > 0) copyMask &= ~(1 << (secondBoolean - 1));
            return m;
        }
        return null;
    }

    static final String[] NONE = new String[0];

    static File file(Context app) { return new File(app.getCacheDir(), "pixelsuite-photos-save-" + StudioNames.versionCode(app.getApplicationInfo())); }
    static String[] cached(Context app) {
        try {
            File f = file(app); if (!f.isFile()) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 1024)]; FileInputStream in = new FileInputStream(f);
            try {
                int n = in.read(b); if (n <= 0) return null;
                String[] s = new String(b, 0, n, "UTF-8").trim().split("\n");
                if (s.length == 1) { String build = StudioNames.moduleBuild(); return build != null && s[0].trim().equals("none " + build) ? NONE : null; }
                return s.length == 2 ? new String[]{s[0].trim(), s[1].trim()} : null;
            } finally { in.close(); }
        } catch (Throwable t) { return null; }
    }
    static void remember(Context app, String[] names) {
        try {
            String text;
            if (names != null) text = names[0] + "\n" + names[1];
            else { String build = StudioNames.moduleBuild(); if (build == null) return; text = "none " + build; }
            File f = file(app); File[] old = f.getParentFile() == null ? null : f.getParentFile().listFiles();
            if (old != null) for (File o : old) if (o.getName().startsWith("pixelsuite-photos-save-") && !o.getName().equals(f.getName())) o.delete();
            FileOutputStream out = new FileOutputStream(f); try { out.write(text.getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable ignored) { }
    }

    static String[] scan(String apk) throws Exception {
        List<DexScan.Dex> dexes = new ArrayList<DexScan.Dex>();
        ZipFile z = new ZipFile(apk);
        try {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement(); String n = entry.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex") || n.contains("/")) continue;
                InputStream in = z.getInputStream(entry);
                ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.max(entry.getSize(), 1 << 16));
                byte[] b = new byte[1 << 16]; int r; while ((r = in.read(b)) > 0) buf.write(b, 0, r); in.close();
                dexes.add(new DexScan.Dex(buf.toByteArray()));
            }
        } finally { z.close(); }
        String state = null;
        for (DexScan.Dex x : dexes) { state = withString(x, ANCHOR); if (state != null) break; }
        if (state == null) return null;
        String desc = "L" + state.replace('.', '/') + ";";
        for (DexScan.Dex x : dexes) { String h = creating(x, desc); if (h != null) return new String[]{state, h}; }
        return null;
    }

    static String withString(DexScan.Dex x, String anchor) {
        int stringsSize = x.u32(0x38), stringsOff = x.u32(0x3c);
        int target = -1, lo = 0, hi = stringsSize - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = x.mutf8(x.u32(stringsOff + mid * 4)).compareTo(anchor);
            if (cmp == 0) { target = mid; break; }
            if (cmp < 0) lo = mid + 1; else hi = mid - 1;
        }
        if (target < 0) return null;
        final int t = target;
        return find(x, new Test() { public boolean hit(DexScan.Insn in) { return (in.op == 0x1a || in.op == 0x1b) && in.index == t; } }, null);
    }

    static String creating(DexScan.Dex x, String selfDesc) {
        int typesSize = x.u32(0x40), typesOff = x.u32(0x44), stringsOff = x.u32(0x3c);
        int type = -1;
        for (int i = 0; i < typesSize; i++) if (selfDesc.equals(x.mutf8(x.u32(stringsOff + x.u32(typesOff + i * 4) * 4)))) { type = i; break; }
        if (type < 0) return null;
        final int t = type;
        return find(x, new Test() { public boolean hit(DexScan.Insn in) { return in.op == 0x22 && in.index == t; } }, selfDesc);
    }

    interface Test { boolean hit(DexScan.Insn in); }

    static String find(DexScan.Dex x, Test test, String skipDesc) {
        int stringsOff = x.u32(0x3c), typesOff = x.u32(0x44), classesSize = x.u32(0x60), classesOff = x.u32(0x64);
        for (int i = 0; i < classesSize; i++) {
            int o = classesOff + i * 32, data = x.u32(o + 24);
            if (data == 0) continue;
            String desc = x.mutf8(x.u32(stringsOff + x.u32(typesOff + x.u32(o) * 4) * 4));
            if (desc.equals(skipDesc)) continue;
            x.pos = data;
            int sf = x.uleb(), inf = x.uleb(), dm = x.uleb(), vm = x.uleb();
            for (int k = 0; k < sf + inf; k++) { x.uleb(); x.uleb(); }
            List<Integer> codes = new ArrayList<Integer>();
            for (int k = 0; k < dm + vm; k++) { x.uleb(); x.uleb(); int code = x.uleb(); if (code != 0) codes.add(code); }
            for (int code : codes) {
                DexScan.Mth m = new DexScan.Mth(); m.dex = x; m.codeOff = code;
                for (DexScan.Insn in : DexScan.code(m)) if (test.hit(in)) return DexScan.name(desc);
            }
        }
        return null;
    }
}
