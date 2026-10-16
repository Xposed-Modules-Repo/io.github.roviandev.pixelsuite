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
import android.view.ViewGroup;
/** A dropdown below the folder footer, with matching tint and native window blur. */
final class FolderOptions {
    static void show(final Object folder,final View anchor){
        Activity a=LauncherFeatures.activity.get();if(a==null)return;
        final Context c=anchor.getContext();final Object info=LauncherFeatures.get(folder,"mInfo");
        final LinearLayout card=new LinearLayout(c);card.setOrientation(1);card.setPadding(0,FrostedSurface.dp(c,6),0,FrostedSurface.dp(c,6));
        final View panel=(View)folder;final float originalY=panel.getTranslationY();
        final ScrollView dropdown=new ScrollView(c);dropdown.addView(card);dropdown.setVerticalScrollBarEnabled(false);dropdown.setOverScrollMode(View.OVER_SCROLL_NEVER);dropdown.setBackground(FrostedSurface.windowDrawable(c,20));dropdown.setClipToOutline(true);
        // preview69: the window is sized for the open Sort choices from the start and the card grows inside it, so opening
        // Sort never resizes or moves the window (that made it jump up over the folder). The empty part is see-through and
        // dismisses like a tap outside.
        final FrameLayout root=new FrameLayout(c);root.addView(dropdown,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        final boolean[] closingAction=new boolean[]{false};
        final PopupWindow window=new PopupWindow(root,FrostedSurface.dp(c,164),-2,true);
        root.setOnClickListener(new View.OnClickListener(){public void onClick(View v){window.dismiss();}});
        window.setBackgroundDrawable(new ColorDrawable(0));window.setOutsideTouchable(true);window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        dropdown.setElevation(FrostedSurface.dp(c,8));
        final View.OnAttachStateChangeListener lifecycle=new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){window.dismiss();}};
        anchor.addOnAttachStateChangeListener(lifecycle);window.setOnDismissListener(new PopupWindow.OnDismissListener(){public void onDismiss(){
            anchor.removeOnAttachStateChangeListener(lifecycle);panel.animate().cancel();
            if(closingAction[0]||!panel.isAttachedToWindow()||panel.getTranslationY()==originalY){panel.setTranslationY(originalY);panel.animate().setUpdateListener(null);}
            // The listener that redraws behind the lifted folder is dropped when it is back: left on the panel's animator it
            // kept invalidating the whole drag layer during later animations of the folder (the small stutter on exit).
            else panel.animate().translationY(originalY).setDuration(150).withEndAction(new Runnable(){public void run(){panel.animate().setUpdateListener(null);}}).start();
        }});
        // Add apps: drawer folders and (from preview28) home screen folders, with the searchable picker.
        row(card,"Add apps",7,false,new Runnable(){public void run(){closingAction[0]=true;window.dismiss();LauncherFeatures.afterClose(folder,new Runnable(){public void run(){if(DrawerFolders.isFolder(info))DrawerFolders.chooseApps(info);else HomeFolderApps.choose(folder);}});}});
        // Sort: the same Auto (A-Z) / Custom choice as the held folder's menu, for drawer and home folders. The two choices
        // are built once and fold open under Sort; the arrow of the Sort icon turns up while they are open.
        final boolean drawer=DrawerFolders.isFolder(info);
        LauncherData.Folder f=drawer?DrawerFolders.record(info):null;
        final boolean auto=drawer?(f!=null&&f.sort==FolderSort.AUTO):FolderSort.homeMode(info)==FolderSort.AUTO;
        final int id=f==null?0:f.id;
        final boolean[] sortOpen=new boolean[]{false};
        final View[] choices=new View[2];final LauncherGlyph[] arrow=new LauncherGlyph[1];
        final android.animation.ValueAnimator[] fold=new android.animation.ValueAnimator[1];
        row(card,"Sort",9,false,new Runnable(){public void run(){
            sortOpen[0]=!sortOpen[0];
            if(arrow[0]!=null)arrow[0].flip(sortOpen[0]);
            final int full=FrostedSurface.dp(c,48);
            float from=choices[0].getLayoutParams().height/(float)full;
            if(fold[0]!=null)fold[0].cancel();
            android.animation.ValueAnimator anim=android.animation.ValueAnimator.ofFloat(from,sortOpen[0]?1f:0f);
            anim.setDuration(sortOpen[0]?260:200);anim.setInterpolator(new android.view.animation.PathInterpolator(.2f,0f,0f,1f));
            anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener(){public void onAnimationUpdate(android.animation.ValueAnimator va){
                float t=(Float)va.getAnimatedValue();
                for(int i=0;i<2;i++){
                    View r=choices[i];r.getLayoutParams().height=Math.max(0,Math.round(full*t));
                    // The second choice follows a little behind the first.
                    float shown=Math.max(0f,Math.min(1f,(t-.15f*i)/(1f-.15f*i)));
                    r.setAlpha(shown);r.setTranslationY(-FrostedSurface.dp(c,6)*(1f-shown));
                }
                card.requestLayout();
            }});
            fold[0]=anim;anim.start();
        }});
        try{arrow[0]=(LauncherGlyph)((ImageView)((ViewGroup)card.getChildAt(card.getChildCount()-1)).getChildAt(0)).getDrawable();}catch(Throwable ignored){}
        row(card,"Auto (A\u2013Z)",13,auto,new Runnable(){public void run(){
            closingAction[0]=!drawer;window.dismiss();if(auto)return;
            if(drawer)DrawerFolderMenu.confirm("Sort apps?","Sort the apps in this folder from A to Z?",new Runnable(){public void run(){
                DrawerFolders.reopenId=id;LauncherFeatures.afterClose(folder,new Runnable(){public void run(){DrawerFolders.sortFolder(id);}});
            }});
            else DrawerFolderMenu.confirm("Sort apps?","Sort the apps in this folder from A to Z?",new Runnable(){public void run(){FolderSort.sortHome(info);}});
        }});
        choices[0]=card.getChildAt(card.getChildCount()-1);
        row(card,"Custom",14,!auto,new Runnable(){public void run(){
            window.dismiss();if(!auto)return;
            if(drawer){DrawerFolders.reopenId=id;LauncherFeatures.afterClose(folder,new Runnable(){public void run(){FolderSort.drawerCustom(id);}});}
            else FolderSort.setHomeMode(info,FolderSort.CUSTOM);
        }});
        choices[1]=card.getChildAt(card.getChildCount()-1);
        for(View r:choices){
            r.getLayoutParams().height=0;r.setAlpha(0f);
            // Indented under Sort: icon and text move in together.
            ViewGroup g=(ViewGroup)r;for(int i=0;i<g.getChildCount();i++){View part=g.getChildAt(i);FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)part.getLayoutParams();if(lp.gravity!=(Gravity.RIGHT|Gravity.CENTER_VERTICAL))lp.leftMargin+=FrostedSurface.dp(c,14);}
        }
        for(final int n:new int[]{5,4,3})row(card,n==3?"3\u00d73 (Stock)":n+"\u00d7"+n,50+n,LauncherFeatures.gridFor(info)==n,new Runnable(){public void run(){
            closingAction[0]=true;window.dismiss();LauncherFeatures.afterClose(folder,new Runnable(){public void run(){
                if(DrawerFolders.isFolder(info))DrawerFolders.changeGrid(info,n);else LauncherFeatures.data.prefs.edit().putInt("grid:"+LauncherFeatures.get(info,"id"),n).apply();
                LauncherFeatures.call(LauncherFeatures.get(folder,"mFolderIcon"),"updatePreviewItems",false);
                LauncherFeatures.call(folder,"animateOpen",0,new java.util.ArrayList<Object>((java.util.List<?>)LauncherFeatures.get(info,"contents")));
            }});
        }});
        // No "Remove folder" here: removal is only in the held folder's menu, with a Yes/No confirmation.
        Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);int[] xy=new int[2];anchor.getLocationOnScreen(xy);
        // Room for every row, the Sort choices included, plus the shadow below the card.
        int width=Math.min(frame.width()-2*FrostedSurface.dp(c,12),FrostedSurface.dp(c,164*Math.max(1f,c.getResources().getConfiguration().fontScale))),height=FrostedSurface.dp(c,12+48*card.getChildCount()+12),gap=FrostedSurface.dp(c,12);
        window.setWidth(width);
        int left=Math.max(frame.left+gap,Math.min(xy[0]+anchor.getWidth()-width,frame.right-width-gap));
        int below=xy[1]+anchor.getHeight()+gap;int[] folderXY=new int[2];panel.getLocationOnScreen(folderXY);
        int needed=Math.max(0,below+height-frame.bottom),room=Math.max(0,folderXY[1]-frame.top-gap);
        int lift=Math.min(needed,room),top=below-lift;
        window.setHeight(Math.min(height,Math.max(FrostedSurface.dp(c,48),frame.bottom-top)));
        if(lift>0){
            panel.animate().setUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener(){public void onAnimationUpdate(android.animation.ValueAnimator animator){if(panel.getParent() instanceof View)((View)panel.getParent()).invalidate();}});
            panel.animate().translationY(originalY-lift).setDuration(150).start();
        }
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
