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
        final List<String> apps = new ArrayList<String>();
    }
    synchronized List<Folder> folders() {
        List<Folder> result = new ArrayList<Folder>();
        try {
            JSONArray array = new JSONArray(prefs.getString("folders", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i); Folder f = new Folder();
                f.id = o.getInt("id"); f.name = o.getString("name");
                f.grid = grid(o.optInt("grid", 4)); f.user = o.getLong("user");
                JSONArray apps = o.getJSONArray("apps");
                for (int j = 0; j < apps.length(); j++) if (!f.apps.contains(apps.getString(j))) f.apps.add(apps.getString(j));
                result.add(f);
            }
        } catch (Exception error) { Module.log("Launcher folder storage cannot be read", error); }
        return result;
    }
    synchronized boolean save(List<Folder> folders) {
        try {
            JSONArray array = new JSONArray();
            for (Folder f : folders) {
                JSONObject o = new JSONObject(); o.put("id", f.id); o.put("name", f.name);
                o.put("grid", grid(f.grid)); o.put("user", f.user); o.put("apps", new JSONArray(f.apps)); array.put(o);
            }
            return prefs.edit().putString("folders", array.toString()).commit();
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
