package io.github.pixelsuite;
import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
final class ApkDownloadPolicy {
 static final long MAX=64L*1024*1024;
 static boolean allowed(URL url){String h=url.getHost();return "https".equals(url.getProtocol())&&(url.getPort()==-1||url.getPort()==443)&&url.getUserInfo()==null&&(h.equals("github.com")||h.equals("release-assets.githubusercontent.com")||h.equals("objects.githubusercontent.com"));}
 static boolean matches(File file,long expected,String hash){
  if(expected<=0||expected>MAX||!file.isFile()||file.length()!=expected||hash==null||!hash.matches("sha256:[0-9a-f]{64}"))return false;
  try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;long read=0;while((n=in.read(b))!=-1){read+=n;if(read>expected)return false;digest.update(b,0,n);}if(read!=expected)return false;}
   StringBuilder actual=new StringBuilder("sha256:");for(byte b:digest.digest())actual.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return actual.toString().equals(hash);
  }catch(Exception invalid){return false;}
 }
}
