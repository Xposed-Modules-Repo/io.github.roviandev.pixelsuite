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
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

final class LauncherIcon {
    static final int REQUEST=0x5053;
    static final String PENDING="pixelsuite_pending_icon";
    static final Map<Object,Object> originals=Collections.synchronizedMap(new WeakHashMap<Object,Object>());
    static final Map<String,Object> nativeIcons=new HashMap<String,Object>();
    static final Map<String,Object> cache=new HashMap<String,Object>();
    static final Set<String> missing=new HashSet<String>();
    static final Set<Object> retired=Collections.newSetFromMap(new WeakHashMap<Object,Boolean>());
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
        if(key==null || resultCode!=Activity.RESULT_OK || result==null || result.getData()==null){finishReceiver(receiver,proxy);return;}
        final Uri uri=result.getData();
        IO.execute(new Runnable(){public void run(){
            boolean success=false;
            try{importImage(receiver,key,uri);success=true;}catch(Throwable error){Module.log("Custom icon import failed",error);}
            final boolean ok=success;
            receiver.runOnUiThread(new Runnable(){public void run(){
                synchronized(cache){retire(key);}
                LauncherFeatures.refresh();
                if(!receiver.isDestroyed())Toast.makeText(receiver,ok?"Icon updated":"Could not read this image. Try another image.",Toast.LENGTH_SHORT).show();
                finishReceiver(receiver,proxy);
            }});
        }});
    }
    private static final char[] HEX="0123456789abcdef".toCharArray();
    static String filename(String key)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));StringBuilder s=new StringBuilder(digest.length*2+4);
        for(byte b:digest)s.append(HEX[(b>>4)&15]).append(HEX[b&15]);return s.append(".png").toString();
    }
    static File file(Context c,String key)throws Exception{return new File(new File(c.getFilesDir(),"pixelsuite-icons"),filename(key));}
    static void choose(final Object info){
        final Activity a=LauncherFeatures.activity.get();final String key=LauncherFeatures.identity(info);if(a==null||key==null)return;
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
    static void nativeLoaded(Object info){
        String key=LauncherFeatures.identity(info);if(key==null)return;
        Object bitmap=get(info,"bitmap");originals.put(info,bitmap);synchronized(cache){nativeIcons.put(key,bitmap);}
    }
    static void apply(Object info,String key){
        synchronized(cache){
            Object current=get(info,"bitmap");
            boolean ours=cache.containsValue(current)||retired.contains(current);
            if(originals.containsKey(info)&&!ours&&current!=originals.get(info)){originals.put(info,current);nativeIcons.put(key,current);}
            if(!originals.containsKey(info)){
                Object saved=nativeIcons.get(key);
                originals.put(info,(current==cache.get(key)||retired.contains(current))&&saved!=null?saved:current);
                if(current!=cache.get(key)&&!retired.contains(current))nativeIcons.put(key,current);
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
                        replacement=call(factory,"createBadgedIconBitmap",new BitmapDrawable(a.getResources(),image),options);
                        cache.put(key,replacement);
                    }finally{call(factory,"close");image.recycle();}
                }
            }catch(Throwable error){missing.add(key);Module.log("Custom icon unavailable",error);}
            LauncherFeatures.set(info,"bitmap",replacement!=null?replacement:originals.get(info));
        }
    }
}
