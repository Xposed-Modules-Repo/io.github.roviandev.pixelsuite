package io.github.pixelsuite;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 2.0.4-preview15: Google Photos doodles kept per photo, so they can be edited again (PhotosEditor.restore).
 *
 * One small text file per photo in Photos' own private no-backup folder (no_backup/pixelsuite-doodles), named by a
 * SHA-1 of the photo's media key or address (one file for each, preview16), never its name. Each line is one shape: kind, two points in the photo's own
 * coordinates, colour, width and fill. No picture data, nothing else, and it never leaves the phone.
 */
final class Kept {
    private Kept() {}

    static String hash(String key) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        StringBuilder h = new StringBuilder();
        for (byte x : md.digest(key.getBytes("UTF-8"))) h.append(String.format("%02x", x));
        return h.toString();
    }
    static File file(Context c, String key) throws Exception {
        return new File(new File(c.getNoBackupFilesDir(), "pixelsuite-doodles"), hash(key) + ".txt");
    }
    static Context app() {
        try { return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); }
        catch (Throwable t) { return null; }
    }

    static void store(String keys, List<Doodle> shapes) {
        if (keys == null) return;
        for (String k : keys.split("\n")) storeOne(k, shapes);
    }
    static void storeOne(String key, List<Doodle> shapes) {
        try {
            Context c = app(); if (c == null) return;
            File f = file(c, key);
            File dir = f.getParentFile(); if (dir != null && !dir.isDirectory()) dir.mkdirs();
            StringBuilder b = new StringBuilder("1\n");
            for (Doodle d : shapes) b.append(d.kind).append(' ').append(d.x1).append(' ').append(d.y1).append(' ').append(d.x2).append(' ').append(d.y2)
                    .append(' ').append(d.color).append(' ').append(d.width).append(' ').append(d.fill ? 1 : 0).append('\n');
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(b.toString().getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable error) { PhotosEditor.logOnce("doodles could not be kept for this photo", error); }
    }

    static List<Doodle> load(Context c, String keys) {
        for (String k : keys.split("\n")) { List<Doodle> got = loadOne(c, k); if (!got.isEmpty()) return got; }
        return new ArrayList<Doodle>();
    }
    static List<Doodle> loadOne(Context c, String key) {
        List<Doodle> out = new ArrayList<Doodle>();
        try {
            File f = file(c, key); if (!f.isFile() || f.length() > 256 * 1024) return out;
            byte[] b = new byte[(int) f.length()]; FileInputStream in = new FileInputStream(f);
            try { int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } } finally { in.close(); }
            String[] lines = new String(b, "UTF-8").split("\n");
            if (lines.length == 0 || !"1".equals(lines[0].trim())) return out;
            for (int i = 1; i < lines.length; i++) {
                String[] v = lines[i].trim().split(" ");
                if (v.length != 8) continue;
                int kind = Integer.parseInt(v[0]); if (kind < 0 || kind >= Doodle.KINDS) continue;
                out.add(new Doodle(kind, Float.parseFloat(v[1]), Float.parseFloat(v[2]), Float.parseFloat(v[3]), Float.parseFloat(v[4]),
                        Integer.parseInt(v[5]), Float.parseFloat(v[6]), "1".equals(v[7])));
            }
        } catch (Throwable error) { PhotosEditor.logOnce("kept doodles unreadable", error); out.clear(); }
        return out;
    }

    static void drop(Context c, String keys) {
        if (keys == null) return;
        for (String k : keys.split("\n")) try { File f = file(c, k); if (f.isFile()) f.delete(); } catch (Throwable ignored) { }
    }
}
