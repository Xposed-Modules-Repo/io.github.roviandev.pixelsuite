package io.github.pixelsuite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;

final class FeaturePreview extends View implements ViewTreeObserver.OnPreDrawListener {
    private static final int ACTION=0,HOLD=1,RESET=2,START=3;
    private static final long HOLD_MS=1100,RESET_MS=240,START_MS=220;
    private final PreviewScenes scenes;
    private final PreviewIcons icons;
    private final Rect visible=new Rect();
    private ValueAnimator animator;
    private boolean active,allowed,focused=true,waiting,disposed;
    private float progress,reset;
    private int phase=ACTION;
    private final Runnable continuation=new Runnable(){public void run(){
        waiting=false;if(!canRun())return;
        if(phase==HOLD){phase=RESET;reset=0;animate(true);}
        else if(phase==START){phase=ACTION;progress=0;animate(false);}
    }};
    FeaturePreview(Context c,String key,int accent,int onAccent,int ink,int muted,int surface,boolean dark){
        super(c);icons=new PreviewIcons(c);scenes=new PreviewScenes(key,accent,onAccent,ink,muted,surface,dark,icons);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void setHostActive(boolean value){active=value;if(value){allowed=motionAllowed();check();}else stop();}
    void dispose(){disposed=true;active=false;stop();if(getViewTreeObserver().isAlive())getViewTreeObserver().removeOnPreDrawListener(this);}
    void onIconsReady(){if(disposed)return;icons.refresh();invalidate();check();}
    private boolean motionAllowed(){
        try{
            if(Settings.Global.getFloat(getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0)return false;
            PowerManager power=(PowerManager)getContext().getSystemService(Context.POWER_SERVICE);if(power!=null&&power.isPowerSaveMode())return false;
            AccessibilityManager access=(AccessibilityManager)getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);if(access!=null&&access.isTouchExplorationEnabled())return false;
        }catch(Exception ignored){}
        return true;
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();disposed=false;getViewTreeObserver().addOnPreDrawListener(this);PreviewIcons.load(getContext(),this);}
    @Override protected void onDetachedFromWindow(){dispose();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)stop();else check();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);focused=focus;if(focus){allowed=motionAllowed();check();}else stop();}
    @Override public boolean onPreDraw(){check();return true;}
    private boolean canRun(){return !disposed&&active&&focused&&allowed&&icons.ready()&&getWindowVisibility()==VISIBLE&&isShown()&&getHeight()>0&&getGlobalVisibleRect(visible)&&visible.height()>=getHeight()/2;}
    private void check(){
        if(!canRun()){stop();return;}
        if(animator!=null||waiting)return;
        if(phase==HOLD||phase==START){waiting=true;postDelayed(continuation,phase==HOLD?HOLD_MS:START_MS);}
        else animate(phase==RESET);
    }
    private void animate(final boolean transition){
        float from=transition?reset:progress;
        animator=ValueAnimator.ofFloat(from,1f);animator.setDuration(Math.max(1,(long)((transition?RESET_MS:scenes.duration())*(1-from))));
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener(){public void onAnimationUpdate(ValueAnimator a){if(transition)reset=(Float)a.getAnimatedValue();else progress=(Float)a.getAnimatedValue();invalidate();}});
        animator.addListener(new AnimatorListenerAdapter(){boolean cancelled;
            @Override public void onAnimationCancel(Animator a){cancelled=true;}
            @Override public void onAnimationEnd(Animator a){
                animator=null;if(cancelled)return;
                if(transition){phase=START;progress=0;reset=0;}else{phase=HOLD;progress=1;}
                invalidate();check();
            }
        });animator.start();
    }
    private void stop(){removeCallbacks(continuation);waiting=false;ValueAnimator running=animator;animator=null;if(running!=null)running.cancel();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);scenes.draw(canvas,getWidth(),getHeight(),progress,phase==RESET?reset:0);}
}
