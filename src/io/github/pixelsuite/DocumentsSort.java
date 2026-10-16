package io.github.pixelsuite;

import android.content.Context;
import android.util.SparseArray;

/**
 * Newest first in Android's system file picker (DocumentsUI, the "Files" screen apps open when
 * you attach a file, e.g. Claude's Add to session > Files). It is a different app from Files by
 * Google, so FilesSort never ran there.
 *
 * DocumentsUI keeps its order in com.android.documentsui.sorting.SortModel: the provider query
 * order comes from getDocumentSortQuery(), and the rows are then sorted in memory by
 * sortCursor() (SortingCursorWrapper keeps folders before files). Before either runs, the model's
 * sorted dimension is set to Date, descending, so every folder and root opens newest first and
 * the picker's own Sort menu shows that choice. Same "Newest first" toggle as Files by Google.
 * DocumentsUI is an AOSP module; its classes are not renamed. Missing names are logged once.
 */
final class DocumentsSort {
    static final String GOOGLE = "com.google.android.documentsui";
    static final String AOSP = "com.android.documentsui";
    private static final Feature.Cached sOn = new Feature.Cached(Feature.FILES_SORT);
    private static volatile Context sContext;
    private static boolean sLogged;
    /** preview34: one line each, so the LSPosed log shows which step stops the sort. */
    private static boolean sLoggedOff, sLoggedNoDate, sLoggedApplied;

    private DocumentsSort() {}

    static void install(ClassLoader cl) {
        try {
            Class<?> model = Xp.findClass("com.android.documentsui.sorting.SortModel", cl);
            Xp.Callback newest = new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (active()) newestFirst(p.thisObject);
                        else if (!sLoggedOff) { sLoggedOff = true; Module.log("documents-sort: Newest first is off, picker order left alone"); }
                    }
                    catch (Throwable error) { if (!sLogged) { sLogged = true; Module.log("documents-sort: could not set newest first", error); } }
                }
            };
            int hooks = Xp.hookAllMethods(model, "sortCursor", newest).size()
                    + Xp.hookAllMethods(model, "addQuerySortArgs", newest).size()
                    + Xp.hookAllMethods(model, "getDocumentSortQuery", newest).size();
            Module.log("documents-sort hooks installed (" + hooks + ")");
        } catch (Throwable error) {
            Module.log("documents-sort unavailable", error);
        }
    }

    private static boolean active() {
        Context c = context();
        return c != null && sOn.on(c);
    }

    /** Sets the model's sorted dimension to Date, descending (2); other dimensions to none (0). */
    static void newestFirst(Object model) throws Exception {
        Context c = context(); if (c == null) return;
        // The resource package can be the AOSP name even when the app package is Google's.
        // The installed picker maps its date ID through Material3Config. Use its resolved
        // constant first, instead of assuming the unqualified resource ID is the live one.
        int dateId=0;
        try{dateId=((Number)Xp.getObjectField(model,"SORT_DIMENSION_ID_DATE")).intValue();}catch(Throwable older){}
        if(dateId==0)dateId = c.getResources().getIdentifier("date", "id", c.getPackageName());
        if (dateId == 0) dateId = c.getResources().getIdentifier("date", "id", AOSP);
        SparseArray<?> dimensions = (SparseArray<?>) Xp.getObjectField(model, "mDimensions");
        Object date = dateId == 0 ? null : dimensions.get(dateId);
        if (date == null) {
            // Fall back to the dimension labelled like a date (sort_dimension_date / _modified).
            for (int i = 0; i < dimensions.size() && date == null; i++) {
                Object d = dimensions.valueAt(i);
                try {
                    int label = ((Number) Xp.getObjectField(d, "mLabelId")).intValue();
                    String name = c.getResources().getResourceEntryName(label);
                    if (name.contains("date") || name.contains("modified")) date = d;
                } catch (Throwable ignored) { }
            }
        }
        if (date == null) {
            if (!sLoggedNoDate) { sLoggedNoDate = true; Module.log("documents-sort: no Date column among " + dimensions.size() + " sort columns (date id " + dateId + ")"); }
            return;
        }
        // preview34: also mark the choice as made, so the picker's own default (Name for most
        // folders, applied when a folder opens) does not switch back to Name and reload, and
        // its Sort menu shows Date while the toggle is on. Older pickers without it: ignored.
        try { Xp.setObjectField(model, "mIsUserSpecified", true); } catch (Throwable older) { }
        Object current = Xp.getObjectField(model, "mSortedDimension");
        if (current == date && ((Number) Xp.getObjectField(date, "mSortDirection")).intValue() == 2) {
            if (!sLoggedApplied) { sLoggedApplied=true; Module.log("documents-sort: already newest first (date id " + dateId + ")"); }
            return;
        }
        for (int i = 0; i < dimensions.size(); i++) {
            Object d = dimensions.valueAt(i);
            if (d != date) Xp.setObjectField(d, "mSortDirection", 0);
        }
        Xp.setObjectField(date, "mSortDirection", 2);
        Xp.setObjectField(model, "mSortedDimension", date);
        if (!sLoggedApplied) { sLoggedApplied = true; Module.log("documents-sort: folders sorted newest first (date id " + dateId + ")"); }
    }

    private static Context context() {
        if (sContext == null) {
            try {
                Object app = Xp.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
                if (app instanceof Context) sContext = (Context) app;
            } catch (Throwable ignored) { }
        }
        return sContext;
    }
}
