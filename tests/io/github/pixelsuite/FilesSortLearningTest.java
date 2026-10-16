package io.github.pixelsuite;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;

/** Exercises production field learning against files created only in the test directory. */
public final class FilesSortLearningTest {
    static final class Model { String path; long a,b; Model(String p,long m,long c){path=p;a=m;b=c;} }
    static final class Seconds { String path; long a; Seconds(String p,long m){path=p;a=m;} }
    static final class Named { String path; long modified,created; Named(String p,long m,long c){path=p;modified=m;created=c;} }
    static final class NotFile { String name="not a path"; long a; NotFile(long t){a=t;} }
    static Object learn(Class<?> type,Object... rows)throws Exception{
        Method m=FilesSort.class.getDeclaredMethod("learn",Class.class,java.util.List.class);m.setAccessible(true);
        return m.invoke(null,type,Arrays.asList(rows));
    }
    static long time(Object accessor,Object row)throws Exception{
        Method m=accessor.getClass().getDeclaredMethod("lastModified",Object.class);m.setAccessible(true);return (Long)m.invoke(accessor,row);
    }
    static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory(new File(args[0]).toPath(),"files-sort-").toFile();
        long[] times={1600000000000L,1610000000000L,1620000000000L};
        Model[] ms=new Model[3];Seconds[] sec=new Seconds[3];
        for(int i=0;i<3;i++){
            File f=new File(root,"fixture"+i);check(f.createNewFile(),"fixture creation");check(f.setLastModified(times[i]),"timestamp setup");
            ms[i]=new Model(f.getAbsolutePath(),f.lastModified(),times[i]+500000000L);
            sec[i]=new Seconds(f.getAbsolutePath(),f.lastModified()/1000);
        }
        Object access=learn(Model.class,(Object[])ms);check(access!=null,"Obfuscated modified field not learned");
        for(int i=0;i<3;i++)check(time(access,ms[i])==times[i],"Created date incorrectly selected over disk match");
        access=learn(Seconds.class,(Object[])sec);check(access!=null,"Seconds field not learned");
        for(int i=0;i<3;i++)check(time(access,sec[i])==times[i],"Seconds not normalized");
        String missing=new File(root,"missing").getAbsolutePath();
        check(learn(Model.class,new Model(missing,times[0],times[1]),new Model(missing,times[1],times[2]),new Model(missing,times[2],times[2]+500000000L))==null,"Ambiguous dates must preserve native sorting");
        Named first=new Named(missing,times[0],times[2]);
        access=learn(Named.class,first,new Named(missing,times[1],times[2]+1000),new Named(missing,times[2],times[2]+2000));
        check(access!=null&&time(access,first)==times[0],"Named modified compatibility fallback");
        check(learn(NotFile.class,new NotFile(times[0]),new NotFile(times[1]),new NotFile(times[2]))==null,"Non-file model changed");
        for(File f:root.listFiles())check(f.delete(),"Fixture cleanup");check(root.delete(),"Directory cleanup");
        System.out.println("PASS: obfuscated ms/seconds disk matches, ambiguous-date rejection, named fallback and non-file exclusion");
    }
}
