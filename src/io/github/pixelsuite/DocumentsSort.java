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
    private static boolean sLogged, sLoggedNoDate;
    /**
     * Lookups whose answer cannot change in this process, so a folder listing does not repeat
     * them: the resource fallback for the Date id (-1 = not looked up yet), and whether this
     * picker's SortModel lacks SORT_DIMENSION_ID_DATE / mIsUserSpecified (older pickers; a
     * missing field otherwise costs two exceptions on every listing).
     */
    private static int sResourceDateId = -1;
    private static boolean sNoDateField, sNoUserSpecified;

    private DocumentsSort() {}

    static void install(ClassLoader cl) {
        try {
            Class<?> model = Xp.findClass("com.android.documentsui.sorting.SortModel", cl);
            Xp.Callback newest = new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param p) {
                    try { if (active()) newestFirst(p.thisObject); }
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
        if(!sNoDateField)try{dateId=((Number)Xp.getObjectField(model,"SORT_DIMENSION_ID_DATE")).intValue();}catch(Throwable older){if(missingField(older))sNoDateField=true;}
        if(dateId==0){
            if(sResourceDateId<0){
                int id = c.getResources().getIdentifier("date", "id", c.getPackageName());
                if (id == 0) id = c.getResources().getIdentifier("date", "id", AOSP);
                sResourceDateId = id;
            }
            dateId = sResourceDateId;
        }
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
            if (!sLoggedNoDate) { sLoggedNoDate = true; Module.log("documents-sort: Date sort column unavailable"); }
            return;
        }
        // preview34: also mark the choice as made, so the picker's own default (Name for most
        // folders, applied when a folder opens) does not switch back to Name and reload, and
        // its Sort menu shows Date while the toggle is on. Older pickers without it: ignored.
        if (!sNoUserSpecified) try { Xp.setObjectField(model, "mIsUserSpecified", true); } catch (Throwable older) { if (missingField(older)) sNoUserSpecified = true; }
        Object current = Xp.getObjectField(model, "mSortedDimension");
        if (current == date && ((Number) Xp.getObjectField(date, "mSortDirection")).intValue() == 2) return;
        for (int i = 0; i < dimensions.size(); i++) {
            Object d = dimensions.valueAt(i);
            if (d != date) Xp.setObjectField(d, "mSortDirection", 0);
        }
        Xp.setObjectField(date, "mSortDirection", 2);
        Xp.setObjectField(model, "mSortedDimension", date);
    }

    /** The field is absent from the class (constant for the process), not a passing failure. */
    private static boolean missingField(Throwable t) {
        return t instanceof NoSuchFieldException || t.getCause() instanceof NoSuchFieldException;
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
