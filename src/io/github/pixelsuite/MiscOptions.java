package io.github.pixelsuite;

import java.util.Set;
import java.util.TreeSet;

final class MiscOptions {
    private MiscOptions() {}
    static boolean defaultOff(String key) {
        return MiscSystem.SECURE_KEY.equals(key) || MiscSystem.A11Y_KEY.equals(key) || KeyboardBarTweak.KEY.equals(key) || MiscSystem.DEV_KEY.equals(key);
    }
    static boolean packageName(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*");
    }
    static Set<String> parse(String value) {
        Set<String> result = new TreeSet<String>();
        if (value != null && !"null".equals(value)) for (String item : value.split(",")) if (packageName(item.trim())) result.add(item.trim());
        return result;
    }
    static String join(Set<String> packages) {
        StringBuilder out = new StringBuilder();
        for (String item : new TreeSet<String>(packages)) if (packageName(item)) {
            if (out.length() > 0) out.append(','); out.append(item);
        }
        return out.toString();
    }
    static boolean matches(boolean system, int filter, String label, String pkg, String query) {
        String q=query.trim().toLowerCase(java.util.Locale.ROOT);
        return (filter==2 || (filter==1)==system) && (q.isEmpty()
                || label.toLowerCase(java.util.Locale.ROOT).contains(q) || pkg.toLowerCase(java.util.Locale.ROOT).contains(q));
    }
    static String listCommand(Set<String> packages) {
        String value = join(packages);
        return value.isEmpty() ? "settings delete secure " + MiscSystem.A11Y_APPS_KEY
                : "settings put secure " + MiscSystem.A11Y_APPS_KEY + " '" + value + "'";
    }
    static Set<String> changed(Set<String> before, Set<String> after) {
        Set<String> result = new TreeSet<String>(before);
        for (String item : after) if (!result.add(item)) result.remove(item);
        return result;
    }
    static String stopCommands(Set<String> packages) {
        StringBuilder out = new StringBuilder();
        for (String pkg : packages) if (packageName(pkg) && !PrivacyLock.OUR.equals(pkg)) out.append("; am force-stop ").append(pkg);
        return out.toString();
    }
    static String keyboardCommand(boolean on) {
        return "settings put secure " + KeyboardBarTweak.KEY + " " + (on ? 1 : 0)
                + "; ime=$(settings get secure default_input_method); ime=${ime%%/*}; case \"$ime\" in ''|*[!A-Za-z0-9_.]*) ;; *) am force-stop \"$ime\";; esac";
    }
}
