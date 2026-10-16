package io.github.pixelsuite;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReleaseGate {
    static final Pattern VERSION = Pattern.compile("(?:v)?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    static final class Release {
        final String version, name, digest; final long code, size;
        Release(String v,long c,String n,long s,String d){version=v;code=c;name=n;size=s;digest=d;}
    }
    static int compare(String a,String b){
        Matcher x=VERSION.matcher(a),y=VERSION.matcher(b);
        if(!x.matches()||!y.matches())throw new IllegalArgumentException("Stable versions required");
        for(int i=1;i<=3;i++){java.math.BigInteger aa=new java.math.BigInteger(x.group(i)),bb=new java.math.BigInteger(y.group(i));int c=aa.compareTo(bb);if(c!=0)return c;}
        return 0;
    }
    static Release candidate(List<Release> github,List<Release> xposed,String installed,long code){
        if(!VERSION.matcher(installed).matches())return null;
        Release best=null;
        for(Release g:github)for(Release x:xposed){
            if(!VERSION.matcher(g.version).matches()||!g.version.equals(x.version)||x.code<=code||compare(g.version,installed)<=0)continue;
            if(g.size<=0||g.size!=x.size||!g.name.equals(x.name)||g.digest==null||!g.digest.matches("sha256:[0-9a-f]{64}")||!g.digest.equals(x.digest))continue;
            if(best==null||compare(g.version,best.version)>0)best=new Release(g.version,x.code,g.name,g.size,g.digest);
        }
        return best;
    }
}
