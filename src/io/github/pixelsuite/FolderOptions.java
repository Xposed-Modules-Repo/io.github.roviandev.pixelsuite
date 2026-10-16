package io.github.pixelsuite;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.FrameLayout;
final class FolderOptions {
    static void show(final Object folder,final View anchor){
        Activity a=LauncherFeatures.activity.get();if(a==null)return;
        Context c=anchor.getContext();final Object info=LauncherFeatures.get(folder,"mInfo");
        final LinearLayout card=new LinearLayout(c);card.setOrientation(1);card.setPadding(0,FrostedSurface.dp(c,6),0,FrostedSurface.dp(c,6));
        final View panel=(View)folder;final float originalY=panel.getTranslationY();
        final ScrollView dropdown=new ScrollView(c);dropdown.addView(card);dropdown.setBackground(FrostedSurface.windowDrawable(c,20));dropdown.setClipToOutline(true);
        final boolean[] closingAction=new boolean[]{false};
        final PopupWindow window=new PopupWindow(dropdown,FrostedSurface.dp(c,164),-2,true);
        window.setBackgroundDrawable(new ColorDrawable(0));window.setOutsideTouchable(true);window.setElevation(FrostedSurface.dp(c,8));window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        final View.OnAttachStateChangeListener lifecycle=new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){window.dismiss();}};
        anchor.addOnAttachStateChangeListener(lifecycle);window.setOnDismissListener(new PopupWindow.OnDismissListener(){public void onDismiss(){anchor.removeOnAttachStateChangeListener(lifecycle);panel.animate().cancel();if(closingAction[0]||!panel.isAttachedToWindow())panel.setTranslationY(originalY);else panel.animate().translationY(originalY).setDuration(150).start();}});
        row(card,"Add apps",7,false,new Runnable(){public void run(){closingAction[0]=true;window.dismiss();LauncherFeatures.afterClose(folder,new Runnable(){public void run(){if(DrawerFolders.isFolder(info))DrawerFolders.chooseApps(info);else HomeFolderApps.choose(folder);}});}});
        for(final int n:new int[]{5,4,3})row(card,n==3?"3\u00d73 (Stock)":n+"\u00d7"+n,50+n,LauncherFeatures.gridFor(info)==n,new Runnable(){public void run(){
            closingAction[0]=true;window.dismiss();LauncherFeatures.afterClose(folder,new Runnable(){public void run(){
                if(DrawerFolders.isFolder(info))DrawerFolders.changeGrid(info,n);else LauncherFeatures.data.prefs.edit().putInt("grid:"+LauncherFeatures.get(info,"id"),n).apply();
                LauncherFeatures.call(LauncherFeatures.get(folder,"mFolderIcon"),"updatePreviewItems",false);
                LauncherFeatures.call(folder,"animateOpen",0,new java.util.ArrayList<Object>((java.util.List<?>)LauncherFeatures.get(info,"contents")));
            }});
        }});
        Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);int[] xy=new int[2];anchor.getLocationOnScreen(xy);
        int width=Math.min(frame.width()-2*FrostedSurface.dp(c,12),FrostedSurface.dp(c,164*Math.max(1f,c.getResources().getConfiguration().fontScale))),height=FrostedSurface.dp(c,12+48*card.getChildCount()),gap=FrostedSurface.dp(c,12);
        window.setWidth(width);
        int left=Math.max(frame.left+gap,Math.min(xy[0]+anchor.getWidth()-width,frame.right-width-gap));
        int below=xy[1]+anchor.getHeight()+gap;int[] folderXY=new int[2];panel.getLocationOnScreen(folderXY);
        int needed=Math.max(0,below+height+gap-frame.bottom),room=Math.max(0,folderXY[1]-frame.top-gap);
        int lift=Math.min(needed,room),top=below-lift;
        window.setHeight(Math.min(height,Math.max(FrostedSurface.dp(c,48),frame.bottom-gap-top)));
        panel.animate().setUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener(){public void onAnimationUpdate(android.animation.ValueAnimator animator){if(panel.getParent() instanceof View)((View)panel.getParent()).invalidate();}});
        if(lift>0)panel.animate().translationY(originalY-lift).setDuration(150).start();
        window.showAtLocation(anchor.getRootView(),Gravity.TOP|Gravity.LEFT,left,top);
        FrostedSurface.popupWindow(dropdown,20);
        dropdown.setAlpha(0);dropdown.setTranslationY(-FrostedSurface.dp(c,8));dropdown.animate().alpha(1).translationY(0).setDuration(150).start();
    }
    static void row(LinearLayout card,String label,int glyph,boolean checked,final Runnable action){
        Context c=card.getContext();FrameLayout row=new FrameLayout(c);
        ImageView icon=new ImageView(c);icon.setImageDrawable(new LauncherGlyph(c,glyph));FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(FrostedSurface.dp(c,20),FrostedSurface.dp(c,20),Gravity.LEFT|Gravity.CENTER_VERTICAL);ip.leftMargin=FrostedSurface.dp(c,12);row.addView(icon,ip);
        int color=LauncherFeatures.surface(c,FrostedSurface.dark(c)?"system_on_surface_dark":"system_on_surface_light",FrostedSurface.dark(c)?0xffeee9f1:0xff202027);
        TextView text=new TextView(c);text.setText(label);boolean grid=glyph>=53&&glyph<=55;text.setTextSize(grid?15:14);text.setTextColor(color);MenuFont.apply(text);text.setSingleLine(true);text.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,-1);tp.leftMargin=FrostedSurface.dp(c,42);tp.rightMargin=FrostedSurface.dp(c,42);row.addView(text,tp);
        TextView mark=new TextView(c);mark.setText(checked?"\u2713":"");mark.setTextSize(16);mark.setTextColor(color);mark.setGravity(Gravity.CENTER);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(FrostedSurface.dp(c,18),-1,Gravity.RIGHT|Gravity.CENTER_VERTICAL);mp.rightMargin=FrostedSurface.dp(c,24);row.addView(mark,mp);
        GradientDrawable mask=new GradientDrawable();mask.setColor(-1);row.setBackground(new RippleDrawable(ColorStateList.valueOf(FrostedSurface.dark(c)?0x22ffffff:0x14000000),null,mask));
        row.setClickable(true);row.setFocusable(true);row.setSelected(checked);row.setContentDescription(label+(checked?", selected":""));row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});
        card.addView(row,new LinearLayout.LayoutParams(-1,FrostedSurface.dp(c,48)));
    }
}
