package io.github.pixelsuite;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root shell for Pixel Suite's own screen. The module app is an ordinary app: Android won't
 * let it read or write the settings these features use, so it uses the `settings` command as
 * root (KernelSU asks once). Call from a background thread.
 */
final class Root {

    private Root() {}

    /** Runs a command as root. @return its output lines, or null if root was denied or it failed. */
    static List<String> run(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            List<String> out = new ArrayList<String>();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            for (String line; (line = r.readLine()) != null; ) out.add(line.trim());
            if (!p.waitFor(15, TimeUnit.SECONDS)) return null;
            return p.exitValue() == 0 ? out : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Reads Settings.Secure ints in one root call; missing keys get their defaults. */
    static int[] readSecure(String[] keys) {
        StringBuilder cmd = new StringBuilder();
        for (String k : keys) cmd.append("settings get secure ").append(k).append(';');
        List<String> out = run(cmd.toString());
        if (out == null || out.size() < keys.length) return null;
        int[] values = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            try {
                values[i] = Integer.parseInt(out.get(i));
            } catch (NumberFormatException e) {   // "null": never set
                values[i] = Feature.defaultFor(keys[i]);
            }
        }
        return values;
    }
}
