package io.github.pixelsuite;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Drawer-only compact actions with a reserved area for the drag-to-home pill. */
final class DrawerPopupLayout {
    static boolean eligible(Object popup){
        if(!LauncherFeatures.type("popup.PopupContainer").isInstance(popup))return false;
        Object info=LauncherFeatures.get(popup,"itemInfo");
        if(LauncherFeatures.privateItem(info))return false;
        if(LauncherFeatures.type("model.data.AppInfo").isInstance(info)||DrawerFolders.isDrawerMember(info))return true;
        // Predicted drawer apps use a different item type; they still need the same
        // sizing and reserved pill area. Keep home-screen menus out of this path.
        android.app.Activity a=LauncherFeatures.activity.get();if(a==null)return false;
        View drawer=(View)LauncherFeatures.call(a,"getAppsView");
        return drawer.isShown()&&drawer.getAlpha()>0f&&drawer.getLocalVisibleRect(new Rect());
    }
    static int floor(ViewGroup layer){
        View pill=DrawerHomeTarget.pill;
        if(pill!=null&&pill.getParent()==layer){
            int y=((Number)LauncherFeatures.get(pill.getLayoutParams(),"y")).intValue();
            return y+pill.getLayoutParams().height+FrostedSurface.dp(layer.getContext(),12);
        }
        // The pill is not on screen yet while the menu is measured: reserve the same band it will take (the search
        // bar's row), so the menu is not sized for one ceiling and then shown under another.
        try{
            android.app.Activity a=LauncherFeatures.activity.get();
            View search=(View)LauncherFeatures.get(LauncherFeatures.call(a,"getAppsView"),"mSearchContainer");
            if(search.getHeight()>0){
                int[] at=new int[2],origin=new int[2];search.getLocationOnScreen(at);layer.getLocationOnScreen(origin);
                int height=FrostedSurface.dp(layer.getContext(),54);
                return at[1]-origin[1]+(search.getHeight()-height)/2+height+FrostedSurface.dp(layer.getContext(),12);
            }
        }catch(Throwable ignored){}
        Rect insets=(Rect)LauncherFeatures.call(layer,"getInsets");
        return insets.top+FrostedSurface.dp(layer.getContext(),12);
    }
    static void compact(final ViewGroup popup){
        if(!eligible(popup)||Xp.getAdditionalInstanceField(popup,"pixelsuite_compact_menu")!=null)return;
        final ViewGroup layer=(ViewGroup)LauncherFeatures.call(LauncherFeatures.activity.get(),"getDragLayer");
        int width=Math.min(popup.getMeasuredWidth(),FrostedSurface.dp(popup.getContext(),240));
        if(width<=0)return;
        compactRows(popup,width);
        final LinearLayout content=new LinearLayout(popup.getContext());content.setOrientation(1);
        Object wrapper=LauncherFeatures.get(popup,"mIterateChildrenTag");content.setTag(wrapper);
        while(popup.getChildCount()>0){View child=popup.getChildAt(0);popup.removeViewAt(0);content.addView(child);}
        ScrollView scroll=new ScrollView(popup.getContext()){
            protected void onMeasure(int w,int h){
                // Natural height first, then cap it to the room at the menu's place (under or over the icon).
                fitRows(content,FrostedSurface.dp(getContext(),ROW_DP));
                super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                int chrome=popup.getPaddingTop()+popup.getPaddingBottom();
                int natural=getMeasuredHeight()+chrome;
                // preview64: a menu with one row too many for either side of the held icon (apps that add "Bubble",
                // often with "Pause app" too) used to scroll inside a list that did not clip, so the last row (Rename)
                // hung out below the panel. Tighten the rows until the whole menu fits on one side instead.
                int side=sideRoom(popup,layer,natural);
                if(side>0&&natural>side){
                    int rows=countRows(content);
                    if(rows>0){
                        int row=FrostedSurface.dp(getContext(),ROW_DP),fixed=getMeasuredHeight()-rows*row;
                        int fit=Math.max(FrostedSurface.dp(getContext(),MIN_ROW_DP),(side-chrome-fixed)/rows);
                        if(fit<row){
                            fitRows(content,fit);
                            super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                            natural=getMeasuredHeight()+chrome;
                        }
                    }
                }
                int[] slot=slot(popup,layer,natural);
                int available;
                if(slot!=null)available=slot[1];
                else{Rect insets=(Rect)LauncherFeatures.call(layer,"getInsets");available=layer.getHeight()-insets.bottom-floor(layer)-FrostedSurface.dp(getContext(),12);}
                int room=available>=natural?natural-chrome:Math.max(1,(int)(available/1.03f)-chrome);
                super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(room,View.MeasureSpec.AT_MOST));
            }
        };
        // If the menu ever does have to scroll, the rows must stay inside the panel.
        scroll.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);scroll.setClipToOutline(true);
        scroll.setTag(wrapper);scroll.setFillViewport(false);scroll.setHorizontalScrollBarEnabled(false);
        scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);   // no scroll indicator on the menu
        // build 205 (owner: with app shortcuts shown, a long menu in a drawer folder ran off the screen): the menu is
        // one panel that scrolls when it is taller than the room; its edges fade where rows continue, so it reads as
        // scrollable instead of cut off.
        scroll.setVerticalFadingEdgeEnabled(true);scroll.setFadingEdgeLength(FrostedSurface.dp(popup.getContext(),28));
        scroll.addView(content,new ScrollView.LayoutParams(width,-2));
        popup.addView(scroll,new LinearLayout.LayoutParams(width,-2));
        Xp.setAdditionalInstanceField(popup,"pixelsuite_compact_menu",Boolean.TRUE);
        popup.measure(0,0);place(popup);
        // ArrowPopup has no onLayout of its own to hook, so place the menu after every layout of it.
        popup.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){
            public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){place(popup);}
        });
    }
    private static final int ROW_DP=48,MIN_ROW_DP=38;
    /** Menu rows (stock and module ones are all DeepShortcutView). */
    static int countRows(View v){
        if(LauncherFeatures.type("shortcuts.DeepShortcutView").isInstance(v))return v.getVisibility()==View.GONE?0:1;
        int n=0;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)n+=countRows(g.getChildAt(i));}
        return n;
    }
    /** Sets every row's height without a layout request (called while measuring). */
    static void fitRows(View v,int height){
        if(LauncherFeatures.type("shortcuts.DeepShortcutView").isInstance(v)){
            ViewGroup.LayoutParams p=v.getLayoutParams();if(p!=null&&p.height!=height)p.height=height;
            if(v.getMinimumHeight()>height)v.setMinimumHeight(height);
            return;
        }
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)fitRows(g.getChildAt(i),height);}
    }
    /** The larger room on one side of the held icon (what the menu gets without covering it), or -1. */
    static int sideRoom(ViewGroup popup,ViewGroup layer,int menuHeight){
        Rect icon=iconRect(popup,layer);if(icon==null)return -1;
        Rect insets=(Rect)LauncherFeatures.call(layer,"getInsets");
        int gap=FrostedSurface.dp(popup.getContext(),8),bottom=layer.getHeight()-insets.bottom-FrostedSurface.dp(popup.getContext(),12);
        int ceiling=floor(layer);
        int below=bottom-Math.max(icon.bottom+gap,ceiling);
        int above=icon.top-gap-ceiling-(int)Math.ceil(menuHeight*.03f);
        return Math.max(below,above);
    }
    static void compactRows(View view,int width){
        if(LauncherFeatures.type("shortcuts.DeepShortcutView").isInstance(view)){
            ViewGroup.LayoutParams p=view.getLayoutParams();p.height=FrostedSurface.dp(view.getContext(),48);p.width=width;view.setLayoutParams(p);
            view.setMinimumHeight(p.height);view.setPadding(view.getPaddingLeft(),0,view.getPaddingRight(),0);
            TextView label=(TextView)LauncherFeatures.get(view,"mBubbleText");label.setTextSize(14);
            View icon=(View)LauncherFeatures.get(view,"mIconView");ViewGroup.LayoutParams ip=icon.getLayoutParams();ip.width=ip.height=FrostedSurface.dp(view.getContext(),20);icon.setLayoutParams(ip);
            return;
        }
        if(view instanceof ViewGroup){ViewGroup.LayoutParams p=view.getLayoutParams();if(p!=null&&p.width>width){p.width=width;view.setLayoutParams(p);}ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)compactRows(group.getChildAt(i),width);}
    }
    /** The held icon's visible (transformed, scrolled) bounds in DragLayer coordinates, or null. */
    static Rect iconRect(ViewGroup popup,ViewGroup layer){
        try{
            View icon=(View)LauncherFeatures.get(popup,"originalView");
            // A drawer-folder member: place by the held icon's real on-screen position (the folder is
            // shifted and scaled, and the menu used to land at the folder's bottom whatever icon was held).
            if(DrawerFolders.isDrawerMember(LauncherFeatures.get(popup,"itemInfo"))){
                View held=DrawerFolders.heldView.get();
                if(held!=null&&held.isAttachedToWindow()&&held.getWidth()>0){
                    int[] a=new int[2],o=new int[2];held.getLocationOnScreen(a);layer.getLocationOnScreen(o);
                    Rect r=new Rect(a[0]-o[0],a[1]-o[1],a[0]-o[0]+Math.round(held.getWidth()*held.getScaleX()*scaleOf(held)),a[1]-o[1]+Math.round(held.getHeight()*held.getScaleY()*scaleOf(held)));
                    if(!r.isEmpty()){
                        if(icon!=null&&icon!=held)LauncherFeatures.logOnce("Drawer folder menu: originalView differs from the held icon",new IllegalStateException(String.valueOf(icon.getClass().getName())));
                        return r;
                    }
                }
            }
            if(icon==null||!icon.isAttachedToWindow())return null;
            Rect r=new Rect();
            try{LauncherFeatures.call(layer,"getDescendantRectRelativeToSelf",icon,r);}
            catch(Throwable noHelper){
                int[] a=new int[2],b=new int[2];icon.getLocationOnScreen(a);layer.getLocationOnScreen(b);
                r.set(a[0]-b[0],a[1]-b[1],a[0]-b[0]+Math.round(icon.getWidth()*icon.getScaleX()),a[1]-b[1]+Math.round(icon.getHeight()*icon.getScaleY()));
            }
            return r.isEmpty()?null:r;
        }catch(Throwable t){return null;}
    }

    /** Scale a parent (the open folder) applies to the icon, so the on-screen rect uses real pixels. */
    private static float scaleOf(View v){
        float s=1f;
        for(android.view.ViewParent p=v.getParent();p instanceof View;p=p.getParent()){View pv=(View)p;if(pv.getScaleX()>0)s*=pv.getScaleX();}
        return s;
    }

    /**
     * Where the menu goes: {top, room}. Directly under the held icon with a small gap. Only when
     * fewer than three rows fit below and there is more room above does it sit directly above
     * the icon instead. Never above the pill's reserved area; the menu scrolls if it is taller
     * than the room.
     */
    static int[] slot(ViewGroup popup,ViewGroup layer,int menuHeight){
        Rect icon=iconRect(popup,layer);if(icon==null)return null;
        Rect insets=(Rect)LauncherFeatures.call(layer,"getInsets");
        int gap=FrostedSurface.dp(popup.getContext(),8),bottom=layer.getHeight()-insets.bottom-FrostedSurface.dp(popup.getContext(),12);
        int ceiling=floor(layer);
        int below=Math.max(icon.bottom+gap,ceiling),roomBelow=bottom-below;
        int roomAbove=icon.top-gap-ceiling-(int)Math.ceil(menuHeight*.03f);   // the menu's 1.02 opening overshoot
        // preview46: every row must be reachable without scrolling. Below the icon when the whole
        // menu fits there, else above it when the whole menu fits there (a held app on the last
        // row used to get its bottom rows cut off). Only when neither fits does the menu take the
        // larger side and scroll.
        if(roomBelow>=menuHeight)return new int[]{below,roomBelow};
        if(roomAbove>=menuHeight)return new int[]{icon.top-gap-menuHeight,roomAbove};
        // preview64: still too tall for either side even with tightened rows: keep every row on screen and let the
        // menu cover part of the icon, rather than scrolling.
        int overshoot=(int)Math.ceil(menuHeight*.03f);
        if(bottom-ceiling-overshoot>=menuHeight){
            int top=roomBelow>=roomAbove?bottom-menuHeight:ceiling+overshoot;
            return new int[]{top,menuHeight};
        }
        if(roomBelow>=roomAbove)return new int[]{below,roomBelow};
        return new int[]{icon.top-gap-roomAbove,roomAbove};
    }

    static void place(ViewGroup popup){
        try{placeNow(popup);}catch(Throwable error){LauncherFeatures.logOnce("Drawer menu placement failed",error);}
    }
    private static void placeNow(ViewGroup popup){
        if(!eligible(popup)||!(popup.getParent() instanceof ViewGroup))return;
        ViewGroup layer=(ViewGroup)popup.getParent();Rect insets=(Rect)LauncherFeatures.call(layer,"getInsets");
        float y;
        int[] slot=slot(popup,layer,popup.getMeasuredHeight());
        if(slot!=null)y=slot[0];
        else{
            int minimum=floor(layer)+(int)Math.ceil(popup.getMeasuredHeight()*.03f),bottom=layer.getHeight()-insets.bottom-FrostedSurface.dp(popup.getContext(),12);
            y=Math.max(minimum,Math.min(popup.getY(),bottom-popup.getMeasuredHeight()));
        }
        if(Math.abs(y-popup.getY())>.5f){
            popup.setY(y);
            View arrow=(View)LauncherFeatures.get(popup,"mArrow");if(arrow!=null)arrow.setVisibility(View.INVISIBLE);
        }
    }
}
