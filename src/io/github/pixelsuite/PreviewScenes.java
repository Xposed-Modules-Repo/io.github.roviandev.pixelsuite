package io.github.pixelsuite;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

/** All demonstrations share one front-facing frame and the same right-side hardware. */
final class PreviewScenes {
    private final String key;
    private final int accent,onAccent,ink,muted;
    private int surface;
    void surfaceColor(int color){surface=color;}
    private final boolean dark;
    private final PreviewIcons icons;
    private final PrivacyBadges.Glyph privacyLockGlyph;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final Typeface font=Typeface.create("sans-serif-medium",0);
    private float opacity=1;
    private boolean customCycle;
    void nextCycle(){customCycle=!customCycle;}
    private static final int BLUE=0xff4285f4,RED=0xffea4335,YELLOW=0xfffbbc05,GREEN=0xff34a853;
    private static final int[] DOCK={5,1,4,12};
    private static final String[] RECENTS_ACTIONS={"Screenshot","Select","Clear all"};
    private static final String[] FILE_NAMES={"Photo.jpg","Invoice.pdf","Voice note.m4a","Notes.docx","Archive.zip","Clip.mp4"};
    private static final String[] FILE_AGES={"Today 09:15","Oct 12, 10:20","Today 14:42","Oct 8, 08:10","Sep 30, 21:00","Yesterday 18:30"};
    private static final int[] FILE_RANK={1,3,0,4,5,2};
    private static final String[] DARK_ROWS={"Display & touch","Notifications","Wallpaper & style"};
    private static final String[] DARK_TILES={"Internet","Bluetooth","Flashlight","Do Not Disturb"};
    PreviewScenes(String key,int accent,int onAccent,int ink,int muted,int surface,boolean dark,PreviewIcons icons){this.key=key;this.accent=accent;this.onAccent=onAccent;this.ink=ink;this.muted=muted;this.surface=surface;this.dark=dark;this.icons=icons;this.privacyLockGlyph=new PrivacyBadges.Glyph(dark?0xffffffff:0xff1c1b1f,dark?0x8c000000:0xb3ffffff,1f);}
    long duration(){
        if("recents_landscape".equals(key))return 6000;
        if("privacy_lock".equals(key)||"privacy_folder".equals(key))return 4500;
        if(SuperDark.KEY.equals(key))return 4500;
        // Shared pacing for these previews: at most 4.5 seconds, plus FeaturePreview's separate loop pause.
        if("folder_sort".equals(key)||LauncherFeatures.GRID.equals(key)||FolderDrag.TWO_FINGER.equals(key)||MultiDrag.KEY.equals(key))return 4500;
        if(ScreenRecordTidy.KEY.equals(key)||"folder_sort".equals(key)||"folder_reorder".equals(key))return 4800;
        if(LauncherFeatures.ICONS.equals(key))return 5000;
        if(Feature.PHOTOS_DOODLES.equals(key))return 5000;
        if(LauncherFeatures.DRAG_PILL.equals(key)||LauncherFeatures.GRID.equals(key)||Feature.SCREENSHOT_DOODLES.equals(key))return 4800;
        if(LauncherFeatures.DRAWER.equals(key)||Feature.SCREENSHOT_FREE_CROP.equals(key)||Feature.FILES_SORT.equals(key))return 4500;
        if(LauncherFeatures.RENAME.equals(key)||FolderDrag.TWO_FINGER.equals(key)||ScatterEffect.KEY.equals(key))return 4300;
        if(MultiDrag.KEY.equals(key))return 5200;
        if(LauncherFeatures.HIDE_SHORTCUTS.equals(key))return 3800;
        if(Feature.THREE_FINGER.equals(key))return 3600;
        if(Feature.VOLDOWN_CAMERA.equals(key))return 3200;
        return 3000;
    }
    private static float clamp(float f){return Math.max(0,Math.min(1,f));}
    private static float e(float a,float b,float t){float f=clamp((t-a)/(b-a));return f*f*(3-2*f);}
    private static float mix(float a,float b,float f){return a+(b-a)*f;}
    private int tint(int a,int b,float f){return Color.rgb((int)mix(Color.red(a),Color.red(b),f),(int)mix(Color.green(a),Color.green(b),f),(int)mix(Color.blue(a),Color.blue(b),f));}
    private void paint(int color,boolean stroke,float width){p.setColor(color);p.setAlpha(Math.round(255*opacity));p.setStyle(stroke?Paint.Style.STROKE:Paint.Style.FILL);p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
    private void box(Canvas c,float x,float y,float w,float h,float r,int color){paint(color,false,0);c.drawRoundRect(x,y,x+w,y+h,r,r,p);}
    private void outline(Canvas c,float x,float y,float w,float h,float r,int color){paint(color,true,2);c.drawRoundRect(x,y,x+w,y+h,r,r,p);}
    private void line(Canvas c,float x,float y,float a,float b,int color){paint(color,true,2.4f);c.drawLine(x,y,a,b,p);}
    private void circle(Canvas c,float x,float y,float r,int color){paint(color,false,0);c.drawCircle(x,y,r,p);}
    private void text(Canvas c,String s,float x,float y,float size,int color){paint(color,false,0);p.setTextSize(size);p.setTypeface(font);p.setTextAlign(Paint.Align.CENTER);c.drawText(s,x,y,p);}
    private void alpha(Canvas c,float f){c.saveLayerAlpha(0,0,240,500,Math.round(255*clamp(f)));}
    private void touch(Canvas c,float x,float y,float t,float start,float length){float f=(t-start)/length;if(f<0||f>1)return;float old=opacity;opacity*=1-f;paint(accent,true,2.5f);c.drawCircle(x,y,8+12*f,p);circle(c,x,y,4,accent);opacity=old;}
    private void pill(Canvas c,float alpha){float old=opacity;opacity*=alpha;box(c,96,486,48,4,2,ink);opacity=old;}
    private void background(Canvas c){box(c,0,0,240,500,0,surface);circle(c,214,190,165,tint(surface,accent,.12f));circle(c,4,357,170,tint(surface,accent,.07f));}
    private void home(Canvas c,boolean dock){background(c);text(c,"10:08",120,132,38,ink);text(c,"Friday, Oct 16",120,158,12,muted);if(dock)dock(c);}
    private void dock(Canvas c){for(int i=0;i<4;i++)app(c,DOCK[i],36+56*i,448,35,false);}

    void draw(Canvas c,int width,int height,float t,float reset){
        if("recents_landscape".equals(key)){
            if(reset<=0)landscapeFrame(c,width,height,t);
            else{c.saveLayerAlpha(0,0,width,height,Math.round(255*(1-reset)));landscapeFrame(c,width,height,1);c.restore();c.saveLayerAlpha(0,0,width,height,Math.round(255*reset));landscapeFrame(c,width,height,0);c.restore();}
            return;
        }
        float scale=Math.min(width/336f,height/300f);if(scale<=0)return;
        c.save();c.translate((width-336*scale)/2,(height-300*scale)/2);c.scale(scale,scale);
        box(c,0,0,336,300,24,tint(surface,accent,.035f));
        // A single bezel, front glass and button geometry for every feature.
        box(c,94,2,148,296,25,tint(surface,ink,.24f));box(c,97,5,142,290,23,0xff111216);
        float power=Feature.FLASHLIGHT.equals(key)?pulse(t,.08f)+pulse(t,.16f):0;
        float down=Feature.VOLDOWN_CAMERA.equals(key)?pulse(t,.08f)+pulse(t,.16f):0;
        box(c,243,55,3.8f,25,1.8f,tint(muted,accent,clamp(power)));box(c,243,103,3.8f,50,1.8f,muted);box(c,243,129,3.8f,24,1.8f,tint(muted,accent,clamp(down)));line(c,243,128,247,128,surface);
        if(power>0||down>0){float yy=power>0?67:141;float old=opacity;opacity=clamp(power+down)*.5f;circle(c,245,yy,11,accent);opacity=old;}
        c.save();path.reset();path.addRoundRect(100,8,236,292,20,20,Path.Direction.CW);c.clipPath(path);c.translate(100,8);c.scale(136/240f,284/500f);
        if(reset<=0)scene(c,t);else{alpha(c,1-reset);scene(c,1);c.restore();alpha(c,reset);boolean previous=customCycle;customCycle=!customCycle;scene(c,0);customCycle=previous;c.restore();}
        c.restore();c.restore();
    }
    private float pulse(float t,float start){return t<start||t>start+.055f?0:(float)Math.sin((t-start)/.055f*Math.PI);}
    private void app(Canvas c,int kind,float x,float y,float size,boolean label){
        if(!icons.draw(c,kind,x,y,size,opacity))fallback(c,kind,x,y,size);
        if(label)text(c,PreviewIcons.NAMES[Math.floorMod(kind,PreviewIcons.NAMES.length)],x,y+size*.77f,10,ink);
    }
    private void fallback(Canvas c,int kind,float x,float y,float size){
        if(kind>=13){int[] colors={0xff397b89,0xff4275bd,0xff6b7280,0xff4285f4,0xff279fce,0xff25a86b,0xff159447,0xffef6134,0xff8250c9,0xffe8a43b,0xffef5428};c.save();c.translate(x-size/2,y-size/2);c.scale(size/48,size/48);box(c,0,0,48,48,15,colors[(kind-13)%colors.length]);text(c,PreviewIcons.NAMES[kind%PreviewIcons.NAMES.length].substring(0,1),24,34,28,0xffffffff);c.restore();return;}
        c.save();c.translate(x-size/2,y-size/2);c.scale(size/48,size/48);box(c,0,0,48,48,15,0xfff8f9fa);
        switch(Math.floorMod(kind,13)){
            case 0:box(c,5,3,38,42,8,YELLOW);circle(c,24,21,10,0xffffffff);box(c,20,28,8,8,2,0xffffffff);line(c,20,39,28,39,0xffffffff);break;
            case 1:paint(RED,false,0);c.drawArc(1,1,47,47,210,120,true,p);paint(YELLOW,false,0);c.drawArc(1,1,47,47,330,120,true,p);paint(GREEN,false,0);c.drawArc(1,1,47,47,90,120,true,p);circle(c,24,24,11,0xffffffff);circle(c,24,24,8,BLUE);break;
            case 2:circle(c,24,19,14,GREEN);path.reset();path.moveTo(13,26);path.lineTo(24,44);path.lineTo(35,26);path.close();paint(BLUE,false,0);c.drawPath(path,p);circle(c,24,19,5,0xffffffff);break;
            case 3:paint(BLUE,false,0);c.drawArc(6,6,28,28,180,180,true,p);paint(RED,false,0);c.drawArc(20,6,42,28,270,180,true,p);paint(YELLOW,false,0);c.drawArc(20,20,42,42,0,180,true,p);paint(GREEN,false,0);c.drawArc(6,20,28,42,90,180,true,p);break;
            case 4:line(c,8,37,8,13,BLUE);line(c,40,37,40,13,GREEN);paint(RED,true,6);path.reset();path.moveTo(8,13);path.lineTo(24,26);path.lineTo(40,13);c.drawPath(path,p);break;
            case 5:circle(c,24,24,22,BLUE);box(c,10,12,28,22,7,0xffffffff);path.reset();path.moveTo(13,30);path.lineTo(13,39);path.lineTo(22,31);path.close();paint(0xffffffff,false,0);c.drawPath(path,p);break;
            case 6:box(c,5,10,38,31,5,BLUE);box(c,5,8,19,10,3,YELLOW);box(c,12,17,25,5,2,0xffffffff);break;
            case 7:box(c,5,5,38,38,5,BLUE);box(c,9,13,30,26,2,0xffffffff);text(c,"31",24,33,20,BLUE);break;
            case 8:box(c,3,9,42,30,9,RED);path.reset();path.moveTo(20,17);path.lineTo(32,24);path.lineTo(20,32);path.close();paint(0xffffffff,false,0);c.drawPath(path,p);break;
            case 9:line(c,12,37,24,10,GREEN);line(c,24,10,39,37,YELLOW);line(c,12,37,39,37,BLUE);break;
            case 10:circle(c,24,24,20,0xffdde6f8);line(c,24,24,24,11,BLUE);line(c,24,24,35,27,BLUE);break;
            case 11:paint(BLUE,true,7);c.drawArc(9,9,39,39,-35,110,false,p);paint(GREEN,true,7);c.drawArc(9,9,39,39,75,95,false,p);paint(YELLOW,true,7);c.drawArc(9,9,39,39,170,45,false,p);paint(RED,true,7);c.drawArc(9,9,39,39,215,110,false,p);paint(BLUE,true,6);c.drawLine(25,24,40,24,p);break;
            default:box(c,5,10,38,29,8,0xff607d8b);circle(c,24,24,12,0xff263238);circle(c,24,24,7,BLUE);break;
        }c.restore();
    }
    private void search(Canvas c){box(c,12,25,216,38,19,tint(surface,ink,.07f));app(c,11,33,44,20,false);text(c,"Search apps",113,48,12,muted);circle(c,207,44,3,muted);}
    private void drawer(Canvas c,int skip){drawer(c,skip,-1);}
    private void drawer(Canvas c,int skip,int skipSecond){
        drawer(c,skip,skipSecond,-1);
    }
    private void drawer(Canvas c,int skip,int skipSecond,int skipThird){
        box(c,0,0,240,500,0,tint(surface,ink,.025f));search(c);
        for(int i=0;i<20;i++)if(i!=skip&&i!=skipSecond&&i!=skipThird)app(c,i%13,33+(i%4)*58,104+(i/4)*71,34,true);
    }
    private void photo(Canvas c,float x,float y,float w,float h){
        box(c,x,y,w,h,8,dark?0xff6f92ad:0xffbdd9ed);circle(c,x+w*.73f,y+h*.23f,w*.09f,0xffffd18a);
        path.reset();path.moveTo(x,y+h*.85f);path.lineTo(x+w*.32f,y+h*.32f);path.lineTo(x+w*.66f,y+h);path.lineTo(x,y+h);path.close();paint(0xff4f7a7b,false,0);c.drawPath(path,p);
        path.reset();path.moveTo(x+w*.32f,y+h);path.lineTo(x+w*.73f,y+h*.49f);path.lineTo(x+w,y+h*.77f);path.lineTo(x+w,y+h);path.close();paint(0xff345b68,false,0);c.drawPath(path,p);
    }
    private void noteIcon(Canvas c,float x,float y,float size){
        c.save();c.translate(x-size/2,y-size/2);c.scale(size/48,size/48);circle(c,24,24,24,0xff7660ca);box(c,12,8,25,32,4,0xfffaf7ff);
        path.reset();path.moveTo(29,8);path.lineTo(37,16);path.lineTo(29,16);path.close();paint(0xffd8ccff,false,0);c.drawPath(path,p);line(c,17,22,31,22,0xff7660ca);line(c,17,27,31,27,0xff7660ca);line(c,17,32,26,32,0xff7660ca);c.restore();
    }
    private void torch(Canvas c,float lit){
        box(c,0,0,240,500,0,0xff111216);
        if(lit>0){float old=opacity;
            for(int i=0;i<7;i++){float spread=78-i*7;opacity=old*lit*(.035f+i*.016f);path.reset();path.moveTo(105+i,248);path.lineTo(120-spread,99);path.lineTo(120+spread,99);path.lineTo(135-i,248);path.close();paint(i<4?0xffffdf91:0xfffff9db,false,0);c.drawPath(path,p);}
            opacity=old*lit*.14f;paint(0xffffedba,false,0);c.drawArc(42,83,198,115,0,360,true,p);opacity=old;
        }
        box(c,106,265,28,75,8,0xff6d707b);box(c,99,244,42,29,6,0xff9297a4);box(c,102,243,36,7,3,tint(0xff4d515c,0xfffff4c6,lit));circle(c,120,292,5,tint(0xff3b3e47,accent,lit));line(c,113,320,127,320,0xff494d57);
    }
    private void recentsActions(Canvas c){
        for(int i=0;i<3;i++){float x=42+i*78;box(c,x-36,414,72,50,15,tint(surface,ink,.07f));
            if(i==0){outline(c,x-8,421,16,12,2,ink);line(c,x-10,419,x-5,419,ink);line(c,x+5,435,x+10,435,ink);}
            else if(i==1){outline(c,x-7,419,14,16,2,ink);line(c,x-3,426,x+3,426,ink);line(c,x-3,430,x+1,430,ink);}
            else{line(c,x-6,420,x+6,432,ink);line(c,x+6,420,x-6,432,ink);}text(c,RECENTS_ACTIONS[i],x,452,10,ink);
        }
    }
    private void folderIcon(Canvas c,float x,float y){box(c,x-13,y-8,26,20,4,0xffffca61);box(c,x-13,y-12,13,7,2,0xffffca61);line(c,x-8,y-3,x+8,y-3,0xffb98120);}
    private void fileIcon(Canvas c,int type,float x,float y){
        c.save();c.translate(x-14,y-17);int color=type==0?0xff3b987b:type==1?0xffdc625e:type==2?0xff8665ba:type==3?0xff4c83cb:type==4?0xffb48c34:0xff4e91b9;
        box(c,1,1,26,32,3,color);path.reset();path.moveTo(19,1);path.lineTo(27,9);path.lineTo(19,9);path.close();paint(0xfff0e8dc,false,0);c.drawPath(path,p);
        if(type==0){circle(c,9,14,3,0xffffdc84);path.reset();path.moveTo(5,28);path.lineTo(11,18);path.lineTo(16,24);path.lineTo(21,17);path.lineTo(25,28);path.close();paint(0xffe6f7ef,false,0);c.drawPath(path,p);}
        else if(type==1)text(c,"PDF",14,24,10,0xffffffff);
        else if(type==2){for(int i=0;i<5;i++){float h=i==2?12:i%2==0?5:8;line(c,7+i*4,21-h/2,7+i*4,21+h/2,0xfffaf5ff);}}
        else if(type==3){for(int i=0;i<3;i++)line(c,6,16+i*5,22-i*3,16+i*5,0xffffffff);}
        else if(type==4){line(c,14,9,14,28,0xffffedb6);for(int i=0;i<4;i++)line(c,11,12+i*4,17,12+i*4,0xff5a451c);}
        else{path.reset();path.moveTo(10,14);path.lineTo(22,21);path.lineTo(10,28);path.close();paint(0xfff2f9ff,false,0);c.drawPath(path,p);}c.restore();
    }
    private void fileRow(Canvas c,int i,float f){
        float y=178+mix(i,FILE_RANK[i],f)*51;box(c,11,y-21,218,46,9,tint(surface,ink,.045f));fileIcon(c,i,32,y);text(c,FILE_NAMES[i],143,y-3,11,ink);text(c,FILE_AGES[i],143,y+13,9,muted);
        if(i==2&&f>0&&f<1)outline(c,11,y-21,218,46,9,accent);
    }
    private void chat(Canvas c){
        box(c,0,0,240,500,0,surface);app(c,5,29,36,27,false);text(c,"Weekend plans",127,40,16,ink);line(c,10,66,230,66,tint(surface,muted,.2f));
        box(c,13,86,169,58,14,tint(surface,ink,.07f));text(c,"Ready for the weekend?",97,109,12,ink);text(c,"Let's find a new place.",94,128,11,muted);
        box(c,55,163,172,49,14,tint(surface,accent,.25f));text(c,"Yes! A coffee sounds good.",141,190,11,ink);
        box(c,13,233,179,128,14,tint(surface,ink,.07f));photo(c,20,240,165,93);text(c,"How about this spot?",100,350,11,ink);
        box(c,58,382,169,45,14,tint(surface,accent,.25f));text(c,"Perfect. See you there!",143,409,11,ink);box(c,12,447,216,30,15,tint(surface,ink,.07f));text(c,"Message",66,466,11,muted);
    }

    /** Photos-only storyboard: library -> photo -> edit -> Doodles -> save. */
    private void photosDoodles(Canvas c,float t){
        box(c,0,0,240,500,0,surface);
        app(c,3,27,33,22,false);text(c,"Google Photos",117,38,17,ink);circle(c,215,32,11,tint(surface,accent,.24f));
        text(c,"Today",34,79,13,ink);
        for(int i=0;i<9;i++)photoTile(c,5+(i%3)*78,94+(i/3)*100,74,96,i);
        text(c,"Yesterday",43,415,12,muted);
        box(c,0,444,240,56,0,tint(surface,ink,.04f));
        String[] nav={"Photos","Collections","Create"};
        for(int i=0;i<3;i++){float x=40+80*i;if(i==0){box(c,x-22,450,44,22,11,tint(surface,accent,.2f));app(c,3,x,461,17,false);}else if(i==1)outline(c,x-8,455,16,13,3,muted);else{line(c,x-6,461,x+6,461,muted);line(c,x,455,x,467,muted);}text(c,nav[i],x,487,10,i==0?accent:muted);}
        touch(c,120,142,t,.04f,.05f);
        float open=e(.10f,.19f,t);
        if(open>0){
            alpha(c,open);box(c,0,0,240,500,0,surface);
            float edit=e(.25f,.32f,t);
            text(c,"\u2039",20,33,25,ink);text(c,edit>0?"Edit photo":"Today",120,32,14,ink);text(c,"\u22ee",217,33,22,ink);
            float x=mix(83,12,open),y=mix(94,70,open),w=mix(74,216,open),h=mix(96,306,open);
            photoTile(c,x,y,w,h,1);
            if(edit<1){float old=opacity;opacity*=1-edit;box(c,70,416,100,38,19,tint(surface,accent,.18f));text(c,"Edit",120,440,14,accent);opacity=old;}
            touch(c,120,434,t,.20f,.05f);
            if(edit>0){
                alpha(c,edit);
                box(c,0,453,240,47,0,surface);
                text(c,"Adjust",45,479,11,muted);text(c,"Crop",112,479,11,muted);
                float tools=e(.37f,.43f,t)*(1-e(.92f,.96f,t));
                box(c,148,458,78,30,15,tint(surface,accent,.15f));text(c,"Doodles",187,479,12,accent);
                touch(c,187,472,t,.32f,.05f);
                if(tools>0){
                    c.save();c.translate(0,16*(1-tools));alpha(c,tools);
                    box(c,5,365,230,83,14,tint(surface,ink,.08f));
                    for(int i=0;i<7;i++){float xx=24+i*32;if(i==0)outline(c,xx-7,379,14,14,7,ink);else if(i==1)arrow(c,xx-6,393,xx+6,379,1,ink);else if(i==2)outline(c,xx-7,380,14,12,2,ink);else if(i==3){line(c,xx-7,393,xx,379,ink);line(c,xx,379,xx+7,393,ink);line(c,xx+7,393,xx-7,393,ink);}else line(c,xx-6,392,xx+6,380,ink);}
                    c.save();c.clipRect(12,403,228,426);float scroll=60*e(.66f,.78f,t);for(int i=0;i<12;i++)circle(c,24+i*26-scroll,415,7,DoodleOverlay.COLORS[i]);c.restore();
                    line(c,20,438,95,438,muted);circle(c,55,438,4,accent);text(c,"Fill",126,441,9,ink);text(c,"Delete",164,441,9,ink);text(c,"Done",210,441,10,accent);
                    c.restore();c.restore();
                }
                touch(c,24,386,t,.40f,.05f);
                float ring=e(.45f,.67f,t);paint(0xffffce6b,true,3.8f);c.drawArc(145,103,195,153,-90,360*ring,false,p);
                touch(c,56,386,t,.65f,.05f);
                float draw=e(.70f,.90f,t);arrow(c,52,287,146,184,draw,0xfff58ab1);
                if(draw>0&&draw<1){circle(c,mix(52,146,draw),mix(287,184,draw),5,accent);}
                touch(c,210,438,t,.90f,.04f);
                if(t>.94f){box(c,163,17,67,29,14,tint(surface,accent,.2f));text(c,t>.99f?"Saved":"Save",196,37,12,accent);touch(c,196,30,t,.95f,.04f);}
                c.restore();
            }
            pill(c,1);c.restore();
        }
    }
    private void photoTile(Canvas c,float x,float y,float w,float h,int variant){
        c.save();path.reset();path.addRoundRect(x,y,x+w,y+h,5,5,Path.Direction.CW);c.clipPath(path);
        if(variant%3==0){box(c,x,y,w,h,0,0xffc7b9db);box(c,x,y+h*.55f,w,h*.45f,0,0xff738d80);circle(c,x+w*.48f,y+h*.42f,w*.23f,0xffe4a879);box(c,x+w*.23f,y+h*.65f,w*.52f,h*.4f,w*.18f,0xff425a6e);}
        else if(variant%3==1){photo(c,x,y,w,h);line(c,x+w*.05f,y+h*.81f,x+w*.8f,y+h*.93f,0xff91bdc2);}
        else{box(c,x,y,w,h,0,0xffe3c1a0);box(c,x,y+h*.67f,w,h*.33f,0,0xff7d6865);for(int i=0;i<4;i++){float xx=x+w*(.07f+i*.24f);box(c,xx,y+h*(.23f+(i%2)*.15f),w*.2f,h*.55f,2,i%2==0?0xff827c92:0xffac8b7b);}circle(c,x+w*.72f,y+h*.15f,w*.09f,0xffffe4ad);}
        c.restore();
    }

    private void editor(Canvas c,String title){box(c,0,0,240,500,0,surface);text(c,title,120,31,15,ink);text(c,"Save",207,31,12,accent);photo(c,12,67,216,322);pill(c,1);}
    private void popup(Canvas c,float x,float y,String a,String b,String d,float show){
        if(show<=0)return;c.save();c.translate(0,8*(1-show));alpha(c,show);box(c,x+1,y+4,194,100,15,tint(surface,ink,.14f));box(c,x,y,194,98,15,tint(surface,ink,.07f));text(c,a,x+97,y+25,12,ink);text(c,b,x+97,y+55,12,ink);text(c,d,x+97,y+83,12,ink);c.restore();c.restore();
    }
    private void arrow(Canvas c,float x,float y,float a,float b,float f,int color){float xx=mix(x,a,f),yy=mix(y,b,f);line(c,x,y,xx,yy,color);if(f>.96f){float angle=(float)Math.atan2(b-y,a-x);line(c,a,b,a-14*(float)Math.cos(angle-.5),b-14*(float)Math.sin(angle-.5),color);line(c,a,b,a-14*(float)Math.cos(angle+.5),b-14*(float)Math.sin(angle+.5),color);}}
    private void morphGrid(Canvas c,int a,int b,float f){
        int count=Math.max(a*a,b*b);for(int i=0;i<count;i++){
            float old=opacity;float alpha=i>=a*a?f:i>=b*b?1-f:1;if(alpha<=0)continue;opacity*=alpha;
            float ax=16+208*(i%a+.5f)/a,ay=105+240*(i/a+.5f)/a;
            float bx=16+208*(i%b+.5f)/b,by=105+240*(i/b+.5f)/b;
            if(i>=a*a){ax=bx;ay=by;}if(i>=b*b){bx=ax;by=ay;}
            float x=mix(ax,bx,f),y=mix(ay,by,f),size=mix(50-a*4,50-b*4,f);app(c,i,x,y,size,false);
            // Reserve separate icon and label space in each row; hide labels during movement.
            float labelAlpha=(f>0&&f<1)?0:1;opacity*=labelAlpha;
            cellLabel(c,PreviewIcons.NAMES[i%PreviewIcons.NAMES.length],x,y+size/2+10,mix(208f/a,208f/b,f)-6,mix(a==3?9:a==4?8:7.5f,b==3?9:b==4?8:7.5f,f));opacity=old;
        }
    }
    private void cellLabel(Canvas c,String value,float x,float y,float width,float size){
        p.setTextSize(size);p.setTypeface(font);String shown=value;
        if(p.measureText(shown)>width){while(shown.length()>0&&p.measureText(shown+"\u2026")>width)shown=shown.substring(0,shown.length()-1);shown+="\u2026";}
        c.save();c.clipRect(x-width/2,y-size-2,x+width/2,y+3);text(c,shown,x,y,size,ink);c.restore();
    }
    private void heldTouch(Canvas c,float x,float y,float show){
        if(show<=0)return;float old=opacity;opacity*=show*.85f;outline(c,x-23,y-23,46,46,23,accent);circle(c,x+17,y+17,6,accent);circle(c,x+17,y+17,2.5f,onAccent);opacity=old;
    }
    /** Curved, nested fingerprint ridges, kept separate at this small preview scale. */
    private void fingerprint(Canvas c,float x,float y,int color){
        c.save();c.translate(x-17,y-19);paint(color,true,1.6f);
        path.reset();path.moveTo(2,15);path.cubicTo(2,-2,32,-2,32,15);path.cubicTo(32,25,28,31,25,36);c.drawPath(path,p);
        path.reset();path.moveTo(6,26);path.cubicTo(7,22,6,19,6,15);path.cubicTo(6,3,28,3,28,15);path.cubicTo(28,24,24,31,21,36);c.drawPath(path,p);
        path.reset();path.moveTo(2,25);path.cubicTo(4,31,5,34,8,36);c.drawPath(path,p);
        path.reset();path.moveTo(11,34);path.cubicTo(13,27,10,22,10,16);path.cubicTo(10,8,24,8,24,16);path.cubicTo(24,25,20,31,17,36);c.drawPath(path,p);
        path.reset();path.moveTo(14,29);path.cubicTo(16,24,14,20,14,16);path.cubicTo(14,12,20,12,20,16);path.cubicTo(20,24,17,28,16,31);c.drawPath(path,p);c.restore();
    }
    private void landscapeFrame(Canvas c,int width,int height,float t){
        float scale=Math.min(width/336f,height/300f);if(scale<=0)return;
        c.save();c.translate((width-336*scale)/2,(height-300*scale)/2);c.scale(scale,scale);
        box(c,0,0,336,300,24,tint(surface,accent,.035f));
        float turn=e(.15f,.38f,t),land=e(.32f,.43f,t);
        c.save();c.rotate(-90*turn,168,150);
        box(c,94,2,148,296,25,tint(surface,ink,.24f));box(c,97,5,142,290,23,0xff111216);
        box(c,243,55,3.8f,25,1.8f,muted);box(c,243,103,3.8f,50,1.8f,muted);
        c.save();path.reset();path.addRoundRect(100,8,236,292,20,20,Path.Direction.CW);c.clipPath(path);c.translate(100,8);c.scale(136/240f,284/500f);
        box(c,0,0,240,500,0,surface);
        if(land<1){alpha(c,1-land);text(c,"10:08",38,28,12,ink);text(c,"100%",206,28,10,ink);box(c,28,105,184,280,17,tint(surface,accent,.13f));app(c,3,59,83,22,false);text(c,"Photos",115,88,14,ink);for(int i=0;i<6;i++)box(c,40+(i%2)*82,120+(i/2)*77,72,66,9,tint(surface,i%2==0?BLUE:GREEN,.3f));recentsActions(c);pill(c,1);c.restore();}
        if(land>0){alpha(c,land);c.translate(120,250);c.rotate(90);c.translate(-250,-120);
            box(c,0,0,500,240,0,surface);
            float bar=1-e(.43f,.56f,t);float old=opacity;opacity*=bar;text(c,"10:08",30,16,10,ink);text(c,"100%",468,16,9,ink);opacity=old;
            float centered=e(.43f,.59f,t);
            for(int i=0;i<2;i++){float x=42+i*226;box(c,x,55,190,145,14,tint(surface,accent,.14f));float cx=mix(x+44,x+95,centered);box(c,cx-42,25,84,23,11,tint(surface,accent,.18f));text(c,i==0?"Photos":"Gmail",cx,41,11,ink);
                if(i==0){for(int n=0;n<6;n++)box(c,x+9+(n%3)*59,65+(n/3)*63,54,56,7,tint(surface,n%2==0?BLUE:GREEN,.35f));}
                else for(int n=0;n<4;n++){circle(c,x+20,76+n*31,8,accent);box(c,x+37,70+n*31,130,5,2,tint(surface,ink,.2f));box(c,x+37,81+n*31,95,4,2,tint(surface,ink,.12f));}
            }
            box(c,209,225,82,3,2,ink);c.restore();}
        c.restore();c.restore();
        float old=opacity;opacity*=e(.6f,.7f,t);text(c,"Centered labels. Clearer view.",168,278,12,muted);opacity=old;c.restore();
    }

    private void privacyFolderScene(Canvas c,float t){
        home(c,true);box(c,95,239,50,50,24,tint(surface,accent,.18f));
        app(c,4,108,252,14,false);app(c,6,132,252,14,false);app(c,3,108,276,14,false);app(c,1,132,276,14,false);
        text(c,"Private",120,311,13,ink);
        float unlocked=e(.58f,.69f,t)*(1-e(.91f,.99f,t));
        privacyLockGlyph.draw(c,120,264,18,unlocked,1f);touch(c,120,264,t,.12f,.1f);
        float sheet=e(.22f,.31f,t)*(1-e(.59f,.68f,t));
        if(sheet>0){alpha(c,sheet);box(c,0,0,240,500,0,tint(surface,0xff000000,.3f));box(c,14,182,212,288,24,surface);text(c,"Private folder",120,228,19,ink);text(c,"Authenticate to open",120,263,12,muted);circle(c,120,320,26,tint(surface,accent,.16f));fingerprint(c,120,320,accent);text(c,"Touch fingerprint sensor",120,369,11,muted);box(c,77,390,86,34,17,tint(surface,accent,.18f));text(c,"PIN",120,412,13,accent);touch(c,120,320,t,.49f,.1f);c.restore();}
        float opened=e(.69f,.76f,t)*(1-e(.88f,.96f,t));
        if(opened>0){alpha(c,opened);box(c,0,0,240,500,0,tint(surface,0xff000000,.18f));c.save();c.translate(120,264);float scale=.75f+.25f*opened;c.scale(scale,scale);c.translate(-120,-264);box(c,24,164,192,201,24,surface);for(int i=0;i<6;i++)app(c,i,61+(i%3)*59,207+(i/3)*64,31,true);text(c,"Private",107,344,15,ink);for(int j=0;j<3;j++)circle(c,197,334+j*4,1.3f,muted);c.restore();touch(c,221,400,t,.84f,.08f);c.restore();}
        pill(c,1);
    }

    private void scene(Canvas c,float t){
        if("privacy_folder".equals(key)){privacyFolderScene(c,t);
        }else if("privacy_lock".equals(key)){
            home(c,true);app(c,3,120,263,42,true);
            privacyLockGlyph.draw(c,120,263,17,e(.60f,.72f,t),1f);
            touch(c,120,263,t,.18f,.12f);
            float sheet=e(.31f,.42f,t)*(1-e(.73f,.8f,t));
            if(sheet>0){alpha(c,sheet);box(c,0,0,240,500,0,tint(surface,0xff000000,.3f));box(c,14,177,212,301,24,surface);app(c,3,120,211,31,false);text(c,"Photos",120,249,20,ink);text(c,"Authenticate to open",120,278,12,muted);circle(c,120,332,27,tint(surface,accent,.16f));fingerprint(c,120,332,accent);text(c,"Touch fingerprint sensor",120,380,11,muted);box(c,77,400,86,34,17,tint(surface,accent,.18f));text(c,"PIN",120,422,13,accent);touch(c,120,333,t,.61f,.1f);c.restore();}
            if(t>.79f){float opened=e(.79f,.87f,t);alpha(c,opened);background(c);text(c,"Photos",120,52,22,ink);for(int i=0;i<6;i++){float x=12+(i%2)*114,y=84+(i/2)*105;box(c,x,y,102,94,10,tint(surface,i%2==0?BLUE:GREEN,.3f));circle(c,x+70,y+27,12,YELLOW);}pill(c,1);c.restore();}else pill(c,1);
        }else if(SuperDark.KEY.equals(key)){
            float enabled=e(.23f,.42f,t),shade=e(.57f,.76f,t);
            int bg=tint(0xff222327,0xff000000,enabled),card=tint(0xff393a40,0xff0d0d0d,enabled),raised=tint(0xff48494f,0xff1e1e1e,enabled),white=0xfff3f1f5,secondary=0xffb9b8c0;
            box(c,0,0,240,500,0,bg);text(c,"10:08",36,27,12,white);text(c,"100%",207,27,10,white);
            text(c,"Super Dark-Mode",120,88,22,white);
            box(c,13,116,214,61,19,card);text(c,"Use Super Dark-Mode",97,152,13,white);
            box(c,178,136,36,22,11,tint(raised,accent,enabled));circle(c,mix(189,203,enabled),147,8,tint(secondary,onAccent,enabled));
            for(int i=0;i<3;i++){float y=204+i*65;box(c,13,y,214,56,16,card);circle(c,36,y+28,10,tint(raised,accent,.18f));text(c,DARK_ROWS[i],135,y+33,13,white);}
            touch(c,195,147,t,.13f,.1f);
            if(shade>0){c.save();c.translate(0,-500*(1-shade));box(c,0,0,240,500,0,bg);text(c,"10:08",47,44,24,white);text(c,"Friday, Oct 16",70,68,11,secondary);
                for(int i=0;i<4;i++){float x=13+(i%2)*113,y=92+(i/2)*69;box(c,x,y,101,59,21,i==0?accent:card);text(c,DARK_TILES[i],x+50,y+36,i==3?10:12,i==0?onAccent:white);}
                box(c,13,244,214,25,12,raised);box(c,13,244,143,25,12,accent);circle(c,150,256,8,onAccent);
                box(c,13,294,214,83,20,card);app(c,5,37,319,24,false);text(c,"Messages",110,321,13,white);text(c,"Your Pixel. Your Way.",120,351,12,secondary);c.restore();}
            if(t>.48f&&t<.76f){float f=e(.5f,.73f,t);arrow(c,196,43,196,204,f,accent);heldTouch(c,196,mix(43,204,f),1);}
            box(c,96,486,48,4,2,white);
        }else if(ScreenRecordTidy.KEY.equals(key)){
            home(c,true);float open=e(.08f,.22f,t);alpha(c,open);
            box(c,12,134,216,220,22,tint(surface,ink,.07f));text(c,"Record your screen",120,167,17,ink);
            text(c,"Entire screen",120,208,14,ink);text(c,"Device audio",120,249,14,ink);text(c,"Show touches",120,290,14,ink);
            box(c,99,321,30,16,8,accent);circle(c,119,329,6,onAccent);
            box(c,48,368,144,43,21,tint(surface,accent,.25f));text(c,"\u00d7",70,396,22,ink);text(c,"Start",143,395,15,ink);
            touch(c,70,390,t,.72f,.09f);c.restore();pill(c,1);
        }else if("folder_sort".equals(key)){
            drawer(c,-1);box(c,9,83,222,306,22,tint(surface,ink,.07f));
            float sorted=customCycle?1:e(.82f,.95f,t),custom=customCycle?e(.7f,.93f,t):0;
            int[] rank={6,2,8,0,5,1,7,3,4},kinds={22,20,1,9,6,4,0,3,8};
            String[] labels={"Amazon","Brave","Chrome","Drive","Files","Gmail","Keep","Photos","YouTube"};
            for(int i=0;i<9;i++){int from=rank[i];float x=mix(46+(from%3)*74,46+(i%3)*74,sorted),y=mix(125+(from/3)*65,125+(i/3)*65,sorted);
                if(i==0)x=mix(x,120,custom);else if(i==1)x=mix(x,46,custom);
                app(c,kinds[i],x,y,32,false);float old=opacity;if((sorted>0&&sorted<1)||(custom>0&&custom<1))opacity=0;cellLabel(c,labels[i],x,y+27,66,9);opacity=old;}
            text(c,"Apps",42,367,16,ink);text(c,"\u22ee",207,367,22,ink);
            // One action per loop: Auto then Custom, each with a readable pause on the choices.
            touch(c,207,360,t,.07f,.07f);touch(c,153,284,t,.28f,.07f);
            float menu=e(.15f,.2f,t)*(1-e(.61f,.65f,t));
            if(menu>0){alpha(c,menu);float expanded=e(.35f,.42f,t);box(c,84,233,143,106+66*expanded,15,tint(surface,ink,.1f));text(c,"+  Add apps",153,258,12,ink);text(c,"Sort  \u2304",153,286,12,ink);
                if(expanded>0){float old=opacity;opacity*=expanded;text(c,"Auto (A\u2013Z)"+(customCycle?"  \u2713":""),153,320,11,ink);text(c,"Custom"+(!customCycle?"  \u2713":""),153,350,11,ink);opacity=old;}
                text(c,"5\u00d75   4\u00d74   3\u00d73",154,323+66*expanded,10,muted);c.restore();}
            touch(c,153,customCycle?350:320,t,.53f,.07f);
            if(!customCycle){float confirm=e(.65f,.7f,t)*(1-e(.8f,.83f,t));if(confirm>0){alpha(c,confirm);box(c,16,192,208,128,20,surface);outline(c,16,192,208,128,20,tint(surface,ink,.15f));text(c,"Sort apps A\u2013Z?",120,224,16,ink);text(c,"No",72,286,13,muted);text(c,"Yes",176,286,13,accent);touch(c,176,280,t,.74f,.06f);c.restore();}}
            else if(t>.68f&&t<.94f)heldTouch(c,mix(46,120,custom),125,1);pill(c,1);
        }else if("folder_reorder".equals(key)){
            drawer(c,0);box(c,9,74,222,320,22,tint(surface,ink,.07f));float move=e(.22f,.65f,t);
            for(int i=1;i<9;i++){float target=i-1;float rank=mix(i,target,move);int from=i,to=i-1;float x=mix(46+(from%3)*74,46+(to%3)*74,move),y=mix(124+(from/3)*78,124+(to/3)*78,move);app(c,i,x,y,36,true);}
            float x=mix(46,194,move),y=mix(124,280,move);app(c,0,x,y,40,false);touch(c,x,y,t,.12f,.1f);text(c,"Apps",44,366,16,ink);pill(c,1);
        }else if(Feature.FLASHLIGHT.equals(key)){
            torch(c,e(.2f,.3f,t));
        }else if(Feature.VOLDOWN_CAMERA.equals(key)){
            box(c,0,0,240,500,0,0xff000000);float camera=e(.21f,.29f,t);if(camera>0){alpha(c,camera);photo(c,0,38,240,331);box(c,0,0,240,38,0,0xff15161b);box(c,0,369,240,131,0,0xff15161b);text(c,"PHOTO",120,400,12,0xfff5f5f5);text(c,"VIDEO",190,400,11,0xff9b9ca3);circle(c,120,446,29,0xfffafafa);circle(c,120,446,24,0xff25262c);circle(c,120,446,21,0xfffafafa);outline(c,40,438,18,18,5,0xff9b9ca3);circle(c,198,447,10,0xff60626a);c.restore();}
        }else if(Feature.THREE_FINGER.equals(key)){
            chat(c);float swipe=e(.08f,.32f,t);float visible=1-e(.35f,.43f,t);if(visible>0){float old=opacity;opacity=visible;for(int i=0;i<3;i++){float x=75+i*45,y=130+swipe*190;line(c,x,y-26,x,y,accent);circle(c,x,y,9,accent);}opacity=old;}
            float flash=e(.32f,.36f,t)*(1-e(.36f,.42f,t));if(flash>0){float old=opacity;opacity=flash*.65f;box(c,0,0,240,480,0,0xffffffff);opacity=old;}
            float captured=e(.36f,.6f,t);if(captured>0){c.save();float size=mix(1,.27f,captured),x=mix(0,12,captured),y=mix(0,338,captured);c.translate(x,y);c.scale(size,size);for(int i=5;i>0;i--)box(c,-i*2,6,240+i*4,480,18,tint(surface,ink,.035f*i));chat(c);outline(c,0,0,240,480,10,accent);c.restore();}pill(c,1);
        }else if(Feature.SCREENSHOT_FREE_CROP.equals(key)){
            editor(c,"Crop");float squeeze=e(.12f,.62f,t);float x=mix(20,61,squeeze),top=mix(80,220,squeeze),w=mix(200,118,squeeze),h=mix(294,12,squeeze);
            float old=opacity;opacity=.55f;box(c,12,67,216,top-67,0,0xff101217);box(c,12,top+h,216,389-top-h,0,0xff101217);box(c,12,top,x-12,h,0,0xff101217);box(c,x+w,top,228-x-w,h,0,0xff101217);opacity=old;
            outline(c,x,top,w,h,1,accent);for(int i=0;i<2;i++){float xx=i==0?x:x+w;line(c,xx-7,top,xx+7,top,accent);line(c,xx-7,top+h,xx+7,top+h,accent);}touch(c,x,top,t,.25f,.3f);text(c,"Crop freely",120,438,15,ink);
        }else if(Feature.PHOTOS_DOODLES.equals(key)){
            photosDoodles(c,t);
        }else if(Feature.SCREENSHOT_DOODLES.equals(key)){
            editor(c,"Doodles");arrow(c,36,325,193,119,e(.06f,.25f,t),0xffffce6b);float oval=e(.21f,.4f,t);paint(0xfffafafa,true,4);c.drawArc(39,106,115,181,-90,360*oval,false,p);float rectangle=e(.37f,.54f,t);outline(c,120,249,76*rectangle,59*rectangle,7,0xfff58ab1);arrow(c,37,355,199,355,e(.54f,.68f,t),0xffb6e0bc);float tri=e(.66f,.81f,t);if(tri>0){float old=opacity;opacity=tri;path.reset();path.moveTo(147,196);path.lineTo(172,238);path.lineTo(122,238);path.close();paint(0xffffce6b,true,3);c.drawPath(path,p);opacity=old;}box(c,19,414,202,50,20,tint(surface,ink,.07f));for(int i=0;i<5;i++){int color=i%2==0?accent:ink;float xx=41+i*40;if(i==0)arrow(c,xx-7,446,xx+7,432,1,color);else if(i==1)outline(c,xx-7,431,14,14,7,color);else if(i==2)outline(c,xx-7,432,14,13,2,color);else line(c,xx-7,444,xx+7,433,color);}
        }else if(Feature.DOUBLE_TAP_SLEEP.equals(key)){
            float lock=e(.2f,.32f,t);background(c);text(c,"10:08",120,210,38,ink);float old=opacity;opacity=1-lock;dock(c);pill(c,1);opacity=old;alpha(c,lock);box(c,0,0,240,500,0,0xff000000);text(c,"10:08",120,210,38,0xffc3c4ce);c.restore();touch(c,120,300,t,.07f,.07f);touch(c,120,300,t,.15f,.07f);
        }else if(Feature.TAP_CHECK.equals(key)){
            float wake=e(.2f,.32f,t);box(c,0,0,240,500,0,tint(0xff000000,0xff17181e,wake));text(c,"10:08",120,210,38,tint(0xff4d4f57,0xfff1f0f5,wake));touch(c,120,300,t,.07f,.07f);touch(c,120,300,t,.15f,.07f);
        }else if(LauncherFeatures.GRID.equals(key)){
            home(c,true);box(c,9,74,222,321,20,tint(surface,ink,.06f));
            // The selected choice changes at the end of the tap, before the grid begins its transition.
            if(t<.49f)morphGrid(c,3,4,e(.25f,.39f,t));else morphGrid(c,4,5,e(.7f,.84f,t));
            text(c,"Apps",43,374,16,ink);for(int dot=0;dot<3;dot++)circle(c,209,361+dot*6,1.8f,ink);
            float menu=e(.06f,.1f,t)*(1-e(.25f,.29f,t))+e(.51f,.55f,t)*(1-e(.7f,.74f,t));
            if(menu>0){alpha(c,menu);box(c,107,263,120,105,14,tint(surface,ink,.12f));String selected=t<.24f?"3\u00d73":t<.69f?"4\u00d74":"5\u00d75";
                text(c,"5\u00d75"+("5\u00d75".equals(selected)?"  \u2713":""),165,288,13,ink);text(c,"4\u00d74"+("4\u00d74".equals(selected)?"  \u2713":""),165,320,13,ink);text(c,"3\u00d73"+("3\u00d73".equals(selected)?"  \u2713":""),165,352,13,ink);c.restore();}
            touch(c,209,367,t,.025f,.045f);touch(c,165,315,t,.18f,.06f);touch(c,209,367,t,.475f,.045f);touch(c,165,283,t,.63f,.06f);pill(c,1);
        }else if(LauncherFeatures.DRAWER.equals(key)){
            drawer(c,0,1);float move=e(.14f,.47f,t),merge=e(.48f,.6f,t);float old=opacity;opacity=1-merge;app(c,1,91,104,34,true);app(c,0,mix(33,91,move),104,34,false);opacity=old;touch(c,33,104,t,.04f,.1f);if(merge>0){alpha(c,merge);box(c,71,84,40,40,14,tint(surface,accent,.32f));app(c,0,83,96,14,false);app(c,1,99,96,14,false);text(c,"Apps",91,131,10,ink);c.restore();}pill(c,1);
        }else if(LauncherFeatures.DRAG_PILL.equals(key)){
            float lift=e(.16f,.4f,t),exit=e(.42f,.62f,t),drop=e(.65f,.84f,t);home(c,true);pill(c,1);c.save();c.translate(0,exit*520);drawer(c,0);float target=e(.05f,.14f,t);box(c,15,25-55*(1-target),210,40,20,tint(surface,accent,.22f));text(c,"+ Add to home screen",120,50-55*(1-target),13,ink);c.restore();float x=mix(33,120,lift),y=mix(104,44,lift);x=mix(x,61,drop);y=mix(y,260,drop);app(c,0,x,y,34,false);if(drop==1)text(c,"Keep",61,288,11,ink);touch(c,33,104,t,.02f,.08f);
        }else if(LauncherFeatures.ICONS.equals(key)){
            home(c,true);float picked=e(.73f,.85f,t);if(picked<1)app(c,0,70,252,42,true);if(picked>0){alpha(c,picked);noteIcon(c,70,252,42);text(c,"Keep",70,285,11,ink);c.restore();}touch(c,70,252,t,.04f,.14f);
            float menu=e(.14f,.2f,t)*(1-e(.35f,.4f,t));popup(c,22,292,"Change icon","Rename","App info",menu);touch(c,120,315,t,.29f,.07f);
            float picker=e(.39f,.46f,t)*(1-e(.7f,.78f,t));if(picker>0){alpha(c,picker);box(c,8,167,224,333,24,tint(surface,ink,.04f));box(c,99,179,42,4,2,muted);text(c,"Choose an image",120,215,18,ink);app(c,3,67,263,36,false);text(c,"Photos",67,295,13,ink);app(c,6,174,263,36,false);text(c,"Files",174,295,13,ink);box(c,23,321,91,115,8,tint(surface,accent,.1f));noteIcon(c,68,378,65);photo(c,126,321,91,115);touch(c,67,263,t,.48f,.08f);touch(c,68,368,t,.63f,.08f);c.restore();}pill(c,1);
        }else if(LauncherFeatures.RENAME.equals(key)){
            home(c,true);app(c,0,70,252,42,false);text(c,t>.84f?"Keep":"Keep Notes",70,286,11,ink);touch(c,70,252,t,.03f,.12f);float menu=e(.14f,.2f,t)*(1-e(.32f,.38f,t));popup(c,22,292,"Change icon","Rename","App info",menu);touch(c,120,347,t,.25f,.07f);
            float dialog=e(.36f,.44f,t)*(1-e(.77f,.84f,t));if(dialog>0){alpha(c,dialog);box(c,14,176,212,161,23,tint(surface,ink,.06f));text(c,"Rename app",120,208,18,ink);box(c,29,225,182,45,8,tint(surface,accent,.12f));text(c,t<.54f?"Keep Notes":t<.6f?"K":t<.65f?"Kee":"Keep",120,254,17,ink);text(c,"Cancel",72,311,13,muted);text(c,"Save",180,311,13,accent);touch(c,180,306,t,.71f,.07f);c.restore();}pill(c,1);
        }else if(Feature.CLEAR_ALL.equals(key)){
            background(c);float dismiss=e(.23f,.53f,t);if(dismiss<1){for(int i=0;i<3;i++){float x=-44+i*112,y=78-dismiss*620;box(c,x,y,104,303,15,tint(surface,ink,.12f));box(c,x+3,y+4,98,295,12,surface);app(c,i==0?0:i==1?5:1,x+52,y+33,24,false);for(int j=0;j<5;j++)box(c,x+12,y+64+j*30,80-j%2*22,8,4,tint(surface,accent,.15f));}}else text(c,"No recent apps",120,245,17,muted);
            float old=opacity;opacity=1-dismiss;recentsActions(c);opacity=old;touch(c,198,439,t,.12f,.1f);pill(c,1);
        }else if(RecentsLock.KEY.equals(key)){
            background(c);float push=e(.5f,.57f,t)*(1-e(.57f,.68f,t));
            for(int i=0;i<3;i++){float x=-44+i*112,y=78-(i==1?push*36:0);box(c,x,y,104,303,15,tint(surface,ink,.12f));box(c,x+3,y+4,98,295,12,surface);app(c,i==0?0:i==1?5:1,x+52,y+33,24,false);for(int j=0;j<5;j++)box(c,x+12,y+64+j*30,80-j%2*22,8,4,tint(surface,accent,.15f));}
            touch(c,120,111,t,.04f,.1f);popup(c,23,126,"App info","Split screen","Lock",e(.12f,.18f,t)*(1-e(.3f,.35f,t)));touch(c,120,209,t,.25f,.07f);
            float badge=e(.34f,.42f,t);if(badge>0){c.save();c.translate(120,52-push*36);c.scale(badge,badge);circle(c,0,0,14,tint(surface,ink,.1f));box(c,-6,-1,12,9,2,ink);paint(ink,true,2.2f);c.drawArc(new android.graphics.RectF(-4,-8,4,0),180,180,false,p);line(c,-4,-4,-4,-1,ink);line(c,4,-4,4,-1,ink);c.restore();}
            touch(c,120,300,t,.47f,.12f);float msg=e(.57f,.63f,t)*(1-e(.86f,.92f,t));if(msg>0){alpha(c,msg);box(c,66,10,108,26,13,tint(surface,ink,.1f));text(c,"App is locked",120,27,11,ink);c.restore();}
            recentsActions(c);pill(c,1);
        }else if(LauncherFeatures.HIDE_SHORTCUTS.equals(key)){
            drawer(c,0,11);app(c,11,33,104,34,true);app(c,0,207,246,34,true);touch(c,33,104,t,.03f,.1f);float show=e(.11f,.18f,t),hide=e(.33f,.59f,t);if(show>0){alpha(c,show);box(c,13,140,214,166-48*hide,18,tint(surface,ink,.08f));float old=opacity;opacity=1-hide;text(c,"Search",120,163,12,ink);text(c,"Voice search",120,187,12,ink);opacity=old;text(c,"App info",120,211-48*hide,12,ink);text(c,"Add to home screen",120,235-48*hide,12,ink);text(c,"Change icon",120,259-48*hide,12,ink);text(c,"Rename",120,283-48*hide,12,ink);c.restore();}pill(c,1);
        }else if(MultiDrag.KEY.equals(key)){
            float lift=e(.08f,.17f,t),pick1=e(.23f,.31f,t),pick2=e(.36f,.44f,t),toTarget=e(.5f,.63f,t),exit=e(.64f,.75f,t),drop=e(.78f,.91f,t);
            home(c,true);pill(c,1);c.save();c.translate(0,exit*520);drawer(c,0,t>=.23f?4:-1,t>=.36f?8:-1);
            float target=e(.1f,.16f,t);if(target>0){alpha(c,target);box(c,15,25,210,40,20,tint(surface,accent,.25f));text(c,"+ Add to home screen",120,50,13,ink);c.restore();}c.restore();
            float hx=mix(mix(33,66,lift),120,toTarget),hy=mix(mix(104,156,lift),45,toTarget);
            hx=mix(hx,61,drop);hy=mix(hy,266,drop);
            float spread=e(.9f,.98f,t);
            if(pick2>0)app(c,8,mix(mix(33,hx+10,pick2),179,spread),mix(mix(246,hy+10,pick2),266,spread),36,false);
            if(pick1>0)app(c,4,mix(mix(33,hx+5,pick1),120,spread),mix(mix(175,hy+5,pick1),266,spread),36,false);
            app(c,0,hx,hy,40,false);touch(c,33,104,t,.025f,.055f);heldTouch(c,hx,hy, e(.06f,.1f,t)*(1-e(.9f,.94f,t)));
            touch(c,33,175,t,.19f,.05f);touch(c,33,246,t,.32f,.05f);
            int count=t>=.36f?3:t>=.23f?2:1;if(t<.9f){circle(c,hx+20,hy-20,11,accent);text(c,String.valueOf(count),hx+20,hy-16,12,onAccent);}
            if(spread==1){text(c,"Keep",61,298,10,ink);text(c,"Gmail",120,298,10,ink);text(c,"YouTube",179,298,10,ink);}
        }else if(ScatterEffect.KEY.equals(key)){
            drawer(c,0,1);float go=e(.3f,.75f,t);float old=opacity;opacity=1-e(.3f,.5f,t);box(c,91,252,60,60,18,tint(surface,accent,.35f));opacity=old;
            for(int i=0;i<14;i++){float a=i*.449f,r=10+go*(36+(i%4)*10);float o=opacity;opacity=go>0?1-go:0;circle(c,121+(float)Math.cos(a)*r,282+(float)Math.sin(a)*r,3.2f*(1-go*.5f),i%3==0?accent:tint(surface,ink,.5f));opacity=o;}
        }else if(FolderDrag.TWO_FINGER.equals(key)){
            float lift=e(.13f,.22f,t),swipe=e(.38f,.67f,t),drop=e(.8f,.91f,t);home(c,true);
            c.save();c.translate(-240*swipe,0);for(int i=0;i<6;i++){if(i!=4)app(c,i,60+60*(i%3),218+82*(i/3),36,true);}if(lift<1){float old=opacity;opacity*=1-lift;app(c,9,120,300,36,true);opacity=old;}
            for(int i=0;i<6;i++)app(c,i<3?i+6:i+7,300+60*(i%3),218+82*(i/3),36,true);c.restore();
            float hx=mix(120,60,drop),hy=mix(300,382,drop);app(c,9,hx,hy,36+6*lift*(1-drop),false);if(drop==1)text(c,"Drive",hx,hy+30,10,ink);
            touch(c,120,300,t,.035f,.1f);heldTouch(c,hx,hy,e(.1f,.16f,t)*(1-e(.9f,.94f,t)));
            float finger=e(.29f,.34f,t)*(1-e(.7f,.76f,t));if(finger>0){float old=opacity;opacity*=finger;arrow(c,199,410,79,410,swipe,accent);heldTouch(c,mix(199,79,swipe),410,1);text(c,"Swipe",120,444,11,accent);opacity=old;}
            if(t>.16f&&t<.79f)text(c,"Hold",120,347,11,accent);circle(c,112,469,3,swipe<.5f?accent:muted);circle(c,128,469,3,swipe<.5f?muted:accent);pill(c,1);
        }else if(Feature.FILES_SORT.equals(key)){
            box(c,0,0,240,500,0,surface);text(c,"Files",43,35,23,ink);text(c,"Newest first",177,35,12,accent);
            box(c,12,57,216,35,10,tint(surface,accent,.12f));folderIcon(c,32,73);text(c,"Downloads",128,80,13,ink);
            box(c,12,99,216,35,10,tint(surface,accent,.12f));folderIcon(c,32,115);text(c,"Documents",128,122,13,ink);
            text(c,"Files \u00b7 date modified",120,151,10,muted);float f=e(.16f,.7f,t);for(int i=0;i<6;i++)if(i!=2)fileRow(c,i,f);fileRow(c,2,f);pill(c,1);
        }else {home(c,true);pill(c,1);}
    }
}
