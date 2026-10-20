package io.github.pixelsuite;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
/** Caller-restricted app customization bridge: launcher writes; Google reads. */
public final class AliasProvider extends ContentProvider {
    static final String AUTHORITY="io.github.roviandev.pixelsuite.aliases";
    static final Uri URI=Uri.parse("content://"+AUTHORITY);
    static final String CHANGE="io.github.roviandev.pixelsuite.ALIASES_CHANGED";
    static final String GOOGLE="com.google.android.googlequicksearchbox";
    public boolean onCreate(){return true;}
    boolean authorized(boolean write){
        if(Binder.getCallingUid()==android.os.Process.myUid())return true;
        String[] packages=getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        return AliasBridgeAccess.allows(packages,write);
    }
    @Override public Bundle call(String method,String arg,Bundle extras){
        boolean write="replace".equals(method)||"icon".equals(method);if(!write&&!"read".equals(method))throw new IllegalArgumentException("Unknown method");
        if(!authorized(write))throw new SecurityException("App-label bridge caller denied");
        if("icon".equals(method)){
            byte[] bytes=extras==null?null:extras.getByteArray("png");
            if(arg==null||!arg.matches("[a-f0-9]{64}")||bytes==null||bytes.length>262144)throw new IllegalArgumentException("Invalid icon data");
            try{
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");StringBuilder hash=new StringBuilder();for(byte b:digest.digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));if(!arg.equals(hash.toString()))throw new IllegalArgumentException("Icon hash mismatch");
                java.io.File dir=new java.io.File(getContext().getFilesDir(),"search-icons");if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("Cannot create icon directory");
                java.io.File file=new java.io.File(dir,arg+".png");if(!file.exists())try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
            }catch(java.io.IOException error){throw new IllegalStateException("Could not save icon",error);}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
            return Bundle.EMPTY;
        }
        android.content.SharedPreferences prefs=getContext().getSharedPreferences("app-label-bridge",0);
        if(write){
            String json=extras==null?null:extras.getString("aliases");if(json==null||json.length()>262144)throw new IllegalArgumentException("Invalid alias data");
            try{new org.json.JSONObject(json);}catch(Exception invalid){throw new IllegalArgumentException("Invalid alias JSON");}
            if(!prefs.edit().putString("aliases",json).commit())throw new IllegalStateException("Could not save aliases");
            getContext().sendBroadcast(new Intent(CHANGE).setPackage(GOOGLE));
            try{
                org.json.JSONObject map=new org.json.JSONObject(json);java.util.Set<String> used=new java.util.HashSet<String>();java.util.Iterator<String> keys=map.keys();
                while(keys.hasNext()){org.json.JSONObject entry=map.optJSONObject(keys.next());if(entry!=null)used.add(entry.optString("icon")+".png");}
                java.io.File[] files=new java.io.File(getContext().getFilesDir(),"search-icons").listFiles();
                if(files!=null)for(java.io.File file:files)if(file.getName().matches("[a-f0-9]{64}\\.png")&&!used.contains(file.getName()))file.delete();
            }catch(Exception ignored){}
        }
        Bundle result=new Bundle();result.putString("aliases",prefs.getString("aliases","{}"));return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException{
        if(!authorized(false))throw new SecurityException("App-icon bridge caller denied");
        String hash=uri.getLastPathSegment();if(!"r".equals(mode)||uri.getPathSegments().size()!=2||!"icons".equals(uri.getPathSegments().get(0))||hash==null||!hash.matches("[a-f0-9]{64}"))throw new java.io.FileNotFoundException("Invalid icon URI");
        return ParcelFileDescriptor.open(new java.io.File(new java.io.File(getContext().getFilesDir(),"search-icons"),hash+".png"),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    public String getType(Uri u){return "application/json";}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
