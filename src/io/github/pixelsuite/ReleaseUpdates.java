package io.github.pixelsuite;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.os.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public GitHub API only. No root, token, telemetry, or persistent connection. */
final class ReleaseUpdates {
    private static final String PREF="release_updates", CHANNEL="pixelsuite_updates";
    private static final int JOB=20184, NOTICE=201;
    private static final AtomicBoolean busy=new AtomicBoolean();
    interface Result { void done(String status); }
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,0);}
    static boolean enabled(Context c){return prefs(c).getBoolean("enabled",true);}
    static void schedule(Context c){
        JobScheduler jobs=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(jobs==null)return;
        if(!enabled(c)){jobs.cancel(JOB);((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTICE);return;}
        boolean scheduled=false; for(JobInfo info:jobs.getAllPendingJobs())if(info.getId()==JOB)scheduled=true; if(!scheduled)jobs.schedule(new JobInfo.Builder(JOB,new ComponentName(c,ReleaseUpdateJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(24*60*60*1000L).build());
    }
    static void check(final Context context,final boolean manual,final Result callback){
        final Context c=context.getApplicationContext();
        if(!manual&&!enabled(c)){reply(callback,"Automatic update checks are off.");return;}
        if(!manual&&System.currentTimeMillis()-prefs(c).getLong("attempt",0)<6*60*60*1000L){reply(callback,status(c));return;}
        if(!busy.compareAndSet(false,true)){reply(callback,"An update check is already running.");return;}
        new Thread(new Runnable(){public void run(){
            String message;
            try{
                prefs(c).edit().putLong("attempt",System.currentTimeMillis()).apply();
                PackageInfo local=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
                ReleaseGate.Release release=ReleaseGate.candidate(fetch("RovianDev/PixelSuite",false),fetch("Xposed-Modules-Repo/io.github.roviandev.pixelsuite",true),local.versionName,local.versionCode);
                String available=release==null?"":release.version;
                message=release==null?"No newer official release is published with matching APKs on both repositories.":"Pixel Suite "+available+" is available on GitHub and Xposed.";
                prefs(c).edit().putString("available",available).putString("status",message).putLong("checked",System.currentTimeMillis()).apply();
                if(release==null)((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTICE);
                else if(enabled(c)&&!available.equals(prefs(c).getString("notified",""))&&notifyUpdate(c,available))prefs(c).edit().putString("notified",available).apply();
            }catch(Exception error){message="Could not verify both release repositories. Try again later.";}
            finally{busy.set(false);}
            reply(callback,message);
        }},"PixelSuite-release-check").start();
    }
    static ReleaseGate.Release availableRelease(Context c)throws Exception {PackageInfo local=c.getPackageManager().getPackageInfo(c.getPackageName(),0);return ReleaseGate.candidate(fetch("RovianDev/PixelSuite",false),fetch("Xposed-Modules-Repo/io.github.roviandev.pixelsuite",true),local.versionName,local.versionCode);}
    static String status(Context c){return prefs(c).getString("status","No release check yet.");}
    static void reply(final Result callback,final String message){if(callback!=null)new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){callback.done(message);}});}
    private static List<ReleaseGate.Release> fetch(String repo,boolean xposed)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL("https://api.github.com/repos/"+repo+"/releases?per_page=30").openConnection();
        connection.setConnectTimeout(12000);connection.setReadTimeout(12000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("User-Agent","PixelSuite-ReleaseChecker");
        try{
            if(connection.getResponseCode()!=200)throw new IOException("Release API unavailable");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>2*1024*1024)throw new IOException("Response too large");bytes.write(buffer,0,n);}}
            JSONArray array=new JSONArray(bytes.toString("UTF-8"));List<ReleaseGate.Release> result=new ArrayList<ReleaseGate.Release>();
            for(int i=0;i<array.length();i++){
                JSONObject r=array.getJSONObject(i);if(r.optBoolean("draft",true)||r.optBoolean("prerelease",true)||r.optString("published_at","").isEmpty())continue;
                String tag=r.optString("tag_name","");long code=0;String version=tag;
                if(xposed){int dash=tag.indexOf('-');if(dash<1)continue;try{code=Long.parseLong(tag.substring(0,dash));}catch(NumberFormatException invalid){continue;}version=tag.substring(dash+1);}
                if(!ReleaseGate.VERSION.matcher(version).matches())continue;
                if(version.startsWith("v"))version=version.substring(1);if(!xposed&&!tag.equals("v"+version))continue;
                JSONArray assets=r.optJSONArray("assets");if(assets==null)continue;
                String filename="PixelSuite-v"+version+".apk";
                for(int j=0;j<assets.length();j++){
                    JSONObject a=assets.getJSONObject(j);
                    if(!filename.equals(a.optString("name"))||!"uploaded".equals(a.optString("state")))continue;
                    String expected="https://github.com/"+repo+"/releases/download/"+tag+"/"+filename;
                    if(!expected.equals(a.optString("browser_download_url")))continue;
                    result.add(new ReleaseGate.Release(version,code,filename,a.optLong("size",0),a.optString("digest","")));
                }
            }
            return result;
        }finally{connection.disconnect();}
    }
    private static boolean notifyUpdate(Context c,String version){
        try{
            if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)return false;
            NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(manager==null)return false;
            if(Build.VERSION.SDK_INT>=24&&!((Boolean)manager.getClass().getMethod("areNotificationsEnabled").invoke(manager)))return false;
            if(Build.VERSION.SDK_INT>=26){Class<?> channel=Class.forName("android.app.NotificationChannel");Object ch=channel.getConstructor(String.class,CharSequence.class,int.class).newInstance(CHANNEL,"Pixel Suite updates",3);manager.getClass().getMethod("createNotificationChannel",channel).invoke(manager,ch);Object existing=manager.getClass().getMethod("getNotificationChannel",String.class).invoke(manager,CHANNEL);if(existing!=null&&((Number)channel.getMethod("getImportance").invoke(existing)).intValue()==0)return false;}
            Intent intent=new Intent(c,SettingsActivity.class).putExtra("page","updates");
            PendingIntent open=PendingIntent.getActivity(c,NOTICE,intent,PendingIntent.FLAG_UPDATE_CURRENT|0x04000000);
            Notification.Builder builder=new Notification.Builder(c).setSmallIcon(c.getResources().getIdentifier("ic_ps_update","drawable",c.getPackageName())).setContentTitle("Pixel Suite "+version+" is available").setContentText("Official release verified on GitHub and Xposed. Tap to download and install in Pixel Suite.").setContentIntent(open).setAutoCancel(true);
            if(Build.VERSION.SDK_INT>=26)builder.getClass().getMethod("setChannelId",String.class).invoke(builder,CHANNEL);
            manager.notify(NOTICE,builder.build());return true;
        }catch(Exception denied){return false;}
    }
}
