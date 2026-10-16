package io.github.pixelsuite;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import java.lang.reflect.Method;
final class FrostedSurface {
    static boolean dark(Context c){return (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    static int dp(Context c,float value){return Math.round(c.getResources().getDisplayMetrics().density*value);}
    static Context launcherContext(Context c){try{return c.getPackageName().equals("com.google.android.apps.nexuslauncher")?c:c.createPackageContext("com.google.android.apps.nexuslauncher",0);}catch(Throwable ignored){return c;}}
    static int resourceColor(Context c,String name,int fallback){Context launcher=launcherContext(c);int id=launcher.getResources().getIdentifier(name,"color","com.google.android.apps.nexuslauncher");try{return id==0?fallback:launcher.getResources().getColor(id,launcher.getTheme());}catch(Throwable ignored){return fallback;}}
    static boolean blurEnabled(Context c){
        Context launcher=launcherContext(c);int reduce=launcher.getResources().getIdentifier("reduce_workspace_blur_usage","bool","com.google.android.apps.nexuslauncher");
        if(reduce!=0&&launcher.getResources().getBoolean(reduce))return false;
        try{Object manager=c.getSystemService(Context.WINDOW_SERVICE);return (Boolean)manager.getClass().getMethod("isCrossWindowBlurEnabled").invoke(manager);}catch(Throwable ignored){return android.provider.Settings.Global.getInt(c.getContentResolver(),"disable_window_blurs",0)==0;}
    }
    static int fallback(Context c){return LauncherFeatures.surface(c,dark(c)?"system_accent2_800":"system_accent2_200",dark(c)?0xff2f3036:0xffc6c5cc);}
    static int composite(int fg,int bg){int fa=Color.alpha(fg),ba=Color.alpha(bg),a=255-((255-fa)*(255-ba)/255);if(a==0)return 0;return Color.argb(a,channel(Color.red(fg),fa,Color.red(bg),ba,a),channel(Color.green(fg),fa,Color.green(bg),ba,a),channel(Color.blue(fg),fa,Color.blue(bg),ba,a));}
    static int channel(int f,int fa,int b,int ba,int a){return ((255-fa)*(b*ba)+(f*255)*fa)/(a*255);}
    static int tone(Context c){
        if(!blurEnabled(c))return fallback(c);
        return composite(resourceColor(c,"blur_shade_panel_fg",dark(c)?0x522c303d:0x66dedee8),resourceColor(c,"blur_shade_panel_bg",dark(c)?0x520e0e0f:0x66f9f9fe));
    }
    static int blend(int color,int amount){int r=(Color.red(color)*(255-amount)+255*amount)/255,g=(Color.green(color)*(255-amount)+255*amount)/255,b=(Color.blue(color)*(255-amount)+255*amount)/255;return Color.rgb(r,g,b);}
    static int[] colors(Context c){int base=(tone(c)&0xffffff)|0xcc000000;if(!c.getPackageName().equals("com.google.android.apps.nexuslauncher"))base=LauncherFeatures.surface(c,dark(c)?"system_surface_container_high_dark":"system_surface_container_high_light",fallback(c));return new int[]{base,base};}
    static int[] folderColors(Context c){return colors(c);}
    static int closedFolderColor(Context c){return (blend(tone(c),dark(c)?44:8)&0xffffff)|0xeb000000;}
    static void popupWindow(View content,float radius){frost(content,radius,"Dropdown");}
    static void frost(View content,float radius,final String what){
        if(!content.isAttachedToWindow()){
            final float corners=radius;
            content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewDetachedFromWindow(View v){}public void onViewAttachedToWindow(final View v){v.removeOnAttachStateChangeListener(this);v.post(new Runnable(){public void run(){if(v.isAttachedToWindow())frost(v,corners,what);}});}});
            return;
        }
        try{
            Object root=LauncherFeatures.call(content,"getViewRootImpl");
            Drawable blur=(Drawable)LauncherFeatures.call(root,"createBackgroundBlurDrawable");
            LauncherFeatures.call(blur,"setBlurRadius",blurEnabled(content.getContext())?depthBlurRadius(content.getContext()):0);
            LauncherFeatures.call(blur,"setCornerRadius",(float)dp(content.getContext(),radius));
            try{LauncherFeatures.call(blur,"setColor",Color.TRANSPARENT);}catch(Throwable ignored){}
            content.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{blur,windowDrawable(content.getContext(),radius)}));
            final Drawable shown=blur;final View view=content;content.post(new Runnable(){public void run(){SuiteLog.log(what+" blur: bounds="+shown.getBounds().toShortString()+", view="+view.getWidth()+"x"+view.getHeight()+", radius="+depthBlurRadius(view.getContext()));}});
        }catch(Throwable unavailable){GradientDrawable bg=windowDrawable(content.getContext(),radius);bg.setColor(tone(content.getContext())|0xff000000);content.setBackground(bg);SuiteLog.log("Popup window blur unavailable",unavailable);}
    }
    static GradientDrawable drawable(Context c,float radius){
        return new BackdropSurface(c,radius);
    }
    static GradientDrawable windowDrawable(Context c,float radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors(c));d.setCornerRadius(dp(c,radius));return d;}
    static int depthBlurRadius(Context c){Context launcher=launcherContext(c);int id=launcher.getResources().getIdentifier("max_depth_blur_radius_enhanced","dimen","com.google.android.apps.nexuslauncher");return id==0?dp(c,30):launcher.getResources().getDimensionPixelSize(id);}
    static int blurRadius(Context c){Context launcher=launcherContext(c);int id=launcher.getResources().getIdentifier("max_depth_blur_radius_enhanced","dimen","com.google.android.apps.nexuslauncher");return 3*(id==0?dp(c,30):launcher.getResources().getDimensionPixelSize(id));}
    static void opaqueFallback(View view){opaqueDrawable(view.getBackground(),tone(view.getContext())|0xff000000);}
    static void opaqueDrawable(Drawable drawable,int color){if(drawable instanceof GradientDrawable)((GradientDrawable)drawable).setColor(color);else if(drawable instanceof android.graphics.drawable.LayerDrawable){android.graphics.drawable.LayerDrawable layers=(android.graphics.drawable.LayerDrawable)drawable;for(int i=0;i<layers.getNumberOfLayers();i++)if(layers.getId(i)!=android.R.id.mask)opaqueDrawable(layers.getDrawable(i),color);}}
    static AlertDialog show(AlertDialog.Builder builder){return show(builder.create());}
    static AlertDialog show(AlertDialog dialog){
        dialog.setCancelable(true);dialog.setCanceledOnTouchOutside(true);
        dialog.show();style(dialog);track(dialog);return dialog;
    }
    static final java.util.Map<AlertDialog,Boolean> openDialogs=new java.util.WeakHashMap<AlertDialog,Boolean>();
    static void track(final AlertDialog dialog){
        openDialogs.put(dialog,Boolean.TRUE);
        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener(){public boolean onKey(android.content.DialogInterface d,int code,android.view.KeyEvent e){
            if(code!=android.view.KeyEvent.KEYCODE_BACK||e.getAction()!=android.view.KeyEvent.ACTION_UP||e.isCanceled())return false;
            dialog.cancel();return true;
        }});
        try{
            Window window=dialog.getWindow();if(window==null)return;
            final Object dispatcher=Window.class.getMethod("getOnBackInvokedDispatcher").invoke(window);
            final Class<?> callbackType=Class.forName("android.window.OnBackInvokedCallback");
            final Class<?> dispatcherType=Class.forName("android.window.OnBackInvokedDispatcher");
            final Object callback=java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},new java.lang.reflect.InvocationHandler(){
                public Object invoke(Object self,Method m,Object[] args){
                    if(m.getName().equals("onBackInvoked")){if(dialog.isShowing())dialog.cancel();return null;}
                    if(m.getName().equals("hashCode"))return System.identityHashCode(self);
                    if(m.getName().equals("equals"))return self==args[0];
                    if(m.getName().equals("toString"))return "PixelSuiteDialogBack";
                    return null;
                }
            });
            final Method register=dispatcherType.getMethod("registerOnBackInvokedCallback",int.class,callbackType);
            final Method unregister=dispatcherType.getMethod("unregisterOnBackInvokedCallback",callbackType);
            register.invoke(dispatcher,1000000,callback);
            window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                public void onViewAttachedToWindow(View v){}
                public void onViewDetachedFromWindow(View v){try{unregister.invoke(dispatcher,callback);}catch(Throwable ignored){}}
            });
        }catch(Throwable olderAndroid){}
    }
    static boolean dismissOpenDialogs(){
        boolean any=false;
        for(AlertDialog d:new java.util.ArrayList<AlertDialog>(openDialogs.keySet())){
            if(d!=null&&d.isShowing()){any=true;try{d.cancel();}catch(Throwable ignored){}}
        }
        openDialogs.clear();return any;
    }
    static void style(AlertDialog dialog){
        Window window=dialog.getWindow();if(window==null)return;Context c=dialog.getContext();window.setDimAmount(dark(c)?.18f:.10f);
        View panel=window.getDecorView().findViewById(android.R.id.content);
        if(!blurEnabled(c)||panel==null){GradientDrawable tint=windowDrawable(c,28);tint.setColor(tone(c)|0xff000000);window.setBackgroundDrawable(tint);return;}
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        final float corners=dp(c,28);
        panel.setOutlineProvider(new android.view.ViewOutlineProvider(){public void getOutline(View v,android.graphics.Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),corners);}});
        panel.setClipToOutline(true);
        frost(panel,28,"Dialog");
    }
    static void popup(ViewGroup popup){
        if(!LauncherFeatures.frosted())return;
        BackdropSurface background=new BackdropSurface(popup.getContext(),24);background.bind(popup);popup.setBackground(background);
        clearRows(popup,LauncherFeatures.get(popup,"mIterateChildrenTag"));
        try{View arrow=(View)LauncherFeatures.get(popup,"mArrow");if(arrow.getBackground()!=null)arrow.getBackground().setColorFilter(colors(popup.getContext())[0],PorterDuff.Mode.SRC_IN);}catch(Throwable ignored){}
    }
    static void clearRows(ViewGroup parent,Object wrapperTag){
        for(int i=0;i<parent.getChildCount();i++){
            View view=parent.getChildAt(i);
            boolean shortcut=LauncherFeatures.type("shortcuts.DeepShortcutView").isInstance(view);
            Object tag=view.getTag();boolean wrapper=tag!=null && tag.equals(wrapperTag);
            if(shortcut||wrapper){Drawable d=view.getBackground();if(d instanceof RippleDrawable){RippleDrawable r=(RippleDrawable)d;for(int j=0;j<r.getNumberOfLayers();j++)if(r.getId(j)!=android.R.id.mask)r.getDrawable(j).setAlpha(0);}else if(d!=null)d.setAlpha(0);}
            if(view instanceof ViewGroup)clearRows((ViewGroup)view,wrapperTag);
        }
    }
}
