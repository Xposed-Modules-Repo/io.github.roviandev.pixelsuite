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
                // Natural height first, then cap it to the room at the menu's place (under the icon).
                super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                int natural=getMeasuredHeight()+popup.getPaddingTop()+popup.getPaddingBottom();
                int[] slot=slot(popup,layer,natural);
                int available;
                if(slot!=null)available=slot[1];
                else{Rect insets=(Rect)LauncherFeatures.call(layer,"getInsets");available=layer.getHeight()-insets.bottom-floor(layer)-FrostedSurface.dp(getContext(),12);}
                int room=Math.max(1,(int)(available/1.03f)-popup.getPaddingTop()-popup.getPaddingBottom());
                super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(room,View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setTag(wrapper);scroll.setFillViewport(false);scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(content,new ScrollView.LayoutParams(width,-2));
        popup.addView(scroll,new LinearLayout.LayoutParams(width,-2));
        Xp.setAdditionalInstanceField(popup,"pixelsuite_compact_menu",Boolean.TRUE);
        popup.measure(0,0);place(popup);
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
        int threeRows=FrostedSurface.dp(popup.getContext(),12+48*3);
        if(roomBelow>=Math.min(menuHeight,threeRows)||roomBelow>=roomAbove)return new int[]{below,roomBelow};
        int h=Math.min(menuHeight,roomAbove);
        return new int[]{icon.top-gap-h,roomAbove};
    }

    static void place(ViewGroup popup){
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
