package io.github.pixelsuite;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Only Google's installed-app strip. Search suggestions and web content are untouched. */
final class GoogleAppCards {
    static final Handler MAIN=new Handler(Looper.getMainLooper());
    static final Map<TextView,Card> cards=new WeakHashMap<TextView,Card>();
    static final ThreadLocal<Boolean> writing=new ThreadLocal<Boolean>();
    static final ExecutorService IO=new ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>());
    static final Set<String> pending=new HashSet<String>();
    // preview47: every app's icon comes from the launcher now (about 64 KB each at 128 px), so room for all of them.
    static final android.util.LruCache<String,Bitmap> images=new android.util.LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String key,Bitmap image){return image.getByteCount();}};
    static int labelId,iconId;
    static boolean posted;
    static final class Card {
        String original;WeakReference<ImageView> icon=new WeakReference<ImageView>(null);Drawable nativeIcon;boolean replaced;
    }
    static boolean target(View view,boolean label){
        if(view.getId()==View.NO_ID)return false;
        if(labelId==0)labelId=view.getResources().getIdentifier("googleapp_app_name","id",AliasProvider.GOOGLE);
        if(iconId==0)iconId=view.getResources().getIdentifier("googleapp_app_icon","id",AliasProvider.GOOGLE);
        int id=label?labelId:iconId;return id!=0&&view.getId()==id;
    }
    static org.json.JSONObject entry(String text){
        GoogleAliases.reload();org.json.JSONObject map=GoogleAliases.aliases;if(map.length()==0)return null;
        Context c=GoogleAliases.context();if(c==null)return null;
        long serial=GoogleAliases.serial(c,android.os.Process.myUserHandle());
        org.json.JSONObject match=null;Iterator<String> keys=map.keys();
        while(keys.hasNext()){
            org.json.JSONObject e=map.optJSONObject(keys.next());if(e==null||e.optLong("user",-1)!=serial||(!e.has("uniqueOriginal")&&!e.has("uniqueShown")))continue;
            if(!text.equals(e.optString("uniqueShown",null))&&!(e.has("uniqueOriginal")&&(text.equals(e.optString("uniqueOriginal"))||text.equals(e.optString("alias")))))continue;
            if(match!=null)return null;match=e;
        }
        return match;
    }
    static void changed(){
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(new Runnable(){public void run(){changed();}});return;}
        if(posted)return;posted=true;MAIN.post(new Runnable(){public void run(){posted=false;for(Map.Entry<TextView,Card> e:new ArrayList<Map.Entry<TextView,Card>>(cards.entrySet()))if(e.getKey()!=null)apply(e.getKey(),e.getValue());}});
    }
    static void apply(TextView label,Card card){
        if(!label.isAttachedToWindow()||card.original==null)return;
        org.json.JSONObject entry=entry(card.original);String alias=entry==null?"":entry.optString("alias");String title=alias.isEmpty()?card.original:alias;
        writing.set(true);
        try{
            if(!title.contentEquals(label.getText()))label.setText(title);
            if(!(label.getParent() instanceof ViewGroup))return;
            View found=((ViewGroup)label.getParent()).findViewById(iconId);if(!(found instanceof ImageView))return;ImageView icon=(ImageView)found;
            if(card.icon.get()!=icon){card.icon=new WeakReference<ImageView>(icon);card.nativeIcon=icon.getDrawable();card.replaced=false;}
            String hash=entry==null?"":entry.optString("icon");Bitmap bitmap=hash.isEmpty()?null:images.get(hash);
            Drawable now=icon.getDrawable();
            if(bitmap!=null){if(!(now instanceof BitmapDrawable&&((BitmapDrawable)now).getBitmap()==bitmap))icon.setImageDrawable(new BitmapDrawable(icon.getResources(),bitmap));card.replaced=true;}
            else if(hash.isEmpty()){if(card.replaced)icon.setImageDrawable(card.nativeIcon);card.replaced=false;}
            else load(hash);
        }finally{writing.remove();}
    }
    /**
     * preview48: the launcher's icon for exactly this app (its component), or null. Off the main thread it is read
     * straight away if needed; on the main thread only a ready one is given (the rest load and follow).
     */
    static Drawable iconFor(ComponentName component,UserHandle user){
        if(component==null)return null;
        GoogleAliases.reload();org.json.JSONObject map=GoogleAliases.aliases;if(map.length()==0)return null;
        Context c=GoogleAliases.context();if(c==null)return null;
        long serial=GoogleAliases.serial(c,user);if(serial<0)return null;
        org.json.JSONObject e=map.optJSONObject(serial+"|"+component.flattenToString());if(e==null)return null;
        String hash=e.optString("icon");if(hash.isEmpty()||!HASH.matcher(hash).matches())return null;
        Bitmap bitmap=images.get(hash);
        if(bitmap==null){
            if(Looper.myLooper()==Looper.getMainLooper()){load(hash);return null;}
            try(java.io.InputStream in=c.getContentResolver().openInputStream(AliasProvider.URI.buildUpon().appendPath("icons").appendPath(hash).build())){bitmap=BitmapFactory.decodeStream(in);}
            catch(Throwable error){return null;}
            if(bitmap==null||bitmap.getWidth()>256||bitmap.getHeight()>256)return null;
            images.put(hash,bitmap);
        }
        return new BitmapDrawable(c.getResources(),bitmap);
    }
    static final java.util.regex.Pattern HASH=java.util.regex.Pattern.compile("[a-f0-9]{64}");
    static volatile boolean loggedIcon;
    static void load(final String hash){
        if(!HASH.matcher(hash).matches())return;synchronized(pending){if(!pending.add(hash))return;}
        IO.execute(new Runnable(){public void run(){try{
            Context c=GoogleAliases.context();if(c==null)return;Bitmap image;
            try(java.io.InputStream in=c.getContentResolver().openInputStream(AliasProvider.URI.buildUpon().appendPath("icons").appendPath(hash).build())){image=BitmapFactory.decodeStream(in);}
            if(image!=null&&image.getWidth()<=256&&image.getHeight()<=256)images.put(hash,image);
        }catch(Throwable error){if(!loggedIcon){loggedIcon=true;Module.log("Google app icon unavailable",error);}}finally{synchronized(pending){pending.remove(hash);}if(images.get(hash)!=null)changed();}}});
    }
    static void install(){
        // Only setText(CharSequence, BufferType), the one overload this acts on (setText(CharSequence)
        // calls it); hooking every overload ran the callback up to three times per setText for nothing.
        Xp.findAndHookMethod(TextView.class,"setText",CharSequence.class,TextView.BufferType.class,new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){
            if(p.args.length!=2||!(p.args[0] instanceof CharSequence)||!(p.args[1] instanceof TextView.BufferType)||Boolean.TRUE.equals(writing.get()))return;
            TextView view=(TextView)p.thisObject;if(!target(view,true))return;
            String original=p.args[0].toString();org.json.JSONObject entry=entry(original);Card card=cards.get(view);if(card==null){card=new Card();cards.put(view,card);}
            card.original=entry==null?original:entry.optString("uniqueOriginal",original);
            String alias=entry==null?"":entry.optString("alias");if(!alias.isEmpty())p.args[0]=alias;changed();
        }});
        // preview47 (owner: while typing, the app's icon kept popping between Google's and the launcher's): Google sets
        // its own icon again on every key; the launcher's icon now goes in in its place, in the same call, instead of
        // a frame later.
        Xp.hookAllMethods(ImageView.class,"setImageDrawable",new Xp.Callback(){
            protected void beforeHookedMethod(Xp.Param p){
                if(Boolean.TRUE.equals(writing.get())||p.args.length!=1)return;ImageView icon=(ImageView)p.thisObject;if(!target(icon,false)||!(icon.getParent() instanceof ViewGroup))return;
                View label=((ViewGroup)icon.getParent()).findViewById(labelId);if(!(label instanceof TextView))return;Card card=cards.get((TextView)label);
                if(card==null)return;
                card.icon=new WeakReference<ImageView>(icon);card.nativeIcon=(Drawable)p.args[0];card.replaced=false;
                if(card.original==null)return;
                org.json.JSONObject entry=entry(card.original);String hash=entry==null?"":entry.optString("icon");if(hash.isEmpty())return;
                Bitmap bitmap=images.get(hash);
                if(bitmap!=null){p.args[0]=new BitmapDrawable(icon.getResources(),bitmap);card.replaced=true;}
                else load(hash);
            }
            protected void afterHookedMethod(Xp.Param p){
                if(Boolean.TRUE.equals(writing.get()))return;ImageView icon=(ImageView)p.thisObject;if(!target(icon,false)||!(icon.getParent() instanceof ViewGroup))return;
                View label=((ViewGroup)icon.getParent()).findViewById(labelId);if(!(label instanceof TextView))return;Card card=cards.get((TextView)label);
                if(card!=null&&!card.replaced)changed();
            }
        });
        Xp.hookAllMethods(View.class,"onAttachedToWindow",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){if(p.thisObject instanceof TextView&&target((View)p.thisObject,true))changed();}});
    }
}
