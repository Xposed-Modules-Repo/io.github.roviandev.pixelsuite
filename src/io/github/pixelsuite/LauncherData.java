package io.github.pixelsuite;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class LauncherData {
    final SharedPreferences prefs;
    LauncherData(Context context) { prefs = context.getSharedPreferences("pixelsuite_launcher", 0); }
    static final class Folder {
        int id; String name; int grid; long user;
        int sort;
        final List<String> apps = new ArrayList<String>();
    }
    private String parsedText; private List<Folder> parsed;
    synchronized List<Folder> folders() {
        List<Folder> result = new ArrayList<Folder>();
        try {
            String text = prefs.getString("folders", "[]");
            if (parsed != null && text != null && text.equals(parsedText)) return copy(parsed);
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i); Folder f = new Folder();
                f.id = o.getInt("id"); f.name = o.getString("name");
                f.grid = grid(o.optInt("grid", 4)); f.user = o.getLong("user"); f.sort = o.optInt("sort", 0) == 1 ? 1 : 0;
                JSONArray apps = o.getJSONArray("apps");
                for (int j = 0; j < apps.length(); j++) if (!f.apps.contains(apps.getString(j))) f.apps.add(apps.getString(j));
                result.add(f);
            }
            parsed = copy(result); parsedText = text;
        } catch (Exception error) { LauncherFeatures.logOnce("Launcher folder storage cannot be read", error); }
        return result;
    }
    private static List<Folder> copy(List<Folder> from) {
        List<Folder> out = new ArrayList<Folder>(from.size());
        for (Folder f : from) {
            Folder c = new Folder(); c.id = f.id; c.name = f.name; c.grid = f.grid; c.user = f.user; c.sort = f.sort; c.apps.addAll(f.apps);
            out.add(c);
        }
        return out;
    }
    static volatile int saves;
    synchronized boolean save(List<Folder> folders) {
        try {
            JSONArray array = new JSONArray();
            for (Folder f : folders) {
                JSONObject o = new JSONObject(); o.put("id", f.id); o.put("name", f.name);
                o.put("grid", grid(f.grid)); o.put("user", f.user); o.put("sort", f.sort == 1 ? 1 : 0); o.put("apps", new JSONArray(f.apps)); array.put(o);
            }
            boolean ok=prefs.edit().putString("folders", array.toString()).commit();
            if(ok)saves++;
            return ok;
        } catch (Exception error) { Module.log("Launcher folder save failed", error); return false; }
    }
    String alias(String identity) { return prefs.getString("alias:" + identity, ""); }
    boolean rename(String identity, String label) {
        SharedPreferences.Editor edit = prefs.edit();
        if (label.length() == 0) edit.remove("alias:" + identity); else edit.putString("alias:" + identity, label);
        return edit.commit();
    }
    static int grid(int value) { return value == 6 ? 5 : value >= 3 && value <= 5 ? value : 4; }
    int homeGrid(int id, int fallback) { return grid(prefs.getInt("grid:" + id, fallback)); }
}
