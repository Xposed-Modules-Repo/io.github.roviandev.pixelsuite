package io.github.pixelsuite;

import android.content.pm.ApplicationInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

final class StudioNames {
    private StudioNames() {}

    static final String[] KEYS = {
        "unit", "undoState", "history", "history.refresh", "state", "fn0", "fn1", "composer", "modifier", "painter",
        "ui", "ui.pen", "ui.tool", "ui.undoBar", "ui.done", "toolbarLambda", "penRestart",
        "painterRes", "painterRes.m", "bitmapPainter", "imageBitmap", "mutable", "policy", "policy.impl",
        "spacer", "spacer.m", "width", "width.m",
        "crop", "rect", "cropLambda", "edges",
        "renderer", "renderer.picture", "config", "area", "area.width", "area.matrix", "scene", "scene.area", "view",
        "cropDraw", "cropDraw.cut", "cropDraw.arcs", "touch", "touch.m",
    };

    private static final String[][] TABLES = {
        {"192717", "sxv", "juw", "klt", "f", "btg", "tap", "tba", "brl", "cda", "ciu",
                "ixs", "aK", "bq", "aE", "aF", "jkq", "jmq",
                "cvj", "j", "cis", "cfy", "bvc", "bvd", "bvl",
                "agl", "w", "ajx", "j",
                "jjv", "cfs", "hyx", "jje",
                "iwg", "a", "iwb", "ivp", "d", "e", "iwp", "k", "iwj",
                "qw", "6", "7", "jne|jij", "N"},
        {"192285", "ssh", "jqx", "khq", "f", "bsd", "svf", "svq", "bqi", "cbw", "chx",
                "jix", "k", "L", "e", "f", "jgn", "jip",
                "dee", "J", "chv", "cey", "btx", "bty", "buf",
                "afq", "z", "ajd", "i",
                "jfs", "cer", "hsj", "jez",
                "irq", "a", "irl", "ira", "d", "f", "irz", "k", "irt",
                "jfp", "0", "2", "jje|jee", "M"},
    };

    private static Map<String, String> sNames = new HashMap<String, String>();
    private static final String NOT_FOUND = "pixelsuite.notFound";

    static String get(String key) { return sNames.get(key); }

    static Class<?> cls(String key, ClassLoader cl) throws ClassNotFoundException {
        String n = sNames.get(key);
        if (n == null) throw new ClassNotFoundException("Pixel Studio name not found for " + key);
        return Class.forName(n, false, cl);
    }

    static String m(String key) throws NoSuchMethodException {
        String n = sNames.get(key);
        if (n == null) throw new NoSuchMethodException("Pixel Studio name not found for " + key);
        return n;
    }

    static void resolve(ClassLoader cl, ApplicationInfo ai) {
        long version = versionCode(ai);
        for (int pass = 0; pass < 2; pass++) {
            for (String[] t : TABLES) {
                boolean exact = t[0].equals(String.valueOf(version));
                if ((pass == 0) != exact) continue;
                Map<String, String> m = table(t);
                if (valid(m, cl)) { sNames = m; return; }
            }
        }
        File cache = cacheFile(ai);
        Map<String, String> m = load(cache);
        if (m != null && valid(m, cl)) { sNames = m; return; }
        sNames = new HashMap<String, String>();
        String build = moduleBuild();
        if (m != null && build != null && build.equals(m.get(NOT_FOUND))) {
            Module.log("screenshot-editor: Pixel Studio " + version + ": the editor's toolbar was not recognised, features stay off");
            return;
        }
        try {
            m = DexScan.read(ai.sourceDir).resolve();
            if (valid(m, cl)) {
                sNames = m;
                save(cache, m);
                if (m.size() < KEYS.length) Module.log("screenshot-editor: Pixel Studio " + version + ": names not found, their features stay off: " + missing(m));
                return;
            }
            Module.log("screenshot-editor: Pixel Studio " + version + ": the editor's toolbar was not recognised, features stay off");
            if (build != null) { Map<String, String> none = new HashMap<String, String>(); none.put(NOT_FOUND, build); save(cache, none); }
        } catch (Throwable t) {
            Module.log("screenshot-editor: Pixel Studio " + version + " could not be read, features stay off", t);
        }
    }

    private static Map<String, String> table(String[] t) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < KEYS.length; i++) m.put(KEYS[i], t[i + 1]);
        return m;
    }

    private static boolean valid(Map<String, String> m, ClassLoader cl) {
        try {
            Class<?> modifier = Class.forName(m.get("modifier"), false, cl), painter = Class.forName(m.get("painter"), false, cl);
            Class<?> fn0 = Class.forName(m.get("fn0"), false, cl), composer = Class.forName(m.get("composer"), false, cl);
            Class.forName(m.get("ui"), false, cl).getDeclaredMethod(m.get("ui.pen"), modifier, painter, painter, long.class, boolean.class, fn0, composer, int.class);
            return true;
        } catch (Throwable notThisBuild) { return false; }
    }

    private static String missing(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (String k : KEYS) if (!m.containsKey(k)) b.append(b.length() == 0 ? "" : ", ").append(k);
        return b.length() == 0 ? "none" : b.toString();
    }

    static String moduleBuild() {
        try {
            ApplicationInfo me = Xp.x() == null ? null : Xp.x().getModuleApplicationInfo();
            long v = me == null ? -1 : versionCode(me);
            return v > 0 ? String.valueOf(v) : null;
        } catch (Throwable unknown) { return null; }
    }

    static long versionCode(ApplicationInfo ai) {
        for (String f : new String[]{"longVersionCode", "versionCode"}) {
            try { java.lang.reflect.Field x = ApplicationInfo.class.getDeclaredField(f); x.setAccessible(true); return ((Number) x.get(ai)).longValue(); }
            catch (Throwable ignored) { }
        }
        return -1;
    }

    private static File cacheFile(ApplicationInfo ai) {
        File apk = new File(ai.sourceDir);
        String id = versionCode(ai) + "-" + apk.length() + "-" + apk.lastModified();
        return new File(new File(ai.dataDir, "cache"), "pixelsuite-names-" + id + ".properties");
    }

    private static Map<String, String> load(File f) {
        if (!f.isFile()) return null;
        try {
            Properties p = new Properties();
            FileInputStream in = new FileInputStream(f);
            try { p.load(in); } finally { in.close(); }
            Map<String, String> m = new LinkedHashMap<String, String>();
            for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
            return m;
        } catch (Throwable unreadable) { return null; }
    }

    private static void save(File f, Map<String, String> m) {
        try {
            File dir = f.getParentFile();
            if (dir != null) {
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File o : old) if (o.getName().startsWith("pixelsuite-names-")) o.delete();
            }
            Properties p = new Properties();
            for (Map.Entry<String, String> e : m.entrySet()) p.setProperty(e.getKey(), e.getValue());
            FileOutputStream out = new FileOutputStream(f);
            try { p.store(out, "Pixel Suite: Pixel Studio names found by reading the app"); } finally { out.close(); }
        } catch (Throwable notSaved) { Module.log("screenshot-editor: names not cached (" + notSaved + ")"); }
    }
}
