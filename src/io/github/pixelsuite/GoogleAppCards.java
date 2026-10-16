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
    static final android.util.LruCache<String,Bitmap> images=new android.util.LruCache<String,Bitmap>(4*1024*1024){protected int sizeOf(String key,Bitmap image){return image.getByteCount();}};
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
        GoogleAliases.reload();Context c=GoogleAliases.context();if(c==null)return null;
        long serial=((UserManager)c.getSystemService(Context.USER_SERVICE)).getSerialNumberForUser(android.os.Process.myUserHandle());
        org.json.JSONObject match=null;Iterator<String> keys=GoogleAliases.aliases.keys();
        while(keys.hasNext()){
            org.json.JSONObject e=GoogleAliases.aliases.optJSONObject(keys.next());if(e==null||e.optLong("user",-1)!=serial||!e.has("uniqueOriginal"))continue;
            if(!text.equals(e.optString("uniqueOriginal"))&&!text.equals(e.optString("alias")))continue;
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
            if(bitmap!=null){icon.setImageDrawable(new BitmapDrawable(icon.getResources(),bitmap));card.replaced=true;}
            else if(hash.isEmpty()){if(card.replaced)icon.setImageDrawable(card.nativeIcon);card.replaced=false;}
            else load(hash);
        }finally{writing.remove();}
    }
    static void load(final String hash){
        if(!hash.matches("[a-f0-9]{64}"))return;synchronized(pending){if(!pending.add(hash))return;}
        IO.execute(new Runnable(){public void run(){try{
            Context c=GoogleAliases.context();if(c==null)return;Bitmap image;
            try(java.io.InputStream in=c.getContentResolver().openInputStream(AliasProvider.URI.buildUpon().appendPath("icons").appendPath(hash).build())){image=BitmapFactory.decodeStream(in);}
            if(image!=null&&image.getWidth()<=256&&image.getHeight()<=256)images.put(hash,image);
        }catch(Throwable error){Module.log("Google app icon unavailable",error);}finally{synchronized(pending){pending.remove(hash);}if(images.get(hash)!=null)changed();}}});
    }
    static void install(){
        Xp.hookAllMethods(TextView.class,"setText",new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){
            if(Boolean.TRUE.equals(writing.get())||p.args.length!=2||!(p.args[0] instanceof CharSequence)||!(p.args[1] instanceof TextView.BufferType))return;
            TextView view=(TextView)p.thisObject;if(!target(view,true))return;
            String original=p.args[0].toString();org.json.JSONObject entry=entry(original);Card card=cards.get(view);if(card==null){card=new Card();cards.put(view,card);}
            card.original=entry==null?original:entry.optString("uniqueOriginal",original);
            String alias=entry==null?"":entry.optString("alias");if(!alias.isEmpty())p.args[0]=alias;changed();
        }});
        Xp.hookAllMethods(ImageView.class,"setImageDrawable",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            if(Boolean.TRUE.equals(writing.get()))return;ImageView icon=(ImageView)p.thisObject;if(!target(icon,false)||!(icon.getParent() instanceof ViewGroup))return;
            View label=((ViewGroup)icon.getParent()).findViewById(labelId);if(!(label instanceof TextView))return;Card card=cards.get((TextView)label);
            if(card!=null){card.nativeIcon=icon.getDrawable();card.replaced=false;card.icon=new WeakReference<ImageView>(icon);changed();}
        }});
        Xp.hookAllMethods(View.class,"onAttachedToWindow",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){if(p.thisObject instanceof TextView&&target((View)p.thisObject,true))changed();}});
    }
}
