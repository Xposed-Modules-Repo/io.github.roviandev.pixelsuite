package io.github.pixelsuite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Images remain in launcher-private storage; the document grant is used only for import. */
final class LauncherIcon {
    static final int REQUEST=0x5053;
    static final String PENDING="pixelsuite_pending_icon";
    static final Map<Object,Object> originals=Collections.synchronizedMap(new WeakHashMap<Object,Object>());
    static final Map<String,Object> nativeIcons=new HashMap<String,Object>();
    static final Map<String,Object> cache=new HashMap<String,Object>();
    static final Set<String> missing=new HashSet<String>();
    /**
     * Custom icon bitmaps that were dropped (Reset icon, a new image, a shape change). Items can still hold one, and
     * apply() must not mistake it for the app's own icon: it used to adopt it as the "original", so Reset icon kept the
     * custom picture for good. Weak, so dropped bitmaps are not kept alive.
     */
    static final Set<Object> retired=Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
    /** Drops the cached custom icon of {@code key} (caller holds the cache lock). */
    static void retire(String key){Object old=cache.remove(key);if(old!=null)retired.add(old);missing.remove(key);}
    static final ExecutorService IO=new ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>());
    static final ThreadLocal<Object> oldTheme=new ThreadLocal<Object>();
    static Object get(Object o,String f){return LauncherFeatures.get(o,f);}
    static Object call(Object o,String f,Object...v){return LauncherFeatures.call(o,f,v);}
    static void install(){
        Xp.hookAllMethods(LauncherFeatures.type("uioverrides.QuickstepLauncher"),"handlePendingActivityRequest",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(((Number)get(p.thisObject,"mPendingActivityRequestCode")).intValue()==REQUEST && Xp.getAdditionalInstanceField(p.thisObject,PENDING)!=null)p.setResult(null);
            }
        });
        Xp.hookAllMethods(LauncherFeatures.type("graphics.ThemeManager"),"verifyIconState",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){oldTheme.set(get(p.thisObject,"iconState"));}
            protected void afterHookedMethod(Xp.Param p){
                Object previous=oldTheme.get();oldTheme.remove();if(previous==get(p.thisObject,"iconState"))return;
                synchronized(cache){retired.addAll(cache.values());cache.clear();missing.clear();}
                final Activity a=LauncherFeatures.activity.get();if(a==null)return;
                a.runOnUiThread(new Runnable(){public void run(){
                    try{
                        Object provider=Xp.callStaticMethod(LauncherFeatures.type("dagger.LauncherComponentProvider"),"get",a);
                        ((java.util.Queue<?>)get(call(provider,"getIconPool"),"pool")).clear();
                        LauncherFeatures.refresh();
                        AppPicker.refreshIcons();
                    }catch(Throwable error){Module.log("Custom icon shape refresh failed",error);}
                }});
            }
        });
        Xp.hookAllMethods(LauncherFeatures.type("Launcher"),"onSaveInstanceState",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){Object key=Xp.getAdditionalInstanceField(p.thisObject,PENDING);if(key instanceof String)((Bundle)p.args[0]).putString(PENDING,(String)key);}
        });
        Xp.hookAllMethods(LauncherFeatures.type("Launcher"),"onCreate",new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){if(p.args[0] instanceof Bundle){String key=((Bundle)p.args[0]).getString(PENDING);if(key!=null)Xp.setAdditionalInstanceField(p.thisObject,PENDING,key);}}
        });
        Xp.hookAllMethods(LauncherFeatures.type("proxy.ProxyActivityStarter"),"getLaunchIntent",new Xp.Callback(){
            protected void afterHookedMethod(Xp.Param p){
                if(p.args.length<2 || p.args[1]==null || ((Number)get(p.args[1],"requestCode")).intValue()!=REQUEST || !(p.getResult() instanceof Intent))return;
                Object key=Xp.getAdditionalInstanceField(p.args[0],PENDING);
                if(key instanceof String)((Intent)p.getResult()).putExtra(PENDING,(String)key);
            }
        });
        Xp.hookAllMethods(LauncherFeatures.type("proxy.ProxyActivityStarter"),"onActivityResult",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                Activity receiver=(Activity)p.thisObject;
                if(((Number)p.args[0]).intValue()!=REQUEST || !receiver.getIntent().hasExtra(PENDING))return;
                p.setResult(null);receive(receiver,((Number)p.args[1]).intValue(),(Intent)p.args[2],true);
            }
        });
        Xp.hookAllMethods(LauncherFeatures.type("Launcher"),"onActivityResult",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(((Number)p.args[0]).intValue()!=REQUEST || Xp.getAdditionalInstanceField(p.thisObject,PENDING)==null)return;
                p.setResult(null);receive((Activity)p.thisObject,((Number)p.args[1]).intValue(),(Intent)p.args[2],false);
            }
        });
    }
    static void finishReceiver(Activity receiver,boolean proxy){
        if(!proxy)return;
        try{Object params=get(receiver,"mParams");Object callback=params==null?null:get(params,"mPICallback");if(callback instanceof android.app.PendingIntent)((android.app.PendingIntent)callback).cancel();}catch(Throwable ignored){}
        receiver.finishAndRemoveTask();
    }
    static void receive(final Activity receiver,int resultCode,Intent result,final boolean proxy){
        Activity owner=proxy?LauncherFeatures.activity.get():receiver;
        String pending=proxy?receiver.getIntent().getStringExtra(PENDING):(String)Xp.getAdditionalInstanceField(receiver,PENDING);
        if(owner!=null){LauncherFeatures.set(owner,"mPendingActivityRequestCode",-1);Xp.setAdditionalInstanceField(owner,PENDING,null);}
        final String key=pending;
        Module.log("Icon picker result: "+resultCode+", pending="+(key!=null));
        if(key==null || resultCode!=Activity.RESULT_OK || result==null || result.getData()==null){finishReceiver(receiver,proxy);return;}
        final Uri uri=result.getData();
        // Keep the granted recipient alive until the selected image has been copied.
        // Forwarding its URI through the launcher's PendingIntent crashes this Android build.
        // 2.0.6-preview45 (owner's Feature 1): the picture is read here, then opens in the icon editor (move, zoom, the
        // launcher's icon shape as the frame); the check mark there saves the icon. Photos and Files both come here.
        IO.execute(new Runnable(){public void run(){
            Bitmap picture=null;
            try{picture=load(receiver,uri);}catch(Throwable error){Module.log("Custom icon import failed",error);}
            final Bitmap loaded=picture;
            receiver.runOnUiThread(new Runnable(){public void run(){
                Activity host=proxy?LauncherFeatures.activity.get():receiver;
                if(loaded==null){
                    if(!receiver.isDestroyed())Toast.makeText(receiver,"Could not read this image. Try another image.",Toast.LENGTH_SHORT).show();
                    finishReceiver(receiver,proxy);return;
                }
                if(host==null||host.isDestroyed()||host.isFinishing()){
                    // No launcher to show the editor on (it was restarted while the picker was open): the picture is
                    // used as before the editor existed, its middle square.
                    final Context app=receiver.getApplicationContext();
                    IO.execute(new Runnable(){public void run(){
                        boolean success=false;
                        try{int side=Math.min(loaded.getWidth(),loaded.getHeight());RectF middle=new RectF((loaded.getWidth()-side)/2f,(loaded.getHeight()-side)/2f,(loaded.getWidth()+side)/2f,(loaded.getHeight()+side)/2f);
                            Bitmap icon=IconEditor.render(loaded,middle,512);try{store(app,key,icon);success=true;}finally{icon.recycle();}}
                        catch(Throwable error){Module.log("Custom icon save failed",error);}
                        finally{loaded.recycle();}
                        final boolean ok=success;
                        main.post(new Runnable(){public void run(){
                            if(ok){synchronized(cache){retire(key);}LauncherFeatures.refresh();}
                            if(!receiver.isDestroyed())Toast.makeText(receiver,ok?"Icon updated":"Could not read this image. Try another image.",Toast.LENGTH_SHORT).show();
                            finishReceiver(receiver,proxy);
                        }});
                    }});
                    return;
                }
                finishReceiver(receiver,proxy);
                final Context hostApp=host.getApplicationContext();
                Chosen chosen=chosen(key);
                IconEditor.show(host,loaded,chosen.label,chosen.icon.get(),new IconEditor.Result(){
                    public void save(final Bitmap picture,final RectF area,final IconEditor.Done done){
                        final Context c=hostApp;
                        IO.execute(new Runnable(){public void run(){
                            boolean success=false;
                            try{Bitmap icon=IconEditor.render(picture,area,512);try{store(c,key,icon);success=true;}finally{icon.recycle();}}
                            catch(Throwable error){Module.log("Custom icon save failed",error);}
                            final boolean ok=success;
                            main.post(new Runnable(){public void run(){
                                if(ok){synchronized(cache){retire(key);}LauncherFeatures.refresh();}
                                done.run(ok);
                            }});
                        }});
                    }
                });
            }});
        }});
    }
    static final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    /** The app whose icon is being changed: its name and its icon on screen (for the editor). */
    static final class Chosen{String label="";java.lang.ref.WeakReference<android.view.View> icon=new java.lang.ref.WeakReference<android.view.View>(null);}
    static final Map<String,Chosen> chosenApps=new HashMap<String,Chosen>();
    static Chosen chosen(String key){synchronized(chosenApps){Chosen c=chosenApps.get(key);return c!=null?c:new Chosen();}}    static String filename(String key)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));StringBuilder s=new StringBuilder();
        for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString()+".png";
    }
    static File file(Context c,String key)throws Exception{return new File(new File(c.getFilesDir(),"pixelsuite-icons"),filename(key));}
    static void choose(final Object info){choose(info,null);}
    /** {@code popup}: the long-press menu it came from (its icon is where the edited icon flies to). */
    static void choose(final Object info,Object popup){
        final Activity a=LauncherFeatures.activity.get();final String key=LauncherFeatures.identity(info);if(a==null||key==null)return;
        Chosen chosen=new Chosen();
        try{Object title=get(info,"title");if(title!=null)chosen.label=title.toString();}catch(Throwable ignored){}
        try{Object view=popup==null?null:get(popup,"originalIcon");if(view instanceof android.view.View)chosen.icon=new java.lang.ref.WeakReference<android.view.View>((android.view.View)view);}catch(Throwable ignored){}
        synchronized(chosenApps){chosenApps.clear();chosenApps.put(key,chosen);}
        pick(a,key);
    }
    static void pick(final Activity a,final String key){
        AlertDialog.Builder chooser=new AlertDialog.Builder(a).setTitle("Change icon")
            .setItems(new String[]{"Photos","Files"},new DialogInterface.OnClickListener(){
                public void onClick(DialogInterface dialog,int which){openPicker(a,key,which==0);}
            }).setNegativeButton("Cancel",null);
        try{if(file(a,key).isFile())chooser.setNeutralButton("Reset icon",new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,int which){reset(a,key);}});}catch(Exception ignored){}
        AlertDialog dialog=FrostedSurface.show(chooser.create());
        android.widget.Button button=dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if(button!=null&&button.getVisibility()==android.view.View.VISIBLE){
            int size=LauncherFeatures.dp(a,24);LauncherGlyph arrow=new LauncherGlyph(a,4);arrow.setBounds(0,0,size,size);
            button.setText("");button.setContentDescription("Reset icon");button.setCompoundDrawablesRelative(arrow,null,null,null);button.setCompoundDrawablePadding(0);
            button.setPadding(LauncherFeatures.dp(a,12),LauncherFeatures.dp(a,12),LauncherFeatures.dp(a,12),LauncherFeatures.dp(a,12));button.setMinimumWidth(LauncherFeatures.dp(a,48));button.setMinimumHeight(LauncherFeatures.dp(a,48));
            android.view.ViewGroup.LayoutParams params=button.getLayoutParams();params.width=LauncherFeatures.dp(a,48);button.setLayoutParams(params);
            try{call(button,"setTooltipText","Reset to original icon");}catch(Throwable older){}
        }
    }
    static void reset(final Activity a,final String key){IO.execute(new Runnable(){public void run(){
        boolean success=false;try{File f=file(a,key);success=!f.exists()||f.delete();}catch(Exception ignored){}
        final boolean ok=success;a.runOnUiThread(new Runnable(){public void run(){
            if(ok)synchronized(cache){retire(key);}
            LauncherFeatures.refresh();Toast.makeText(a,ok?"Original icon restored":"Could not reset the icon",0).show();
        }});
    }});}
    static void openPicker(Activity a,String key,boolean photos){
        Intent intent=new Intent(photos?Intent.ACTION_GET_CONTENT:Intent.ACTION_OPEN_DOCUMENT).setType("image/*");
        if(photos)intent.setPackage("com.google.android.apps.photos");else intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Xp.setAdditionalInstanceField(a,PENDING,key);
        try{a.startActivityForResult(intent,REQUEST);}catch(Exception error){Xp.setAdditionalInstanceField(a,PENDING,null);Toast.makeText(a,photos?"Photos is unavailable. Choose Files instead.":"Files is unavailable",0).show();}
    }
    /**
     * The picked picture, upright, at most 2048 pixels on its longer side (enough to zoom in on part of it for the
     * editor). Copied out of the picker's grant first; the copy is deleted straight away.
     */
    static Bitmap load(Context c,Uri uri)throws Exception{
        File dir=new File(c.getFilesDir(),"pixelsuite-icons");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create icon storage");
        File input=File.createTempFile("import-",".image",dir);
        Bitmap decoded=null,oriented=null;
        try{
            try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(input)){
                if(in==null)throw new IOException("No image stream");byte[] buffer=new byte[16384];int n,total=0;
                while((n=in.read(buffer))!=-1){total+=n;if(total>32*1024*1024)throw new IOException("Image is too large");out.write(buffer,0,n);}
            }
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(input.getPath(),options);
            if(options.outWidth<=0||options.outHeight<=0||Math.max(options.outWidth,options.outHeight)>32768)throw new IOException("Unsupported dimensions");
            options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            decoded=BitmapFactory.decodeFile(input.getPath(),options);if(decoded==null)throw new IOException("Unsupported image");
            int orientation=1;try{orientation=new ExifInterface(input.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
            Matrix matrix=new Matrix();
            if(orientation==2)matrix.setScale(-1,1);else if(orientation==3)matrix.setRotate(180);else if(orientation==4)matrix.setScale(1,-1);
            else if(orientation==5){matrix.setRotate(90);matrix.postScale(-1,1);}else if(orientation==6)matrix.setRotate(90);
            else if(orientation==7){matrix.setRotate(270);matrix.postScale(-1,1);}else if(orientation==8)matrix.setRotate(270);
            oriented=matrix.isIdentity()?decoded:Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),matrix,true);
            if(oriented!=decoded){decoded.recycle();decoded=null;}
            Bitmap result=oriented;oriented=null;decoded=null;return result;
        }finally{
            input.delete();if(oriented!=null&&oriented!=decoded)oriented.recycle();if(decoded!=null)decoded.recycle();
        }
    }
    /** Writes the finished icon picture for the app (replacing any earlier one in one step). */
    static void store(Context c,String key,Bitmap icon)throws Exception{
        File dir=new File(c.getFilesDir(),"pixelsuite-icons");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create icon storage");
        File output=File.createTempFile("icon-",".png",dir);
        try{
            try(FileOutputStream out=new FileOutputStream(output)){if(!icon.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot encode image");out.getFD().sync();}
            if(!output.renameTo(file(c,key)))throw new IOException("Cannot save image");
        }finally{output.delete();}
    }
    static void importImage(Context c,String key,Uri uri)throws Exception{
        File dir=new File(c.getFilesDir(),"pixelsuite-icons");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create icon storage");
        File input=File.createTempFile("import-",".image",dir),output=File.createTempFile("icon-",".png",dir);
        Bitmap decoded=null,oriented=null,cropped=null;
        try{
            try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(input)){
                if(in==null)throw new IOException("No image stream");byte[] buffer=new byte[16384];int n,total=0;
                while((n=in.read(buffer))!=-1){total+=n;if(total>32*1024*1024)throw new IOException("Image is too large");out.write(buffer,0,n);}
            }
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(input.getPath(),options);
            if(options.outWidth<=0||options.outHeight<=0||Math.max(options.outWidth,options.outHeight)>32768)throw new IOException("Unsupported dimensions");
            options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            decoded=BitmapFactory.decodeFile(input.getPath(),options);if(decoded==null)throw new IOException("Unsupported image");
            int orientation=1;try{orientation=new ExifInterface(input.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
            Matrix matrix=new Matrix();
            if(orientation==2)matrix.setScale(-1,1);else if(orientation==3)matrix.setRotate(180);else if(orientation==4)matrix.setScale(1,-1);
            else if(orientation==5){matrix.setRotate(90);matrix.postScale(-1,1);}else if(orientation==6)matrix.setRotate(90);
            else if(orientation==7){matrix.setRotate(270);matrix.postScale(-1,1);}else if(orientation==8)matrix.setRotate(270);
            oriented=matrix.isIdentity()?decoded:Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),matrix,true);
            cropped=crop(oriented,512);
            try(FileOutputStream out=new FileOutputStream(output)){if(!cropped.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot encode image");out.getFD().sync();}
            if(!output.renameTo(file(c,key)))throw new IOException("Cannot save image");
        }finally{
            input.delete();output.delete();if(cropped!=null)cropped.recycle();if(oriented!=null&&oriented!=decoded)oriented.recycle();if(decoded!=null)decoded.recycle();
        }
    }
    static Bitmap crop(Bitmap input,int size){
        int side=Math.min(input.getWidth(),input.getHeight());int x=(input.getWidth()-side)/2,y=(input.getHeight()-side)/2;
        Bitmap output=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(output);c.drawBitmap(input,new Rect(x,y,x+side,y+side),new Rect(0,0,size,size),new Paint(3));return output;
    }
    /**
     * 2.0.6-preview46 (owner: a thin cut along the icon's right and bottom edge): the launcher places a full-bleed
     * picture through two rounding steps (AdaptiveIconDrawable's layer and the inset), which can leave it a pixel short
     * on the right and bottom, where the black behind it then shows at the edge of the shape. The picture is drawn a
     * hair larger (about 1%, at least 3 pixels) so it always reaches every edge.
     */
    static final class Bleed extends BitmapDrawable{
        Bleed(android.content.res.Resources r,Bitmap b){super(r,b);}
        @Override public void draw(Canvas c){
            Rect b=getBounds();Bitmap image=getBitmap();if(image==null||b.isEmpty())return;
            float extra=Math.max(3f,b.width()*0.01f);
            c.drawBitmap(image,null,new android.graphics.RectF(b.left-extra,b.top-extra,b.right+extra,b.bottom+extra),getPaint());
        }
    }
    /**
     * 2.0.6-preview46 (owner: in the Google search the custom icon was a square): the icon as the launcher shows it,
     * in the shape and colours chosen in Wallpaper & style (themed icons included), drawn by the launcher's own icon
     * drawable; for the Google app's results. Main thread; recorded as a Picture (the launcher's icons are hardware
     * bitmaps, which only the hardware path can draw: preview47, the owner's log), made into pixels by picture().
     */
    static android.graphics.Picture shown(Context c,Object bitmapInfo,int size){
        android.graphics.Picture picture=new android.graphics.Picture();
        try{
            Object manager=Xp.callMethod(LauncherFeatures.type("graphics.ThemeManager").getField("INSTANCE").get(null),"get",c);
            Object state=get(manager,"iconState");
            Object shape=get(get(manager,"iconShapeData"),"value");
            int flags=2|4;   // no badges
            if(get(state,"themeController")!=null)flags|=1;   // themed icons on: as on the home screen
            android.graphics.drawable.Drawable d=(android.graphics.drawable.Drawable)Xp.callMethod(bitmapInfo,"newIcon",c,flags,shape);
            d.setBounds(0,0,size,size);
            Canvas canvas=picture.beginRecording(size,size);
            try{d.draw(canvas);}finally{picture.endRecording();}
            return picture;
        }catch(Throwable error){LauncherFeatures.logOnce("Icon export: launcher drawing unavailable, shape used",error);}
        // Fallback: the picture in the launcher's shape, at the size the launcher draws icons in their cell.
        try{
            Object image=get(bitmapInfo,"icon");if(!(image instanceof Bitmap))return null;
            picture=new android.graphics.Picture();
            Canvas canvas=picture.beginRecording(size,size);
            try{
                float inner=size*0.92f,at=(size-inner)/2f;
                android.graphics.Path path=IconEditor.shape(c,inner);path.offset(at,at);
                canvas.clipPath(path);canvas.drawBitmap((Bitmap)image,null,new android.graphics.RectF(at,at,at+inner,at+inner),new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG));
            }finally{picture.endRecording();}
            return picture;
        }catch(Throwable error){return null;}
    }
    /** A recorded icon as ordinary pixels (any thread). */
    static Bitmap picture(android.graphics.Picture picture)throws Exception{
        Bitmap drawn=(Bitmap)Bitmap.class.getMethod("createBitmap",android.graphics.Picture.class).invoke(null,picture);   // Android 9+: drawn on the hardware path
        if(drawn.getConfig()==Bitmap.Config.ARGB_8888)return drawn;
        Bitmap plain=drawn.copy(Bitmap.Config.ARGB_8888,false);drawn.recycle();
        if(plain==null)throw new IOException("Icon could not be read back");
        return plain;
    }
    /**
     * 2.0.6-preview52 (owner: Reset icon brought back an earlier custom picture, not the app's own icon): since build
     * 271 the launcher hands out copies of icon records (themed lookups); a copy of one of our icons was not
     * recognised and was kept as the app's "original". Ours are now known by their picture, which every copy shares.
     */
    static final Set<Object> ourPictures=Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
    static boolean ours(Object bitmapInfo){
        if(bitmapInfo==null)return false;
        if(cache.containsValue(bitmapInfo)||retired.contains(bitmapInfo))return true;
        try{Object picture=get(bitmapInfo,"icon");return picture!=null&&ourPictures.contains(picture);}catch(Throwable t){return false;}
    }
    static final Set<String> reloaded=new HashSet<String>();
    /** The launcher loads every app's icon again (once per app per launcher start), so the real icon is known. */
    static void reloadNatives(String key){
        if(!reloaded.add(key))return;
        main.removeCallbacks(reloadAll);main.postDelayed(reloadAll,300);
    }
    static final Runnable reloadAll=new Runnable(){public void run(){
        try{Activity a=LauncherFeatures.activity.get();if(a==null)return;Object model=get(a,"mModel");call(model,"forceReload","pixelsuite icon reset");Module.log("Custom icons: launcher icons reloaded to find an app's own icon");}
        catch(Throwable error){LauncherFeatures.logOnce("Icon reload unavailable",error);}
    }};
    static void nativeLoaded(Object info){
        String key=LauncherFeatures.identity(info);if(key==null)return;
        Object bitmap=get(info,"bitmap");synchronized(cache){if(ours(bitmap))return;originals.put(info,bitmap);nativeIcons.put(key,bitmap);}
    }
    static void apply(Object info,String key){
        synchronized(cache){
            Object current=get(info,"bitmap");
            boolean ours=ours(current);
            // The launcher loads a new native icon after a shape or theme change: keep that one as the
            // original, or the old shape would be written back over it (the drawer stayed in the old shape).
            if(originals.containsKey(info)&&!ours&&current!=originals.get(info)){originals.put(info,current);nativeIcons.put(key,current);}
            if(!originals.containsKey(info)){
                Object saved=nativeIcons.get(key);
                // Workspace/folder copies may have inherited the replacement bitmap.
                originals.put(info,ours&&saved!=null?saved:current);
                if(!ours)nativeIcons.put(key,current);
            }
            // preview52: an "original" that is really one of our pictures (kept before this fix): the app's own icon
            // is taken from what is known, or the launcher loads its icons again once.
            if(ours(originals.get(info))){
                Object saved=nativeIcons.get(key);
                if(saved!=null&&!ours(saved))originals.put(info,saved);else reloadNatives(key);
            }
            if(!LauncherFeatures.on(LauncherFeatures.ICONS)){LauncherFeatures.set(info,"bitmap",originals.get(info));return;}
            Object replacement=cache.get(key);
            if(replacement==null&&!missing.contains(key))try{
                Activity a=LauncherFeatures.activity.get();File f=file(a,key);
                if(!f.isFile()){missing.add(key);}else{
                    Bitmap image=BitmapFactory.decodeFile(f.getPath());if(image==null)throw new IOException("Invalid icon file");
                    Object factory=Xp.callStaticMethod(LauncherFeatures.type("icons.LauncherIcons$Companion"),"obtain",a);
                    try{
                        Class<?> optionsType=LauncherFeatures.type("icons.BaseIconFactory$IconOptions");Object options;
                        try{options=Xp.newInstance(optionsType);}catch(Throwable absent){
                            Class<?> unsafe=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
                            options=unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),optionsType);
                        }
                        LauncherFeatures.set(options,"wrapNonAdaptiveIcon",true);LauncherFeatures.set(options,"addShadows",true);
                        LauncherFeatures.set(options,"iconScale",1f);LauncherFeatures.set(options,"isFullBleed",true);
                        LauncherFeatures.set(options,"userHandle",get(info,"user"));
                        replacement=call(factory,"createBadgedIconBitmap",new Bleed(a.getResources(),image),options);
                        cache.put(key,replacement);
                        try{Object picture=get(replacement,"icon");if(picture!=null)ourPictures.add(picture);}catch(Throwable ignored){}
                    }finally{call(factory,"close");image.recycle();}
                }
            }catch(Throwable error){missing.add(key);Module.log("Custom icon unavailable",error);}
            LauncherFeatures.set(info,"bitmap",replacement!=null?replacement:originals.get(info));
        }
    }
}
