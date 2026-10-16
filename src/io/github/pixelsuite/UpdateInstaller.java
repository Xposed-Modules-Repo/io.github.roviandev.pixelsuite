package io.github.pixelsuite;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-started download followed by Android's installer. Never installs silently or uses root. */
final class UpdateInstaller {
    private static final AtomicBoolean busy=new AtomicBoolean();
    interface Progress { void show(String text,int percent); }
    static File apk(Context c){return new File(new File(c.getCacheDir(),"release-update"),"verified.apk");}
    private static void progress(final Progress callback,final String text,final int percent){new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){callback.show(text,percent);}});}
    static void start(final Activity activity,final Progress callback){
        if(!busy.compareAndSet(false,true)){progress(callback,"An update download is already running.",-1);return;}
        final Context c=activity.getApplicationContext();
        new Thread(new Runnable(){public void run(){
            File partial=new File(apk(c).getParentFile(),"download.part");
            try{
                progress(callback,"Verifying the official releases…",-1);
                ReleaseGate.Release release=ReleaseUpdates.availableRelease(c);
                if(release==null){progress(callback,"No newer verified release is available on both repositories.",-1);return;}
                if(release.size>ApkDownloadPolicy.MAX)throw new IOException("APK exceeds download limit");
                File target=apk(c);target.getParentFile().mkdirs();
                if(!valid(c,target,release)){
                    URL url=new URL("https://github.com/RovianDev/PixelSuite/releases/download/v"+release.version+"/"+release.name);
                    HttpURLConnection connection=connect(url);
                    try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(partial)){
                        byte[] bytes=new byte[32768];long read=0;int n,last=-1;
                        while((n=in.read(bytes))!=-1){read+=n;if(read>release.size)throw new IOException("Unexpected APK size");out.write(bytes,0,n);int percent=(int)(read*100/release.size);if(percent!=last){last=percent;progress(callback,"Downloading Pixel Suite "+release.version+" — "+percent+"%",percent);}}
                        if(read!=release.size)throw new IOException("Incomplete APK");
                    }finally{connection.disconnect();}
                    progress(callback,"Checking APK hash and signing certificate…",100);
                    if(!valid(c,partial,release))throw new IOException("APK verification failed");
                    if(target.exists()&&!target.delete())throw new IOException("Cannot replace cached APK");
                    if(!partial.renameTo(target))throw new IOException("Cannot prepare APK");
                }
                progress(callback,"Verified. Opening Android installer…",100);
                new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){
                    if(activity.isDestroyed()||activity.isFinishing()){callback.show("Download complete. Open Updates and tap Download and install to continue.",100);return;}
                    try{launch(activity);}catch(Exception error){callback.show("Could not open Android installer. Try Download and install again.",-1);}
                }});
            }catch(Exception failed){progress(callback,"Update could not be downloaded or verified. Check your connection and try again.",-1);}
            finally{if(partial.exists())partial.delete();busy.set(false);}
        }},"PixelSuite-update-download").start();
    }
    private static HttpURLConnection connect(URL first)throws IOException{
        URL url=first;
        for(int attempt=0;attempt<6;attempt++){
            String host=url.getHost();
            if(!ApkDownloadPolicy.allowed(url))throw new IOException("Unexpected download destination");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestProperty("User-Agent","PixelSuite-UpdateInstaller");
            int code=connection.getResponseCode();
            if(code==200)return connection;
            String location=connection.getHeaderField("Location");connection.disconnect();
            if(code>=300&&code<400&&location!=null){url=new URL(url,location);continue;}
            throw new IOException("Download unavailable");
        }
        throw new IOException("Too many redirects");
    }
    static boolean valid(Context c,File file,ReleaseGate.Release release){
        try{
            if(!ApkDownloadPolicy.matches(file,release.size,release.digest))return false;
            PackageManager pm=c.getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES),installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNATURES);
            if(archive==null||!c.getPackageName().equals(archive.packageName)||!release.version.equals(archive.versionName)||archive.versionCode!=release.code||archive.versionCode<=installed.versionCode)return false;
            return archive.signatures!=null&&installed.signatures!=null&&archive.signatures.length==1&&installed.signatures.length==1&&archive.signatures[0].equals(installed.signatures[0]);
        }catch(Exception invalid){return false;}
    }
    private static void launch(Activity a)throws Exception{
        if(Build.VERSION.SDK_INT>=26&&!((Boolean)a.getPackageManager().getClass().getMethod("canRequestPackageInstalls").invoke(a.getPackageManager()))){
            a.getIntent().putExtra("resume_update_install",true);
            a.startActivityForResult(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",Uri.parse("package:"+a.getPackageName())),202);return;
        }
        Uri uri=Uri.parse("content://"+a.getPackageName()+".updates/verified.apk");
        Intent install=new Intent("android.intent.action.INSTALL_PACKAGE").setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.setClipData(ClipData.newRawUri("Pixel Suite update",uri));
        a.startActivity(install);
    }
}
