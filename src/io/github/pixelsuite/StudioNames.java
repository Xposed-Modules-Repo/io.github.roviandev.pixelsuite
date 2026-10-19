package io.github.pixelsuite;

import android.content.pm.ApplicationInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Pixel Studio's R8 names, which change with every Pixel Studio update.
 *
 * 1. Checked tables for the builds tested so far. The table whose names really exist in the running app is
 *    used, whatever its version number says.
 * 2. Any other build: {@link DexScan} reads the app's own dex once and finds the names from anchors that
 *    survive renaming (Compose group keys, data-class toString text, signatures). The result is cached in
 *    the app's cache folder per APK, so it runs once per Pixel Studio update (about a second).
 * 3. Nothing found: every feature that needs a missing name stays off, and Pixel Studio runs unchanged.
 *
 * The features' on/off switches are Pixel Suite settings, untouched by any of this: once names are found
 * again after an update, the features come back exactly as the user left them.
 */
final class StudioNames {
    private StudioNames() {}

    static final String[] KEYS = {
        "unit", "undoState", "history", "history.refresh", "state", "fn0", "fn1", "composer", "modifier", "painter",
        "ui", "ui.pen", "ui.tool", "ui.undoBar", "ui.done", "toolbarLambda", "penRestart",
        "painterRes", "painterRes.m", "bitmapPainter", "imageBitmap", "mutable", "policy", "policy.impl",
        "spacer", "spacer.m", "width", "width.m",
        "crop", "rect", "cropLambda", "edges",
        "renderer", "renderer.picture", "config", "area", "area.width", "area.matrix", "scene", "scene.area", "view",
    };

    /** versionCode, then the values in {@link #KEYS} order. Newest first. */
    private static final String[][] TABLES = {
        {"192717", "sxv", "juw", "klt", "f", "btg", "tap", "tba", "brl", "cda", "ciu",
                "ixs", "aK", "bq", "aE", "aF", "jkq", "jmq",
                "cvj", "j", "cis", "cfy", "bvc", "bvd", "bvl",
                "agl", "w", "ajx", "j",
                "jjv", "cfs", "hyx", "jje",
                "iwg", "a", "iwb", "ivp", "d", "e", "iwp", "k", "iwj"},
        {"192285", "ssh", "jqx", "khq", "f", "bsd", "svf", "svq", "bqi", "cbw", "chx",
                "jix", "k", "L", "e", "f", "jgn", "jip",
                "dee", "J", "chv", "cey", "btx", "bty", "buf",
                "afq", "z", "ajd", "i",
                "jfs", "cer", "hsj", "jez",
                "irq", "a", "irl", "ira", "d", "f", "irz", "k", "irt"},
    };

    private static Map<String, String> sNames = new HashMap<String, String>();
    static String source = "none";

    /** The name for this build, or null (the caller's feature then stays off). */
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
        // 1. a checked table whose names are really there (the matching version first)
        for (int pass = 0; pass < 2; pass++) {
            for (String[] t : TABLES) {
                boolean exact = t[0].equals(String.valueOf(version));
                if ((pass == 0) != exact) continue;
                Map<String, String> m = table(t);
                if (valid(m, cl)) { sNames = m; source = "table " + t[0]; Module.log("screenshot-editor: Pixel Studio " + version + ", names from " + source); return; }
            }
        }
        // 2. cached scan of this exact APK, else a new scan
        File cache = cacheFile(ai);
        Map<String, String> m = load(cache);
        if (m != null && valid(m, cl)) { sNames = m; source = "cached scan"; Module.log("screenshot-editor: Pixel Studio " + version + ", names from " + source + " (" + m.size() + ")"); return; }
        try {
            long t0 = android.os.SystemClock.uptimeMillis();
            m = DexScan.read(ai.sourceDir).resolve();
            long ms = android.os.SystemClock.uptimeMillis() - t0;
            if (valid(m, cl)) {
                sNames = m; source = "scan";
                save(cache, m);
                Module.log("screenshot-editor: Pixel Studio " + version + " is new to Pixel Suite; " + m.size() + "/" + KEYS.length
                        + " names found by reading the app (" + ms + " ms), missing: " + missing(m));
                return;
            }
            Module.log("screenshot-editor: Pixel Studio " + version + ": the editor's toolbar was not recognised (" + ms + " ms), features stay off");
        } catch (Throwable t) {
            Module.log("screenshot-editor: Pixel Studio " + version + " could not be read, features stay off", t);
        }
        sNames = new HashMap<String, String>(); source = "none";
    }

    private static Map<String, String> table(String[] t) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < KEYS.length; i++) m.put(KEYS[i], t[i + 1]);
        return m;
    }

    /** The anchor everything hangs on: the pen button composable with its exact parameter types. */
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
