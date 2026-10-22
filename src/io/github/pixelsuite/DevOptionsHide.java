package io.github.pixelsuite;

import android.content.Context;
import android.provider.Settings;

/**
 * 2.0.6-preview80 (owner): Hide Developer Options, the part inside an app (for the apps Pixel Suite is enabled for in
 * LSPosed, e.g. a banking app the owner ticks). The settings are hidden for every app by MiscSystem; here the system
 * properties an app can read itself show no USB debugging: the USB mode without "adb" (sys.usb.config / state,
 * persist.sys.usb.config: "mtp,adb" reads "mtp"), the debugging service stopped (init.svc.adbd), the old
 * "persist.service.adb.enable" 0. Off by default ({@link MiscSystem#DEV_KEY}); read once when the app starts.
 * Not covered: an app reading properties from its own native code.
 */
final class DevOptionsHide {
    private DevOptionsHide() {}
    private static boolean installed;
    private static Boolean on;

    private static boolean on() {
        Boolean v = on; if (v != null) return v;
        boolean value = false;
        try {
            Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (c != null) { value = "1".equals(Settings.Secure.getString(c.getContentResolver(), MiscSystem.DEV_KEY)); on = value; }
        } catch (Throwable ignored) { }
        return value;
    }

    /** The value an app sees for a property, or null to leave it. */
    static String hide(String key, String value) {
        if (key == null) return null;
        if ("sys.usb.config".equals(key) || "sys.usb.state".equals(key) || "persist.sys.usb.config".equals(key) || "sys.usb.ffs.ready.adb".equals(key)) {
            if (value == null || !value.contains("adb")) return null;
            StringBuilder b = new StringBuilder();
            for (String part : value.split(",")) { if (part.trim().equals("adb") || part.trim().isEmpty()) continue; if (b.length() > 0) b.append(','); b.append(part.trim()); }
            return b.length() == 0 ? "none" : b.toString();
        }
        if ("init.svc.adbd".equals(key)) return "running".equals(value) ? "stopped" : null;
        if ("persist.service.adb.enable".equals(key) || "service.adb.tcp.port".equals(key)) return value == null || value.isEmpty() ? null : ("service.adb.tcp.port".equals(key) ? "-1" : "0");
        return null;
    }

    static synchronized void install(String pkg) {
        if (installed) return;
        installed = true;
        int n = 0;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            n += Xp.hookAllMethods(sp, "get", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof String) || !(p.getResult() instanceof String) || !on()) return;
                    String v = hide((String) p.args[0], (String) p.getResult());
                    if (v != null) p.setResult(v);
                }
            }).size();
            Xp.Callback number = new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof String) || !on()) return;
                    String k = (String) p.args[0];
                    if ("persist.service.adb.enable".equals(k)) { if (p.getResult() instanceof Integer) p.setResult(0); else if (p.getResult() instanceof Long) p.setResult(0L); else if (p.getResult() instanceof Boolean) p.setResult(Boolean.FALSE); }
                    else if ("service.adb.tcp.port".equals(k)) { if (p.getResult() instanceof Integer) p.setResult(-1); else if (p.getResult() instanceof Long) p.setResult(-1L); }
                }
            };
            n += Xp.hookAllMethods(sp, "getInt", number).size();
            n += Xp.hookAllMethods(sp, "getLong", number).size();
            n += Xp.hookAllMethods(sp, "getBoolean", number).size();
        } catch (Throwable error) { Module.log("Hide developer options: properties unavailable in " + pkg, error); }
        // "getprop <key>" run as a command answers the hidden value too.
        try {
            n += Xp.hookAllMethods(Runtime.class, "exec", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (!on() || p.args.length == 0) return;
                    String[] cmd = p.args[0] instanceof String[] ? (String[]) p.args[0] : p.args[0] instanceof String ? ((String) p.args[0]).trim().split("\\s+") : null;
                    if (cmd == null) return;
                    if (cmd.length == 2 && "getprop".equals(cmd[0])) {
                        String real = null; try { real = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, cmd[1]); } catch (Throwable ignored) { }
                        String v = hide(cmd[1], real);
                        if (v != null) p.args[0] = new String[] { "echo", v };
                    }
                }
            }).size();
        } catch (Throwable error) { Module.log("Hide developer options: getprop command unavailable", error); }
        Module.log("Hide developer options: " + n + " hooks in " + pkg);
    }
}
