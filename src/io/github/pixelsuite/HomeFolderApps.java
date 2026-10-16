package io.github.pixelsuite;

import android.app.Activity;
import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Add apps" for a home screen folder, with the same searchable picker as drawer folders.
 * The folder's own apps are ticked and listed first; untick to take an app out of this folder,
 * tick to add it. Only this folder changes: its items go through the stock folder and model
 * writer (so they are saved in the launcher's database like dragged-in apps). Shortcuts, widgets
 * and other non-app items in the folder are never touched, and an app may still also be on
 * the home screen or in another home folder, as in the stock launcher.
 */
final class HomeFolderApps {
    private HomeFolderApps() {}

    static void choose(final Object folder) {
        final Activity a = LauncherFeatures.activity.get(); if (a == null) return;
        final Object info = LauncherFeatures.get(folder, "mInfo");
        final List<Object> contents = new ArrayList<Object>((List<?>) LauncherFeatures.get(info, "contents"));
        Set<String> inFolder = new HashSet<String>();
        for (Object item : contents) { String key = appKey(item); if (key != null) inFolder.add(key); }
        final List<Object> apps = LauncherFeatures.apps();
        final Set<String> offered=new HashSet<String>();
        for(Object app:apps){String key=LauncherFeatures.identity(app);if(key!=null)offered.add(key);}
        AppPicker.show(a, "Add apps", apps, inFolder, 2, new AppPicker.Save() {
            public boolean save(List<String> chosen) {
                List<Object> now = new ArrayList<Object>((List<?>) LauncherFeatures.get(info, "contents"));
                int others = 0; Set<String> present = new HashSet<String>();
                for (Object item : now) { String key = appKey(item); if (key == null||!offered.contains(key)) others++; else present.add(key); }
                if (others + chosen.size() < 2) return false;
                try {
                    // Resolve all native editing APIs before touching the folder.
                    verifyBindings();
                    // Add first (ticked), then remove (unticked), so the folder never drops to one item on the way.
                    List<Object> added = new ArrayList<Object>();
                    for (String key : chosen) if (!present.contains(key)) {
                        Object app = find(apps, key); if (app == null) return false;
                        Object item = LauncherFeatures.call(app, "makeWorkspaceItem", a);
                        added.add(item);
                    }
                    for(Object item:added)add(folder,info,item);
                    List<Object> removed=new ArrayList<Object>();
                    for (Object item : now) { String key = appKey(item); if (key != null && offered.contains(key) && !chosen.contains(key)) removed.add(item); }
                    if(!removed.isEmpty())remove(a,folder,removed);
                    Object icon = LauncherFeatures.get(folder, "mFolderIcon");
                    try { LauncherFeatures.call(icon, "updatePreviewItems", false); ((View) icon).invalidate(); } catch (Throwable ignored) { }
                    LauncherFeatures.refresh();
                    ((View) folder).post(new Runnable() { public void run() {
                        try { LauncherFeatures.call(folder, "animateOpen", 0, new ArrayList<Object>((List<?>) LauncherFeatures.get(info, "contents"))); } catch (Throwable ignored) { }
                    }});
                    return true;
                } catch (Throwable error) { Module.log("Home folder Add apps failed", error); return false; }
            }
        });
    }

    /** Identity of an app item (itemType 0); null for shortcuts and anything else. */
    static String appKey(Object item) {
        if(LauncherFeatures.privateItem(item))return null;
        try { if (((Number) LauncherFeatures.get(item, "itemType")).intValue() != 0) return null; } catch (Throwable t) { return null; }
        return LauncherFeatures.identity(item);
    }

    static Object find(List<Object> apps, String key) {
        for (Object app : apps) if (key.equals(LauncherFeatures.identity(app))) return app;
        return null;
    }

    /** Nexus 17's own addition path handles ranks, model writes, views and preview notifications. */
    static void add(Object folder, Object info, Object item) {
        LauncherFeatures.call(folder,"addFolderContent",item,((List<?>)LauncherFeatures.get(info,"contents")).size(),false);
    }
    static void verifyBindings()throws Exception {
        Class<?> item=LauncherFeatures.type("model.data.ItemInfo"),folder=LauncherFeatures.type("folder.Folder");
        Class<?> array=java.lang.reflect.Array.newInstance(item,0).getClass();
        folder.getMethod("addFolderContent",item,int.class,boolean.class);folder.getMethod("removeFolderContent",boolean.class,array);
        folder.getMethod("updateItemLocationsInDatabaseBatch",boolean.class);
        Class<?> callback=Xp.findClass("cb.c",LauncherFeatures.loader);callback.getMethod("invoke",Object.class);
        LauncherFeatures.type("model.ModelWriter").getMethod("execute",callback);
        LauncherFeatures.type("model.TransactionContext").getMethod("deleteItemsFromDatabase",String.class,List.class);
        Xp.findClass("oa.i",LauncherFeatures.loader).getField("a");
    }
    /** Removes only the selected folder item records, through the native model transaction queue. */
    static void remove(Activity a,Object folder,final List<Object> items)throws Exception {
        final Object unit=Xp.findClass("oa.i",LauncherFeatures.loader).getField("a").get(null);
        Class<?> callback=Xp.findClass("cb.c",LauncherFeatures.loader);
        Object block=java.lang.reflect.Proxy.newProxyInstance(LauncherFeatures.loader,new Class<?>[]{callback},new java.lang.reflect.InvocationHandler(){
            public Object invoke(Object proxy,java.lang.reflect.Method method,Object[] args){
                if(method.getName().equals("invoke")){LauncherFeatures.call(args[0],"deleteItemsFromDatabase","Pixel Suite: removed in Add apps",items);return unit;}
                if(method.getName().equals("toString"))return "PixelSuiteFolderEdit";
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];return null;
            }
        });
        Object array=java.lang.reflect.Array.newInstance(LauncherFeatures.type("model.data.ItemInfo"),items.size());
        for(int i=0;i<items.size();i++)java.lang.reflect.Array.set(array,i,items.get(i));
        LauncherFeatures.call(folder,"removeFolderContent",false,array);
        LauncherFeatures.call(LauncherFeatures.call(a,"getModelWriter"),"execute",block);
        LauncherFeatures.call(folder,"updateItemLocationsInDatabaseBatch",false);
    }
}
