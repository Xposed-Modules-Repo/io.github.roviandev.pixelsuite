package io.github.pixelsuite;

import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Closed drawer folders line up with the real apps in their row (preview32 rewrite).
 *
 * Reference: an actually bound, laid-out app cell in the SAME row of the drawer list (same top
 * and height as this folder's cell). Its icon top (the compound drawable sits at its top
 * padding), icon size and first-line label baseline are read directly. Only when the row has
 * no app (a row of folders) is the detached stock cell measured instead, as preview31 did.
 *
 * One coordinate space: the FolderIcon fills its cell at (0,0) and is never translated any
 * more (preview31 moved the whole FolderIcon AND the caption, and took the preview's top from
 * the FolderIcon's own padding, which native measuring changes afterwards; that double-counted
 * and pushed the caption down and out of the cell). Now:
 *   preview top  = reference icon top, set as PreviewBackground.basePreviewOffsetY directly;
 *   preview size = reference icon size, centred horizontally;
 *   caption      = one translationY so its first baseline equals the reference baseline.
 * Hit testing, drag and the open/close animation all use the same unmoved FolderIcon.
 * A one-line log reports the measured geometry once per change.
 */
final class DrawerFolderAlignment {
    static boolean drawer(Object icon){return Boolean.TRUE.equals(Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_folder_cell"));}

    static void attach(final View icon){
        View.OnLayoutChangeListener listener=new View.OnLayoutChangeListener(){
            public void onLayoutChange(View v,int l,int t,int r,int b,int oldL,int oldT,int oldR,int oldB){schedule(icon);}
        };
        icon.addOnLayoutChangeListener(listener);
        ((View)LauncherFeatures.get(icon,"mFolderName")).addOnLayoutChangeListener(listener);
    }

    /** After the whole row has been laid out (neighbours to the right lay out after us). */
    static void schedule(final View icon){
        if(Boolean.TRUE.equals(Xp.getAdditionalInstanceField(icon,"pixelsuite_align_pending")))return;
        Xp.setAdditionalInstanceField(icon,"pixelsuite_align_pending",true);
        icon.post(new Runnable(){public void run(){
            Xp.setAdditionalInstanceField(icon,"pixelsuite_align_pending",false);
            try{align(icon);}catch(Throwable error){LauncherFeatures.logOnce("Drawer folder alignment unavailable",error);}
        }});
    }

    static void install(){
        Xp.hookAllMethods(LauncherFeatures.type("folder.PreviewBackground"),"setup",new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){
                if(p.args.length<5||!(p.args[2] instanceof View)||!drawer(p.args[2]))return;
                View icon=(View)p.args[2];
                Object size=Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_preview_size");
                Object top=Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_preview_top");
                if(!(size instanceof Integer)||!(top instanceof Integer))return;
                LauncherFeatures.set(p.thisObject,"previewSize",size);
                LauncherFeatures.set(p.thisObject,"basePreviewOffsetX",(((Number)p.args[3]).intValue()-(Integer)size)/2);
                LauncherFeatures.set(p.thisObject,"basePreviewOffsetY",top);
            }
        });
    }

    /** {icon top, icon size, first baseline} in the cell's own coordinates, or null. */
    static int[] reference(FrameLayout frame){
        if(frame.getParent() instanceof ViewGroup){
            ViewGroup list=(ViewGroup)frame.getParent();TextView best=null;
            for(int i=0;i<list.getChildCount();i++){
                View child=list.getChildAt(i);
                if(child==frame||child.getTop()!=frame.getTop()||child.getHeight()!=frame.getHeight()||child.getVisibility()!=View.VISIBLE)continue;
                if(!LauncherFeatures.type("BubbleTextView").isInstance(child)||!LauncherFeatures.type("model.data.AppInfo").isInstance(child.getTag()))continue;
                TextView t=(TextView)child;if(t.getLayout()==null||t.getText().length()==0)continue;
                if(best==null||Math.abs(child.getLeft()-frame.getLeft())<Math.abs(best.getLeft()-frame.getLeft()))best=t;
            }
            if(best!=null){
                int[] r=new int[]{best.getPaddingTop(),((Number)LauncherFeatures.get(best,"mIconSize")).intValue(),best.getBaseline()};
                Xp.setAdditionalInstanceField(frame,"pixelsuite_row_reference",r);
                return r;
            }
        }
        // A row without apps: the last reference this cell saw, else the stock cell measured detached.
        Object last=Xp.getAdditionalInstanceField(frame,"pixelsuite_row_reference");
        if(last instanceof int[])return (int[])last;
        Object template=Xp.getAdditionalInstanceField(frame,"pixelsuite_native_app_cell");
        if(!(template instanceof TextView))return null;
        TextView sample=(TextView)template;
        LauncherLabelSizing.apply(sample);
        int size=((Number)LauncherFeatures.get(sample,"mIconSize")).intValue();
        ColorDrawable placeholder=new ColorDrawable(0);placeholder.setBounds(0,0,size,size);
        sample.setCompoundDrawables(null,placeholder,null,null);
        if(sample.getText().length()==0)sample.setText("Ag");
        sample.measure(View.MeasureSpec.makeMeasureSpec(frame.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(frame.getHeight(),View.MeasureSpec.EXACTLY));
        sample.layout(0,0,frame.getWidth(),frame.getHeight());
        return new int[]{sample.getPaddingTop(),size,sample.getBaseline()};
    }

    static void align(View icon){
        if(!(icon.getParent() instanceof FrameLayout))return;
        FrameLayout frame=(FrameLayout)icon.getParent();
        if(frame.getWidth()<=0||frame.getHeight()<=0)return;
        int[] ref=reference(frame);if(ref==null)return;
        int top=ref[0],size=ref[1],baseline=ref[2];
        if(icon.getTranslationY()!=0)icon.setTranslationY(0);         // preview31 moved the whole FolderIcon
        // Caption: the same type as the drawer's app labels (preview31's copy kept; real cell when present).
        TextView label=(TextView)LauncherFeatures.get(icon,"mFolderName");
        TextView style=styleSource(frame);
        if(style!=null){
            if(Math.abs(label.getTextSize()-style.getTextSize())>.1f)label.setTextSize(0,style.getTextSize());
            if(label.getTypeface()!=style.getTypeface())label.setTypeface(style.getTypeface());
            if(label.getLetterSpacing()!=style.getLetterSpacing())label.setLetterSpacing(style.getLetterSpacing());
            if(label.getIncludeFontPadding()!=style.getIncludeFontPadding())label.setIncludeFontPadding(style.getIncludeFontPadding());
            if(label.getPaddingLeft()!=style.getPaddingLeft()||label.getPaddingRight()!=style.getPaddingRight())label.setPadding(style.getPaddingLeft(),label.getPaddingTop(),style.getPaddingRight(),label.getPaddingBottom());
        }
        if(label.getMaxLines()!=1)label.setMaxLines(1);
        if(label.getEllipsize()!=android.text.TextUtils.TruncateAt.END)label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(label.getLayout()!=null){
            float move=baseline-(label.getTop()+label.getBaseline());
            if(Math.abs(label.getTranslationY()-move)>.5f)label.setTranslationY(move);
        }
        // Preview: same top and size as the neighbouring app icons.
        Object oldSize=Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_preview_size"),oldTop=Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_preview_top");
        if(!(oldSize instanceof Integer)||(Integer)oldSize!=size||!(oldTop instanceof Integer)||(Integer)oldTop!=top){
            Xp.setAdditionalInstanceField(icon,"pixelsuite_drawer_preview_size",size);
            Xp.setAdditionalInstanceField(icon,"pixelsuite_drawer_preview_top",top);
            Object manager=LauncherFeatures.get(icon,"mPreviewItemManager");
            LauncherFeatures.set(manager,"mTotalWidth",-1);
            LauncherFeatures.call(manager,"recomputePreviewDrawingParams");
            icon.invalidate();
        }
        String note="Drawer folder aligned: cell "+frame.getWidth()+"x"+frame.getHeight()+", icon top "+top+", size "+size+", baseline "+baseline+", caption top "+label.getTop()+" shift "+Math.round(label.getTranslationY());
        if(!note.equals(lastNote)){lastNote=note;Module.log(note);}
    }
    static String lastNote;

    /** A real app label in the list for typography, else the stock template. */
    static TextView styleSource(FrameLayout frame){
        if(frame.getParent() instanceof ViewGroup){
            ViewGroup list=(ViewGroup)frame.getParent();
            for(int i=0;i<list.getChildCount();i++){View c=list.getChildAt(i);if(LauncherFeatures.type("BubbleTextView").isInstance(c)&&LauncherFeatures.type("model.data.AppInfo").isInstance(c.getTag()))return (TextView)c;}
        }
        Object template=Xp.getAdditionalInstanceField(frame,"pixelsuite_native_app_cell");
        return template instanceof TextView?(TextView)template:null;
    }
}
