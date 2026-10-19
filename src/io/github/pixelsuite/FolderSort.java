package io.github.pixelsuite;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FolderSort {
    static final int CUSTOM = 0, AUTO = 1;

    private FolderSort() {}

    static List<String> shownOrder(LauncherData.Folder f) {
        List<String> keys = new ArrayList<String>(f.apps);
        if (f.sort != AUTO) return keys;
        final Map<String, String> names = new HashMap<String, String>();
        for (String key : keys) names.put(key, DrawerFolders.displayName(key));
        Collections.sort(keys, new Comparator<String>() {
            public int compare(String a, String b) { return DrawerOrder.compare(names.get(a), a, names.get(b), b); }
        });
        return keys;
    }

    static boolean drawerCustom(int id) {
        List<LauncherData.Folder> list = LauncherFeatures.data.folders(); boolean found = false;
        for (LauncherData.Folder f : list) if (f.id == id) {
            List<String> shown = shownOrder(f); f.apps.clear(); f.apps.addAll(shown); f.sort = CUSTOM; found = true;
        }
        if (!found || !LauncherFeatures.data.save(list)) return false;
        LauncherFeatures.refresh(); return true;
    }

    static boolean drawerMove(int id, String key, int to) {
        List<LauncherData.Folder> list = LauncherFeatures.data.folders(); boolean changed = false;
        for (LauncherData.Folder f : list) if (f.id == id && f.sort != AUTO) {
            int from = f.apps.indexOf(key); if (from < 0) continue;
            f.apps.remove(from); to = Math.max(0, Math.min(to, f.apps.size())); f.apps.add(to, key); changed = from != to;
        }
        return changed && LauncherFeatures.data.save(list);
    }

    static int homeId(Object info) { try { return ((Number) LauncherFeatures.get(info, "id")).intValue(); } catch (Throwable t) { return -1; } }

    static int homeMode(Object info) {
        try { return LauncherFeatures.data.prefs.getInt("foldersort:" + homeId(info), CUSTOM) == AUTO && homeSorted(info) ? AUTO : CUSTOM; }
        catch (Throwable t) { return CUSTOM; }
    }

    static void setHomeMode(Object info, int mode) {
        try { LauncherFeatures.data.prefs.edit().putInt("foldersort:" + homeId(info), mode).apply(); } catch (Throwable ignored) { }
    }

    static List<Object> homeContents(Object info) { return new ArrayList<Object>((List<?>) LauncherFeatures.get(info, "contents")); }

    static Comparator<Object> homeComparator() {
        return new Comparator<Object>() {
            public int compare(Object a, Object b) { return DrawerOrder.compare(name(a), key(a), name(b), key(b)); }
        };
    }
    private static String name(Object item) { try { return String.valueOf(LauncherFeatures.get(item, "title")); } catch (Throwable t) { return ""; } }
    private static String key(Object item) { String k = LauncherFeatures.identity(item); return k == null ? name(item) : k; }

    static boolean homeSorted(Object info) {
        List<Object> items = homeContents(info), sorted = new ArrayList<Object>(items);
        Collections.sort(sorted, homeComparator());
        return items.equals(sorted);
    }

    static boolean sortHome(Object info) {
        try {
            final Object folder = LauncherFeatures.get(findFolderIcon(info), "mFolder");
            List<Object> contents = (List<Object>) LauncherFeatures.get(info, "contents");
            Collections.sort(contents, homeComparator());
            LauncherFeatures.call(folder, "updateItemLocationsInDatabaseBatch", false);
            Object paged = LauncherFeatures.get(folder, "mContent");
            if (Boolean.TRUE.equals(LauncherFeatures.get(paged, "mViewsBound"))) {
                List<View> views = new ArrayList<View>((List<View>) LauncherFeatures.call(folder, "getIconsInReadingOrder"));
                Collections.sort(views, new Comparator<View>() {
                    public int compare(View a, View b) { return homeComparator().compare(a.getTag(), b.getTag()); }
                });
                LauncherFeatures.call(paged, "arrangeChildren", views);
                LauncherFeatures.set(folder, "mItemsInvalidated", true);
            }
            try { LauncherFeatures.call(findFolderIcon(info), "updatePreviewItems", false); } catch (Throwable ignored) { }
            setHomeMode(info, AUTO);
            final Object snapshot = info;
            if (((View) folder).getParent() != null) ((View) folder).post(new Runnable() { public void run() {
                try { LauncherFeatures.call(folder, "animateOpen", 0, homeContents(snapshot)); } catch (Throwable ignored) { }
            }});
            return true;
        } catch (Throwable error) { Module.log("Home folder sort failed", error); return false; }
    }

    static View findFolderIcon(Object info) {
        Activity a = LauncherFeatures.activity.get(); if (a == null) return null;
        return find(a.getWindow().getDecorView(), info);
    }
    private static View find(View v, Object info) {
        try { if (LauncherFeatures.type("folder.FolderIcon").isInstance(v) && LauncherFeatures.get(v, "mInfo") == info) return v; } catch (Throwable ignored) { }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { View r = find(g.getChildAt(i), info); if (r != null) return r; }
        }
        return null;
    }

    static void homeRename(final Object info) {
        final Activity a = LauncherFeatures.activity.get(); if (a == null) return;
        final android.widget.EditText edit = new android.widget.EditText(a); edit.setSingleLine(true);
        try { edit.setText((CharSequence) LauncherFeatures.get(info, "title")); } catch (Throwable ignored) { }
        edit.selectAll();
        edit.setPadding(FrostedSurface.dp(a, 20), FrostedSurface.dp(a, 10), FrostedSurface.dp(a, 20), FrostedSurface.dp(a, 10)); edit.setTextSize(16);
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(a).setTitle("Rename folder").setView(edit)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        FrostedSurface.show(dialog);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View button) {
                String name = edit.getText().toString().trim();
                if (name.length() == 0 || name.length() > 80) { edit.setError("Use a name between 1 and 80 characters"); return; }
                try {
                    LauncherFeatures.call(info, "setTitle", name, LauncherFeatures.call(a, "getModelWriter"));
                    View icon = findFolderIcon(info);
                    if (icon != null) try { ((android.widget.TextView) LauncherFeatures.get(icon, "mFolderName")).setText(name); } catch (Throwable ignored) { }
                    dialog.dismiss();
                } catch (Throwable error) {
                    Module.log("Home folder rename failed", error);
                    android.widget.Toast.makeText(a, "Could not save the name", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
