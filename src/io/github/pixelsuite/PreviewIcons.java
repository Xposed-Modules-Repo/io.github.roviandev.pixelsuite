package io.github.pixelsuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Known demo apps only; local icons load once off the UI thread, never during a frame. */
final class PreviewIcons {
    static final String[] PACKAGES={"com.google.android.keep","com.android.chrome","com.google.android.apps.maps","com.google.android.apps.photos","com.google.android.gm","com.google.android.apps.messaging","com.google.android.apps.nbu.files","com.google.android.calendar","com.google.android.youtube","com.google.android.apps.docs","com.google.android.deskclock","com.google.android.googlequicksearchbox","com.google.android.GoogleCamera"};
    static final String[] NAMES={"Keep","Chrome","Maps","Photos","Gmail","Messages","Files","Calendar","YouTube","Drive","Clock","Google","Camera"};
    private static final Drawable.ConstantState[] CACHE=new Drawable.ConstantState[PACKAGES.length];
    private static final ArrayList<WeakReference<FeaturePreview>> WAITERS=new ArrayList<WeakReference<FeaturePreview>>();
    private static boolean loading,loaded;
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(0,1,5,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>());
    private final Context context;
    private final Drawable[] drawables=new Drawable[PACKAGES.length];
    PreviewIcons(Context context){this.context=context;refresh();}
    boolean ready(){synchronized(CACHE){return loaded;}}
    void refresh(){synchronized(CACHE){if(!loaded)return;for(int i=0;i<CACHE.length;i++)if(CACHE[i]!=null&&drawables[i]==null)drawables[i]=CACHE[i].newDrawable(context.getResources()).mutate();}}
    static void load(Context context,FeaturePreview view){
        final Context app=context.getApplicationContext();
        synchronized(CACHE){if(loaded){view.onIconsReady();return;}WAITERS.add(new WeakReference<FeaturePreview>(view));if(loading)return;loading=true;}
        IO.execute(new Runnable(){public void run(){
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Drawable.ConstantState[] found=new Drawable.ConstantState[PACKAGES.length];
            for(int i=0;i<PACKAGES.length;i++)try{found[i]=app.getPackageManager().getApplicationIcon(PACKAGES[i]).getConstantState();}catch(Exception unavailable){}
            final ArrayList<WeakReference<FeaturePreview>> views;
            synchronized(CACHE){System.arraycopy(found,0,CACHE,0,CACHE.length);loaded=true;loading=false;views=new ArrayList<WeakReference<FeaturePreview>>(WAITERS);WAITERS.clear();}
            for(final WeakReference<FeaturePreview> ref:views){FeaturePreview v=ref.get();if(v!=null)v.post(new Runnable(){public void run(){FeaturePreview live=ref.get();if(live!=null)live.onIconsReady();}});}
        }});
    }
    boolean draw(Canvas canvas,int app,float x,float y,float size,float alpha){
        Drawable icon=drawables[Math.floorMod(app,drawables.length)];if(icon==null)return false;
        icon.setBounds(Math.round(x-size/2),Math.round(y-size/2),Math.round(x+size/2),Math.round(y+size/2));icon.setAlpha(Math.round(255*alpha));icon.draw(canvas);return true;
    }
}
