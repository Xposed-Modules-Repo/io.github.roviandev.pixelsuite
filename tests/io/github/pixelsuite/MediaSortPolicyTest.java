package io.github.pixelsuite;
import java.util.Arrays;
public final class MediaSortPolicyTest {
 static final String[] ROW={"_id","_display_name","date_modified","_data","mime_type"};
 static int count;
 static String order(String authority,String path,String[] projection,boolean distinct,String selection,boolean grouped,String requested){return MediaSortPolicy.order(authority,Arrays.asList(path.split("/")),projection,distinct,selection,grouped,requested);}
 static void check(boolean ok){count++;if(!ok)throw new AssertionError("Media sort policy case "+count);}
 static String run(String path,String[] projection,String selection,String requested){return order("media",path,projection,false,selection,false,requested);}
 public static void main(String[] args){
  check(run("external/file",ROW,"_data LIKE ?","_display_name ASC").startsWith("(format = 12289) DESC"));
  for(String path:new String[]{"external/images/media","external/video/media","external/audio/media","external/downloads"})check("date_modified DESC, _id DESC".equals(run(path,ROW,null,"_display_name ASC")));
  check(order("media","external/file",ROW,false,null,false,"[_display_name]")!=null);
  check(run("external/file",new String[]{"_display_name"},null,"_display_name ASC")==null);
  check(run("external/file",new String[]{"bucket_display_name"},null,"bucket_display_name ASC")==null);
  check(run("external/file",new String[]{"count(*)","date_modified"},null,"date_modified ASC")==null);
  check(order("other.provider","external/file",ROW,false,null,false,"name ASC")==null);
  check(order("media","external/file",ROW,true,null,false,"name ASC")==null);
  check(order("media","external/file",ROW,false,null,true,"name ASC")==null);
  for(String path:new String[]{"external/file/42","external/images/thumbnails","external/audio/albums"})check(run(path,ROW,null,"name ASC")==null);
  for(String selection:new String[]{"_display_name > ?","_id > ?","date_modified <= ?","x) GROUP BY (bucket_display_name"})check(run("external/file",ROW,selection,"name ASC")==null);
  check(run("external/file",ROW,null,"name ASC LIMIT 10 OFFSET 5")==null);
  check(run("external/file",ROW,null,null)==null);check(run("external/file",ROW,null,"")==null);
  check(run("external/file",null,null,"name ASC")==null);
  System.out.println("PASS: "+count+" media-list/aggregate/URI/paging policy cases");
 }
}
