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
/** A transient drawer drag target. No timers, sensors, root calls or wake locks. */
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
    static LinearLayout removePill;
    static GradientDrawable removeBackground;
    static int removeHover;
    /** The finger is over the Remove pill (only offered for apps held out of a drawer folder). */
    static boolean overRemove;
    static void arm(View source){
        if(!LauncherFeatures.on(LauncherFeatures.DRAG_PILL)||LauncherFeatures.privateItem(source.getTag()))return;
        final Activity a=LauncherFeatures.activity.get();if(a==null)return;
        if(owner==a&&pill!=null)return;hide();
        try{
            ViewGroup layer=(ViewGroup)LauncherFeatures.call(a,"getDragLayer");
            View search=(View)LauncherFeatures.get(LauncherFeatures.call(a,"getAppsView"),"mSearchContainer");
            if(search.getWidth()==0||search.getHeight()==0)return;
            // preview58: an app held out of a drawer folder also gets a Remove pill, to the right of Add to home screen.
            boolean remove=DrawerFolders.isDrawerMember(source.getTag())&&!DrawerFolders.isFolder(source.getTag());
            search.getLocationOnScreen(position);layer.getLocationOnScreen(origin);
            int height=FrostedSurface.dp(a,54);
            int total=remove?Math.min((int)(search.getWidth()*.94f),layer.getWidth()-FrostedSurface.dp(a,32)):Math.min((int)(search.getWidth()*.84f),layer.getWidth()-FrostedSurface.dp(a,48));
            int gap=FrostedSurface.dp(a,8);
            int addWidth=remove?Math.round((total-gap)*.6f):total,removeWidth=total-gap-addWidth;
            int x=position[0]-origin[0]+(search.getWidth()-total)/2;
            int y=position[1]-origin[1]+(search.getHeight()-height)/2;
            final LinearLayout view=makePill(a,"Add to home screen",3,remove,"Drag here to add to home screen");
            background=pillBackground;
            addPill(layer,view,addWidth,height,x,y);
            owner=a;pill=view;hover=0;intro=true;
            animateIn(view,height,true);
            if(remove){
                final LinearLayout second=makePill(a,"Remove",12,true,"Drag here to take out of the folder");
                removeBackground=pillBackground;
                addPill(layer,second,removeWidth,height,x+addWidth+gap,y);
                removePill=second;removeHover=0;
                animateIn(second,height,false);
            }
        }catch(Throwable error){Module.log("Drawer home target unavailable",error);hide();}
    }
    private static GradientDrawable pillBackground;
    private static LinearLayout makePill(Activity a,String text,int glyph,boolean compact,String description){
        final LinearLayout view=new LinearLayout(a);view.setGravity(Gravity.CENTER);view.setPadding(FrostedSurface.dp(a,compact?12:18),0,FrostedSurface.dp(a,compact?12:18),0);
        pillBackground=FrostedSurface.drawable(a,40);view.setBackground(pillBackground);view.setElevation(FrostedSurface.dp(a,9));
        ((BackdropSurface)pillBackground).bind(view);
        int iconSize=FrostedSurface.dp(a,compact?22:24);
        ImageView icon=new ImageView(a);icon.setImageDrawable(new LauncherGlyph(a,glyph));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(iconSize,iconSize);ip.rightMargin=FrostedSurface.dp(a,compact?7:10);view.addView(icon,ip);
        TextView label=new TextView(a);label.setText(text);label.setTextSize(compact?14.5f:16);label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        label.setTextColor(LauncherFeatures.surface(a,FrostedSurface.dark(a)?"system_on_surface_dark":"system_on_surface_light",FrostedSurface.dark(a)?0xffeee9f1:0xff202027));label.setSingleLine(true);view.addView(label);
        view.setContentDescription(description);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }
    private static void addPill(ViewGroup layer,View view,int width,int height,int x,int y)throws Exception{
        Object params=LauncherFeatures.call(layer,"generateLayoutParams",new FrameLayout.LayoutParams(width,height));
        LauncherFeatures.set(params,"customPosition",true);LauncherFeatures.set(params,"ignoreInsets",true);LauncherFeatures.set(params,"x",x);LauncherFeatures.set(params,"y",y);
        layer.addView(view,(ViewGroup.LayoutParams)params);
    }
    private static void animateIn(final View view,int height,final boolean first){
        Activity a=LauncherFeatures.activity.get();
        // No status-bar hiding: toggling it on long-press and again on finger-up changed the
        // window insets and relaid out the whole drawer exactly while the icon returned.
        // 2.0.4 (owner): the pill inflates like a balloon in place, instead of sliding and fading in.
        view.setTranslationY(0);
        Balloon.inflate(view,first?0:45,new Runnable(){public void run(){if(first&&pill==view){intro=false;motion();}}});
    }
    /**
     * Hover over the Remove pill; true while the held app touches it.
     * preview38 (owner: a pill lit up before it counted, so letting go there did nothing until the vibration came):
     * a pill now counts the moment the held app's icon touches it (the finger within half an icon of the pill), and
     * it lights up, vibrates and takes effect at that same moment. Between the two pills, the nearer one wins.
     */
    static boolean updateRemove(int x,int y,ViewGroup layer){
        LinearLayout view=removePill;if(view==null){overRemove=false;return false;}
        layer.getLocationOnScreen(origin);int rawX=x+origin[0],rawY=y+origin[1];
        float d=distance(view,rawX,rawY),other=pill==null?Float.MAX_VALUE:distance(pill,rawX,rawY);
        boolean inside=d<=reach(view)&&d<=other;
        int next=inside?2:0;
        if(next!=removeHover){removeHover=next;removeBackground.setStroke(FrostedSurface.dp(view.getContext(),1),next>0?0x66ffffff:0x25ffffff);
            view.animate().scaleX(next>0?1.035f:1).scaleY(next>0?1.035f:1).translationY(next>0?-FrostedSurface.dp(view.getContext(),3):0).setDuration(110).setInterpolator(new DecelerateInterpolator()).start();}
        if(inside&&!overRemove)view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        overRemove=inside;return inside;
    }
    static boolean update(int x,int y,ViewGroup layer){
        LinearLayout view=pill;if(view==null)return false;
        layer.getLocationOnScreen(origin);int rawX=x+origin[0],rawY=y+origin[1];
        float d=distance(view,rawX,rawY),other=removePill==null?Float.MAX_VALUE:distance(removePill,rawX,rawY);
        boolean inside=d<=reach(view)&&d<other;
        int next=inside?2:0;
        if(next!=hover){hover=next;background.setStroke(FrostedSurface.dp(view.getContext(),1),next>0?0x66ffffff:0x25ffffff);if(!intro)motion();}
        if(inside)view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        return inside;
    }
    /** How far the finger may be from a pill while the held icon already touches it: half an icon. */
    private static float reach(View v){return FrostedSurface.dp(v.getContext(),26);}
    /** Distance from a screen point to a pill's resting area (0 inside); its hover lift is left out, so it does not flicker. */
    private static float distance(View v,int rawX,int rawY){
        v.getLocationOnScreen(position);
        float tx=v.getTranslationX(),ty=v.getTranslationY();
        float left=position[0]-tx,top=position[1]-ty,right=left+v.getWidth(),bottom=top+v.getHeight();
        float dx=rawX<left?left-rawX:rawX>right?rawX-right:0,dy=rawY<top?top-rawY:rawY>bottom?rawY-bottom:0;
        return (float)Math.sqrt(dx*dx+dy*dy);
    }
    static void motion(){LinearLayout view=pill;if(view==null)return;view.animate().scaleX(hover>0?1.035f:1).scaleY(hover>0?1.035f:1).translationY(hover>0?-FrostedSurface.dp(view.getContext(),3):0).setDuration(110).setInterpolator(new DecelerateInterpolator()).start();}
    static void release(){if(!DrawerDrag.active())hide();}
    static void hide(){
        final LinearLayout second=removePill;removePill=null;removeBackground=null;removeHover=0;overRemove=false;
        if(second!=null)Balloon.deflate(second,0,new Runnable(){public void run(){if(second.getParent() instanceof ViewGroup)((ViewGroup)second.getParent()).removeView(second);}});
        final LinearLayout old=pill;final Activity a=owner;pill=null;owner=null;background=null;hover=-1;intro=false;
        if(old!=null)Balloon.deflate(old,25,new Runnable(){public void run(){if(old.getParent() instanceof ViewGroup)((ViewGroup)old.getParent()).removeView(old);}});
    }
}
