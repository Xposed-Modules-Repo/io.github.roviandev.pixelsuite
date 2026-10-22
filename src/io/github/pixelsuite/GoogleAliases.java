package io.github.pixelsuite;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.util.*;
final class GoogleAliases {
    static volatile org.json.JSONObject aliases=new org.json.JSONObject();
    static long loaded;static boolean listening;static String published;
    static final Set<String> exportedIcons=new HashSet<String>();
    static final Map<String,String[]> iconHashes=new HashMap<String,String[]>();
    static void publish(){
        Activity a=LauncherFeatures.activity.get();if(a==null||LauncherFeatures.data==null)return;
        try{
            final org.json.JSONObject root=new org.json.JSONObject();final Map<String,android.graphics.Picture> icons=new HashMap<String,android.graphics.Picture>();
            final Map<String,String> signatures=new HashMap<String,String>();
            List<Object> apps=LauncherFeatures.apps();final Map<String,String> nativeLabels=new HashMap<String,String>();
            for(Object info:apps){String key=LauncherFeatures.identity(info);CharSequence title=LauncherFeatures.nativeTitles.get(key);if(title!=null)nativeLabels.put(key,title.toString());}
            boolean iconsOn=LauncherFeatures.on(LauncherFeatures.ICONS);
            String look=ShapeWatch.token(a);
            for(Object info:apps){
                String key=LauncherFeatures.identity(info);if(key==null)continue;String alias=LauncherFeatures.on(LauncherFeatures.RENAME)?LauncherFeatures.data.alias(key):"";
                Object replacement; synchronized(LauncherIcon.cache){replacement=iconsOn?LauncherIcon.cache.get(key):null;}
                Object shownFrom=replacement;
                boolean locked=false;try{Object tc=LauncherFeatures.call(info,"getTargetComponent");locked=tc instanceof ComponentName&&PrivacyLock.lockedNow(a).contains(((ComponentName)tc).getPackageName());}catch(Throwable ignored){}
                if(shownFrom==null&&(iconsOn||locked)){try{shownFrom=LauncherFeatures.get(info,"bitmap");}catch(Throwable ignored){}}
                if(alias.isEmpty()&&shownFrom==null)continue;
                ComponentName c=(ComponentName)LauncherFeatures.call(info,"getTargetComponent");
                org.json.JSONObject entry=new org.json.JSONObject();entry.put("alias",alias);entry.put("package",c.getPackageName());entry.put("component",c.flattenToString());entry.put("user",Long.parseLong(key.substring(0,key.indexOf('|'))));root.put(key,entry);
                if(shownFrom!=null){
                    String signature=System.identityHashCode(shownFrom)+"|"+look+(locked?"|locked":"");
                    String[] known;synchronized(iconHashes){known=iconHashes.get(key);}
                    if(known!=null&&known[0].equals(signature))entry.put("icon",known[1]);
                    else{android.graphics.Picture image=LauncherIcon.shown(a,shownFrom,128);if(image!=null&&locked)image=PrivacyBadges.withPadlock(a,image,128);if(image!=null){icons.put(key,image);signatures.put(key,signature);}}
                }
            }
            final Activity owner=a;
            LauncherIcon.IO.execute(new Runnable(){public void run(){try{
                LauncherApps launcherApps=(LauncherApps)owner.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                UserManager users=(UserManager)owner.getSystemService(Context.USER_SERVICE);
                Map<String,String> originals=new HashMap<String,String>();Iterator<String> customized=root.keys();
                while(customized.hasNext()){
                    String key=customized.next();org.json.JSONObject entry=root.getJSONObject(key);
                    ComponentName component=ComponentName.unflattenFromString(entry.getString("component"));UserHandle user=users.getUserForSerialNumber(entry.getLong("user"));if(component==null||user==null)continue;
                    LauncherActivityInfo resolved=launcherApps.resolveActivity(new Intent(Intent.ACTION_MAIN).setComponent(component),user);
                    if(resolved!=null&&resolved.getComponentName().equals(component)){String original=resolved.getLabel().toString();originals.put(key,original);nativeLabels.put(key,original);}
                }
                Map<String,Integer> counts=new HashMap<String,Integer>();for(String title:nativeLabels.values()){Integer count=counts.get(title);counts.put(title,count==null?1:count+1);}
                for(Map.Entry<String,String> original:originals.entrySet())if(Integer.valueOf(1).equals(counts.get(original.getValue())))root.getJSONObject(original.getKey()).put("uniqueOriginal",original.getValue());
                Map<String,String> shownNames=new HashMap<String,String>();
                for(Map.Entry<String,String> n:nativeLabels.entrySet()){org.json.JSONObject e=root.optJSONObject(n.getKey());String alias=e==null?"":e.optString("alias");shownNames.put(n.getKey(),alias.isEmpty()?n.getValue():alias);}
                Map<String,Integer> shownCounts=new HashMap<String,Integer>();for(String title:shownNames.values()){Integer count=shownCounts.get(title);shownCounts.put(title,count==null?1:count+1);}
                Iterator<String> each=root.keys();
                while(each.hasNext()){String key=each.next();String shown=shownNames.get(key);if(shown!=null&&Integer.valueOf(1).equals(shownCounts.get(shown)))root.getJSONObject(key).put("uniqueShown",shown);}
                for(Map.Entry<String,android.graphics.Picture> item:icons.entrySet()){
                    try{
                        android.graphics.Bitmap image=LauncherIcon.picture(item.getValue());
                        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);image.recycle();byte[] png=out.toByteArray();
                        StringBuilder hash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(png))hash.append(String.format(Locale.ROOT,"%02x",b&255));
                        if(!exportedIcons.contains(hash.toString())){Bundle data=new Bundle();data.putByteArray("png",png);owner.getContentResolver().call(AliasProvider.URI,"icon",hash.toString(),data);exportedIcons.add(hash.toString());}
                        root.getJSONObject(item.getKey()).put("icon",hash.toString());
                        synchronized(iconHashes){iconHashes.put(item.getKey(),new String[]{signatures.get(item.getKey()),hash.toString()});}
                    }catch(Throwable error){LauncherFeatures.logOnce("Icon export for the Google app unavailable",error);}
                }
                String json=root.toString();if(json.equals(published))return;
                Bundle data=new Bundle();data.putString("aliases",json);owner.getContentResolver().call(AliasProvider.URI,"replace",null,data);published=json;
                Set<String> active=new HashSet<String>();Iterator<String> keys=root.keys();while(keys.hasNext())active.add(root.getJSONObject(keys.next()).optString("icon"));
                exportedIcons.retainAll(active);
            }catch(Throwable error){Module.log("Google app customization sync unavailable",error);}}});
        }catch(Throwable error){Module.log("Google app label sync unavailable",error);}
    }
    private static volatile Context sContext;
    static Context context(){
        Context c=sContext;if(c!=null)return c;
        try{c=(Context)Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null);}catch(Throwable ignored){return null;}
        if(c!=null)sContext=c;return c;
    }
    private static final java.util.concurrent.ConcurrentHashMap<UserHandle,Long> serials=new java.util.concurrent.ConcurrentHashMap<UserHandle,Long>();
    static long serial(Context c,UserHandle user){
        Long known=serials.get(user);if(known!=null)return known;
        long serial=((UserManager)c.getSystemService(Context.USER_SERVICE)).getSerialNumberForUser(user);
        if(serial>=0)serials.put(user,serial);return serial;
    }
    static synchronized void reload(){
        long now=SystemClock.uptimeMillis();if(now-loaded<2000)return;Context c=context();if(c==null)return;
        try{
            Bundle reply=c.getContentResolver().call(AliasProvider.URI,"read",null,null);if(reply==null)return;
            aliases=new org.json.JSONObject(reply.getString("aliases","{}"));loaded=now;GoogleAppCards.changed();
            if(!listening){
                BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){loaded=0;reload();}};
                IntentFilter filter=new IntentFilter(AliasProvider.CHANGE);
                if(Build.VERSION.SDK_INT>=33)Context.class.getMethod("registerReceiver",BroadcastReceiver.class,IntentFilter.class,int.class).invoke(c,receiver,filter,2);
                else c.registerReceiver(receiver,filter);
                listening=true;
            }
        }catch(Throwable ignored){loaded=now;}
    }
    static String alias(ComponentName component,String pkg,Object user){
        reload();org.json.JSONObject map=aliases;if(map.length()==0)return null;
        Context c=context();if(c==null)return null;
        try{
            if(user==null)user=android.os.Process.myUserHandle();
            long serial=serial(c,(UserHandle)user);
            if(serial<0)return null;
            if(component!=null){org.json.JSONObject entry=map.optJSONObject(serial+"|"+component.flattenToString());return entry==null?null:entry.optString("alias",null);}
            String single=null;Iterator<String> keys=map.keys();
            while(keys.hasNext()){
                org.json.JSONObject entry=map.optJSONObject(keys.next());if(entry==null||entry.optLong("user",-1)!=serial||!pkg.equals(entry.optString("package")))continue;
                String value=entry.optString("alias",null);if(single!=null&&!single.equals(value))return null;single=value;
            }
            return single;
        }catch(Throwable ignored){return null;}
    }
    static void install(){
        GoogleAppCards.install();
        Xp.hookAllMethods(LauncherActivityInfo.class,"getLabel",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            LauncherActivityInfo info=(LauncherActivityInfo)p.thisObject;String value=alias(info.getComponentName(),info.getComponentName().getPackageName(),info.getUser());if(value!=null&&!value.isEmpty())p.setResult(value);
        }});
        Xp.hookAllMethods(PackageItemInfo.class,"loadLabel",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            PackageItemInfo info=(PackageItemInfo)p.thisObject;if(info.packageName==null)return;
            if(!(info instanceof ApplicationInfo)&&!(info instanceof ActivityInfo))return;
            ComponentName component=info instanceof ActivityInfo&&info.name!=null?new ComponentName(info.packageName,info.name):null;
            String value=alias(component,info.packageName,null);if(value!=null&&!value.isEmpty())p.setResult(value);
        }});
        Xp.Callback icon=new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            try{
                LauncherActivityInfo info=(LauncherActivityInfo)p.thisObject;
                if(!android.os.Process.myUserHandle().equals(info.getUser()))return;
                android.graphics.drawable.Drawable mine=GoogleAppCards.iconFor(info.getComponentName(),info.getUser());
                if(mine!=null)p.setResult(mine);
            }catch(Throwable ignored){}
        }};
        Xp.hookAllMethods(LauncherActivityInfo.class,"getIcon",icon);
        Xp.hookAllMethods(LauncherActivityInfo.class,"getBadgedIcon",icon);
        Xp.hookAllMethods(Instrumentation.class,"callActivityOnResume",new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){loaded=0;reload();}});
        Module.log("Google app label hooks installed");
    }
}
