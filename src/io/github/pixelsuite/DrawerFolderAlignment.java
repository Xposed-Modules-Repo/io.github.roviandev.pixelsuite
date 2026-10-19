package io.github.pixelsuite;

import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

final class DrawerFolderAlignment {
    static boolean drawer(Object icon){return Boolean.TRUE.equals(Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_folder_cell"));}

    static void attach(final View icon){
        View.OnLayoutChangeListener listener=new View.OnLayoutChangeListener(){
            public void onLayoutChange(View v,int l,int t,int r,int b,int oldL,int oldT,int oldR,int oldB){schedule(icon);}
        };
        icon.addOnLayoutChangeListener(listener);
        ((View)LauncherFeatures.get(icon,"mFolderName")).addOnLayoutChangeListener(listener);
    }

    static void schedule(final View icon){
        if(Boolean.TRUE.equals(Xp.getAdditionalInstanceField(icon,"pixelsuite_align_pending")))return;
        Xp.setAdditionalInstanceField(icon,"pixelsuite_align_pending",true);
        final android.view.ViewTreeObserver.OnPreDrawListener[] hook=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        hook[0]=new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){
            try{android.view.ViewTreeObserver o=icon.getViewTreeObserver();if(o.isAlive())o.removeOnPreDrawListener(this);}catch(Throwable ignored){}
            if(!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(icon,"pixelsuite_align_pending")))return true;
            Xp.setAdditionalInstanceField(icon,"pixelsuite_align_pending",false);
            boolean changed=false;
            try{changed=align(icon);}catch(Throwable error){LauncherFeatures.logOnce("Drawer folder alignment unavailable",error);}
            Object skipped=Xp.getAdditionalInstanceField(icon,"pixelsuite_align_skipped");
            if(changed&&!Boolean.TRUE.equals(skipped)){Xp.setAdditionalInstanceField(icon,"pixelsuite_align_skipped",true);return false;}
            Xp.setAdditionalInstanceField(icon,"pixelsuite_align_skipped",false);
            return true;
        }};
        try{icon.getViewTreeObserver().addOnPreDrawListener(hook[0]);}catch(Throwable ignored){}
        icon.post(new Runnable(){public void run(){
            try{android.view.ViewTreeObserver o=icon.getViewTreeObserver();if(o.isAlive())o.removeOnPreDrawListener(hook[0]);}catch(Throwable ignored){}
            if(!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(icon,"pixelsuite_align_pending")))return;
            Xp.setAdditionalInstanceField(icon,"pixelsuite_align_pending",false);
            try{align(icon);}catch(Throwable error){LauncherFeatures.logOnce("Drawer folder alignment unavailable",error);}
        }});
    }

    static void seed(View icon,FrameLayout frame){
        Object last=Xp.getAdditionalInstanceField(frame,"pixelsuite_last_alignment");
        if(!(last instanceof int[]))return;
        int[] v=(int[])last;
        if(!(Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_preview_size") instanceof Integer)){
            Xp.setAdditionalInstanceField(icon,"pixelsuite_drawer_preview_size",v[0]);
            Xp.setAdditionalInstanceField(icon,"pixelsuite_drawer_preview_top",v[1]);
            try{Object manager=LauncherFeatures.get(icon,"mPreviewItemManager");LauncherFeatures.set(manager,"mTotalWidth",-1);}catch(Throwable ignored){}
        }
        try{View label=(View)LauncherFeatures.get(icon,"mFolderName");if(label.getTranslationY()==0&&v[2]!=0)label.setTranslationY(v[2]);}catch(Throwable ignored){}
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

    static boolean align(View icon){
        if(!(icon.getParent() instanceof FrameLayout))return false;
        FrameLayout frame=(FrameLayout)icon.getParent();
        if(frame.getWidth()<=0||frame.getHeight()<=0)return false;
        int[] ref=reference(frame);if(ref==null)return false;
        int top=ref[0],size=ref[1],baseline=ref[2];
        boolean changed=false;
        if(icon.getTranslationY()!=0){icon.setTranslationY(0);changed=true;}
        TextView label=(TextView)LauncherFeatures.get(icon,"mFolderName");
        TextView style=styleSource(frame);
        if(style!=null){
            if(Math.abs(label.getTextSize()-style.getTextSize())>.1f){label.setTextSize(0,style.getTextSize());changed=true;}
            if(label.getTypeface()!=style.getTypeface()){label.setTypeface(style.getTypeface());changed=true;}
            if(label.getLetterSpacing()!=style.getLetterSpacing()){label.setLetterSpacing(style.getLetterSpacing());changed=true;}
            if(label.getIncludeFontPadding()!=style.getIncludeFontPadding()){label.setIncludeFontPadding(style.getIncludeFontPadding());changed=true;}
            if(label.getPaddingLeft()!=style.getPaddingLeft()||label.getPaddingRight()!=style.getPaddingRight()){label.setPadding(style.getPaddingLeft(),label.getPaddingTop(),style.getPaddingRight(),label.getPaddingBottom());changed=true;}
        }
        if(label.getMaxLines()!=1){label.setMaxLines(1);changed=true;}
        if(label.getEllipsize()!=android.text.TextUtils.TruncateAt.END){label.setEllipsize(android.text.TextUtils.TruncateAt.END);changed=true;}
        if(label.getLayout()!=null){
            float move=baseline-(label.getTop()+label.getBaseline());
            if(Math.abs(label.getTranslationY()-move)>.5f){label.setTranslationY(move);changed=true;}
        }
        Object oldSize=Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_preview_size"),oldTop=Xp.getAdditionalInstanceField(icon,"pixelsuite_drawer_preview_top");
        if(!(oldSize instanceof Integer)||(Integer)oldSize!=size||!(oldTop instanceof Integer)||(Integer)oldTop!=top){
            Xp.setAdditionalInstanceField(icon,"pixelsuite_drawer_preview_size",size);
            Xp.setAdditionalInstanceField(icon,"pixelsuite_drawer_preview_top",top);
            Object manager=LauncherFeatures.get(icon,"mPreviewItemManager");
            LauncherFeatures.set(manager,"mTotalWidth",-1);
            LauncherFeatures.call(manager,"recomputePreviewDrawingParams");
            icon.invalidate();changed=true;
        }
        Xp.setAdditionalInstanceField(frame,"pixelsuite_last_alignment",new int[]{size,top,Math.round(label.getTranslationY())});
        String note="Drawer folder aligned: cell "+frame.getWidth()+"x"+frame.getHeight()+", icon top "+top+", size "+size+", baseline "+baseline+", caption top "+label.getTop()+" shift "+Math.round(label.getTranslationY());
        if(!note.equals(lastNote)){lastNote=note;Module.log(note);}
        return changed;
    }
    static String lastNote;

    static TextView styleSource(FrameLayout frame){
        if(frame.getParent() instanceof ViewGroup){
            ViewGroup list=(ViewGroup)frame.getParent();
            for(int i=0;i<list.getChildCount();i++){View c=list.getChildAt(i);if(LauncherFeatures.type("BubbleTextView").isInstance(c)&&LauncherFeatures.type("model.data.AppInfo").isInstance(c.getTag()))return (TextView)c;}
        }
        Object template=Xp.getAdditionalInstanceField(frame,"pixelsuite_native_app_cell");
        return template instanceof TextView?(TextView)template:null;
    }
}
