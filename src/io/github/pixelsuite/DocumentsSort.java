package io.github.pixelsuite;

import android.content.Context;
import android.util.SparseArray;

final class DocumentsSort {
    static final String GOOGLE = "com.google.android.documentsui";
    static final String AOSP = "com.android.documentsui";
    private static final Feature.Cached sOn = new Feature.Cached(Feature.FILES_SORT);
    private static volatile Context sContext;
    private static boolean sLogged;
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

    static void newestFirst(Object model) throws Exception {
        Context c = context(); if (c == null) return;
        int dateId=0;
        try{dateId=((Number)Xp.getObjectField(model,"SORT_DIMENSION_ID_DATE")).intValue();}catch(Throwable older){}
        if(dateId==0)dateId = c.getResources().getIdentifier("date", "id", c.getPackageName());
        if (dateId == 0) dateId = c.getResources().getIdentifier("date", "id", AOSP);
        SparseArray<?> dimensions = (SparseArray<?>) Xp.getObjectField(model, "mDimensions");
        Object date = dateId == 0 ? null : dimensions.get(dateId);
        if (date == null) {
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
