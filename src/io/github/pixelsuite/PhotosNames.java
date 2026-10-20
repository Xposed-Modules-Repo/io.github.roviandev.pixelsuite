package io.github.pixelsuite;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * 2.0.4-preview22: the photo's own name in Google Photos' database, read from inside Photos.
 *
 * The owner's preview21 log (phone offline): a fresh screenshot is opened by its media-store number, saved, and comes
 * back through Photos' editor provider (content://...editor.contentprovider/account/edit) with no number and, offline,
 * no media key either. So the doodles kept under the number were not found again. Photos itself joins the two by the
 * item's dedup key: table local_media (content_uri -> dedup_key) for the item as taken, table edits (_id ->
 * original_fingerprint, the same dedup key, plus the media_store_uri it was saved to) for the edit. Both are read here,
 * read-only, from Photos' per-account database (databases/gphotos&lt;account&gt;.db). Nothing is written and no
 * picture data is read: one text key and one address per lookup.
 */
final class PhotosNames {
    private PhotosNames() {}

    /** Adds "dedup:" (and "store:") names for the editor's intent; also widens {@code storeIds} with the edit's address. */
    static void fromDatabase(Context app, Intent i, Set<String> storeIds, Set<String> keys) {
        if (app == null || i == null) return;
        int account = -1;
        try { account = i.getIntExtra("account_id", -1); } catch (Throwable ignored) { }
        long editId = -1;
        Uri d = i.getData();
        if (d != null && d.getAuthority() != null && d.getAuthority().endsWith(".editor.contentprovider")) {
            try {
                List<String> seg = d.getPathSegments();
                if (seg.size() >= 2) { if (account < 0) account = Integer.parseInt(seg.get(0)); editId = Long.parseLong(seg.get(1)); }
            } catch (Throwable ignored) { }
        }
        java.util.ArrayList<File> dbs = new java.util.ArrayList<File>();
        try {
            if (account >= 0) dbs.add(app.getDatabasePath("gphotos" + account + ".db"));
            else { File dir = app.getDatabasePath("x").getParentFile(); File[] all = dir == null ? null : dir.listFiles(); if (all != null) for (File f : all) if (f.getName().matches("gphotos\\d+\\.db")) dbs.add(f); }
        } catch (Throwable ignored) { }
        android.os.StrictMode.ThreadPolicy old = android.os.StrictMode.allowThreadDiskReads();
        try {
            for (File f : dbs) {
                if (f == null || !f.isFile()) continue;
                SQLiteDatabase db = null;
                try {
                    db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                    if (editId >= 0) {
                        Cursor c = db.rawQuery("SELECT original_fingerprint, media_store_uri, original_uri FROM edits WHERE _id = ?", new String[]{Long.toString(editId)});
                        try {
                            if (c.moveToFirst()) {
                                String dk = c.getString(0);
                                if (dk != null && dk.length() > 0) keys.add("dedup:" + dk);
                                PhotosEditor.storeIds(c.getString(1), storeIds); PhotosEditor.storeIds(c.getString(2), storeIds);
                            }
                        } finally { c.close(); }
                    }
                    for (String id : new java.util.ArrayList<String>(storeIds)) {
                        Cursor c = db.rawQuery("SELECT dedup_key FROM local_media WHERE content_uri LIKE ? LIMIT 1", new String[]{"%/media/" + id});
                        try {
                            if (c.moveToFirst()) { String dk = c.getString(0); if (dk != null && dk.length() > 0) keys.add("dedup:" + dk); }
                        } finally { c.close(); }
                    }
                    // A photo opened right after it was taken still has Photos' placeholder key ("fake:..."); its edit
                    // record later has the real one. The real key leads back to the item's media-store number, the name
                    // of the first opening.
                    for (String k : new java.util.ArrayList<String>(keys)) {
                        if (!k.startsWith("dedup:")) continue;
                        Cursor c = db.rawQuery("SELECT content_uri FROM local_media WHERE dedup_key = ? LIMIT 1", new String[]{k.substring(6)});
                        try {
                            if (c.moveToFirst()) PhotosEditor.storeIds(c.getString(0), storeIds);
                        } finally { c.close(); }
                    }
                } catch (Throwable error) {
                    PhotosEditor.logOnce("Photos' database could not be read for the photo's name", error);
                } finally { if (db != null) try { db.close(); } catch (Throwable ignored) { } }
            }
        } finally { android.os.StrictMode.setThreadPolicy(old); }
    }
}
