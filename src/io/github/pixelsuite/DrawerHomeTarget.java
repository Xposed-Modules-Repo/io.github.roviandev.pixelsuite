package io.github.pixelsuite;
import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
final class DrawerHomeTarget {
    static Activity owner;
    static LinearLayout pill;
    static GradientDrawable background;
    static int savedFlags,hover=-1;
    static boolean intro;
    static final Rect bounds=new Rect();
    static final int[] position=new int[2],origin=new int[2];
    static void install(){
        Xp.hookAllMethods(LauncherFeatures.type("popup.ArrowPopup"),"closeComplete",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){if(!DrawerDrag.active())hide();}});
    }
    static void arm(View source){
        if(!LauncherFeatures.on(LauncherFeatures.DRAG_PILL)||LauncherFeatures.privateItem(source.getTag()))return;
        final Activity a=LauncherFeatures.activity.get();if(a==null)return;
        if(owner==a&&pill!=null)return;hide();
        try{
            ViewGroup layer=(ViewGroup)LauncherFeatures.call(a,"getDragLayer");
            View search=(View)LauncherFeatures.get(LauncherFeatures.call(a,"getAppsView"),"mSearchContainer");
            if(search.getWidth()==0||search.getHeight()==0)return;
            search.getLocationOnScreen(position);layer.getLocationOnScreen(origin);
            int width=Math.min((int)(search.getWidth()*.84f),layer.getWidth()-FrostedSurface.dp(a,48));
            int height=FrostedSurface.dp(a,54),x=position[0]-origin[0]+(search.getWidth()-width)/2;
            int y=position[1]-origin[1]+(search.getHeight()-height)/2;
            final LinearLayout view=new LinearLayout(a);view.setGravity(Gravity.CENTER);view.setPadding(FrostedSurface.dp(a,18),0,FrostedSurface.dp(a,18),0);
            background=FrostedSurface.drawable(a,40);view.setBackground(background);view.setElevation(FrostedSurface.dp(a,9));
            ((BackdropSurface)background).bind(view);
            ImageView icon=new ImageView(a);icon.setImageDrawable(new LauncherGlyph(a,3));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(FrostedSurface.dp(a,24),FrostedSurface.dp(a,24));ip.rightMargin=FrostedSurface.dp(a,10);view.addView(icon,ip);
            TextView label=new TextView(a);label.setText("Add to home screen");label.setTextSize(16);label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            label.setTextColor(LauncherFeatures.surface(a,FrostedSurface.dark(a)?"system_on_surface_dark":"system_on_surface_light",FrostedSurface.dark(a)?0xffeee9f1:0xff202027));label.setSingleLine(true);view.addView(label);
            view.setContentDescription("Drag here to add to home screen");view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            Object params=LauncherFeatures.call(layer,"generateLayoutParams",new FrameLayout.LayoutParams(width,height));
            LauncherFeatures.set(params,"customPosition",true);LauncherFeatures.set(params,"ignoreInsets",true);LauncherFeatures.set(params,"x",x);LauncherFeatures.set(params,"y",y);
            layer.addView(view,(ViewGroup.LayoutParams)params);
            owner=a;pill=view;hover=0;intro=true;
            view.setAlpha(0);view.setTranslationY(-height-FrostedSurface.dp(a,26));view.setScaleX(.96f);view.setScaleY(.96f);
            view.animate().translationY(0).scaleX(1).scaleY(1).alpha(1).setDuration(260).setInterpolator(new OvershootInterpolator(.65f)).withEndAction(new Runnable(){public void run(){if(pill==view){intro=false;motion();}}}).start();
        }catch(Throwable error){Module.log("Drawer home target unavailable",error);hide();}
    }
    static boolean update(int x,int y,ViewGroup layer){
        LinearLayout view=pill;if(view==null)return false;
        view.getLocationOnScreen(position);layer.getLocationOnScreen(origin);bounds.set(position[0],position[1],position[0]+view.getWidth(),position[1]+view.getHeight());
        int rawX=x+origin[0],rawY=y+origin[1];boolean inside=bounds.contains(rawX,rawY);
        int pad=FrostedSurface.dp(view.getContext(),42);boolean near=rawX>bounds.left-pad&&rawX<bounds.right+pad&&rawY>bounds.top-pad&&rawY<bounds.bottom+pad;
        int next=inside?2:near?1:0;
        if(next!=hover){hover=next;background.setStroke(FrostedSurface.dp(view.getContext(),1),next>0?0x66ffffff:0x25ffffff);if(!intro)motion();}
        if(inside)view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        return inside;
    }
    static void motion(){LinearLayout view=pill;if(view==null)return;view.animate().scaleX(hover>0?1.035f:1).scaleY(hover>0?1.035f:1).translationY(hover>0?-FrostedSurface.dp(view.getContext(),3):0).setDuration(110).setInterpolator(new DecelerateInterpolator()).start();}
    static void release(){if(!DrawerDrag.active())hide();}
    static void hide(){
        final LinearLayout old=pill;final Activity a=owner;pill=null;owner=null;background=null;hover=-1;intro=false;
        if(old!=null){old.animate().cancel();old.animate().alpha(0).translationY(-FrostedSurface.dp(old.getContext(),12)).setDuration(120).withEndAction(new Runnable(){public void run(){if(old.getParent() instanceof ViewGroup)((ViewGroup)old.getParent()).removeView(old);}}).start();}
    }
}
