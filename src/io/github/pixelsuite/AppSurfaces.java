package io.github.pixelsuite;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** App-only surfaces. Dynamic text/accent colors are untouched; no blur, timers or polling. */
final class AppSurfaces {
    private AppSurfaces() {}
    private static volatile boolean loaded, superDark;
    static boolean enabled(boolean dark,boolean setting){return dark&&setting;}
    static void refresh(Context c){superDark=Feature.on(c,SuperDark.KEY);loaded=true;}
    static boolean active(Context c){if(!loaded)refresh(c);return enabled(FrostedSurface.dark(c),superDark);}
    static int normalCard(Context c){return LauncherFeatures.surface(c,FrostedSurface.dark(c)?"system_surface_container_high_dark":"system_surface_bright_light",FrostedSurface.dark(c)?0xff2b2930:0xfffef7ff);}
    static GradientDrawable card(Context c,float radius){return new Surface(c,radius,0xff0b0b0b,normalCard(c),false);}
    static GradientDrawable menu(Context c,float radius){return new Surface(c,radius,0xff0a0a0a,FrostedSurface.dark(c)?0xff202024:0xfff4f1f5,true);}
    static GradientDrawable field(Context c,float radius){return new Surface(c,radius,0xff1a1a1a,normalCard(c),false);}
    static GradientDrawable dialog(Context c,float radius){return new Surface(c,radius,0xff0e0e0e,normalCard(c),true);}
    static Drawable support(Context c,Drawable normal,float radius){return new SwitchSurface(c,normal,new Surface(c,radius,0xff0b0b0b,normalCard(c),false));}
    static Drawable pill(Context c,Drawable normal){return new SwitchSurface(c,normal,new Surface(c,24,0xff000000,0xff000000,true));}
    static void invalidateTree(View v){if(v.getBackground()!=null)v.getBackground().invalidateSelf();v.invalidate();if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++)invalidateTree(g.getChildAt(i));}}
    private static final class Surface extends GradientDrawable {
        final Context context;final int black,normal;final float radius;final boolean floating;
        final Paint rim=new Paint(Paint.ANTI_ALIAS_FLAG);int last;final RectF frame=new RectF();
        Surface(Context c,float radius,int black,int normal,boolean floating){context=c;this.radius=radius*c.getResources().getDisplayMetrics().density;this.black=black;this.normal=normal;this.floating=floating;last=active(c)?black:normal;setColor(last);setCornerRadius(this.radius);float d=c.getResources().getDisplayMetrics().density;rim.setStyle(Paint.Style.STROKE);rim.setStrokeWidth(2.4f*d);rim.setMaskFilter(new BlurMaskFilter(2*d,BlurMaskFilter.Blur.NORMAL));}
        @Override protected void onBoundsChange(Rect bounds){super.onBoundsChange(bounds);frame.set(bounds);float inset=2.4f*context.getResources().getDisplayMetrics().density;frame.inset(inset,inset);rim.setShader(new LinearGradient(0,frame.top,0,Math.max(frame.top+1,frame.bottom),new int[]{0x30ffffff,0x12ffffff,0x0cffffff,0x16ffffff},new float[]{0,.35f,.75f,1},Shader.TileMode.CLAMP));}
        @Override public void draw(Canvas canvas){boolean on=active(context);int color=on?black:normal;if(last!=color){last=color;super.setColor(color);}super.draw(canvas);if(on&&floating){float inset=2.4f*context.getResources().getDisplayMetrics().density;canvas.drawRoundRect(frame,Math.max(0,radius-inset),Math.max(0,radius-inset),rim);}}

    }
    private static final class SwitchSurface extends Drawable {
        final Context context;final Drawable normal,black;
        SwitchSurface(Context c,Drawable normal,Drawable black){context=c;this.normal=normal;this.black=black;}
        private Drawable shown(){return active(context)?black:normal;}
        @Override protected void onBoundsChange(Rect b){normal.setBounds(b);black.setBounds(b);}
        @Override public void draw(Canvas c){shown().draw(c);}
        @Override public void setAlpha(int alpha){normal.setAlpha(alpha);black.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){normal.setColorFilter(filter);black.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
        @Override public void getOutline(Outline outline){shown().getOutline(outline);}
        @Override public boolean isStateful(){return normal.isStateful();}
        @Override protected boolean onStateChange(int[] state){return normal.setState(state);}
    }
}
