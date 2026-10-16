package io.github.pixelsuite;
import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
/** Restore only a folder displaced by a theme recreation; no timers or polling. */
final class ThemeFolderRestore {
    static int pending=-1;
    static boolean drawer;
    static long captured;
    static WeakReference<Activity> previous=new WeakReference<Activity>(null);
    static boolean pending(){if(pending<0)return false;if(SystemClock.uptimeMillis()-captured>15000){clear();return false;}return true;}
    static void clear(){pending=-1;previous.clear();}
    static void install(){try{
        Xp.hookAllMethods(LauncherFeatures.type("util.WallpaperThemeManager"),"onConfigurationChanged",new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){
            Activity a=(Activity)LauncherFeatures.get(p.thisObject,"activity");
            if(a!=LauncherFeatures.activity.get()||!LauncherFeatures.frosted())return;
            int old=((Number)LauncherFeatures.get(p.thisObject,"themeRes")).intValue();
            int next=((Number)Xp.callStaticMethod(LauncherFeatures.type("util.Themes"),"getActivityThemeRes",a)).intValue();
            if(old==next)return;
            Object folder=Xp.callStaticMethod(LauncherFeatures.type("AbstractFloatingView"),"getOpenView",a,1);
            if(folder==null||!LauncherFeatures.type("folder.Folder").isInstance(folder))return;
            Object info=LauncherFeatures.get(folder,"mInfo");drawer=DrawerFolders.isFolder(info);
            if(drawer){LauncherData.Folder record=DrawerFolders.record(info);if(record==null)return;pending=record.id;}
            else pending=((Number)LauncherFeatures.get(info,"id")).intValue();
            captured=SystemClock.uptimeMillis();previous=new WeakReference<Activity>(a);
        }});
        Xp.hookAllMethods(LauncherFeatures.type("Launcher"),"onResume",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            if(!pending()||!drawer||previous.get()==p.thisObject||!LauncherFeatures.on(LauncherFeatures.DRAWER))return;
            try{Object state=LauncherFeatures.type("LauncherState").getField("ALL_APPS").get(null);LauncherFeatures.call(LauncherFeatures.call(p.thisObject,"getStateManager"),"goToState",state,false);LauncherFeatures.refresh();}catch(Throwable e){clear();Module.log("Theme folder drawer restoration unavailable",e);}
        }});
        Xp.hookAllMethods(LauncherFeatures.type("folder.FolderIcon"),"inflateFolderAndIcon",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            if(!pending()||!(p.getResult() instanceof View)||LauncherFeatures.activity.get()==previous.get())return;
            final View icon=(View)p.getResult();final Object info=LauncherFeatures.get(icon,"mInfo");
            int id;if(drawer){LauncherData.Folder record=DrawerFolders.record(info);if(record==null)return;id=record.id;}else{if(DrawerFolders.isFolder(info))return;id=((Number)LauncherFeatures.get(info,"id")).intValue();}
            if(id!=pending)return;
            icon.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewDetachedFromWindow(View v){}public void onViewAttachedToWindow(View v){
                v.removeOnAttachStateChangeListener(this);
                v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){
                    icon.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if(!pending()||LauncherFeatures.activity.get()==previous.get())return;
                    clear();icon.post(new Runnable(){public void run(){try{
                        Object folder=LauncherFeatures.get(icon,"mFolder");LauncherFeatures.call(folder,"animateOpen",0,new ArrayList<Object>((List<?>)LauncherFeatures.get(info,"contents")));
                    }catch(Throwable e){Module.log("Theme folder restoration unavailable",e);}}});
                }});
            }});
        }});
    }catch(Throwable e){Module.log("Theme folder restoration unavailable",e);}}
}
