package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import java.lang.reflect.Method;

/** High-refresh scrolling votes during touch/fling only; never changes phone-wide display settings. */
final class SmoothScrollView extends ScrollView {
    private final Activity activity;
    private final Method setRate;
    private boolean boosted, touching;
    private float restoreRate, restoreChildRate, windowRate;
    private final Runnable idle = new Runnable() { public void run() { if (!touching) release(); } };

    SmoothScrollView(Activity activity) {
        super(activity); this.activity=activity;
        Method method=null;
        try { method=View.class.getMethod("setRequestedFrameRate",float.class); }
        catch (Throwable older) { }
        setRate=method;
    }
    private float rate() {
        try {
            PowerManager power=(PowerManager)getContext().getSystemService(Context.POWER_SERVICE);
            if(power!=null&&power.isPowerSaveMode())return 0;
            Display display=getDisplay();if(display==null)return 0;
            Display.Mode current=display.getMode();float best=current.getRefreshRate();
            for(Display.Mode mode:display.getSupportedModes()) {
                if(mode.getPhysicalWidth()==current.getPhysicalWidth()&&mode.getPhysicalHeight()==current.getPhysicalHeight())best=Math.max(best,mode.getRefreshRate());
            }
            float peak=Settings.System.getFloat(getContext().getContentResolver(),"peak_refresh_rate",best);
            if(peak>0)best=Math.min(best,peak);
            return best>60.5f?best:0;
        }catch(Throwable unavailable){return 0;}
    }
    private float currentRate(View v) {
        try{return (Float)View.class.getMethod("getRequestedFrameRate").invoke(v);}catch(Throwable older){return 0;}
    }
    private void boost() {
        if(boosted||!hasWindowFocus()||getWindowVisibility()!=VISIBLE)return;
        float rate=rate();if(rate<=0)return;
        try {
            boosted=true;
            if(setRate!=null) {
                restoreRate=currentRate(this);setRate.invoke(this,rate);
                if(getChildCount()>0){View child=getChildAt(0);restoreChildRate=currentRate(child);setRate.invoke(child,rate);}
            }else {
                WindowManager.LayoutParams params=activity.getWindow().getAttributes();windowRate=params.preferredRefreshRate;
                params.preferredRefreshRate=rate;activity.getWindow().setAttributes(params);
            }
        }catch(Throwable unavailable){release();}
    }
    private void release() {
        removeCallbacks(idle);
        if(!boosted)return;
        boosted=false;
        try {
            if(setRate!=null){setRate.invoke(this,restoreRate);if(getChildCount()>0)setRate.invoke(getChildAt(0),restoreChildRate);}
            else{WindowManager.LayoutParams params=activity.getWindow().getAttributes();params.preferredRefreshRate=windowRate;activity.getWindow().setAttributes(params);}
        }catch(Throwable ignored){ }
    }
    private void settle() { removeCallbacks(idle);if(!touching)postDelayed(idle,300); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){touching=true;removeCallbacks(idle);boost();}
        boolean handled=super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){touching=false;settle();}
        return handled;
    }
    @Override protected void onScrollChanged(int x,int y,int oldX,int oldY){super.onScrollChanged(x,y,oldX,oldY);if(x!=oldX||y!=oldY){boost();settle();}}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus){touching=false;release();}}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE){touching=false;release();}}
    @Override protected void onDetachedFromWindow(){touching=false;release();super.onDetachedFromWindow();}
}
