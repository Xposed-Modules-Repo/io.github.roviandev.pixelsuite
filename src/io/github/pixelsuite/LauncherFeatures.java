package io.github.pixelsuite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.drawable.GradientDrawable;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

final class LauncherFeatures {
    static final String GRID = "pixelsuite_folder_grid";
    static final String DRAWER = "pixelsuite_drawer_folders";
    static final String RENAME = "pixelsuite_rename_apps";
    static final String ICONS = "pixelsuite_change_icons";
    static final String DRAG_PILL = "pixelsuite_drawer_home_target";
    static final String HIDE_SHORTCUTS = "pixelsuite_drawer_hide_shortcuts";
    static final String LABEL_SIZE = "pixelsuite_icon_label_size";
    static final String LABEL_LEVEL = "pixelsuite_icon_label_level";
    static volatile int labelLevel=2;
    static final String GRID_SIZE = "pixelsuite_folder_grid_size";
    static final String DRAWER_GRID_SIZE = "pixelsuite_drawer_folder_grid_size";
    static final String ROOT = "com.android.launcher3.";
    static WeakReference<Activity> activity = new WeakReference<Activity>(null);
    static LauncherData data;
    static ClassLoader loader;
    static final Map<Object, CharSequence> originals = new WeakHashMap<Object, CharSequence>();
    static final Map<Object, CharSequence> originalAppTitles = new WeakHashMap<Object, CharSequence>();
    static final Map<String,CharSequence> nativeTitles=new java.util.HashMap<String,CharSequence>();
    static final Map<String,CharSequence> nativeAppTitles=new java.util.HashMap<String,CharSequence>();
    static final ThreadLocal<Integer> activeGrid = new ThreadLocal<Integer>();
    static final ThreadLocal<Integer> activeFolderColors = new ThreadLocal<Integer>();
    static final ThreadLocal<Integer> activePreviewColor = new ThreadLocal<Integer>();
    static final ThreadLocal<int[]> folderTitleMinimum = new ThreadLocal<int[]>();
    static volatile android.graphics.Typeface labelTypeface;
    static volatile Map<String,Boolean> enabled=java.util.Collections.emptyMap();
    static boolean on(String key){return Boolean.TRUE.equals(enabled.get(key));}
    static void reload(Activity a){
        Map<String,Boolean> next=new java.util.HashMap<String,Boolean>();
        for(String key:new String[]{GRID,DRAWER,RENAME,ICONS,DRAG_PILL,HIDE_SHORTCUTS,LABEL_SIZE,FolderDrag.TWO_FINGER,MultiDrag.KEY})next.put(key,Feature.on(a,key));
        labelLevel=IconLabelScale.level(Settings.Secure.getInt(a.getContentResolver(),LABEL_LEVEL,2));
        enabled=next;
    }
    static boolean frosted(){return on(GRID)||on(DRAWER)||on(RENAME)||on(ICONS)||on(DRAG_PILL);}
    static void install(ClassLoader cl) {
        loader = cl;
        try {
            Xp.hookAllMethods(type("Launcher"), "onCreate", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    Activity a = (Activity) p.thisObject; activity = new WeakReference<Activity>(a); data = new LauncherData(a); reload(a);
                }
                protected void afterHookedMethod(Xp.Param p) {
                    final Activity a=(Activity)p.thisObject;
                    ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())) {
                        public void onChange(boolean self) {
                            if (activity.get()!=a) return;
                            reload(a);
                            if (!DrawerFolders.dragEnabled()) DrawerDrag.cancel();
                            if (!on(DRAG_PILL)) DrawerHomeTarget.hide();
                            refresh();
                        }
                    };
                    for(String key:new String[]{GRID,DRAWER,RENAME,ICONS,DRAG_PILL,HIDE_SHORTCUTS,LABEL_SIZE,LABEL_LEVEL,GRID_SIZE,DRAWER_GRID_SIZE,FolderDrag.TWO_FINGER,MultiDrag.KEY})
                        a.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key),false,observer);
                    Xp.setAdditionalInstanceField(a,"pixelsuite_settings_observer",observer);
                    GoogleAliases.publish();
                }
            });
            Xp.hookAllMethods(type("Launcher"), "onDestroy", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    Object observer=Xp.getAdditionalInstanceField(p.thisObject,"pixelsuite_settings_observer");
                    if(observer instanceof ContentObserver)((Activity)p.thisObject).getContentResolver().unregisterContentObserver((ContentObserver)observer);
                    if (activity.get() == p.thisObject) {
                        MultiDrag.cancel(); DrawerDrag.cancel(); DrawerFolders.clearIconCache();
                        activity.clear(); DrawerFolders.known.clear(); DrawerFolders.cells.clear(); DrawerFolders.downs.clear();
                    }
                }
            });
            Xp.hookAllMethods(type("Launcher"),"onResume",new Xp.Callback(){
                protected void beforeHookedMethod(Xp.Param p){
                    Activity a=(Activity)p.thisObject;activity=new WeakReference<Activity>(a);data=new LauncherData(a);reload(a);
                }
            });
            Xp.hookAllMethods(type("Launcher"),"onPause",new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){MultiDrag.cancel();DrawerFolders.cancelHold();DrawerFolderMenu.dismiss();DrawerHomeTarget.hide();DrawerDrag.pause();}});
            Xp.hookAllMethods(type("views.ActivityContext"),"dispatchDeviceProfileChanged",new Xp.Callback(){
                protected void afterHookedMethod(Xp.Param p){
                    final Activity a=activity.get();
                    if(a!=p.thisObject || (!on(GRID)&&!on(DRAWER)&&!on(LABEL_SIZE)))return;
                    a.getWindow().getDecorView().post(new Runnable(){public void run(){
                        if(activity.get()==a)refresh();
                    }});
                }
            });
            installAliases();
            LauncherIcon.install();
            installGrid();
            installBackdropDrawing();
            DrawerFolders.install();
            DrawerDrag.install();DrawerHomeTarget.install();FolderDrag.install();ScatterEffect.install();MultiDrag.install();ShapeWatch.install();
            ThemeFolderRestore.install();
            installMenus();
            try{HomeOptions.install();}catch(Throwable error){Module.log("Pixel Suite settings row unavailable",error);}
            Module.log("Launcher customization hooks installed (Launcher 17, 2.0.1)");
        } catch (Throwable error) { Module.log("Launcher customization unavailable", error); }
    }
    static Class<?> type(String name) { return Xp.findClass(ROOT + name, loader); }
    static void installBackdropDrawing(){
        final Class<?> folderClass=type("folder.Folder");
        final Class<?> dragLayerClass=type("dragndrop.DragLayer");
        Xp.hookAllMethods(folderClass,"setClipPath",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            View folder=(View)p.thisObject;Object bg=get(folder,"mBackground");
            if(bg instanceof BackdropSurface&&folder.getParent() instanceof View)((View)folder.getParent()).invalidate();
        }});
        Xp.hookAllMethods(ViewGroup.class,"drawChild",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                DropIndicator.beforeDrawChild((ViewGroup)p.thisObject,(android.graphics.Canvas)p.args[0],(View)p.args[1]);
                if(dragLayerClass.isInstance(p.thisObject))BackdropSurface.beforeDrawChild((ViewGroup)p.thisObject,(android.graphics.Canvas)p.args[0],(View)p.args[1]);
            }
            protected void afterHookedMethod(Xp.Param p){if(dragLayerClass.isInstance(p.thisObject))BackdropSurface.afterDrawChild((android.graphics.Canvas)p.args[0]);}
        });
    }
    static String lastHomeIcons;
    static void logHomeIcons(String when){
        try{
            Activity a=activity.get();if(a==null)return;
            View workspace=(View)get(a,"mWorkspace");TextView icon=firstIcon(workspace);
            Object size=null;try{size=get(call(a,"getDeviceProfile"),"iconSizePx");}catch(Throwable ignored){}
            String now=(icon==null?"no icon":get(icon,"mIconSize")+"px icon in "+icon.getWidth()+"x"+icon.getHeight()+" cell")+", profile iconSizePx="+size+", workspace scale="+workspace.getScaleX();
            if(!now.equals(lastHomeIcons)){lastHomeIcons=now;Module.log("Home icons "+when+": "+now);}
        }catch(Throwable ignored){}
    }
    static TextView firstIcon(View v){
        if(type("BubbleTextView").isInstance(v)&&v.getWidth()>0)return (TextView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView t=firstIcon(g.getChildAt(i));if(t!=null)return t;}}
        return null;
    }
    static Object get(Object o, String name) { return Xp.getObjectField(o, name); }
    static void set(Object o, String name, Object v) { Xp.setObjectField(o, name, v); }
    static Object call(Object o, String name, Object... args) { return Xp.callMethod(o, name, args); }
    static String identity(Object info) {
        try {
            ComponentName component = (ComponentName) call(info, "getTargetComponent");
            UserHandle user = (UserHandle) get(info, "user");
            Activity a = activity.get(); if (component == null || user == null || a == null) return null;
            long serial = ((UserManager) a.getSystemService(Context.USER_SERVICE)).getSerialNumberForUser(user);
            if (serial < 0) return null;
            return serial + "|" + component.flattenToString();
        } catch (Throwable ignored) { return null; }
    }
    static boolean privateItem(Object info) {
        try { return (((Number) get(info, "runtimeStatusFlags")).intValue() & 8192) != 0; }
        catch (Throwable ignored) { return false; }
    }
    static void label(Object info) {
        if (data == null || info == null || privateItem(info)) return;
        try { if (((Number)get(info,"itemType")).intValue() != 0) return; } catch (Throwable ignored) { return; }
        String key = identity(info); if (key == null) return;
        LauncherIcon.apply(info,key);
        synchronized (originals) {
            if (!originals.containsKey(info)) {
                CharSequence base=nativeTitles.get(key),baseApp=nativeAppTitles.get(key);
                if(base==null){base=(CharSequence)get(info,"title");baseApp=(CharSequence)get(info,"appTitle");nativeTitles.put(key,base);nativeAppTitles.put(key,baseApp);}
                originals.put(info,base);originalAppTitles.put(info,baseApp);
            }
            String alias = on(RENAME) ? data.alias(key) : "";
            CharSequence title = alias.length() == 0 ? originals.get(info) : alias;
            if (title != null) { set(info, "title", title); set(info, "contentDescription", title); }
            set(info,"appTitle",alias.length()==0 ? originalAppTitles.get(info) : alias);
            try { set(info, "sectionName", title == null || title.length() == 0 ? "#" : title.toString().substring(0, 1).toUpperCase(Locale.ROOT)); }
            catch (Throwable ignored) { }
        }
    }
    static List<Object> apps() {
        Activity a = activity.get(); if (a == null) return new ArrayList<Object>();
        Object store = call(call(a, "getActivityComponent"), "getAppsStore");
        Object[] array = (Object[]) get(store, "mApps");
        List<Object> list = new ArrayList<Object>();
        for (Object app : array) if (!privateItem(app)) { label(app); list.add(app); }
        return list;
    }
    static void refresh() {
        Activity a = activity.get(); if (a == null) return;
        DrawerFolders.clearIconCache();
        try {
            View decor = a.getWindow().getDecorView(); refreshLabels(decor);
            Object ah = get(call(a, "getAppsView"), "mAH");
            for (Object holder : (List<?>) ah) if (holder != null) call(get(holder, "mAppsList"), "onAppsUpdated");
        } catch (Throwable e) { Module.log("Launcher refresh failed", e); }
        DrawerFolders.scheduleRebind();
        GoogleAliases.publish();
    }
    static void refreshLabels(View v) {
        if(type("BubbleTextView").isInstance(v)){
            LauncherLabelSizing.apply((TextView)v);v.requestLayout();
        }
        if(type("folder.FolderIcon").isInstance(v)&&DrawerFolderAlignment.drawer(v)){
            final View folderIcon=v;
            folderIcon.post(new Runnable(){public void run(){try{DrawerFolderAlignment.align(folderIcon);}catch(Throwable error){logOnce("Drawer folder label refresh unavailable",error);}}});
        }
        Object info = v.getTag();
        if(info!=null && type("model.data.FolderInfo").isInstance(info)){
            for(Object member:(List<?>)get(info,"contents"))label(member);
            if(type("folder.FolderIcon").isInstance(v)){call(v,"updatePreviewItems",false);v.invalidate();}
        }
        if (info != null && type("model.data.ItemInfoWithIcon").isInstance(info)) {
            label(info);
            if(type("BubbleTextView").isInstance(v))call(v,"applyIconAndLabel",info);
            if (v instanceof TextView) ((TextView) v).setText((CharSequence) get(info, "title"));
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) refreshLabels(group.getChildAt(i));
        }
    }
    private static void installAliases() {
        Xp.Callback before = new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) { if (p.args.length > 0) label(p.args[0]); }
        };
        for (String name : new String[]{"applyFromApplicationInfo", "applyFromWorkspaceItem", "applyFromItemInfoWithIcon"})
            Xp.hookAllMethods(type("BubbleTextView"), name, before);
        Xp.hookAllMethods(type("allapps.AppInfoComparator"), "compare", new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) { label(p.args[0]); label(p.args[1]); }
        });
        Xp.hookAllMethods(type("icons.IconCache"), "getTitleAndIcon", new Xp.Callback() {
            protected void afterHookedMethod(Xp.Param p) {
                if (p.args.length == 0 || !type("model.data.ItemInfoWithIcon").isInstance(p.args[0])) return;
                synchronized (originals) { originals.remove(p.args[0]); originalAppTitles.remove(p.args[0]); }
                String nativeKey=identity(p.args[0]);if(nativeKey!=null&&((Number)get(p.args[0],"itemType")).intValue()==0)synchronized(originals){nativeTitles.put(nativeKey,(CharSequence)get(p.args[0],"title"));nativeAppTitles.put(nativeKey,(CharSequence)get(p.args[0],"appTitle"));}
                LauncherIcon.nativeLoaded(p.args[0]);
                label(p.args[0]);
            }
        });
        LauncherSearchAliases.install();
        Xp.hookAllMethods(type("allapps.AllAppsStore"),"setApps",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){GoogleAliases.publish();}});
    }
    static String searchText(String text){
        return java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
    private static void installGrid() throws Exception {
        Xp.hookAllMethods(type("folder.Folder"),"getMaxContentAreaHeight",new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){
                if(gridFor(get(p.thisObject,"mInfo"))<=3)return;
                Activity a=activity.get();if(a==null)return;
                Object profile=call(a,"getDeviceProfile");
                Object nativeFolder=get(profile,"mFolderProfile");
                int top=((Number)get(nativeFolder,"contentPaddingTop")).intValue();
                p.setResult(folderContentLimit(profile,nativeFolder,a,get(p.thisObject,"mInfo"))+top);
            }
        });
        for(Method method:type("BubbleTextView").getDeclaredMethods())if(method.getName().equals("onMeasure"))Xp.x().hook(method).intercept(new XposedInterface.Hooker(){
            public Object intercept(XposedInterface.Chain chain)throws Throwable{
                TextView text=(TextView)chain.getThisObject();
                LauncherLabelSizing.apply(text);
                int display=((Number)get(text,"mDisplay")).intValue();
                if(display==0||display==1||display==8||display==9)labelTypeface=text.getPaint().getTypeface();
                if(!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(text,"pixelsuite_two_line_cell")))return chain.proceed();
                boolean centered=(Boolean)get(text,"mCenterVertically");set(text,"mCenterVertically",false);
                int height=View.MeasureSpec.getSize(((Number)chain.getArgs().get(1)).intValue());
                android.graphics.Paint.FontMetrics metrics=text.getPaint().getFontMetrics();
                Object gridLines=Xp.getAdditionalInstanceField(text,"pixelsuite_cell_lines");
                int lines=Math.max(1,Math.min(2,gridLines instanceof Integer?(Integer)gridLines:text.getMaxLines()));
                int labelHeight=(int)Math.ceil(metrics.bottom-metrics.top)+(lines-1)*text.getLineHeight();
                int content=((Number)get(text,"mIconSize")).intValue()+text.getCompoundDrawablePadding()+labelHeight;
                text.setPadding(text.getPaddingLeft(),Math.max(0,(height-content-text.getPaddingBottom())/2),text.getPaddingRight(),text.getPaddingBottom());
                try{return chain.proceed();}finally{set(text,"mCenterVertically",centered);}
            }
        });
        Xp.hookAllMethods(type("folder.FolderGridOrganizer"), "setContentSize", new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) {
                Integer n = activeGrid.get(); if (n == null) return;
                set(p.thisObject, "mMaxCountX", n); set(p.thisObject, "mMaxCountY", n); set(p.thisObject, "mMaxItemsPerPage", n * n);
            }
        });
        Xp.hookAllMethods(type("folder.FolderGridOrganizer"), "setFolderInfo", new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) {
                int n = gridFor(p.args[0]); if (n == 0) return;
                set(p.thisObject, "mMaxCountX", n); set(p.thisObject, "mMaxCountY", n); set(p.thisObject, "mMaxItemsPerPage", n*n);
                set(p.thisObject, "mNumItemsInFolder", -1);
                Xp.setAdditionalInstanceField(p.thisObject, "pixelsuite_full_rows", Boolean.FALSE);
            }
        });
        Xp.hookAllMethods(type("folder.FolderGridOrganizer"), "setContentSize", new Xp.Callback() {
            protected void afterHookedMethod(Xp.Param p) {
                try {
                    if (!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(p.thisObject, "pixelsuite_full_rows"))) return;
                    int rows = ((Number) get(p.thisObject, "mMaxCountY")).intValue();
                    if (rows >= 4 && ((Number) get(p.thisObject, "mCountY")).intValue() < rows) set(p.thisObject, "mCountY", rows);
                } catch (Throwable error) { logOnce("Equal folder height unavailable", error); }
            }
        });
        for (Method m : type("folder.FolderPagedView").getDeclaredMethods()) {
            if (!m.getName().equals("arrangeChildren") && !m.getName().equals("createNewView")) continue;
            Xp.x().hook(m).intercept(new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object paged = chain.getThisObject(); Object folder = get(paged, "mFolder");
                    Object info = folder == null ? null : get(folder, "mInfo");
                    int n = gridFor(info);
                    if (n == 0) {
                        try{Activity a=activity.get();if(a!=null&&paged instanceof ViewGroup)applyChrome((ViewGroup)paged,get(call(a,"getDeviceProfile"),"mFolderProfile"));}catch(Throwable ignored){}
                        return chain.proceed();
                    }
                    Activity a = activity.get(); Object profile = call(a, "getDeviceProfile");
                    Object old = get(profile, "mFolderProfile"); Object fitted = fittedProfile(old, n, a, info, folder instanceof View ? (View) folder : null);
                    if (paged instanceof ViewGroup) applyChrome((ViewGroup) paged, fitted);
                    if (paged instanceof ViewGroup) {
                        ViewGroup pages = (ViewGroup) paged;
                        for (int i = 0; i < pages.getChildCount(); i++) {
                            View page = pages.getChildAt(i);
                            int w = ((Number)get(fitted,"cellWidthPx")).intValue(), h = ((Number)get(fitted,"cellHeightPx")).intValue();
                            set(page,"mCellWidth",w);set(page,"mFixedCellWidth",w);set(page,"mCellHeight",h);set(page,"mFixedCellHeight",h);
                            Object children = get(page,"mShortcutsAndWidgets");set(children,"mCellWidth",w);set(children,"mCellHeight",h);
                        }
                    }
                    Integer previous = activeGrid.get(); activeGrid.set(n); set(profile, "mFolderProfile", fitted);
                    try {
                        if(m.getName().equals("arrangeChildren"))for(Object child:(List<?>)chain.getArgs().get(0))if(child instanceof View)fitFolderIcons((View)child,fitted);
                        Object result=chain.proceed();
                        if(result instanceof View)fitFolderIcons((View)result,fitted);
                        fitFolderIcons((View)paged,fitted);
                        if(paged instanceof ViewGroup)applyChrome((ViewGroup)paged,fitted);
                        return result;
                    }
                    finally { set(profile, "mFolderProfile", old); if (previous == null) activeGrid.remove(); else activeGrid.set(previous); }
                }
            });
        }
        Xp.hookAllMethods(type("folder.Folder"), "animateOpen", new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) { logHomeIcons("before a folder opens");styleFolder(p.thisObject);attachFolderMenu(p.thisObject);if(gridFor(get(p.thisObject,"mInfo"))>0)((View)get(p.thisObject,"mFolderName")).setVisibility(View.VISIBLE); }
        });
        for(Method method:type("folder.Folder").getDeclaredMethods())if(method.getName().equals("onSizeChanged"))Xp.x().hook(method).intercept(new XposedInterface.Hooker(){public Object intercept(XposedInterface.Chain chain)throws Throwable{
            View folder=(View)chain.getThisObject();View footer=(View)get(folder,"mFooter");
            if(footer==null||footer.findViewWithTag("pixelsuite_folder_menu")==null)return chain.proceed();
            int[] previous=folderTitleMinimum.get();Context c=folder.getContext();
            folderTitleMinimum.set(new int[]{c.getResources().getIdentifier("folder_title_min_width","dimen",c.getPackageName()),dp(c,40)});
            try{return chain.proceed();}finally{if(previous==null)folderTitleMinimum.remove();else folderTitleMinimum.set(previous);}
        }});
        Xp.hookAllMethods(android.content.res.Resources.class,"getDimensionPixelSize",new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){int[] minimum=folderTitleMinimum.get();if(minimum!=null&&minimum[0]!=0&&((Number)p.args[0]).intValue()==minimum[0])p.setResult(minimum[1]);}});
        Xp.hookAllMethods(type("folder.Folder"), "closeComplete", new Xp.Callback() {
            protected void afterHookedMethod(Xp.Param p) {
                logHomeIcons("after a folder closed");
                if(Boolean.TRUE.equals(Xp.getAdditionalInstanceField(p.thisObject,"pixelsuite_hidden_close"))){Xp.setAdditionalInstanceField(p.thisObject,"pixelsuite_hidden_close",false);((View)p.thisObject).setAlpha(1f);}
                Object task=Xp.getAdditionalInstanceField(p.thisObject,"pixelsuite_after_close");
                if(task instanceof Runnable){Xp.setAdditionalInstanceField(p.thisObject,"pixelsuite_after_close",null);new Handler(Looper.getMainLooper()).post((Runnable)task);}
            }
        });
        Xp.hookAllMethods(type("folder.PreviewBackground"),"drawBackground",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                Object background=p.thisObject;Object delegate=get(background,"mInvalidateDelegate");
                if(!(delegate instanceof View))return;
                Object info=((View)delegate).getTag();boolean styled=DrawerFolders.isFolder(info)||on(GRID);
                Paint paint=(Paint)get(background,"mPaint");
                Xp.setAdditionalInstanceField(background,"pixelsuite_original_shader",paint.getShader());
                Xp.setAdditionalInstanceField(background,"pixelsuite_draw_original_color",get(background,"mBgColor"));
                if(!styled)return;
                Context context=((View)delegate).getContext();
                int stamp=context.getResources().getConfiguration().uiMode;
                Object oldStamp=Xp.getAdditionalInstanceField(background,"pixelsuite_gradient_theme");
                if(oldStamp==null||!oldStamp.equals(stamp)){
                    Xp.setAdditionalInstanceField(background,"pixelsuite_gradient",null);
                    Xp.setAdditionalInstanceField(background,"pixelsuite_gradient_theme",stamp);
                }
                Shader shader=(Shader)Xp.getAdditionalInstanceField(background,"pixelsuite_gradient");
                if(shader==null){
                    Context c=((View)delegate).getContext();
                    int first=FrostedSurface.closedFolderColor(c),last=first;
                    int size=((Number)get(background,"previewSize")).intValue();int y=((Number)get(background,"basePreviewOffsetY")).intValue();
                    shader=new LinearGradient(0,y,0,y+Math.max(1,size),first|0xff000000,last|0xff000000,Shader.TileMode.CLAMP);
                    Xp.setAdditionalInstanceField(background,"pixelsuite_gradient",shader);
                    Xp.setAdditionalInstanceField(background,"pixelsuite_surface_color",first);
                }
                paint.setShader(shader);set(background,"mBgColor",Xp.getAdditionalInstanceField(background,"pixelsuite_surface_color"));
            }
            protected void afterHookedMethod(Xp.Param p){
                ((Paint)get(p.thisObject,"mPaint")).setShader((Shader)Xp.getAdditionalInstanceField(p.thisObject,"pixelsuite_original_shader"));
                Object color=Xp.getAdditionalInstanceField(p.thisObject,"pixelsuite_draw_original_color");
                if(color!=null)set(p.thisObject,"mBgColor",color);
            }
        });
        for (Method m : type("folder.FolderAnimationSpringBuilderManager").getDeclaredMethods()) {
            if (!m.getName().equals("createAnimatorSet")) continue;
            Xp.x().hook(m).intercept(new XposedInterface.Hooker() {
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    int n = gridFor(get(get(chain.getThisObject(), "folder"), "mInfo"));
                    if (n == 4 || n == 5) {
                        try { View opening = (View) get(chain.getThisObject(), "folder"); if (isOpening(chain, opening)) alignAboveDock(opening); }
                        catch (Throwable error) { logOnce("Folder dock alignment unavailable", error); }
                    }
                    final Integer previousPreview=activePreviewColor.get();
                    View animatedFolder=(View)get(chain.getThisObject(),"folder");
                    activePreviewColor.set(styledPreview(get(animatedFolder,"mInfo"))?FrostedSurface.closedFolderColor(animatedFolder.getContext()):null);
                    try{
                    Integer previousColors=activeFolderColors.get();Object folderBackground=get(get(chain.getThisObject(),"folder"),"mBackground");int color=FrostedSurface.colors(((View)get(chain.getThisObject(),"folder")).getContext())[0];if(folderBackground instanceof BackdropSurface&&(!((BackdropSurface)folderBackground).allowed||((BackdropSurface)folderBackground).failed))color|=0xff000000;activeFolderColors.set(frosted()?color:null);
                    if (n == 0) {try{return chain.proceed();}finally{if(previousColors==null)activeFolderColors.remove();else activeFolderColors.set(previousColors);}}
                    Activity a = activity.get(); Object profile = call(a, "getDeviceProfile"); Object old = get(profile,"mFolderProfile");
                    Object animated = get(chain.getThisObject(),"folder");
                    Integer previous = activeGrid.get(); activeGrid.set(n); set(profile,"mFolderProfile",fittedProfile(old,n,a,get(animated,"mInfo"),animated instanceof View?(View)animated:null));
                    try {
                        Object result=chain.proceed();
                        return result;
                    }
                    finally { set(profile,"mFolderProfile",old); if(previous==null)activeGrid.remove();else activeGrid.set(previous);if(previousColors==null)activeFolderColors.remove();else activeFolderColors.set(previousColors); }
                    }finally{if(previousPreview==null)activePreviewColor.remove();else activePreviewColor.set(previousPreview);}
                }
            });
        }
        Xp.hookAllMethods(type("icons.GraphicsUtils"),"getAttrColor",new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){
            if(activeFolderColors.get()==null||p.args.length!=2||!(p.args[1] instanceof Context))return;
            Context c=(Context)p.args[1];int attr=((Number)p.args[0]).intValue();
            if(attr==c.getResources().getIdentifier("folderBackgroundColor","attr",c.getPackageName()))p.setResult(activeFolderColors.get());
            else if(activePreviewColor.get()!=null&&attr==c.getResources().getIdentifier("folderPreviewColor","attr",c.getPackageName()))p.setResult(activePreviewColor.get());
        }});
        Xp.hookAllMethods(type("folder.PreviewBackground"),"getBgColor",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            Object delegate=get(p.thisObject,"mInvalidateDelegate");
            if(delegate instanceof View&&styledPreview(((View)delegate).getTag()))p.setResult(FrostedSurface.closedFolderColor(((View)delegate).getContext()));
        }});
        Xp.hookAllMethods(type("folder.Folder"),"onMeasure",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            View folder=(View)p.thisObject;int n=gridFor(get(folder,"mInfo"));if(n<4)return;
            String line=n+"x"+n+" folder measured "+folder.getMeasuredWidth()+"x"+folder.getMeasuredHeight()+" in a "+folder.getResources().getDisplayMetrics().widthPixels+"x"+folder.getResources().getDisplayMetrics().heightPixels+" display ("+lastChrome+")";
            if(!line.equals(lastFolderMeasure)){lastFolderMeasure=line;Module.log(line);}
        }});
        if(Xp.hookAllMethods(type("folder.Folder"),"centerAboutIcon",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            try{alignAboveDock((View)p.thisObject);}catch(Throwable error){logOnce("Folder dock alignment unavailable",error);}
        }}).isEmpty())Module.log("Folder.centerAboutIcon not found: 4x4 folders are placed from the open animation instead");
    }
    static String lastChrome="";
    static final java.util.Set<String> loggedOnce=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    static void logOnce(String message,Throwable error){if(!loggedOnce.add(message))return;if(error==null)Module.log(message);else Module.log(message,error);}
    static final int DOCK_GAP_DP = 28;
    static final int HOME_5X5_GAP_DP = 20, DRAWER_5X5_GAP_DP = -20;
    static void alignAboveDock(View folder){
        Object folderInfo=get(folder,"mInfo");
        int grid=gridFor(folderInfo);
        if(grid!=4&&grid!=5)return;
        Activity a=activity.get();if(a==null)return;
        Object params=folder.getLayoutParams();if(params==null)return;
        ViewGroup drag=(ViewGroup)call(a,"getDragLayer");
        int gap=grid==4?DOCK_GAP_DP:(DrawerFolders.isFolder(folderInfo)?DRAWER_5X5_GAP_DP:HOME_5X5_GAP_DP);
        int[] dock=new int[1];int line=dockLine(a,drag,dock,gap);if(line<=0)return;
        int height=((ViewGroup.LayoutParams)params).height;if(height<=0)height=folder.getMeasuredHeight();if(height<=0)return;
        android.graphics.Rect insets=(android.graphics.Rect)call(drag,"getInsets");
        int top=((Number)get(params,"y")).intValue(),target=line-height;
        int width=((ViewGroup.LayoutParams)params).width;if(width<=0)width=folder.getMeasuredWidth();
        int left=((Number)get(params,"x")).intValue();
        int across=drag.getWidth()-insets.left-insets.right;
        int targetLeft=grid==4&&width>0&&across>=width?insets.left+(across-width)/2:left;
        String note=grid+"x"+grid+" folder placed: top "+top+" -> "+target+", left "+left+" -> "+targetLeft+", size "+width+"x"+height+", dock icons at "+dock[0]+", line "+line;
        if(!note.equals(lastPlacement)){lastPlacement=note;Module.log(note);}
        boolean moveY=target>=insets.top+dp(a,8)&&target!=top,moveX=targetLeft!=left;
        if(!moveX&&!moveY)return;
        if(moveY){set(params,"y",target);folder.setPivotY(folder.getPivotY()-(target-top));}
        if(moveX){set(params,"x",targetLeft);folder.setPivotX(folder.getPivotX()-(targetLeft-left));}
        folder.requestLayout();
    }
    static String lastPlacement;
    static boolean isOpening(XposedInterface.Chain chain,View folder){
        List<Object> args=chain.getArgs();
        if(args.size()>0&&args.get(0) instanceof Boolean)return (Boolean)args.get(0);
        try{return Boolean.TRUE.equals(get(folder,"mIsOpen"));}catch(Throwable unknown){return false;}
    }
    static int dockLine(Activity a,ViewGroup drag,int[] dock){return dockLine(a,drag,dock,DOCK_GAP_DP);}
    static int dockLine(Activity a,ViewGroup drag,int[] dock,int gapDp){
        View hotseat;
        try{hotseat=(View)get(a,"mHotseat");}catch(Throwable olderLauncher){hotseat=(View)call(a,"getHotseat");}
        if(hotseat==null||hotseat.getHeight()<=0)return 0;
        if(hotseat.getHeight()>hotseat.getWidth())return 0;
        int base=layoutTop(hotseat,drag);if(base==Integer.MIN_VALUE)return 0;
        int iconTop=Integer.MAX_VALUE;
        Object children=null;try{children=get(hotseat,"mShortcutsAndWidgets");}catch(Throwable ignored){}
        if(children instanceof ViewGroup){
            ViewGroup group=(ViewGroup)children;
            for(int i=0;i<group.getChildCount();i++){
                View child=group.getChildAt(i);if(child.getVisibility()!=View.VISIBLE||child.getHeight()<=0)continue;
                int y=layoutTop(child,drag);if(y==Integer.MIN_VALUE)continue;
                int inset=child.getPaddingTop();
                if(!(child instanceof TextView)||((TextView)child).getCompoundDrawables()[1]==null){
                    try{android.graphics.Rect icon=new android.graphics.Rect();call(child,"getIconBounds",icon);if(!icon.isEmpty()&&icon.top>=0&&icon.top<child.getHeight())inset=icon.top;}catch(Throwable ignored){}
                }
                iconTop=Math.min(iconTop,y+inset);
            }
        }
        if(iconTop==Integer.MAX_VALUE)iconTop=base+hotseat.getPaddingTop();
        dock[0]=iconTop;
        return iconTop-dp(a,gapDp);
    }
    static int layoutTop(View v,View ancestor){
        int y=0;View current=v;
        while(current!=null&&current!=ancestor){
            y+=current.getTop();
            if(!(current.getParent() instanceof View))return Integer.MIN_VALUE;
            current=(View)current.getParent();
            if(current!=ancestor)y-=current.getScrollY();
        }
        return current==ancestor?y:Integer.MIN_VALUE;
    }
    static String lastFolderMeasure;
    static boolean styledPreview(Object info){return info!=null&&(DrawerFolders.isFolder(info)||on(GRID));}
    static void decorateAnimator(Object result,final XposedInterface.Chain chain){
        if(result instanceof AnimatorSet && Boolean.TRUE.equals(chain.getArgs().get(0))){
            final Object folder=get(chain.getThisObject(),"folder");
            ((AnimatorSet)result).addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){styleFolder(folder);}});
        }
    }
    static void afterClose(Object folder,Runnable task){
        Xp.setAdditionalInstanceField(folder,"pixelsuite_after_close",task);call(folder,"close",true);
    }
    static void fitFolderIcons(View view,Object fitted){
        if(type("BubbleTextView").isInstance(view)){
            int size=((Number)get(fitted,"childIconSizePx")).intValue();
            set(view,"mIconSize",size);
            TextView text=(TextView)view;
            Xp.setAdditionalInstanceField(view,"pixelsuite_two_line_cell",((Number)get(fitted,"numColumns")).intValue()>3);
            text.setTextSize(0,((Number)get(fitted,"childTextSizePx")).floatValue());
            text.setCompoundDrawablePadding(((Number)get(fitted,"childDrawablePaddingPx")).intValue());
            int lines=((Number)get(fitted,"maxChildTextLineCount")).intValue();
            Xp.setAdditionalInstanceField(view,"pixelsuite_cell_lines",lines);
            if(lines>1&&text.getText().toString().indexOf(' ')<0)lines=1;
            text.setSingleLine(lines==1);text.setHorizontallyScrolling(false);text.setMaxLines(lines);text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if(lines>1)text.setBreakStrategy(2);
            call(view,"applyCompoundDrawables",get(view,"mIcon"));view.requestLayout();
        }else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)fitFolderIcons(group.getChildAt(i),fitted);}
    }
    static void styleFolder(Object folder){
        if(!frosted())return;
        View view=(View)folder;Context c=view.getContext();
        GradientDrawable background=(GradientDrawable)get(folder,"mBackground");
        if(!(background instanceof BackdropSurface)){
            float radius=((Number)call(background,"getCornerRadius")).floatValue();
            int left=view.getPaddingLeft(),top=view.getPaddingTop(),right=view.getPaddingRight(),bottom=view.getPaddingBottom();
            android.graphics.Rect bounds=new android.graphics.Rect(background.getBounds());background.setCallback(null);
            background=new BackdropSurface(c,0);background.setCornerRadius(radius);background.setBounds(bounds);set(folder,"mBackground",background);
            view.setPadding(left,top,right,bottom);
        }
        int left=view.getPaddingLeft(),top=view.getPaddingTop(),right=view.getPaddingRight(),bottom=view.getPaddingBottom();
        if(view.getBackground() instanceof BackdropSurface){view.setBackground(null);view.setPadding(left,top,right,bottom);}
        background.setCallback(view);
        background.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);background.setColors(FrostedSurface.folderColors(c));background.setAlpha(255);background.setStroke(0,0);
        ((BackdropSurface)background).bind(view);
        view.invalidate();
    }
    static int surface(Context c,String name,int fallback){
        int id=c.getResources().getIdentifier(name,"color","android");
        try{return id==0?fallback:c.getResources().getColor(id,c.getTheme());}catch(Throwable ignored){return fallback;}
    }
    static int gridFor(Object info) {
        if (info == null || data == null) return 0;
        int id = ((Number) get(info, "id")).intValue();
        if (DrawerFolders.isFolder(info)) { LauncherData.Folder f = DrawerFolders.record(info); return f == null ? 4 : f.grid; }
        if (!on(GRID)) return 0;
        Activity a = activity.get(); int fallback = Settings.Secure.getInt(a.getContentResolver(), GRID_SIZE, 3);
        return data.homeGrid(id, LauncherData.grid(fallback));
    }
    static int defaultDrawerGrid(){Activity a=activity.get();return a==null?3:LauncherData.grid(Settings.Secure.getInt(a.getContentResolver(),DRAWER_GRID_SIZE,3));}
    static final float ICON_4X4_SCALE = 1.013f;
    static final float ICON_4X4_MATCH = 1.036f, ICON_5X5_TRIM = 0.965f;
    static final float ICON_5X5_PREVIEW35 = 1.07f;
    static final float ICON_4X4_BOOST = 1.042f, ICON_5X5_BOOST = ICON_5X5_PREVIEW35 * 0.96f;
    static final float FOLDER_4X4_HEIGHT = 0.87f;
    static final float FOLDER_4X4_COLUMN_GAP = 0.5f;
    static void applyChrome(ViewGroup paged,Object profile){
        try{
            Object nativePad=Xp.getAdditionalInstanceField(paged,"pixelsuite_native_pad");
            if(nativePad==null){nativePad=new int[]{paged.getPaddingLeft(),paged.getPaddingRight()};Xp.setAdditionalInstanceField(paged,"pixelsuite_native_pad",nativePad);}
            int[] stock=(int[])nativePad;
            Activity a=activity.get();Object stockProfile=a==null?null:get(call(a,"getDeviceProfile"),"mFolderProfile");
            int stockPad=stockProfile==null?-1:((Number)get(stockProfile,"contentPaddingLeftRight")).intValue();
            if(stock[0]==stockPad&&stock[1]==stockPad){
                int pad=((Number)get(profile,"contentPaddingLeftRight")).intValue();
                if(paged.getPaddingLeft()!=pad||paged.getPaddingRight()!=pad)paged.setPadding(pad,paged.getPaddingTop(),pad,paged.getPaddingBottom());
            }
            Point border=(Point)get(profile,"cellLayoutBorderSpacePx");
            for(int i=0;i<paged.getChildCount();i++){
                View page=paged.getChildAt(i);
                Object space=null;try{space=get(page,"mBorderSpace");}catch(Throwable ignored){}
                if(space instanceof Point&&!space.equals(border)){((Point)space).set(border.x,border.y);page.requestLayout();}
                Object children=null;try{children=get(page,"mShortcutsAndWidgets");}catch(Throwable ignored){}
                Object inner=null;try{if(children!=null)inner=get(children,"mBorderSpace");}catch(Throwable ignored){}
                if(inner instanceof Point&&inner!=space&&!inner.equals(border)){((Point)inner).set(border.x,border.y);((View)children).requestLayout();}
            }
        }catch(Throwable error){logOnce("Folder spacing unavailable",error);}
    }
    static int folderChrome(View folder,int pad,Activity a){
        if(folder==null)return 2*pad;
        try{return measuredChrome(folder,pad,a);}catch(Throwable error){logOnce("Folder chrome measurement unavailable",error);return 2*pad;}
    }
    static int measuredChrome(View folder,int pad,Activity a){
        int chrome=folder.getPaddingLeft()+folder.getPaddingRight();
        Object content=null;try{content=get(folder,"mContent");}catch(Throwable ignored){}
        if(content instanceof ViewGroup){
            ViewGroup paged=(ViewGroup)content;
            Object nativePad=Xp.getAdditionalInstanceField(paged,"pixelsuite_native_pad");
            int left=nativePad instanceof int[]?((int[])nativePad)[0]:paged.getPaddingLeft(),right=nativePad instanceof int[]?((int[])nativePad)[1]:paged.getPaddingRight();
            Object stockProfile=get(call(a,"getDeviceProfile"),"mFolderProfile");
            int stockPad=((Number)get(stockProfile,"contentPaddingLeftRight")).intValue();
            chrome+=left==stockPad&&right==stockPad?2*pad:left+right;
            if(paged.getChildCount()>0){View page=paged.getChildAt(0);chrome+=page.getPaddingLeft()+page.getPaddingRight();}
        }else chrome+=2*pad;
        return chrome;
    }
    static Object fittedProfile(Object old, int n, Activity a, Object info, View folder) {
        if(n==3)return old;
        int width = a.getResources().getDisplayMetrics().widthPixels;
        int height = a.getResources().getDisplayMetrics().heightPixels;
        Object profile=call(a,"getDeviceProfile");
        Object device=get(profile,"mDeviceProperties");
        width=Math.min(width,((Number)get(device,"availableWidthPx")).intValue());
        height=Math.min(height,((Number)get(device,"availableHeightPx")).intValue());
        int pad = ((Number) get(old, "contentPaddingLeftRight")).intValue();
        if(n==5)pad=Math.min(pad,dp(a,4));
        int oldWidth = ((Number) get(old, "cellWidthPx")).intValue();
        int oldHeight = ((Number) get(old, "cellHeightPx")).intValue();
        Point border = (Point) get(old, "cellLayoutBorderSpacePx");
        border=new Point(border);
        if(n==5){border.x=Math.min(border.x,dp(a,2));border.y=Math.min(border.y,dp(a,6));}
        int chrome = folderChrome(folder, pad, a);
        int cellWidth = n==5
                ? FolderGridSizing.cellWidth(width,chrome,border.x,dp(a,12))
                : Math.min(oldWidth, (width - dp(a, 40) - 2 * pad - (n - 1) * border.x) / n);
        int contentLimit=folderContentLimit(profile,old,a,info);
        int maxCellHeight = Math.max(1, (contentLimit - (n - 1) * border.y) / n);
        int cellHeight = Math.min(oldHeight, maxCellHeight);
        int nativeIcon=((Number)get(old,"childIconSizePx")).intValue();
        int preview33 = n==5 ? Math.max(1,Math.round(FolderGridSizing.iconSize(nativeIcon,cellWidth,dp(a,4))*ICON_5X5_TRIM))
                             : Math.max(1,Math.min(Math.round(Math.min(nativeIcon,cellWidth-dp(a,12))*ICON_4X4_SCALE*ICON_4X4_MATCH),cellWidth-dp(a,8)));
        int target = Math.max(1, Math.round(preview33 * (n==5 ? ICON_5X5_BOOST : ICON_4X4_BOOST)));
        int margin = n==5 ? dp(a,12) : dp(a,20);
        if (cellWidth < target + dp(a,4)) {
            if (n==5) {
                margin = FolderGridSizing.marginFor(width, chrome, border.x, 5, target + dp(a,4), dp(a,12), dp(a,8));
                cellWidth = Math.max(cellWidth, FolderGridSizing.cells(width, chrome, border.x, 5, margin));
            } else {
                margin = FolderGridSizing.marginFor(width, 2 * pad, border.x, n, target + dp(a,4), dp(a,20), dp(a,12));
                cellWidth = Math.max(cellWidth, FolderGridSizing.cells(width, 2 * pad, Math.round(border.x * FOLDER_4X4_COLUMN_GAP), n, margin));
            }
        }
        int nativeText=((Number)get(old,"childTextSizePx")).intValue();
        int drawablePadding=((Number)get(old,"childDrawablePaddingPx")).intValue();
        int minText=Math.round(10*a.getResources().getDisplayMetrics().scaledDensity);
        int icon = Math.max(1, Math.min(target, cellWidth - dp(a,2)));
        int text=nativeText,fittedPadding=drawablePadding,labelLines=n==5?1:2;
        for(int pass=0;pass<4;pass++){
            float ratio=Math.min(1f,icon/(float)Math.max(1,nativeIcon));
            text=Math.min(nativeText,Math.max(minText,Math.round(nativeText*ratio*.95f)));
            if(n==4)labelLines=1;
            fittedPadding=n==5?dp(a,3):Math.max(dp(a,4),Math.round(drawablePadding*ratio));
            int needed=icon+fittedPadding+labelHeight(text,labelLines)+dp(a,4);
            if(needed<=cellHeight)break;
            if(needed<=maxCellHeight){cellHeight=needed;break;}
            cellHeight=maxCellHeight;
            int smaller=Math.max(1,icon-(needed-maxCellHeight));
            if(smaller==icon)break;
            icon=smaller;
        }
        if(n==5){
            int before=Math.max(1,Math.round(preview33*ICON_5X5_PREVIEW35));
            if(icon<before){int needed5=icon+fittedPadding+labelHeight(text,1)+dp(a,4);cellHeight=Math.max(needed5,cellHeight-(before-icon));}
        }
        if(n==4){
            int needed=icon+fittedPadding+labelHeight(text,labelLines)+dp(a,4);
            int natural=n*cellHeight+(n-1)*border.y,cut=natural-Math.round(natural*FOLDER_4X4_HEIGHT);
            int spacing=Math.max(0,Math.min(cut/(n-1),border.y/2));
            border.y-=spacing;cut-=spacing*(n-1);
            if(cut>0)cellHeight=Math.max(needed,cellHeight-(cut+n-1)/n);
            border.x=Math.round(border.x*FOLDER_4X4_COLUMN_GAP);
        }
        lastChrome = "label lines " + labelLines + ", cell " + cellWidth + "x" + cellHeight + ", spacing " + border.x + ", chrome " + chrome
                + ", margin " + margin + ", icon " + icon + " (preview33 " + preview33 + ", target " + target + ")";
        return Xp.newInstance(old.getClass(), get(old,"labelTextSizePx"), icon,
                text, labelLines,
                fittedPadding, new Point(border), pad, get(old,"contentPaddingTop"),
                get(old,"footerHeightPx"), cellWidth, cellHeight, get(old,"labelTextScale"), n, n,
                get(old,"folderIconSizePx"), get(old,"folderIconOffsetYPx"));
    }
    static int labelHeight(int textPx,int lines){
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTextSize(textPx);
        if(labelTypeface!=null)paint.setTypeface(labelTypeface);
        Paint.FontMetrics metrics=paint.getFontMetrics();
        return (int)Math.ceil(metrics.bottom-metrics.top)+Math.max(0,lines-1)*paint.getFontMetricsInt(null);
    }
    static int menuReserve(Object info,Activity a){
        int rows=4;
        return dp(a,12+48*rows)+2*dp(a,12);
    }
    static int folderContentLimit(Object profile,Object folderProfile,Activity a,Object info){
        int available=((Number)get(get(profile,"mDeviceProperties"),"availableHeightPx")).intValue();
        ViewGroup drag=(ViewGroup)call(a,"getDragLayer");
        android.graphics.Rect insets=(android.graphics.Rect)call(drag,"getInsets");
        if(drag.getHeight()>0)available=Math.min(available,drag.getHeight()-insets.top-insets.bottom);
        int footer=((Number)get(folderProfile,"footerHeightPx")).intValue();
        int top=((Number)get(folderProfile,"contentPaddingTop")).intValue();
        return Math.max(1,available-footer-top-dp(a,64)-menuReserve(info,a));
    }
    static void attachFolderMenu(final Object folder) {
        final Object info = get(folder, "mInfo");
        if (gridFor(info) == 0) return;
        final LinearLayout footer = (LinearLayout) get(folder, "mFooter");
        if (footer.findViewWithTag("pixelsuite_folder_menu") != null) return;
        final TextView menu = new TextView(footer.getContext()); menu.setText("\u22ee"); menu.setTextSize(24);
        menu.setGravity(17); menu.setContentDescription("Folder options"); menu.setTag("pixelsuite_folder_menu");
        footer.addView(menu, new LinearLayout.LayoutParams(dp(footer.getContext(),48), -1));
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                FolderOptions.show(folder,v);
            }
        });
    }
    static final Map<Object,Long> openingUntil = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object,Long>());
    private static void installMenus() {
        try {
            Xp.hookAllMethods(View.class, "setAlpha", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    if (openingUntil.isEmpty() || !(p.thisObject instanceof ViewGroup) || p.args.length != 1 || !(p.args[0] instanceof Float)) return;
                    Long until = openingUntil.get(p.thisObject);
                    if (until == null) return;
                    if (android.os.SystemClock.uptimeMillis() > until) { openingUntil.remove(p.thisObject); return; }
                    if (((Float) p.args[0]).floatValue() < 1f) p.args[0] = Float.valueOf(1f);
                }
            });
        } catch (Throwable error) { logOnce("Menu open fade unavailable", error); }
        Xp.hookAllMethods(type("popup.PopupContainerWithArrow"),"populateAndShowRows",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(!on(HIDE_SHORTCUTS))return;
                Object info=get(p.thisObject,"itemInfo");
                if((type("model.data.AppInfo").isInstance(info)||DrawerFolders.isDrawerMember(info))&&!privateItem(info))p.args[0]=0;
            }
        });
        Xp.hookAllMethods(type("popup.ArrowPopup"),"assignMarginsAndBackgrounds",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            if(p.args.length>0&&p.args[0]==p.thisObject&&type("popup.PopupContainer").isInstance(p.thisObject)&&!privateItem(get(p.thisObject,"itemInfo"))){
                try{DrawerPopupLayout.compact((ViewGroup)p.thisObject);}catch(Throwable error){Module.log("Drawer compact menu unavailable",error);}
                try{MenuFont.match((ViewGroup)p.thisObject);}catch(Throwable error){logOnce("Menu font matching unavailable",error);}
                FrostedSurface.popup((ViewGroup)p.thisObject);
            }
        }});
        Xp.hookAllMethods(type("popup.ArrowPopup"),"onLayout",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            if(Xp.getAdditionalInstanceField(p.thisObject,"pixelsuite_compact_menu")!=null)DrawerPopupLayout.place((ViewGroup)p.thisObject);
        }});
        Xp.hookAllMethods(type("popup.ArrowPopup"),"addArrow",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            if(frosted()&&type("popup.PopupContainer").isInstance(p.thisObject)&&!privateItem(get(p.thisObject,"itemInfo"))){View arrow=(View)get(p.thisObject,"mArrow");if(arrow.getBackground()!=null)arrow.getBackground().setColorFilter(FrostedSurface.colors(arrow.getContext())[0],android.graphics.PorterDuff.Mode.SRC_IN);}
        }});
        Xp.hookAllMethods(type("popup.ArrowPopup"), "show", new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) {
                if (!type("popup.PopupContainer").isInstance(p.thisObject)) return;
                final Object popup = p.thisObject; final Object info = get(popup, "itemInfo");
                if (frosted() && !privateItem(info)) openingUntil.put(popup, android.os.SystemClock.uptimeMillis() + 450);
                if (info != null && type("model.data.FolderInfo").isInstance(info) && !DrawerFolders.isFolder(info)) { try { addHomeFolderRows(popup, info); } catch (Throwable error) { logOnce("Home folder menu unavailable", error); } return; }
                if (identity(info) == null || privateItem(info)) return;
                ViewGroup rows = (ViewGroup) get(popup, "systemShortcutContainer");
                if (rows == null) {
                    rows = new LinearLayout(((View)popup).getContext()); ((LinearLayout)rows).setOrientation(1);
                    ((ViewGroup)popup).addView(rows); set(popup,"systemShortcutContainer",rows);
                }
                if (rows.findViewWithTag("pixelsuite_create") != null || rows.findViewWithTag("pixelsuite_rename") != null || rows.findViewWithTag("pixelsuite_icon") != null) return;
                if(on(DRAWER)||on(ICONS)||on(RENAME))moveBubbleAboveHome(rows);
                boolean drawer = type("model.data.AppInfo").isInstance(info);
                if (drawer && on(DRAWER)) addMenuRow(rows, "Create folder", "pixelsuite_create", new View.OnClickListener() {
                    public void onClick(View v) { call(popup,"close",true); DrawerFolders.create(info); }
                }, true);
                if (on(ICONS)) addMenuRow(rows, "Change icon", "pixelsuite_icon", new View.OnClickListener() {
                    public void onClick(View v) { call(popup,"close",true); LauncherIcon.choose(info); }
                }, false);
                if (on(RENAME)) addMenuRow(rows, "Rename", "pixelsuite_rename", new View.OnClickListener() {
                    public void onClick(View v) { call(popup,"close",true); rename(info); }
                }, false);
            }
        });
    }
    static void addHomeFolderRows(final Object popup, final Object info) {
        ViewGroup rows = (ViewGroup) get(popup, "systemShortcutContainer");
        if (rows == null) return;
        if (rows.findViewWithTag("pixelsuite_folder_rename") != null) return;
        ScatterEffect.arm(info);
        addFolderRow(rows, "Rename", "pixelsuite_folder_rename", 1, 0, new View.OnClickListener() {
            public void onClick(View v) { call(popup, "close", true); FolderSort.homeRename(info); }
        });
        addFolderRow(rows, "Sort", "pixelsuite_folder_sort", 9, 1, new View.OnClickListener() {
            public void onClick(View v) { call(popup, "close", true); FolderSort.showHomeMenu(info); }
        });
    }
    static void addFolderRow(ViewGroup rows, String title, String tag, int glyph, int index, View.OnClickListener listener) {
        Context context = rows.getContext();
        int layout = context.getResources().getIdentifier("system_shortcut", "layout", context.getPackageName());
        View row = LayoutInflater.from(context).inflate(layout, rows, false);
        ((TextView)get(row,"mBubbleText")).setText(title);
        ((View)get(row,"mIconView")).setBackground(new LauncherGlyph(context, glyph));
        row.setTag(tag); row.setContentDescription(title); row.setOnClickListener(listener);
        rows.addView(row, Math.min(index, rows.getChildCount()));
    }
    static void moveBubbleAboveHome(ViewGroup rows){
        View bubble=null,home=null;
        for(int i=0;i<rows.getChildCount();i++){
            View row=rows.getChildAt(i);Object tag=row.getTag();if(tag==null)continue;
            try{
                int id=((Number)get(tag,"mLabelResId")).intValue();String resource=rows.getResources().getResourceEntryName(id);
                if(resource.equals("bubble"))bubble=row;
                if(resource.equals("action_add_to_workspace")||resource.equals("action_add_to_home_screen"))home=row;
            }catch(Throwable ignored){}
        }
        if(bubble!=null&&home!=null&&rows.indexOfChild(bubble)>rows.indexOfChild(home)){
            rows.removeView(bubble);rows.addView(bubble,rows.indexOfChild(home));
        }
    }
    static void addMenuRow(ViewGroup rows, String title, String tag, View.OnClickListener listener, boolean belowHome) {
        Context context = rows.getContext();
        int layout = context.getResources().getIdentifier("system_shortcut", "layout", context.getPackageName());
        View row = LayoutInflater.from(context).inflate(layout, rows, false);
        ((TextView)get(row,"mBubbleText")).setText(title);
        View icon = (View)get(row,"mIconView");
        icon.setBackground(new LauncherGlyph(context, belowHome ? 0 : (tag.equals("pixelsuite_icon") ? 2 : 1)));
        row.setTag(tag); row.setContentDescription(title); row.setOnClickListener(listener);
        int index = rows.getChildCount();
        if (belowHome) {
            for (int i = 0; i < rows.getChildCount(); i++) {
                Object oldTag = rows.getChildAt(i).getTag();
                if (oldTag != null) {
                    try {
                        int labelId = ((Number)get(oldTag,"mLabelResId")).intValue();
                        String resource = context.getResources().getResourceEntryName(labelId);
                        if (resource.equals("action_add_to_workspace") || resource.equals("action_add_to_home_screen")) { index=i+1;break; }
                    } catch (Throwable ignored) { }
                }
            }
        }
        rows.addView(row, index);
    }
    static void rename(final Object info) {
        final Activity a = activity.get(); final String key = identity(info); if (a == null || key == null) return;
        final EditText edit = new EditText(a); edit.setSingleLine(true); edit.setText((CharSequence)get(info,"title")); edit.selectAll();
        edit.setPadding(dp(a,24),dp(a,16),dp(a,24),dp(a,16));
        final AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Rename app").setView(edit).setMessage("This name appears on the home screen, in folders, the app drawer and launcher search.")
            .setNegativeButton("Cancel",null).setNeutralButton("Reset",new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d,int which) { data.rename(key,""); refresh(); }
            }).setPositiveButton("Save",null).create();
        FrostedSurface.show(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){
                public void onClick(View button) {
                    String value = edit.getText().toString().trim();
                    if (value.length() == 0 || value.length() > 80) { edit.setError("Use a name between 1 and 80 characters"); return; }
                    if (data.rename(key,value)) { dialog.dismiss();refresh(); } else Toast.makeText(a,"Could not save the name",0).show();
                }
        });
    }
    static int dp(Context c, float n) { return Math.round(c.getResources().getDisplayMetrics().density*n); }
}
