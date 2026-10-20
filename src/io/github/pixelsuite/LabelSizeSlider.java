package io.github.pixelsuite;

import android.content.Context;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.SeekBar;

/** Five positions, aligned before native geometry is calculated; no inherited progress artwork. */
final class LabelSizeSlider extends SeekBar {
    private final int stops;
    LabelSizeSlider(Context c,int accent,int track){this(c,accent,track,5);}
    /** build 190: the same slider with any number of stops (the Themes surface-style slider has three). */
    LabelSizeSlider(Context c,int accent,int track,int stops){
        super(c);this.stops=stops;setMax(stops-1);setSplitTrack(false);
        GradientDrawable base=rounded(track,3),fill=rounded(accent,3);
        ClipDrawable clipped=new ClipDrawable(fill,Gravity.LEFT,ClipDrawable.HORIZONTAL);
        LayerDrawable layers=new LayerDrawable(new Drawable[]{base,clipped});
        layers.setId(0,android.R.id.background);layers.setId(1,android.R.id.progress);
        for(int i=0;i<2;i++){layers.setLayerGravity(i,Gravity.CENTER_VERTICAL|Gravity.FILL_HORIZONTAL);layers.setLayerHeight(i,dp(3));}
        setProgressDrawable(layers);
        GradientDrawable thumb=new GradientDrawable();thumb.setShape(GradientDrawable.OVAL);thumb.setColor(accent);thumb.setSize(dp(20),dp(20));
        setThumb(thumb);setThumbOffset(dp(10));setBackground(null);
        // Modern platform themes may add end/tick decorations independently of the drawable.
        try{LauncherFeatures.call(this,"setTickMark",new Object[]{null});}catch(Throwable older){}
        // Matching circular stops for Themes and font size; the thumb covers the chosen dot.
        if(stops>=2){
            GradientDrawable ticks=new GradientDrawable();ticks.setShape(GradientDrawable.OVAL);ticks.setColor(track);ticks.setSize(dp(8),dp(8));
            try{LauncherFeatures.call(this,"setTickMark",new Object[]{ticks});}catch(Throwable older){}
        }
        setProgress(Math.min(2,stops-1));
    }
    private GradientDrawable rounded(int color,int height){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(height));d.setSize(dp(1),dp(height));return d;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
        // Each thumb stop is centered above its corresponding equal-width caption column.
        int side=w/(2*stops);if(getPaddingLeft()!=side||getPaddingRight()!=side)setPadding(side,getPaddingTop(),side,getPaddingBottom());
        super.onSizeChanged(w,h,oldW,oldH);
    }
}
