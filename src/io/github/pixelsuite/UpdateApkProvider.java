package io.github.pixelsuite;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
/** Read-only single-file URI, accessible externally only with an explicit URI grant. */
public final class UpdateApkProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private void check(Uri uri){if(!"/verified.apk".equals(uri.getPath())||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Unknown update URI");}
    public String getType(Uri uri){check(uri);return "application/vnd.android.package-archive";}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{check(uri);if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(UpdateInstaller.apk(getContext()),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){check(uri);String[] cols=projection==null?new String[]{"_display_name","_size"}:projection;MatrixCursor c=new MatrixCursor(cols);Object[] row=new Object[cols.length];for(int i=0;i<cols.length;i++){if("_display_name".equals(cols[i]))row[i]="PixelSuite-update.apk";if("_size".equals(cols[i]))row[i]=UpdateInstaller.apk(getContext()).length();}c.addRow(row);return c;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
