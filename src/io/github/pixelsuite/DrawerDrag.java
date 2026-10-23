package io.github.pixelsuite;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/** Uses the stock DragView and touch driver; the search-bar top is the only drawer exit. */
final class DrawerDrag {
    private static Session session;
    private static Object get(Object o,String n){return LauncherFeatures.get(o,n);}
    private static void set(Object o,String n,Object v){LauncherFeatures.set(o,n,v);}
    private static Object call(Object o,String n,Object...v){return LauncherFeatures.call(o,n,v);}
    private static Class<?> type(String n){return LauncherFeatures.type(n);}
    private static Object constant(String name)throws Exception{return type("LauncherState").getField(name).get(null);}
    static void install(){
        Xp.hookAllMethods(type("statemanager.StateManager"),"goToState",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p)throws Throwable{
                if(session==null||session.exited||p.args.length==0)return;
                if(p.args[0]==constant("NORMAL")||p.args[0]==constant("SPRING_LOADED"))p.setResult(null);
            }
        });
        Xp.Callback keepDrawer=new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){if(session!=null&&!session.exited)p.setResult(null);}};
        Xp.hookAllMethods(type("Workspace"),"onDragStart",keepDrawer);
        Xp.hookAllMethods(type("Workspace"),"onDragEnd",keepDrawer);
        Xp.hookAllMethods(type("DropTargetBar"),"onDragStart",keepDrawer);
        Xp.hookAllMethods(type("dragndrop.DragController"),"handleMoveEvent",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p)throws Throwable{
                Session s=session;if(s==null||s.exited)return;
                int x=((Number)p.args[0]).intValue(),y=((Number)p.args[1]).intValue();
                s.lastX=x;s.lastY=y;
                if(s.stay!=null)s.stay.update(x,y);
                boolean remove=DrawerHomeTarget.updateRemove(x,y,s.layer);
                if((!remove&&DrawerHomeTarget.update(x,y,s.layer))||(!remove&&y<s.exitY)){s.exitToHome();}
            }
        });
        // preview75: the top Remove/Cancel button picks its label when a drag starts, from the dragged item: "Remove" when the
        // item has a database id. A drawer folder has one, but it can never be removed from there, so it must read "Cancel"
        // like an app from the drawer. Its id is hidden from the button while it chooses (relabelling it later, preview73/74,
        // left the text clipped).
        try{
            final ThreadLocal<Object[]> hidden=new ThreadLocal<Object[]>();
            Xp.hookAllMethods(type("DeleteDropTarget"),"onDragStart",new Xp.Callback(){
                protected void beforeHookedMethod(Xp.Param p){
                    try{Object item=get(p.args[0],"dragInfo");if(item!=null&&DrawerFolders.isFolder(item)){hidden.set(new Object[]{item,get(item,"id")});set(item,"id",-1);}}catch(Throwable ignored){}
                }
                protected void afterHookedMethod(Xp.Param p){
                    Object[] h=hidden.get();hidden.remove();if(h!=null)try{set(h[0],"id",h[1]);}catch(Throwable ignored){}
                }
            });
        }catch(Throwable error){LauncherFeatures.logOnce("Drawer folder Cancel label unavailable",error);}
        // preview77: every drag (home screen apps, apps from folders, apps and folders leaving the drawer) shows the top
        // Remove/Cancel/Uninstall buttons a moment after it starts, fading in with their frost: shown at once they were laid
        // out and blurred mid-transition and looked cut off.
        Xp.hookAllMethods(type("DropTargetBar"),"onDragStart",new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){
                Session s=session;if(s!=null&&!s.exited)return;   // still in the drawer: the bar is not shown yet
                // Balloon handles the drop-target entrance animation.
            }
        });
        Xp.hookAllMethods(type("dragndrop.DragController"),"callOnDragEnd",new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){if(session!=null)finish();}
        });
    }
    static boolean active(){return session!=null;}
    /** The held app came out of an open drawer folder. */
    static boolean fromFolder(){Session s=session;return s!=null&&s.stay!=null;}
    /** The view is inside the folder the held app came out of. */
    static boolean inHeldFolder(View v){
        Session s=session;if(s==null||s.stay==null)return false;
        for(android.view.ViewParent p=v.getParent();p!=null;p=p.getParent())if(p==s.stay.folder)return true;
        return false;
    }
    /** The key of the held drawer app, or null. */
    static String heldKey(){Session s=session;return s==null?null:s.key;}
    /** Re-runs the hover logic at the last finger position (the list moved under a held finger). */
    static void refreshHover(){Session s=session;if(s==null||s.exited)return;try{call(s.controller,"handleMoveEvent",s.lastX,s.lastY);}catch(Throwable ignored){}}
    static void pause(){if(session!=null&&!session.exited)cancel();}
    static boolean start(final View source){
        Activity a=LauncherFeatures.activity.get();if(a==null||session!=null)return false;
        Object info=source.getTag();String key=DrawerFolders.isFolder(info)?"folder:"+DrawerFolders.folderIds.get(info):LauncherFeatures.identity(info);
        if(key==null)return false;
        try{
            Session s=new Session(a,source,info,key);session=s;DrawerHomeTarget.arm(source);
            DrawerFolders.closePopup();
            Object options=Xp.newInstance(type("dragndrop.DragOptions"));
            // A movement-triggered drag is already beyond the popup pre-drag threshold.
            set(options,"isMouseDrag",true);
            Object nativeInfo=info;
            // A drawer folder's own info also has the drawer container id, but it is dragged as
            // itself (preview25 looked it up as an app: "App is no longer available").
            if(!DrawerFolders.isFolder(info)&&DrawerFolders.isDrawerMember(info)){
                nativeInfo=DrawerFolders.known.get(key);if(nativeInfo==null)throw new IllegalStateException("App is no longer available");
            }
            source.setTag(nativeInfo);
            call(s.controller,"addDropTarget",s.target);
            try{call(get(a,"mWorkspace"),"beginDragShared",source,s.dragSource,options);}
            finally{source.setTag(info);set(get(a,"mWorkspace"),"mDragSourceInternal",null);}
            source.setVisibility(View.INVISIBLE);
            return true;
        }catch(Throwable error){Module.log("Drawer drag could not start",error);finish();return false;}
    }
    static void cancel(){
        Session s=session;if(s==null)return;
        try{call(s.controller,"cancelDrag");}catch(Throwable error){Module.log("Drawer drag cleanup",error);}
        if(session!=null)finish();
    }
    static void move(float rawX,float rawY){
        Session s=session;if(s==null)return;int[] base=new int[2];s.layer.getLocationOnScreen(base);
        call(s.controller,"handleMoveEvent",(int)rawX-base[0],(int)rawY-base[1]);
    }
    private static void finish(){
        Session s=session;session=null;DrawerHomeTarget.hide();if(s==null)return;
        if(s.reorder!=null)s.reorder.restore();if(s.push!=null)s.push.restore();s.source.setVisibility(View.VISIBLE);s.clearHover();s.setMember(null);if(s.stay!=null)s.stay.stop();
        try{call(s.controller,"removeDropTarget",s.target);}catch(Throwable ignored){}
        // preview80: the end of every drawer drag used to run a full refresh. It throws away every built folder icon, so after a
        // drop that had already refreshed the drawer (or changed nothing at all) all the folders were rebuilt and their
        // captions re-aligned a frame later: the shake. Now it refreshes only when something was saved and nothing refreshed yet.
        if(!s.exited&&LauncherData.saves!=s.savesAtStart&&LauncherFeatures.refreshes==s.refreshesAtStart)LauncherFeatures.refresh();
    }
    private static final class Session implements InvocationHandler {
        final Activity activity;final View source;final Object info;final String key;
        final ViewGroup layer;final Object controller;final Object target;final Object dragSource;
        final int exitY;boolean exited,indicating,lifted;View hovered;int localX,localY;
        final int savesAtStart=LauncherData.saves,refreshesAtStart=LauncherFeatures.refreshes;
        /** Set when the held app came out of an open drawer folder: it then stays in it (see FolderDrag.Stay). */
        FolderDrag.Stay stay;FolderReorder reorder;FolderPush push;View hoverMember;int lastX,lastY;
        Session(Activity a,View v,Object i,String k){
            activity=a;source=v;info=i;key=k;layer=(ViewGroup)call(a,"getDragLayer");controller=get(a,"mDragController");
            Object appsView=call(a,"getAppsView");View search=(View)get(appsView,"mSearchContainer");
            int[] top=new int[2],origin=new int[2];search.getLocationOnScreen(top);layer.getLocationOnScreen(origin);
            exitY=top[1]-origin[1];
            if(exitY<=0)throw new IllegalStateException("Search bar boundary is not laid out");
            if(!k.startsWith("folder:")&&DrawerFolders.isDrawerMember(i)){View open=FolderDrag.openFolder(a);if(open!=null){stay=new FolderDrag.Stay(open,layer);reorder=FolderReorder.start(open,v);}}
            if(k.startsWith("folder:"))push=new FolderPush(v,layer);
            target=Proxy.newProxyInstance(LauncherFeatures.loader,new Class<?>[]{type("DropTarget")},this);
            dragSource=Proxy.newProxyInstance(LauncherFeatures.loader,new Class<?>[]{type("DragSource")},new InvocationHandler(){
                public Object invoke(Object self,Method method,Object[] args){
                    if(method.getName().equals("onDropCompleted")){
                        source.setVisibility(View.VISIBLE);
                        if(!exited)set(args[1],"deferDragViewCleanupPostAnimation",false);
                        return null;
                    }
                    return objectMethod(self,method,args);
                }
            });
        }
        public Object invoke(Object self,Method method,Object[] args)throws Throwable{
            String name=method.getName();
            if(name.equals("isDropEnabled"))return !exited;
            if(name.equals("getDropView"))return layer;
            if(name.equals("getHitRectRelativeToDragLayer")){((Rect)args[0]).set(0,exitY,layer.getWidth(),layer.getHeight());return null;}
            if(name.equals("onDragOver")||name.equals("onDragEnter")){
                int x=((Number)get(args[0],"x")).intValue(),y=((Number)get(args[0],"y")).intValue();
                if(DrawerHomeTarget.overRemove){clearHover();setMember(null);return null;}
                if(stay!=null&&!stay.left){
                    clearHover();
                    // preview68: the other apps make room live, like a home-screen folder (FolderReorder).
                    if(reorder!=null){setMember(null);reorder.over(args[0],x,y);return null;}
                    View m=stay.memberAt(x,y);setMember(m==source?null:m);return null;
                }
                if(stay!=null&&reorder!=null)reorder.leave();
                // preview70: a held folder pushes the other folders aside and can only land among them.
                if(push!=null){clearHover();setMember(null);push.over(x,y);return null;}
                setMember(null);
                View candidate=findCell((View)call(activity,"getAppsView"),x,y,layer);
                if(candidate==source)candidate=null;
                if(candidate!=hovered){clearHover();hovered=candidate;}
                if(hovered!=null){int[] location=new int[2],base=new int[2];hovered.getLocationOnScreen(location);layer.getLocationOnScreen(base);localX=x-(location[0]-base[0]);localY=y-(location[1]-base[1]);}
                // Feedback for what a drop here does: the folder indicator when it makes or fills
                // a folder (as on the home screen), a slight lift when it moves a folder.
                boolean folderDrop=hovered!=null&&LauncherFeatures.on(LauncherFeatures.DRAWER)&&intoFolder(hovered);
                if(folderDrop!=indicating){indicating=folderDrop;try{if(folderDrop)DropIndicator.show(hovered);else DropIndicator.hide(hovered);}catch(Throwable error){LauncherFeatures.logOnce("Drawer drop indicator unavailable",error);}}
                boolean lift=hovered!=null&&key.startsWith("folder:")&&DrawerFolders.isFolder(hovered.getTag());
                if(lift!=lifted&&hovered!=null){lifted=lift;hovered.animate().scaleX(lift?1.06f:1f).scaleY(lift?1.06f:1f).setDuration(150).start();}
                return null;
            }
            if(name.equals("onDragExit")){if(!Boolean.TRUE.equals(get(args[0],"dragComplete")))clearHover();return null;}
            if(name.equals("acceptDrop")&&DrawerHomeTarget.overRemove&&stay!=null)return true;
            if(name.equals("acceptDrop")&&stay!=null&&!stay.left)return true;
            if(name.equals("acceptDrop")&&push!=null)return push.active();
            if(name.equals("acceptDrop"))return LauncherFeatures.on(LauncherFeatures.DRAWER)&&hovered!=null && hovered!=source;
            if(name.equals("prepareAccessibilityDrop"))return null;
            if(name.equals("onDrop")&&DrawerHomeTarget.overRemove&&stay!=null){
                clearHover();setMember(null);set(args[0],"deferDragViewCleanupPostAnimation",false);takeOut();return null;
            }
            if(name.equals("onDrop")&&stay!=null&&!stay.left){
                View m=hoverMember;setMember(null);set(args[0],"deferDragViewCleanupPostAnimation",false);
                if(reorder!=null){reorder.commit(args[0]);MultiDrag.handledInFolder();return null;}   // picked apps land with the held one
                dropInside(m);MultiDrag.drawerDrop();return null;
            }
            if(name.equals("onDrop")&&push!=null){clearHover();set(args[0],"deferDragViewCleanupPostAnimation",false);push.commit();return null;}
            if(name.equals("onDrop")){
                if(reorder!=null)reorder.restore();
                View destination=hovered;clearHover();set(args[0],"deferDragViewCleanupPostAnimation",false);
                if(destination==null||destination==source)return null;
                DrawerFolders.DragSource origin=new DrawerFolders.DragSource(key,info,source);
                Object targetInfo=destination.getTag();
                if(!DrawerFolders.isFolder(targetInfo)&&!key.startsWith("folder:")&&!inCenter(destination))DrawerFolders.reorder(origin,targetInfo);
                else DrawerFolders.drop(origin,targetInfo);
                MultiDrag.drawerDrop();   // apps picked up with a second finger follow the held one
                return null;
            }
            return objectMethod(self,method,args);
        }
        /** An app dropped here goes into a folder: onto a drawer folder, or onto the middle of an app. */
        boolean intoFolder(View target){
            if(key.startsWith("folder:"))return false;
            return DrawerFolders.isFolder(target.getTag())||inCenter(target);
        }
        /** The finger is over the middle of an app icon (the zone the drop has always used). */
        boolean inCenter(View target){
            float cx=target.getWidth()/2f,cy=target.getPaddingTop()+LauncherFeatures.dp(activity,28),radius=LauncherFeatures.dp(activity,25);
            return Math.abs(localX-cx)<=radius&&Math.abs(localY-cy)<=radius;
        }
        void setMember(View m){
            if(m==hoverMember)return;
            if(hoverMember!=null)hoverMember.animate().scaleX(1).scaleY(1).setDuration(120).start();
            hoverMember=m;
            if(m!=null)m.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
        }
        /** Dropped inside its folder: in Custom order it takes the place of the app under it. */
        void dropInside(View target){
            try{
                Object folderInfo=get(stay.folder,"mInfo");Integer id=DrawerFolders.folderIds.get(folderInfo);
                LauncherData.Folder f=DrawerFolders.record(folderInfo);
                if(id==null||f==null||target==null||target==source||f.sort==FolderSort.AUTO)return;
                String targetKey=LauncherFeatures.identity(target.getTag());if(targetKey==null)return;
                int to=f.apps.indexOf(targetKey);if(to<0)return;
                if(FolderSort.drawerMove(id,key,to)){DrawerFolders.reopenId=id;try{call(stay.folder,"close",false);}catch(Throwable ignored){}}
            }catch(Throwable error){Module.log("Drawer folder reorder failed",error);}
        }
        /** Dropped on the Remove pill: the held app and every app picked from the same folder leave it. */
        void takeOut(){
            try{
                if(reorder!=null)reorder.restore();
                DrawerFolders.closeDrawerFolder(false);   // close first, while the folder still has its apps
                MultiDrag.takeOut(key);
            }catch(Throwable error){Module.log("Drawer folder remove failed",error);}
        }
        void clearHover(){
            if(hovered!=null){
                if(indicating)try{DropIndicator.hide(hovered);}catch(Throwable ignored){}
                if(lifted)hovered.animate().scaleX(1).scaleY(1).setDuration(150).start();
                hovered=null;
            }
            indicating=false;lifted=false;
        }
        void exitToHome()throws Exception{
            // Crossing above the search bar is a one-way handoff to the existing home drag.
            exited=true;DrawerHomeTarget.hide();clearHover();call(controller,"removeDropTarget",target);
            // The held app leaves for the home screen: its folder must not stay open over it.
            if(reorder!=null)reorder.restore();
            if(push!=null)push.restore();
            if(stay!=null){
                // Fade it out and close it without its shrink animation: that animation aimed at the drawer icon and
                // showed up for a moment over the home screen.
                stay.stop();final View open=stay.folder;
                open.animate().cancel();
                open.animate().alpha(0f).setDuration(90).withEndAction(new Runnable(){public void run(){
                    try{DrawerFolders.closeDrawerFolder(false);}catch(Throwable ignored){}
                    open.setAlpha(1f);
                }}).start();
            }
            Object drag=get(controller,"mDragObject");
            if(DrawerFolders.isFolder(info)){
                LauncherData.Folder f=DrawerFolders.record(info);Object copy=Xp.newInstance(type("model.data.FolderInfo"));
                set(copy,"title",get(info,"title"));set(copy,"user",get(info,"user"));
                // preview72: not on the home screen yet (no database id), so the stock drop bar offers "Cancel", not "Remove",
                // as for an app dragged out of the drawer.
                try{set(copy,"id",-1);}catch(Throwable ignored){}
                List<Object> content=(List<Object>)get(copy,"contents");
                if(f!=null)for(String appKey:f.apps){Object app=DrawerFolders.known.get(appKey);if(app!=null)content.add(call(app,"makeWorkspaceItem",activity));}
                set(drag,"dragInfo",copy);set(drag,"originalDragInfo",call(copy,"makeShallowCopy"));
                PrivacyFolders.draggedToHome(activity,info,copy);   // 2.0.8-preview5: a locked drawer folder's home copy is locked too
            }
            Object workspace=get(activity,"mWorkspace");set(workspace,"mDragSourceInternal",null);
            call(workspace,"onDragStart",drag,get(controller,"mOptions"));
            call(get(activity,"mDropTargetBar"),"onDragStart",drag,get(controller,"mOptions"));
            Module.log("Drawer drag handed off above search bar");
        }
    }
    static Object objectMethod(Object self,Method m,Object[] args){
        if(m.getName().equals("hashCode"))return System.identityHashCode(self);
        if(m.getName().equals("equals"))return self==args[0];
        if(m.getName().equals("toString"))return "PixelSuiteDrawerDrag";return null;
    }
    private static View findCell(View root,int x,int y,ViewGroup layer){
        if(root.getVisibility()!=View.VISIBLE||Xp.getAdditionalInstanceField(root,"pixelsuite_picked")!=null)return null;   // picked-up apps (MultiDrag) are hidden
        int[] base=new int[2];layer.getLocationOnScreen(base);Rect bounds=new Rect();if(!root.getGlobalVisibleRect(bounds)||!bounds.contains(x+base[0],y+base[1]))return null;
        Object tag=root.getTag();if(tag!=null&&(DrawerFolders.isFolder(tag)||type("model.data.AppInfo").isInstance(tag))&&!LauncherFeatures.privateItem(tag))return root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=group.getChildCount()-1;i>=0;i--){View result=findCell(group.getChildAt(i),x,y,layer);if(result!=null)return result;}}
        return null;
    }
}
