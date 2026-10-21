package io.github.pixelsuite;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

final class BackdropSurface extends GradientDrawable {
    final Context context;
    boolean failed;
    boolean allowed;
    boolean parentBlur;
    boolean reported;
    int night,radiusPx;
    WeakReference<View> host=new WeakReference<View>(null);
    Object node;
    Drawable region;Object regionRoot;
    final RectF lastFrame=new RectF(),requestedFrame=new RectF();float lastAlpha=-1f,requestedAlpha=-1f;
    ViewTreeObserver.OnPreDrawListener preDraw;

    BackdropSurface(Context c,float radius){context=c;night=mode();allowed=FrostedSurface.blurEnabled(c)&&c.getPackageName().equals(LAUNCHER);radiusPx=FrostedSurface.depthBlurRadius(c);colors();setOrientation(Orientation.TOP_BOTTOM);setColors(FrostedSurface.colors(c));setCornerRadius(FrostedSurface.dp(c,radius));}
    static final String LAUNCHER="com.google.android.apps.nexuslauncher";
    int mode(){return context.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK;}
    void refresh(){night=mode();allowed=FrostedSurface.blurEnabled(context)&&context.getPackageName().equals(LAUNCHER);radiusPx=FrostedSurface.depthBlurRadius(context);colors();if(failed||!allowed){int color=FrostedSurface.tone(context)|0xff000000;setColors(new int[]{color,color});}else setColors(FrostedSurface.colors(context));invalidateSelf();}
    int solid;boolean solidSet;
    int openColor,closedColor;
    @Override public void setColor(int color){solid=color;solidSet=true;super.setColor(failed?color|0xff000000:color);}
    @Override public void setColors(int[] colors){solidSet=false;super.setColors(colors);}

    float strength(View view){
        if(!solidSet||!isFolder(view))return 1f;
        float dot=0f,len=0f;
        for(int shift=0;shift<32;shift+=8){
            float o=(openColor>>>shift)&0xff,c=(closedColor>>>shift)&0xff,n=(solid>>>shift)&0xff;
            dot+=(n-c)*(o-c);len+=(o-c)*(o-c);
        }
        if(len<1f)return 1f;
        return Math.max(0f,Math.min(1f,dot/len));
    }
    private void colors(){openColor=FrostedSurface.colors(context)[0];closedColor=FrostedSurface.closedFolderColor(context);}

    void bind(final View view){
        parentBlur=true;
        if(!allowed)setColor(FrostedSurface.tone(context)|0xff000000);
        if(host.get()==view)return;
        unwatch();
        host=new WeakReference<View>(view);
        synchronized(hosts){hosts.put(view,this);}
        if(view.isAttachedToWindow())watch(view);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){if(host.get()==v)watch(v);}
            public void onViewDetachedFromWindow(View v){if(host.get()==v)unwatch();}
        });
    }

    @Override public void draw(Canvas canvas){
        if(night!=mode())refresh();
        View view=host.get();
        if(view!=null&&allowed&&!failed&&canvas.isHardwareAccelerated()){
            try{drawFrost(canvas,view);}
            catch(Throwable unsupported){
                failed=true;refresh();
                SuiteLog.log("Local frost unavailable, using an opaque panel",unsupported);
            }
        }
        super.draw(canvas);
    }

    private void drawFrost(Canvas canvas,View view)throws Exception{
        ViewGroup layer=dragLayerOf(view);
        if(layer==null)return;
        Path frame=frame(view,this);
        if(frame==null)return;
        RectF bounds=new RectF();frame.computeBounds(bounds,true);
        if(bounds.isEmpty())return;
        Matrix toLayer=new Matrix();
        View top=mapToLayer(view,layer,toLayer);
        if(top==null)return;
        float alpha=effectiveAlpha(view,layer);
        int blur=Math.round(radiusPx*strength(view));
        int save=canvas.save();
        try{
            canvas.clipPath(frame);
            Drawable wallpaper=region(view);
            if(wallpaper!=null){
                Rect r=new Rect();bounds.roundOut(r);
                wallpaper.setBounds(r);
                call(wallpaper,"setBlurRadius",blur);
                call(wallpaper,"setCornerRadius",cornerRadius(view,bounds));
                wallpaper.setAlpha(Math.round(255*alpha));
                wallpaper.draw(canvas);
            }
            Matrix toLocal=new Matrix();
            if(toLayer.invert(toLocal)){
                RectF area=new RectF(bounds);toLayer.mapRect(area);
                int under=recordUnder(layer,top,area,blur);
                if(under>0){canvas.concat(toLocal);DRAW_NODE.invoke(canvas,node);}
                if(!reported){
                    reported=true;
                    if(reportedTypes.add(view.getClass().getName()))SuiteLog.log("Local frost: "+view.getClass().getSimpleName()+" frame="+Math.round(bounds.width())+"x"+Math.round(bounds.height())+", under="+under+", radius="+radiusPx+", wallpaper region="+(wallpaper!=null));
                }
            }
        }finally{canvas.restoreToCount(save);}
        lastFrame.set(frameInLayer(view,this,layer,null));lastAlpha=alpha;
    }

    private int recordUnder(ViewGroup layer,View top,RectF area,int blur)throws Exception{
        init();
        if(node==null){
            node=NODE.getConstructor(String.class).newInstance("PixelSuite local frost");
        }
        int pad=radiusPx*2;
        Rect r=new Rect();area.roundOut(r);r.inset(-pad,-pad);
        if(!r.intersect(0,0,layer.getWidth(),layer.getHeight()))return 0;
        POSITION.invoke(node,r.left,r.top,r.right,r.bottom);
        if(blur!=effectRadius){
            effect=blur>0?CREATE_BLUR.invoke(null,(float)blur,(float)blur,Shader.TileMode.CLAMP):null;effectRadius=blur;
        }
        SET_EFFECT.invoke(node,effect);
        java.util.Set<View> panels=activeTops(layer);
        Canvas recording=(Canvas)BEGIN.invoke(node,r.width(),r.height());
        int count=0;
        try{
            recording.translate(-r.left-layer.getScrollX(),-r.top-layer.getScrollY());
            for(int i=0;i<layer.getChildCount();i++){
                View child=layer.getChildAt(i);
                if(!drawnUnder(layer,child,top)||child.getVisibility()!=View.VISIBLE||child.getAlpha()<=0f)continue;
                if(panels.contains(child)){count+=recordPanel(recording,child);continue;}
                Object childNode=RENDER_NODE.get(child);
                if(childNode==null)continue;
                DRAW_NODE.invoke(recording,childNode);count++;
            }
        }finally{END.invoke(node);}
        return count;
    }

    private static int recordPanel(Canvas recording,View panel)throws Exception{
        if(!(panel instanceof ViewGroup)||panel.getVisibility()!=View.VISIBLE||panel.getAlpha()<.99f)return 0;
        ViewGroup group=(ViewGroup)panel;
        int save=recording.save();
        try{
            recording.translate(panel.getLeft(),panel.getTop());
            recording.concat(panel.getMatrix());
            Drawable bg=panel.getBackground();
            if(bg!=null&&bg.getConstantState()!=null){
                Drawable tint=bg.getConstantState().newDrawable();
                if(!(tint instanceof BackdropSurface)){tint.setBounds(bg.getBounds());tint.draw(recording);}
            }
            recording.translate(-panel.getScrollX(),-panel.getScrollY());
            for(int i=0;i<group.getChildCount();i++){
                View c=group.getChildAt(i);
                if(c.getVisibility()!=View.VISIBLE||c.getAlpha()<=0f)continue;
                Object n=RENDER_NODE.get(c);if(n!=null)DRAW_NODE.invoke(recording,n);
            }
            return 1;
        }finally{recording.restoreToCount(save);}
    }

    private Drawable region(View view){
        if(regionRoot==Boolean.FALSE)return null;
        try{
            Object root=LauncherFeatures.call(view,"getViewRootImpl");
            if(root==null)return null;
            if(region==null||regionRoot!=root){
                Drawable created=(Drawable)LauncherFeatures.call(root,"createBackgroundBlurDrawable");
                try{call(created,"setColor",Color.TRANSPARENT);}catch(Throwable ignored){}
                region=created;regionRoot=root;
            }
            return region;
        }catch(Throwable unavailable){
            if(regionRoot!=Boolean.FALSE){regionRoot=Boolean.FALSE;SuiteLog.log("Local frost: wallpaper region unavailable",unavailable);}
            return null;
        }
    }

    static Path frame(View view,BackdropSurface surface){
        Path path=new Path();
        if(isFolder(view)){
            try{
                Object clip=LauncherFeatures.get(view,"mClipPath");
                Path reveal=clip instanceof Path?(Path)clip:clip!=null?(Path)LauncherFeatures.get(clip,"path"):null;
                if(reveal!=null&&!reveal.isEmpty()){path.set(reveal);return path;}
                if(clip!=null&&surface.strength(view)<.5f)return null;
            }catch(Throwable ignored){}
        }
        Rect bounds=surface.getBounds();
        if(bounds.isEmpty())return null;
        RectF rect=new RectF(bounds);float radius=surface.radius();
        try{
            if(view.getClipToOutline()&&view.getOutlineProvider()!=null){
                Outline outline=new Outline();view.getOutlineProvider().getOutline(view,outline);
                Rect r=new Rect();
                if(Boolean.TRUE.equals(call(outline,"getRect",r))&&!r.isEmpty()){
                    rect.set(r);radius=((Number)call(outline,"getRadius")).floatValue();
                    rect.intersect(new RectF(bounds));
                }
            }
        }catch(Throwable ignored){}
        radius=Math.max(0f,Math.min(radius,Math.min(rect.width(),rect.height())/2f));
        path.addRoundRect(rect,radius,radius,Path.Direction.CW);
        return path;
    }

    boolean owns(View view){
        if(isFolder(view)){try{return LauncherFeatures.get(view,"mBackground")==this;}catch(Throwable t){return false;}}
        return view.getBackground()==this;
    }

    float radius(){try{return ((Number)call(this,"getCornerRadius")).floatValue();}catch(Throwable t){return 0f;}}

    private float cornerRadius(View view,RectF bounds){
        float r=radius();
        return Math.max(0f,Math.min(r,Math.min(bounds.width(),bounds.height())/2f));
    }

    static RectF frameInLayer(View view,BackdropSurface surface,ViewGroup layer,Path out){
        RectF bounds=new RectF();
        Path frame=frame(view,surface);
        Matrix toLayer=new Matrix();
        if(frame==null||mapToLayer(view,layer,toLayer)==null)return bounds;
        frame.transform(toLayer);
        if(out!=null)out.set(frame);
        frame.computeBounds(bounds,true);
        return bounds;
    }

    static View mapToLayer(View view,ViewGroup layer,Matrix out){
        out.reset();
        View current=view;
        while(true){
            Matrix own=new Matrix(current.getMatrix());
            ViewParent parent=current.getParent();
            if(!(parent instanceof ViewGroup))return null;
            ViewGroup group=(ViewGroup)parent;
            own.postTranslate(current.getLeft()-group.getScrollX(),current.getTop()-group.getScrollY());
            out.postConcat(own);
            if(group==layer)return current;
            current=group;
        }
    }

    static float effectiveAlpha(View view,ViewGroup layer){
        float alpha=1f;
        for(View v=view;v!=null&&v!=layer;v=v.getParent() instanceof View?(View)v.getParent():null)alpha*=v.getAlpha();
        return Math.max(0f,Math.min(1f,alpha));
    }

    static boolean drawnUnder(ViewGroup layer,View child,View top){
        if(child==top)return false;
        float a=child.getZ(),b=top.getZ();
        if(a!=b)return a<b;
        return layer.indexOfChild(child)<layer.indexOfChild(top);
    }

    static ViewGroup dragLayerOf(View view){
        Class<?> type=dragLayerType();
        if(type==null)return null;
        for(ViewParent p=view.getParent();p!=null;p=p.getParent())if(type.isInstance(p))return (ViewGroup)p;
        return null;
    }

    static final Map<View,BackdropSurface> hosts=new WeakHashMap<View,BackdropSurface>();
    private static final ArrayDeque<Integer> saves=new ArrayDeque<Integer>();
    private static boolean clipErrorLogged;

    static Map<View,View> active(ViewGroup layer){
        Map<View,View> out=new java.util.HashMap<View,View>();
        ArrayList<Map.Entry<View,BackdropSurface>> list;
        synchronized(hosts){list=new ArrayList<Map.Entry<View,BackdropSurface>>(hosts.entrySet());}
        for(Map.Entry<View,BackdropSurface> entry:list){
            View view=entry.getKey();BackdropSurface surface=entry.getValue();
            if(view==null||surface==null||surface.host.get()!=view||!surface.allowed||surface.failed)continue;
            if(!view.isAttachedToWindow()||!view.isShown()||dragLayerOf(view)!=layer||!surface.owns(view))continue;
            Matrix toLayer=new Matrix();View top=mapToLayer(view,layer,toLayer);
            if(top!=null)out.put(view,top);
        }
        return out;
    }
    static java.util.Set<View> activeTops(ViewGroup layer){return new java.util.HashSet<View>(active(layer).values());}

    static void beforeDrawChild(ViewGroup layer,Canvas canvas,View child){
        int save=-1;
        try{
            Map<View,View> panels=canvas.isHardwareAccelerated()?active(layer):null;
            if(panels!=null&&!panels.isEmpty()){
                for(Map.Entry<View,View> entry:panels.entrySet()){
                    View view=entry.getKey(),top=entry.getValue();
                    if(top==child)continue;
                    BackdropSurface surface;synchronized(hosts){surface=hosts.get(view);}
                    if(surface==null)continue;
                    if(!drawnUnder(layer,child,top))continue;
                    if(effectiveAlpha(view,layer)<.99f)continue;
                    Path path=new Path();RectF bounds=frameInLayer(view,surface,layer,path);
                    if(bounds.isEmpty())continue;
                    if(save<0)save=canvas.save();
                    canvas.clipPath(path,Region.Op.DIFFERENCE);
                }
            }
        }catch(Throwable error){
            if(!clipErrorLogged){clipErrorLogged=true;SuiteLog.log("Local frost clip skipped",error);}
        }
        saves.push(save);
    }

    static void afterDrawChild(Canvas canvas){
        Integer save=saves.poll();
        if(save!=null&&save>=0)canvas.restoreToCount(save);
    }

    private void watch(final View view){
        if(preDraw!=null)return;
        preDraw=new ViewTreeObserver.OnPreDrawListener(){
            public boolean onPreDraw(){
                try{
                    if(!allowed||failed)return true;
                    ViewGroup layer=dragLayerOf(view);if(layer==null)return true;
                    RectF now=frameInLayer(view,BackdropSurface.this,layer,null);
                    float alpha=effectiveAlpha(view,layer);
                    if((!now.equals(lastFrame)||alpha!=lastAlpha)&&(!now.equals(requestedFrame)||alpha!=requestedAlpha)){
                        requestedFrame.set(now);requestedAlpha=alpha;
                        view.invalidate();layer.invalidate();
                    }
                }catch(Throwable ignored){}
                return true;
            }
        };
        view.getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    private void unwatch(){
        View view=host.get();
        if(view!=null&&preDraw!=null&&view.getViewTreeObserver().isAlive())view.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        preDraw=null;lastFrame.setEmpty();lastAlpha=-1f;requestedFrame.setEmpty();requestedAlpha=-1f;
    }

    private static final java.util.Set<String> reportedTypes=new java.util.HashSet<String>();
    private static Class<?> folderType;private static boolean folderMissing;
    static boolean isFolder(View view){
        if(folderType==null&&!folderMissing){try{folderType=LauncherFeatures.type("folder.Folder");}catch(Throwable t){folderMissing=true;}}
        return folderType!=null&&folderType.isInstance(view);
    }
    static Class<?> NODE;static Method BEGIN,END,POSITION,SET_EFFECT,CREATE_BLUR,DRAW_NODE;static Field RENDER_NODE;
    Object effect;int effectRadius=-1;
    private static Class<?> dragLayer;private static boolean dragLayerMissing;
    static void init()throws Exception{
        if(NODE!=null)return;
        Class<?> nt=Class.forName("android.graphics.RenderNode"),et=Class.forName("android.graphics.RenderEffect");
        BEGIN=nt.getMethod("beginRecording",int.class,int.class);END=nt.getMethod("endRecording");
        POSITION=nt.getMethod("setPosition",int.class,int.class,int.class,int.class);
        SET_EFFECT=nt.getMethod("setRenderEffect",et);
        CREATE_BLUR=et.getMethod("createBlurEffect",float.class,float.class,Shader.TileMode.class);
        DRAW_NODE=Canvas.class.getMethod("drawRenderNode",nt);
        Field f=View.class.getDeclaredField("mRenderNode");f.setAccessible(true);RENDER_NODE=f;
        NODE=nt;
    }
    static Class<?> dragLayerType(){
        if(dragLayer==null&&!dragLayerMissing){
            try{dragLayer=LauncherFeatures.type("dragndrop.DragLayer");}catch(Throwable t){dragLayerMissing=true;}
        }
        return dragLayer;
    }
    static Object call(Object o,String name,Object...args){return LauncherFeatures.call(o,name,args);}
}
