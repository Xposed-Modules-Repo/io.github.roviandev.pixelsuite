package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.libxposed.api.XposedInterface;

final class DrawerFolders {
    static final int BASE = 0x01000000;
    static final int CONTAINER = -2000000;
    static final Map<Object, Integer> folderIds = Collections.synchronizedMap(new java.util.WeakHashMap<Object,Integer>());
    static final Map<View, float[]> downs = new java.util.WeakHashMap<View,float[]>();
    static final Map<String,Object> known = new HashMap<String,Object>();
    static final Map<String,View> cells = new HashMap<String,View>();
    static int animateId;
    static int reopenId;
    static boolean folderType(int value){return (value&(BASE|3))==(BASE|2) && ((value&~BASE)>>>10)>0;}
    static Object get(Object o,String f){return LauncherFeatures.get(o,f);}
    static void set(Object o,String f,Object v){LauncherFeatures.set(o,f,v);}
    static Object call(Object o,String f,Object...v){return LauncherFeatures.call(o,f,v);}
    static Class<?> type(String n){return LauncherFeatures.type(n);}
    static boolean isFolder(Object info){return folderIds.containsKey(info);}
    static LauncherData.Folder record(Object info){
        Integer id=folderIds.get(info); if(id==null || LauncherFeatures.data==null)return null;
        for(LauncherData.Folder f:LauncherFeatures.data.folders())if(f.id==id)return f; return null;
    }
    static boolean dragEnabled(){return LauncherFeatures.on(LauncherFeatures.DRAWER)||LauncherFeatures.on(LauncherFeatures.DRAG_PILL);}
    static void install() throws Exception {
        DrawerFolderAlignment.install();
        installOpenTracking();
        Xp.hookAllMethods(Xp.findClass("com.google.android.apps.nexuslauncher.NexusLauncherActivity",LauncherFeatures.loader),"onStateBack",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(FrostedSurface.dismissOpenDialogs()){p.setResult(null);return;}
                long ago=android.os.SystemClock.uptimeMillis()-folderClosedAt;
                Object top=null;try{top=Xp.callStaticMethod(type("AbstractFloatingView"),"getTopOpenView",p.thisObject);}catch(Throwable ignored){}
                View open=openDrawerFolder();
                if(folderClosedAt>0 && ago<700){p.setResult(null);return;}
                if(open!=null){folderClosedAt=android.os.SystemClock.uptimeMillis();call(open,"close",true);p.setResult(null);return;}
                if(top!=null && type("folder.Folder").isInstance(top) && isFolder(get(top,"mInfo"))){
                    folderClosedAt=android.os.SystemClock.uptimeMillis();call(top,"close",true);p.setResult(null);
                }
            }
        });
        Xp.Callback backClose=new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){try{if(type("folder.Folder").isInstance(p.thisObject)&&isFolder(get(p.thisObject,"mInfo")))folderClosedAt=android.os.SystemClock.uptimeMillis();}catch(Throwable ignored){}}
        };
        Xp.Callback closeFromBack=new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                try{
                    if(!isFolder(get(p.thisObject,"mInfo")))return;
                    for(StackTraceElement e:new Throwable().getStackTrace()){String m=e.getMethodName();if(m.startsWith("onBack")||m.equals("onStateBack")){folderClosedAt=android.os.SystemClock.uptimeMillis();return;}}
                }catch(Throwable ignored){}
            }
        };
        for(String closing:new String[]{"handleClose","animateClosed"})Xp.hookAllMethods(type("folder.Folder"),closing,closeFromBack);
        for(String back:new String[]{"onBackInvoked","onBackPressed","onBackStarted"}){
            Xp.hookAllMethods(type("AbstractFloatingView"),back,backClose);
            Xp.hookAllMethods(type("folder.Folder"),back,backClose);
        }
        for(String cls:new String[]{"com.android.launcher3.uioverrides.states.AllAppsState","com.android.launcher3.LauncherState","com.android.launcher3.allapps.AllAppsTransitionController","com.android.launcher3.uioverrides.touchcontrollers.AllAppsSwipeController"}){
            for(String m:new String[]{"onBackStarted","onBackProgressed","onBackInvoked","onBackCancelled","startBackAnimation","onBackAnimationStarted","handleBackInvoked"}){
                try{
                    Xp.hookAllMethods(Xp.findClass(cls,LauncherFeatures.loader),m,new Xp.Callback(){
                        protected void beforeHookedMethod(Xp.Param p){
                            View folder=openDrawerFolder();long ago=android.os.SystemClock.uptimeMillis()-folderClosedAt;
                            if(folder==null&&(folderClosedAt<=0||ago>=700))return;
                            String n=((java.lang.reflect.Method)p.method).getName();
                            if(n.equals("onBackInvoked")&&folder!=null){folderClosedAt=android.os.SystemClock.uptimeMillis();try{call(folder,"close",true);}catch(Throwable ignored){}}
                            if(n.equals("onBackStarted")&&folder!=null)folderClosedAt=android.os.SystemClock.uptimeMillis();
                            p.setResult(null);
                        }
                    });
                }catch(Throwable missing){}
            }
        }
        for(String launcherClass:new String[]{"com.android.launcher3.Launcher","com.android.launcher3.uioverrides.QuickstepLauncher","com.google.android.apps.nexuslauncher.NexusLauncherActivity"}){
            try{
                Xp.hookAllMethods(Xp.findClass(launcherClass,LauncherFeatures.loader),"getOnBackAnimationCallback",new Xp.Callback(){
                    protected void afterHookedMethod(Xp.Param p){
                        final View folder=openDrawerFolder();if(folder==null)return;
                        Class<?> want=((java.lang.reflect.Method)p.method).getReturnType();
                        if(!want.isInterface())return;
                        p.setResult(java.lang.reflect.Proxy.newProxyInstance(LauncherFeatures.loader,new Class<?>[]{want},new java.lang.reflect.InvocationHandler(){
                            public Object invoke(Object proxy,java.lang.reflect.Method m,Object[] args){
                                String n=m.getName();
                                if(n.equals("onBackInvoked")){try{folderClosedAt=android.os.SystemClock.uptimeMillis();call(folder,"close",true);}catch(Throwable error){Module.log("Drawer folder back",error);}return null;}
                                if(n.equals("equals"))return proxy==(args==null?null:args[0]);
                                if(n.equals("hashCode"))return System.identityHashCode(proxy);
                                if(n.equals("toString"))return "PixelSuiteDrawerFolderBack";
                                return null;
                            }
                        }));
                    }
                });
            }catch(Throwable missing){}
        }
        try{
            Class<?> dispatcher=Class.forName("android.window.WindowOnBackInvokedDispatcher");
            Xp.hookAllMethods(dispatcher,"registerOnBackInvokedCallback",new Xp.Callback(){
                protected void beforeHookedMethod(Xp.Param p){wrapBack(p,1);}
            });
            Xp.hookAllMethods(dispatcher,"unregisterOnBackInvokedCallback",new Xp.Callback(){
                protected void beforeHookedMethod(Xp.Param p){if(p.args.length>0){Object w=wrappedBack.remove(p.args[0]);if(w!=null)p.args[0]=w;}}
            }).size();
        }catch(Throwable missing){Module.log("Drawer folder back: dispatcher unavailable ("+missing+")");}
        try{
            Xp.hookAllMethods(Xp.findClass("com.android.launcher3.Launcher",LauncherFeatures.loader),"onBackPressed",new Xp.Callback(){
                protected void beforeHookedMethod(Xp.Param p){View folder=openDrawerFolder();if(folder!=null){folderClosedAt=android.os.SystemClock.uptimeMillis();call(folder,"close",true);p.setResult(null);}}
            }).size();
        }catch(Throwable missing){}
        Xp.hookAllMethods(type("statemanager.StateManager"),"goToState",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(p.args.length==0||DrawerDrag.active())return;
                try{if(p.args[0]==type("LauncherState").getField("ALL_APPS").get(null))return;}catch(Throwable unknown){return;}
                FrostedSurface.dismissOpenDialogs();
                closeDrawerFolder(false);
            }
        });
        Xp.hookAllMethods(type("Launcher"),"onNewIntent",new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){FrostedSurface.dismissOpenDialogs();if(!DrawerDrag.active())closeDrawerFolder(false);}
        });
        for(String stop:new String[]{"onStop","onPause"})Xp.hookAllMethods(type("Launcher"),stop,new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){
                try{
                    Activity a=(Activity)p.thisObject;
                    android.os.PowerManager power=(android.os.PowerManager)a.getSystemService(Context.POWER_SERVICE);
                    android.app.KeyguardManager keyguard=(android.app.KeyguardManager)a.getSystemService(Context.KEYGUARD_SERVICE);
                    boolean locking=(power!=null&&!power.isInteractive())||(keyguard!=null&&keyguard.isKeyguardLocked());
                    if(locking){FrostedSurface.dismissOpenDialogs();closeAnyFolder(a);}
                }catch(Throwable error){LauncherFeatures.logOnce("Folder close on lock unavailable",error);}
            }
        });
        Xp.hookAllMethods(type("Launcher"),"onResume",new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){
                try{
                    Object manager=call(p.thisObject,"getStateManager");Object state;
                    try{state=get(manager,"mState");}catch(Throwable older){state=call(manager,"getState");}
                    if(state!=type("LauncherState").getField("ALL_APPS").get(null))closeDrawerFolder(false);
                }catch(Throwable unknown){}
            }
        });
        Xp.hookAllMethods(type("folder.LauncherDelegate"),"getLauncher",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(Boolean.TRUE.equals(Xp.getAdditionalInstanceField(p.thisObject,"pixelsuite_drawer_delegate")))p.setResult(null);
            }
        });
        Xp.hookAllMethods(type("folder.LauncherDelegate"),"forEachVisibleWorkspacePage",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(Boolean.TRUE.equals(Xp.getAdditionalInstanceField(p.thisObject,"pixelsuite_drawer_delegate")))p.setResult(null);
            }
        });
        Class<?> adapter=type("allapps.BaseAllAppsAdapter");
        for(Method m:adapter.getDeclaredMethods()) if(m.getName().equals("onCreateViewHolder") && m.getParameterTypes().length==2) {
            Xp.x().hook(m).intercept(new XposedInterface.Hooker(){
                public Object intercept(XposedInterface.Chain chain)throws Throwable{
                    List<Object> args=chain.getArgs(); int vt=((Number)args.get(1)).intValue();
                    if(!folderType(vt))return chain.proceed();
                    ViewGroup parent=(ViewGroup)args.get(0);
                    Object holder=chain.proceed(new Object[]{parent,256});
                    View nativeCell=(View)call(chain.getThisObject(),"getIconOnCreateSetup",parent);
                    FrameLayout frame=new FrameLayout(parent.getContext());
                    Xp.setAdditionalInstanceField(frame,"pixelsuite_native_app_cell",nativeCell);
                    frame.setClipChildren(false); frame.setClipToPadding(false);
                    Object profile=call(get(chain.getThisObject(),"mActivityContext"),"getDeviceProfile");
                    int height=((Number)get(get(profile,"mAllAppsProfile"),"cellHeightPx")).intValue();
                    frame.setLayoutParams(new ViewGroup.LayoutParams(-1,height));
                    set(holder,"itemView",frame); return holder;
                }
            });
        }
        Xp.hookAllMethods(adapter,"onBindViewHolder",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                List<?> items=(List<?>)get(get(p.thisObject,"mApps"),"mAdapterItems"); int position=(Integer)p.args[1];
                if(position<0||position>=items.size())return; Object item=items.get(position);
                int vt=((Number)get(item,"viewType")).intValue(); if(!folderType(vt))return;
                p.setResult(null);
                FrameLayout frame=(FrameLayout)get(p.args[0],"itemView");
                Context context=(Context)get(p.thisObject,"mActivityContext");
                bindFolder(frame,context,(vt&~BASE)>>>10);warm(context);
            }
            protected void afterHookedMethod(Xp.Param p){
                View view=(View)get(p.args[0],"itemView");
                try{MultiDrag.rebound(view);}catch(Throwable ignored){}
                if(LauncherFeatures.on(LauncherFeatures.DRAWER) && type("model.data.AppInfo").isInstance(view.getTag()) && !LauncherFeatures.privateItem(view.getTag()))attachDrop(view);
            }
        });
        Xp.hookAllMethods(type("allapps.BaseAllAppsAdapter$AdapterItem"),"isCountedForAccessibility",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){if(folderType(((Number)get(p.thisObject,"viewType")).intValue()))p.setResult(true);}
        });
        Xp.hookAllMethods(type("allapps.AlphabeticalAppsList"),"addAppsWithSections",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(!LauncherFeatures.on(LauncherFeatures.DRAWER) || p.args[1]!=get(p.thisObject,"mApps") || !((List<?>)get(p.thisObject,"mSearchResults")).isEmpty())return;
                List<?> source=(List<?>)p.args[1]; if(source.isEmpty())return;
                List<Object> visible=new ArrayList<Object>(); String first=LauncherFeatures.identity(source.get(0)); if(first==null)return;
                long user=Long.parseLong(first.substring(0,first.indexOf('|')));
                List<LauncherData.Folder> folders=LauncherFeatures.data.folders();
                List<Object> prepared=new ArrayList<Object>();
                for(LauncherData.Folder f:folders) if(f.user==user){
                    Object ai=Xp.newInstance(type("allapps.BaseAllAppsAdapter$AdapterItem"), BASE|(f.id<<10)|2);
                    prepared.add(ai);
                }
                java.util.Set<String> present=new java.util.HashSet<String>();
                for(Object app:source){
                    String key=LauncherFeatures.identity(app); if(key==null||LauncherFeatures.privateItem(app)){visible.add(app);continue;}
                    known.put(key,app);present.add(key); boolean grouped=false;
                    for(LauncherData.Folder f:folders)if(f.user==user&&f.apps.contains(key)){grouped=true;break;}
                    if(!grouped)visible.add(app);
                }
                boolean gone=false;
                for(java.util.Iterator<String> it=known.keySet().iterator();it.hasNext();){
                    String key=it.next();
                    if(key.startsWith(user+"|")&&!present.contains(key)){it.remove();gone=true;}
                }
                if(gone)scheduleRebind();
                sortStrict(visible);
                ((List<Object>)get(p.thisObject,"mAdapterItems")).addAll(prepared);
                p.args[0]=(Integer)p.args[0]+prepared.size(); p.args[1]=visible;
            }
        });
        Xp.hookAllMethods(type("folder.Folder"),"updateItemLocationsInDatabaseBatch",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){if(isFolder(get(p.thisObject,"mInfo")))p.setResult(null);}
        });
        Xp.hookAllMethods(type("folder.LauncherDelegate"),"replaceFolderWithFinalItem",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){if(isFolder(get(p.args[0],"mInfo")))p.setResult(false);}
        });
        Xp.hookAllMethods(type("model.data.FolderInfo"),"setTitle",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){if(isFolder(p.thisObject))p.args[1]=null;}
            protected void afterHookedMethod(Xp.Param p){
                LauncherData.Folder f=record(p.thisObject); if(f==null)return;
                String name=String.valueOf(get(p.thisObject,"title")).trim(); if(name.length()==0)name="Folder";
                List<LauncherData.Folder> list=LauncherFeatures.data.folders(); for(LauncherData.Folder item:list)if(item.id==f.id)item.name=name;
                LauncherFeatures.data.save(list); LauncherFeatures.refresh();
            }
        });
        for(String name:new String[]{"updateItemInDatabase","addOrMoveItemInDatabase","addItemToDatabase","moveItemInDatabase","deleteItemFromDatabase"})
            Xp.hookAllMethods(type("model.ModelWriter"),name,new Xp.Callback(){
                protected void beforeHookedMethod(Xp.Param p){
                    for(Object arg:p.args)if(arg!=null&&type("model.data.ItemInfo").isInstance(arg)){
                        int id=((Number)get(arg,"id")).intValue();
                        if(isFolder(arg)||isDrawerMember(arg)||id<=CONTAINER){p.setResult(null);return;}
                    }
                }
            });
        Xp.hookAllMethods(type("folder.Folder"),"startDrag",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(!isFolder(get(p.thisObject,"mInfo")))return;
                View v=(View)p.args[0]; p.setResult(null);Xp.setAdditionalInstanceField(v,"pixelsuite_drag_armed",true);lockScroll(v);DrawerHomeTarget.arm(v);
                Haptics.tick(v.getContext());
                showPopup(v);
            }
        });
        Xp.hookAllMethods(type("touch.ItemLongClickListener$$ExternalSyntheticLambda0"),"onLongClick",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                View v=(View)p.args[0]; Object info=v.getTag();
                if(dragEnabled()&&type("model.data.AppInfo").isInstance(info)&&!LauncherFeatures.privateItem(info)){
                    if(scrolled(v)){v.setPressed(false);p.setResult(false);return;}
                    p.setResult(true);Xp.setAdditionalInstanceField(v,"pixelsuite_drag_armed",true);lockScroll(v);DrawerHomeTarget.arm(v);showPopup(v);
                }
            }
        });
        Xp.hookAllMethods(type("touch.AllAppsItemCustomActionsListener"),"onStartDrag",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                View v=(View)p.args[1];if(v==null||!dragEnabled()||LauncherFeatures.privateItem(v.getTag()))return;
                if(type("model.data.AppInfo").isInstance(v.getTag())){p.setResult(null);closePopup();startDrag(v);}
            }
        });
        Xp.hookAllMethods(type("touch.WorkspaceItemCustomActionsListener"),"onStartDrag",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                View v=(View)p.args[0];if(v==null||!dragEnabled())return;
                if(isFolder(v.getTag())||isDrawerMember(v.getTag())){p.setResult(null);closePopup();startDrag(v);}
            }
        });
        Xp.hookAllMethods(type("BubbleTextView"),"onTouchEvent",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){touch(p);}
            protected void afterHookedMethod(Xp.Param p){touched(p);}
        });
        final Class<?> folderIcon=type("folder.FolderIcon");
        try{
            Xp.hookMethod(folderIcon.getMethod("onTouchEvent",MotionEvent.class),new Xp.Callback(){
                protected void beforeHookedMethod(Xp.Param p){if(folderIcon.isInstance(p.thisObject)&&isFolder(((View)p.thisObject).getTag()))touch(p);}
                protected void afterHookedMethod(Xp.Param p){if(folderIcon.isInstance(p.thisObject)&&isFolder(((View)p.thisObject).getTag()))touched(p);}
            });
        }catch(Throwable missing){Module.log("Drawer folder touch hook unavailable",missing);}
        Xp.hookAllMethods(type("popup.PopupContainerWithArrow"),"onInterceptTouchEvent",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(!dragEnabled())return;
                View source=(View)get(p.thisObject,"originalView"); Object info=source.getTag();
                if(!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(source,"pixelsuite_drag_armed")))return;
                if(!type("model.data.AppInfo").isInstance(info) && !isDrawerMember(info))return;
                MotionEvent e=(MotionEvent)p.args[0];float[] down=downs.get(source);
                if(down==null || e.getActionMasked()!=2)return;
                Hold h=hold;if(h!=null&&h.view==source){h.x=e.getRawX();h.y=e.getRawY();}
                float dx=e.getRawX()-down[0],dy=e.getRawY()-down[1];int slop=ViewConfiguration.get(source.getContext()).getScaledTouchSlop();
                if(dx*dx+dy*dy<slop*slop*4)return;
                cancelHold();call(p.thisObject,"close",false);if(startDrag(source))allowIntercept(source);p.setResult(true);downs.remove(source);
            }
        });
    }
    static void sortStrict(List<Object> list){
        List<Integer> slots=new ArrayList<Integer>();List<Object> apps=new ArrayList<Object>();
        final Map<Object,String> names=new java.util.IdentityHashMap<Object,String>(),keys=new java.util.IdentityHashMap<Object,String>();
        for(int i=0;i<list.size();i++){
            Object app=list.get(i);if(LauncherFeatures.privateItem(app))continue;
            String key=LauncherFeatures.identity(app);if(key==null)continue;
            slots.add(i);apps.add(app);keys.put(app,key);names.put(app,String.valueOf(get(app,"title")));
        }
        Collections.sort(apps,new Comparator<Object>(){public int compare(Object a,Object b){return DrawerOrder.compare(names.get(a),keys.get(a),names.get(b),keys.get(b));}});
        for(int i=0;i<slots.size();i++)list.set(slots.get(i),apps.get(i));
    }
    static String displayName(String key){Object app=known.get(key);return app==null?key:String.valueOf(get(app,"title"));}
    static boolean isDrawerMember(Object info){try{return ((Number)get(info,"container")).intValue()<=CONTAINER;}catch(Throwable t){return false;}}
    static volatile long folderClosedAt;

    private static final java.util.Map<Object,Object> wrappedBack=java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<Object,Object>());

    private static void wrapBack(Xp.Param p,int at){
        if(p.args.length<=at||p.args[at]==null)return;
        final Object original=p.args[at];
        if(original.getClass().getClassLoader()!=LauncherFeatures.loader)return;
        if(wrappedBack.containsValue(original))return;
        if(wrappedBack.containsKey(original)){p.args[at]=wrappedBack.get(original);return;}
        java.util.List<Class<?>> faces=new java.util.ArrayList<Class<?>>();
        for(Class<?> k=original.getClass();k!=null;k=k.getSuperclass())for(Class<?> f:k.getInterfaces())if(f.getName().startsWith("android.window.")&&!faces.contains(f))faces.add(f);
        if(faces.isEmpty())return;
        final boolean[] mine=new boolean[1];
        Object wrapper=java.lang.reflect.Proxy.newProxyInstance(LauncherFeatures.loader,faces.toArray(new Class<?>[0]),new java.lang.reflect.InvocationHandler(){
            public Object invoke(Object proxy,java.lang.reflect.Method m,Object[] args)throws Throwable{
                String n=m.getName();
                if(n.equals("equals"))return proxy==(args==null?null:args[0]);
                if(n.equals("hashCode"))return System.identityHashCode(proxy);
                if(n.equals("toString"))return "PixelSuiteBack("+original+")";
                if(n.equals("onBackStarted")){
                    mine[0]=openDrawerFolder()!=null;
                    long shutAgo=android.os.SystemClock.uptimeMillis()-folderShutAt;
                    if(!mine[0]&&folderShutAt>0&&shutAgo<250){mine[0]=true;folderClosedAt=android.os.SystemClock.uptimeMillis();}
                    else if(!mine[0])folderClosedAt=0;
                }
                if(mine[0]||(n.equals("onBackInvoked")&&openDrawerFolder()!=null)){
                    if(n.equals("onBackInvoked")){mine[0]=false;View folder=openDrawerFolder();if(folder!=null)try{folderClosedAt=android.os.SystemClock.uptimeMillis();call(folder,"close",true);}catch(Throwable error){Module.log("Drawer folder back",error);}}
                    if(n.equals("onBackCancelled"))mine[0]=false;
                    return null;
                }
                try{return m.invoke(original,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            }
        });
        wrappedBack.put(original,wrapper);p.args[at]=wrapper;
    }

    private static java.lang.ref.WeakReference<View> openFolder=new java.lang.ref.WeakReference<View>(null);
    static volatile long folderOpenedAt, folderShutAt;

    static void markOpen(View f){
        if(f==null)return;
        long now=android.os.SystemClock.uptimeMillis();
        Xp.setAdditionalInstanceField(f,"pixelsuite_opened_at",now);
        openFolder=new java.lang.ref.WeakReference<View>(f);folderOpenedAt=now;
    }
    static void markShut(View f){
        long now=android.os.SystemClock.uptimeMillis();
        Object opened=Xp.getAdditionalInstanceField(f,"pixelsuite_opened_at");
        if(!(opened instanceof Long))return;
        Object closed=Xp.getAdditionalInstanceField(f,"pixelsuite_closed_at");
        boolean wasOpen=!(closed instanceof Long)||(Long)closed<(Long)opened;
        Xp.setAdditionalInstanceField(f,"pixelsuite_closed_at",now);
        if(openFolder.get()==f)openFolder=new java.lang.ref.WeakReference<View>(null);
        if(wasOpen){folderShutAt=now;busyWaits=0;scheduleRebind();}
    }
    static Boolean openByRecord(View f){
        Object opened=Xp.getAdditionalInstanceField(f,"pixelsuite_opened_at");
        if(!(opened instanceof Long))return null;
        Object closed=Xp.getAdditionalInstanceField(f,"pixelsuite_closed_at");
        return !(closed instanceof Long)||(Long)closed<(Long)opened;
    }

    private static void installOpenTracking(){
        try{
            Xp.hookAllMethods(type("folder.Folder"),"animateOpen",new Xp.Callback(){
                protected void afterHookedMethod(Xp.Param p){
                    View f=(View)p.thisObject;
                    if(drawerFolderView(f))markOpen(f);
                }
            });
            for(final String m:new String[]{"close","handleClose","animateClosed","closeComplete"}){
                Xp.Callback shut=new Xp.Callback(){
                    protected void beforeHookedMethod(Xp.Param p){
                        if(!(p.thisObject instanceof View))return;
                        markShut((View)p.thisObject);
                    }
                };
                Xp.hookAllMethods(type("folder.Folder"),m,shut);
                if(m.equals("close"))try{Xp.hookAllMethods(type("AbstractFloatingView"),m,shut);}catch(Throwable ignored){}
            }
            installClickGuard();
        }catch(Throwable error){Module.log("Drawer folder open tracking unavailable, using the on-screen check",error);}
    }

    private static void installClickGuard(){
        try{
            Xp.hookAllMethods(Xp.findClass("com.android.launcher3.touch.ItemClickHandler",LauncherFeatures.loader),"onClick",new Xp.Callback(){
                protected void beforeHookedMethod(Xp.Param p){
                    if(p.args.length!=1||!(p.args[0] instanceof View))return;
                    View v=(View)p.args[0];
                    if(Xp.getAdditionalInstanceField(v,"pixelsuite_drawer_folder_cell")!=null)return;
                    long now=android.os.SystemClock.uptimeMillis();
                    View folder=openDrawerFolder();
                    boolean inside=folder!=null&&isInside(v,folder);
                    String why=null;
                    if(folder!=null&&!inside)why="outside the open folder";
                    else if(inside&&folderOpenedAt>0&&now-folderOpenedAt<200)why="folder opened "+(now-folderOpenedAt)+" ms ago";
                    else if(folder==null&&folderShutAt>0&&now-folderShutAt<300&&drawerTap(v))why="folder closed "+(now-folderShutAt)+" ms ago";
                    if(why==null)return;
                    p.setResult(null);
                }
            });
        }catch(Throwable error){Module.log("Drawer folder tap guard unavailable",error);}
    }
    private static boolean drawerTap(View v){
        for(View q=v;q!=null;){
            if(q.getClass().getName().contains(".allapps."))return true;
            if(type("folder.Folder").isInstance(q)&&drawerFolderView(q))return true;
            android.view.ViewParent parent=q.getParent();q=parent instanceof View?(View)parent:null;
        }
        return false;
    }
    private static boolean isInside(View v,View folder){
        for(android.view.ViewParent q=v.getParent();q!=null;q=q.getParent())if(q==folder)return true;
        return false;
    }

    static View openDrawerFolder(){
        View tracked=openFolder.get();
        if(tracked!=null&&tracked.isAttachedToWindow()&&tracked.isShown())return tracked;
        Activity a=LauncherFeatures.activity.get();if(a==null)return null;
        try{
            ViewGroup layer=(ViewGroup)call(a,"getDragLayer");
            for(int i=layer.getChildCount()-1;i>=0;i--){
                View child=layer.getChildAt(i);
                if(!type("folder.Folder").isInstance(child))continue;
                if(!drawerFolderView(child))continue;
                Boolean record=openByRecord(child);
                if(record!=null){if(record&&child.isAttachedToWindow()&&child.isShown())return child;continue;}
                if(folderShown(child))return child;
            }
        }catch(Throwable error){LauncherFeatures.logOnce("Drawer folder lookup unavailable",error);}
        try{return findShownFolder(a.getWindow().getDecorView());}catch(Throwable ignored){}
        return null;
    }
    private static View findShownFolder(View v){
        if(v==null||v.getVisibility()!=View.VISIBLE)return null;
        if(type("folder.Folder").isInstance(v)){
            if(!drawerFolderView(v))return null;
            Boolean record=openByRecord((View)v);
            return (record!=null?record&&v.isAttachedToWindow()&&v.isShown():folderShown(v))?v:null;
        }
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=g.getChildCount()-1;i>=0;i--){View r=findShownFolder(g.getChildAt(i));if(r!=null)return r;}}
        return null;
    }

    static boolean drawerFolderView(View folder){
        try{Object info=get(folder,"mInfo");if(info!=null&&isFolder(info))return true;}catch(Throwable ignored){}
        try{Object d=get(folder,"mLauncherDelegate");if(d!=null&&Xp.getAdditionalInstanceField(d,"pixelsuite_drawer_delegate")!=null)return true;}catch(Throwable ignored){}
        return false;
    }
    static boolean folderShown(View folder){
        try{Object r=call(folder,"isOpen");if(Boolean.TRUE.equals(r))return true;}catch(Throwable ignored){}
        try{Object r=get(folder,"mIsOpen");if(Boolean.TRUE.equals(r))return true;}catch(Throwable ignored){}
        return folder.isAttachedToWindow()&&folder.isShown()&&folder.getAlpha()>0.05f&&!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(folder,"pixelsuite_hidden_close"));
    }
    static void closeDrawerFolder(boolean animate){
        Activity a=LauncherFeatures.activity.get();if(a==null)return;
        try{
            ViewGroup layer=(ViewGroup)call(a,"getDragLayer");
            for(int i=layer.getChildCount()-1;i>=0;i--){
                View child=layer.getChildAt(i);
                if(!type("folder.Folder").isInstance(child)||!isFolder(get(child,"mInfo")))continue;
                boolean open=false;try{open=Boolean.TRUE.equals(get(child,"mIsOpen"));}catch(Throwable ignored){}
                if(open){call(child,"close",animate);if(animate)continue;}
                Object animator=null;try{animator=get(child,"mCurrentAnimator");}catch(Throwable ignored){}
                if(animator instanceof android.animation.Animator&&((android.animation.Animator)animator).isRunning())((android.animation.Animator)animator).end();
                else if(child.getParent()==layer&&!open){child.setAlpha(0f);Xp.setAdditionalInstanceField(child,"pixelsuite_hidden_close",true);}
            }
        }catch(Throwable error){LauncherFeatures.logOnce("Drawer folder close on state change unavailable",error);}
    }
    static void closeAnyFolder(Activity a){
        Object top=Xp.callStaticMethod(type("AbstractFloatingView"),"getTopOpenView",a);
        if(top!=null&&type("folder.Folder").isInstance(top))call(top,"close",false);
    }
    static java.lang.ref.WeakReference<View> heldView=new java.lang.ref.WeakReference<View>(null);
    static void showPopup(View view){
        heldView=new java.lang.ref.WeakReference<View>(view);
        Activity a=LauncherFeatures.activity.get();if(a!=null)call(get(a,"mPopupControllerForAppIcons"),"show",view);
    }
    static void closePopup(){
        DrawerFolderMenu.dismiss();
        Activity a=LauncherFeatures.activity.get();if(a==null)return;
        try{Object popup=Xp.callStaticMethod(type("popup.PopupContainer$Companion"),"getOpen",a);if(popup!=null)call(popup,"close",false);}catch(Throwable ignored){}
    }
    static Object nativeInfo(LauncherData.Folder f,Context context){
        Object info=Xp.newInstance(type("model.data.FolderInfo"));folderIds.put(info,f.id);
        int id=CONTAINER-f.id;set(info,"id",id);set(info,"container",CONTAINER);set(info,"title",f.name);
        List<Object> contents=(List<Object>)get(info,"contents");
        for(String key:FolderSort.shownOrder(f)){Object app=known.get(key);if(app==null)continue;
            Object member=call(app,"makeWorkspaceItem",context);set(member,"id",CONTAINER-100000-contents.size());
            set(member,"container",id);set(member,"rank",contents.size());
            synchronized(LauncherFeatures.originals){
                LauncherFeatures.originals.put(member,LauncherFeatures.originals.containsKey(app)?LauncherFeatures.originals.get(app):(CharSequence)get(app,"title"));
                LauncherFeatures.originalAppTitles.put(member,LauncherFeatures.originalAppTitles.containsKey(app)?LauncherFeatures.originalAppTitles.get(app):(CharSequence)get(app,"appTitle"));
            }
            LauncherFeatures.label(member);contents.add(member);
            set(info,"user",get(app,"user"));
        }return info;
    }
    private static final Map<Integer,Object[]> iconCache=new HashMap<Integer,Object[]>();
    private static int cacheGeneration;
    static void clearIconCache(){iconCache.clear();cacheGeneration++;warming=false;}
    private static String signature(LauncherData.Folder f,Context context){
        StringBuilder b=new StringBuilder();
        b.append(f.id).append('|').append(f.name).append('|').append(f.grid).append('|').append(f.sort).append('|').append(System.identityHashCode(context)).append('|').append(context.getResources().getConfiguration().uiMode);
        for(String key:f.apps){b.append('|').append(key);Object app=known.get(key);b.append('#').append(app==null?0:System.identityHashCode(app));}
        try{b.append('|').append(android.provider.Settings.Secure.getString(context.getContentResolver(),"theme_customization_overlay_packages"));}catch(Throwable ignored){}
        b.append('|').append(ShapeWatch.token(context));
        return b.toString();
    }
    static void bindFolder(final FrameLayout frame,Context context,int id){
        Object profile=call(context,"getDeviceProfile");
        int height=((Number)get(get(profile,"mAllAppsProfile"),"cellHeightPx")).intValue();
        ViewGroup.LayoutParams params=frame.getLayoutParams();
        if(params!=null && params.height!=height){params.height=height;frame.setLayoutParams(params);}
        LauncherData.Folder f=null;for(LauncherData.Folder item:LauncherFeatures.data.folders())if(item.id==id){f=item;break;}
        frame.removeAllViews();if(f==null)return;
        Xp.setAdditionalInstanceField(frame,"pixelsuite_bind_context",context);
        String sig=signature(f,context);Object[] hit=iconCache.get(id);
        final View icon;final Object info;
        if(hit!=null&&sig.equals(hit[0])){
            icon=(View)hit[1];info=hit[2];
            if(icon.getParent() instanceof ViewGroup)((ViewGroup)icon.getParent()).removeView(icon);
        }else{
            Object[] built=build(frame,context,f);icon=(View)built[0];info=built[1];iconCache.put(id,new Object[]{sig,icon,info});
        }
        DrawerFolderAlignment.seed(icon,frame);
        frame.addView(icon,new FrameLayout.LayoutParams(-1,-1));frame.setTag(info);
        try{styleLabel(icon,frame);}catch(Throwable error){LauncherFeatures.logOnce("Drawer folder label style unavailable",error);}
        DrawerFolderAlignment.schedule(icon);
        attachDrop(frame);cells.put("folder:"+id,frame);
        if(reopenId==id){reopenId=0;icon.post(new Runnable(){public void run(){call(get(icon,"mFolder"),"animateOpen",0,new ArrayList<Object>((List<?>)get(info,"contents")));}});}
        if(animateId==id){animateId=0;icon.post(new Runnable(){public void run(){
            try{Object manager=get(icon,"mPreviewItemManager");List<?> params=(List<?>)get(manager,"mFirstPageParams");
                if(params.size()>0){Object animation=Xp.newInstance(type("folder.FolderPreviewItemAnim"),manager,params.get(0),-1,-1,0,2,350,null);call(get(animation,"mAnimator"),"start");}
            }catch(Throwable error){Module.log("Drawer folder creation animation unavailable",error);}
        }});}
    }
    private static boolean warming;
    static void warm(final Context context){
        if(warming)return;warming=true;
        final int generation=cacheGeneration;
        android.os.Looper.myQueue().addIdleHandler(new android.os.MessageQueue.IdleHandler(){
            private final ArrayList<LauncherData.Folder> todo=new ArrayList<LauncherData.Folder>(LauncherFeatures.data.folders());
            public boolean queueIdle(){
                if(generation!=cacheGeneration)return false;
                try{
                    while(!todo.isEmpty()){
                        LauncherData.Folder f=todo.remove(0);Object[] hit=iconCache.get(f.id);String sig=signature(f,context);
                        if(hit!=null&&sig.equals(hit[0]))continue;
                        Object[] built=build(new FrameLayout(context),context,f);iconCache.put(f.id,new Object[]{sig,built[0],built[1]});
                        return true;
                    }
                }catch(Throwable error){LauncherFeatures.logOnce("Drawer folder warm-up unavailable",error);}
                warming=false;return false;
            }
        });
    }
    private static Object[] build(final ViewGroup frame,Context context,LauncherData.Folder f){
        final Object info=nativeInfo(f,context);
        int layout=context.getResources().getIdentifier("folder_icon","layout",context.getPackageName());
        final View icon=(View)Xp.callStaticMethod(type("folder.FolderIcon"),"inflateFolderAndIcon",layout,context,frame,info);
        Xp.setAdditionalInstanceField(icon,"pixelsuite_drawer_folder_cell",true);
        Xp.setAdditionalInstanceField(get(icon,"mFolderName"),"pixelsuite_drawer_folder_label",true);
        Xp.setAdditionalInstanceField(get(get(icon,"mFolder"),"mLauncherDelegate"),"pixelsuite_drawer_delegate",true);
        DrawerFolderAlignment.attach(icon);
        icon.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            Object folder=get(v,"mFolder");ArrayList<Object> content=new ArrayList<Object>((List<?>)get(info,"contents"));
            if(content.size()<2){chooseApps(info);return;}
            call(folder,"animateOpen",0,content);
            if(folder instanceof View&&openFolder.get()!=folder)markOpen((View)folder);
        }});
        icon.setOnLongClickListener(new View.OnLongClickListener(){public boolean onLongClick(View v){
            if(!dragEnabled())return false;
            if(scrolled(v)){v.setPressed(false);return false;}
            Xp.setAdditionalInstanceField(v,"pixelsuite_drag_armed",true);lockScroll(v);DrawerHomeTarget.arm(v);DrawerFolderMenu.show(v);
            return true;
        }});
        return new Object[]{icon,info};
    }
    static boolean startDrag(View source){
        return DrawerDrag.start(source);
    }

    static void styleLabel(View icon,View frame){
        Object name=get(icon,"mFolderName");if(!(name instanceof android.widget.TextView))return;
        android.widget.TextView label=(android.widget.TextView)name;
        Object[] style=drawerLabelStyle(frame.getContext(),frame);if(style==null)return;
        label.setTextColor((android.content.res.ColorStateList)style[0]);
        label.setShadowLayer((Float)style[1],(Float)style[2],(Float)style[3],(Integer)style[4]);
        if(!label.getClass().getName().equals(style[5]))plainShadow(label);
    }
    private static Object[] labelStyle;private static int labelStyleMode=-1;
    static Object[] drawerLabelStyle(Context c,View near){
        int mode=c.getResources().getConfiguration().uiMode;
        android.widget.TextView sample=null;
        if(near!=null&&near.getParent() instanceof ViewGroup){
            ViewGroup list=(ViewGroup)near.getParent();
            for(int i=0;i<list.getChildCount()&&sample==null;i++){View child=list.getChildAt(i);if(type("BubbleTextView").isInstance(child)&&type("model.data.AppInfo").isInstance(child.getTag()))sample=(android.widget.TextView)child;}
        }
        if(sample==null&&labelStyle!=null&&labelStyleMode==mode)return labelStyle;
        if(sample==null){
            int id=c.getResources().getIdentifier("all_apps_icon","layout",c.getPackageName());
            if(id!=0){View inflated=android.view.LayoutInflater.from(c).inflate(id,null,false);if(inflated instanceof android.widget.TextView)sample=(android.widget.TextView)inflated;}
        }
        if(sample==null)return null;
        labelStyle=new Object[]{sample.getTextColors(),sample.getShadowRadius(),sample.getShadowDx(),sample.getShadowDy(),sample.getShadowColor(),sample.getClass().getName()};labelStyleMode=mode;
        return labelStyle;
    }
    static void plainShadow(android.widget.TextView label){
        Object info;try{info=get(label,"mShadowInfo");}catch(Throwable none){return;}
        if(info==null)return;
        for(String field:new String[]{"ambientShadowColor","keyShadowColor","mAmbientShadowColor","mKeyShadowColor"}){
            for(Class<?> k=info.getClass();k!=null&&k!=Object.class;k=k.getSuperclass()){
                try{java.lang.reflect.Field f=k.getDeclaredField(field);f.setAccessible(true);f.setInt(info,0);break;}catch(Throwable ignored){}
            }
        }
        label.invalidate();
    }

    private static boolean rebindPending;
    static void scheduleRebind(){
        if(rebindPending)return;rebindPending=true;
        android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable pass=new Runnable(){public void run(){
            rebindPending=false;
            try{rebindStale();}catch(Throwable error){LauncherFeatures.logOnce("Drawer folder preview refresh unavailable",error);}
        }};
        main.post(pass);
        main.postDelayed(new Runnable(){public void run(){try{rebindStale();}catch(Throwable error){LauncherFeatures.logOnce("Drawer folder preview refresh unavailable",error);}}},350);
    }
    private static int busyWaits;
    private static boolean busyRebindPending;
    static void rebindStale(){
        if(LauncherFeatures.data==null||DrawerDrag.active())return;
        if(folderBusy()){
            if(busyRebindPending||busyWaits>=20)return;
            busyWaits++;busyRebindPending=true;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable(){public void run(){
                busyRebindPending=false;
                try{rebindStale();}catch(Throwable error){LauncherFeatures.logOnce("Drawer folder preview refresh unavailable",error);}
            }},250);
            return;
        }
        busyWaits=0;
        List<LauncherData.Folder> folders=LauncherFeatures.data.folders();
        java.util.Set<View> found=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<View,Boolean>());
        found.addAll(cells.values());
        Activity a=LauncherFeatures.activity.get();
        if(a!=null)try{collectFolderCells((View)call(a,"getAppsView"),found);}catch(Throwable ignored){}
        for(View cell:found){
            if(!(cell instanceof FrameLayout)||!cell.isAttachedToWindow())continue;
            Object info=cell.getTag();Integer id=info==null?null:folderIds.get(info);if(id==null)continue;
            LauncherData.Folder f=null;for(LauncherData.Folder item:folders)if(item.id==id)f=item;
            if(f==null||!stale(info,f))continue;
            View icon=((FrameLayout)cell).getChildAt(0);
            if(icon!=null&&isOpen(icon))continue;
            Object bound=Xp.getAdditionalInstanceField(cell,"pixelsuite_bind_context");
            Context context=bound instanceof Context?(Context)bound:(a!=null?a:cell.getContext());
            bindFolder((FrameLayout)cell,context,id);
        }
    }
    static boolean folderBusy(){
        if(openDrawerFolder()!=null)return true;
        if(folderShutAt>0&&android.os.SystemClock.uptimeMillis()-folderShutAt<700)return true;
        try{
            Activity a=LauncherFeatures.activity.get();if(a==null)return false;
            ViewGroup layer=(ViewGroup)call(a,"getDragLayer");
            for(int i=0;i<layer.getChildCount();i++){View c=layer.getChildAt(i);if(type("folder.Folder").isInstance(c)&&drawerFolderView(c))return true;}
        }catch(Throwable ignored){}
        return false;
    }
    static boolean isOpen(View icon){
        try{Object folder=get(icon,"mFolder");if(folder==null)return false;
            try{return Boolean.TRUE.equals(get(folder,"mIsOpen"));}catch(Throwable noField){return Boolean.TRUE.equals(call(folder,"isOpen"));}
        }catch(Throwable unknown){return false;}
    }
    static void collectFolderCells(View v,java.util.Set<View> out){
        if(v==null)return;
        if(v instanceof FrameLayout&&!type("folder.FolderIcon").isInstance(v)&&isFolder(v.getTag())&&((FrameLayout)v).getChildCount()>0&&type("folder.FolderIcon").isInstance(((FrameLayout)v).getChildAt(0))){out.add(v);return;}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collectFolderCells(g.getChildAt(i),out);}
    }
    static boolean stale(Object info,LauncherData.Folder f){
        if(!String.valueOf(get(info,"title")).equals(f.name))return true;
        List<?> contents=(List<?>)get(info,"contents");
        List<String> saved=new ArrayList<String>();for(String key:FolderSort.shownOrder(f))if(known.containsKey(key))saved.add(key);
        if(contents.size()!=saved.size())return true;
        for(int i=0;i<saved.size();i++)if(!saved.get(i).equals(LauncherFeatures.identity(contents.get(i))))return true;
        return false;
    }

    static final long HOLD_MS=1500;
    static Hold hold;
    static final class Hold implements Runnable{
        final View view;float x,y;boolean cancelled;
        Hold(View v,float rawX,float rawY){view=v;x=rawX;y=rawY;}
        public void run(){
            if(cancelled||hold!=this)return;
            hold=null;
            if(!view.isAttachedToWindow()||!downs.containsKey(view)||!dragEnabled()||scrolled(view))return;
            Xp.setAdditionalInstanceField(view,"pixelsuite_drag_armed",false);
            if(startDrag(view)){allowIntercept(view);DrawerDrag.move(x,y);}
        }
    }
    static boolean holdable(View v){
        Object info=v.getTag();
        if(info==null||LauncherFeatures.privateItem(info))return false;
        return isFolder(info)||isDrawerMember(info)||type("model.data.AppInfo").isInstance(info);
    }
    static void cancelHold(){Hold h=hold;hold=null;if(h!=null){h.cancelled=true;h.view.removeCallbacks(h);}}
    static void lockScroll(View v){android.view.ViewParent parent=v.getParent();if(parent!=null)parent.requestDisallowInterceptTouchEvent(true);}
    static void allowIntercept(View v){android.view.ViewParent parent=v.getParent();if(parent!=null)parent.requestDisallowInterceptTouchEvent(false);}

    static void touch(Xp.Param p){
        View v=(View)p.thisObject;MotionEvent e=(MotionEvent)p.args[0];int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            int[] at=new int[2];v.getLocationOnScreen(at);
            downs.put(v,new float[]{e.getRawX(),e.getRawY(),at[0],at[1]});Xp.setAdditionalInstanceField(v,"pixelsuite_drag_armed",false);
            cancelHold();
        }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            downs.remove(v);Xp.setAdditionalInstanceField(v,"pixelsuite_drag_armed",false);
            Hold h=hold;if(h!=null&&h.view==v)cancelHold();
            DrawerHomeTarget.release();
        }else if(action==MotionEvent.ACTION_MOVE){
            Hold h=hold;if(h!=null&&h.view==v){h.x=e.getRawX();h.y=e.getRawY();}
            float[] down=downs.get(v);if(down==null)return;
            float dx=e.getRawX()-down[0],dy=e.getRawY()-down[1];int slop=ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
            boolean armed=Boolean.TRUE.equals(Xp.getAdditionalInstanceField(v,"pixelsuite_drag_armed"));
            if(!armed){if(h!=null&&h.view==v&&dx*dx+dy*dy>slop*slop)cancelHold();return;}
            if(dragEnabled()&&dx*dx+dy*dy>slop*slop*4){
                Xp.setAdditionalInstanceField(v,"pixelsuite_drag_armed",false);cancelHold();
                if(startDrag(v)){allowIntercept(v);DrawerDrag.move(e.getRawX(),e.getRawY());p.setResult(true);}
            }
        }
    }
    static void touched(Xp.Param p){
        MotionEvent e=(MotionEvent)p.args[0];if(e.getActionMasked()!=MotionEvent.ACTION_DOWN)return;
        View v=(View)p.thisObject;
        if(!Boolean.TRUE.equals(p.getResult())){downs.remove(v);return;}
        if(dragEnabled()&&holdable(v)){
            cancelHold();
            Hold h=new Hold(v,e.getRawX(),e.getRawY());hold=h;
            long waited=android.os.SystemClock.uptimeMillis()-e.getDownTime();
            v.postDelayed(h,Math.max(0,HOLD_MS-waited));
        }
    }
    static boolean scrolled(View v){
        try{
            for(android.view.ViewParent parent=v.getParent();parent instanceof View;parent=parent.getParent()){
                if(!parent.getClass().getName().contains("RecyclerView")&&!(parent.getClass().getSuperclass()!=null&&parent.getClass().getSuperclass().getName().contains("RecyclerView")))continue;
                try{if(((Number)call(parent,"getScrollState")).intValue()!=0)return true;}catch(Throwable ignored){}
                break;
            }
        }catch(Throwable ignored){}
        float[] down=downs.get(v);if(down==null||down.length<4)return false;
        int[] at=new int[2];v.getLocationOnScreen(at);int slop=ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        return Math.abs(at[0]-down[2])>slop||Math.abs(at[1]-down[3])>slop;
    }
    static final class DragSource{final String key;final Object info;final View view;DragSource(String k,Object i,View v){key=k;info=i;view=v;}}
    static void attachDrop(final View view){
        String key=isFolder(view.getTag())?"folder:"+folderIds.get(view.getTag()):LauncherFeatures.identity(view.getTag());
        if(key!=null)cells.put(key,view);
    }
    static boolean reorder(DragSource source,Object target){
        String targetKey=LauncherFeatures.identity(target);if(targetKey==null||targetKey.equals(source.key))return false;
        long user=Long.parseLong(source.key.substring(0,source.key.indexOf('|')));if(!targetKey.startsWith(user+"|"))return false;
        List<LauncherData.Folder> folders=LauncherFeatures.data.folders();boolean changed=false;
        for(int i=folders.size()-1;i>=0;i--){LauncherData.Folder f=folders.get(i);if(f.apps.remove(source.key))changed=true;if(f.apps.isEmpty())folders.remove(i);}
        if(!changed)return false;
        boolean ok=LauncherFeatures.data.save(folders);
        if(ok)LauncherFeatures.refresh();return ok;
    }
    static boolean takeOut(List<String> keys){
        List<LauncherData.Folder> folders=LauncherFeatures.data.folders();boolean changed=false;
        for(int i=folders.size()-1;i>=0;i--){LauncherData.Folder f=folders.get(i);if(f.apps.removeAll(keys))changed=true;if(f.apps.isEmpty())folders.remove(i);}
        if(!changed)return false;
        boolean ok=LauncherFeatures.data.save(folders);if(ok)LauncherFeatures.refresh();return ok;
    }
    static boolean renameTo(int id,String name){
        List<LauncherData.Folder> list=LauncherFeatures.data.folders();boolean found=false;
        for(LauncherData.Folder f:list)if(f.id==id){f.name=name;found=true;}
        if(!found||!LauncherFeatures.data.save(list))return false;
        LauncherFeatures.refresh();return true;
    }
    static boolean sortFolder(int id){
        List<LauncherData.Folder> list=LauncherFeatures.data.folders();LauncherData.Folder target=null;
        for(LauncherData.Folder f:list)if(f.id==id)target=f;
        if(target==null)return false;
        List<String> apps=new ArrayList<String>(target.apps);
        final Map<String,String> names=new HashMap<String,String>();for(String key:apps)names.put(key,displayName(key));
        Collections.sort(apps,new Comparator<String>(){public int compare(String a,String b){return DrawerOrder.compare(names.get(a),a,names.get(b),b);}});
        target.apps.clear();target.apps.addAll(apps);target.sort=FolderSort.AUTO;
        if(!LauncherFeatures.data.save(list))return false;
        LauncherFeatures.refresh();return true;
    }
    static void removeFolder(Object info){
        LauncherData.Folder f=record(info);if(f==null)return;List<LauncherData.Folder> list=LauncherFeatures.data.folders();
        try{ScatterEffect.play(shownCell(f.id));}catch(Throwable error){LauncherFeatures.logOnce("Folder remove animation unavailable",error);}
        for(int i=list.size()-1;i>=0;i--)if(list.get(i).id==f.id)list.remove(i);
        if(LauncherFeatures.data.save(list)){try{Object icon=cells.get("folder:"+f.id);if(icon instanceof FrameLayout){View child=((FrameLayout)icon).getChildAt(0);call(get(child,"mFolder"),"close",true);}}catch(Throwable ignored){}LauncherFeatures.refresh();}
    }
    static View shownCell(int id){
        View cell=cells.get("folder:"+id);
        if(cell!=null&&cell.isAttachedToWindow()&&cell.isShown()&&cell.getWidth()>0)return cell;
        Activity a=LauncherFeatures.activity.get();if(a==null)return cell;
        View found=findCell(a.getWindow().getDecorView(),id);
        return found!=null?found:cell;
    }
    private static View findCell(View v,int id){
        Object tag=v.getTag();
        if(v instanceof FrameLayout&&tag!=null&&isFolder(tag)&&Integer.valueOf(id).equals(folderIds.get(tag))&&v.isShown()&&v.getWidth()>0)return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View r=findCell(g.getChildAt(i),id);if(r!=null)return r;}}
        return null;
    }
    static boolean drop(DragSource source,Object target){
        if(target==null)return false;String targetKey=isFolder(target)?"folder:"+folderIds.get(target):LauncherFeatures.identity(target);
        if(targetKey==null||targetKey.equals(source.key))return false;
        List<LauncherData.Folder> folders=LauncherFeatures.data.folders();
        if(source.key.startsWith("folder:")){
            if(!targetKey.startsWith("folder:"))return false;
            int from=Integer.parseInt(source.key.substring(7)),to=Integer.parseInt(targetKey.substring(7));
            LauncherData.Folder moved=null;int at=0;for(LauncherData.Folder f:folders)if(f.id==from)moved=f;
            if(moved==null)return false;folders.remove(moved);for(int i=0;i<folders.size();i++)if(folders.get(i).id==to)at=i;
            folders.add(at,moved);
        }else{
            long user=Long.parseLong(source.key.substring(0,source.key.indexOf('|')));
            if(isFolder(target)){
                LauncherData.Folder destination=record(target);if(destination==null||destination.user!=user)return false;
                for(LauncherData.Folder f:folders){f.apps.remove(source.key);if(f.id==destination.id)f.apps.add(source.key);}
            }else{
                if(!targetKey.startsWith(user+"|"))return false;
                LauncherData.Folder f=new LauncherData.Folder();f.id=nextId(folders);f.name="Folder";f.grid=LauncherFeatures.defaultDrawerGrid();f.user=user;f.apps.add(targetKey);f.apps.add(source.key);
                for(LauncherData.Folder old:folders){old.apps.remove(source.key);old.apps.remove(targetKey);}folders.add(f);animateId=f.id;
            }
            for(int i=folders.size()-1;i>=0;i--)if(folders.get(i).apps.isEmpty())folders.remove(i);
        }
        if(!LauncherFeatures.data.save(folders))return false;LauncherFeatures.refresh();return true;
    }
    static boolean moveFolder(int from,int to,boolean after){
        List<LauncherData.Folder> folders=LauncherFeatures.data.folders();
        LauncherData.Folder moved=null;for(LauncherData.Folder f:folders)if(f.id==from)moved=f;
        if(moved==null||from==to)return false;
        folders.remove(moved);int at=-1;for(int i=0;i<folders.size();i++)if(folders.get(i).id==to)at=i;
        if(at<0)return false;
        folders.add(after?at+1:at,moved);
        if(!LauncherFeatures.data.save(folders))return false;LauncherFeatures.refreshOrder();return true;
    }
    static int nextId(List<LauncherData.Folder> list){int id=1;for(LauncherData.Folder f:list)id=Math.max(id,f.id+1);if(id>=16000)throw new IllegalStateException("Folder limit reached");return id;}
    static void create(Object app){
        String key=LauncherFeatures.identity(app);if(key==null)return;
        LauncherData.Folder f=new LauncherData.Folder();f.id=nextId(LauncherFeatures.data.folders());f.name="Folder";f.grid=LauncherFeatures.defaultDrawerGrid();
        f.user=Long.parseLong(key.substring(0,key.indexOf('|')));f.apps.add(key);choose(f,true);
    }
    static void chooseApps(Object info){LauncherData.Folder f=record(info);if(f!=null)choose(f,false);}
    static void choose(final LauncherData.Folder f,final boolean creating){
        final Activity a=LauncherFeatures.activity.get();if(a==null)return;
        List<Object> offered=new ArrayList<Object>();
        List<LauncherData.Folder> folders=LauncherFeatures.data.folders();
        for(Object app:LauncherFeatures.apps()){String key=LauncherFeatures.identity(app);if(key==null||!key.startsWith(f.user+"|"))continue;
            boolean assigned=false;for(LauncherData.Folder other:folders)if(other.id!=f.id&&other.apps.contains(key))assigned=true;
            if(!assigned){offered.add(app);known.put(key,app);}
        }
        AppPicker.show(a,creating?"Create folder":"Add apps",offered,new java.util.HashSet<String>(f.apps),2,new AppPicker.Save(){public boolean save(List<String> chosen){
            List<String> next=new ArrayList<String>();
            for(String key:f.apps)if(chosen.contains(key))next.add(key);
            for(String key:chosen)if(!next.contains(key))next.add(key);
            f.apps.clear();f.apps.addAll(next);List<LauncherData.Folder> list=LauncherFeatures.data.folders();
            int at=list.size();for(int i=list.size()-1;i>=0;i--)if(list.get(i).id==f.id){at=i;list.remove(i);}list.add(at,f);
            if(!LauncherFeatures.data.save(list))return false;
            if(creating)animateId=f.id;else reopenId=f.id;LauncherFeatures.refresh();return true;
        }});
    }
    static void changeGrid(Object info,int n){
        LauncherData.Folder f=record(info);if(f==null)return;List<LauncherData.Folder> list=LauncherFeatures.data.folders();for(LauncherData.Folder item:list)if(item.id==f.id)item.grid=LauncherData.grid(n);LauncherFeatures.data.save(list);
    }
}
