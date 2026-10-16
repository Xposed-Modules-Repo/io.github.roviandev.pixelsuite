package io.github.pixelsuite;
import java.util.*;
public class DrawerOrderTest {
    static int fails=0;
    static void check(boolean ok,String what){if(!ok){fails++;System.out.println("FAIL: "+what);}}
    public static void main(String[] x){
        String[][] apps={
            {"Zoom","k1"},{"amazon Shopping","k2"},{"Amazon","k3"},{"Ärzte","k4"},{"تطبيق","k5"},{"أمازون","k6"},
            {"١٢٣ تطبيق","k7"},{"123 App","k8"},{"9GAG","k9"},{"#hash","k10"},{"@home","k11"},{"Ωmega","k12"},
            {"Telegram","k13"},{"Telegram","k14"},{" Keep","k15"},{"‏واتساب","k16"},{"App 10","k17"},{"App 2","k18"},
            {"","k19"},{"bilibili","k20"},{"ب","k21"},{"10 Apps","k22"},{"2 Apps","k23"},{"٢ تطبيق","k24"}};
        List<String[]> list=new ArrayList<String[]>(Arrays.asList(apps));
        Collections.shuffle(list,new Random(7));
        Collections.sort(list,new Comparator<String[]>(){public int compare(String[] a,String[] b){return DrawerOrder.compare(a[0],a[1],b[0],b[1]);}});
        List<String> names=new ArrayList<String>();for(String[] a:list)names.add(a[0]+"("+a[1]+")");
        System.out.println(names);
        int last=-1;for(String[] a:list){int g=DrawerOrder.group(a[0],DrawerOrder.start(a[0]));check(g>=last,"group order at "+a[0]);last=g;}
        check(DrawerOrder.group("١٢٣ تطبيق",0)==DrawerOrder.SYMBOL,"arabic-indic digits are numbers");
        check(DrawerOrder.group("‏واتساب",DrawerOrder.start("‏واتساب"))==DrawerOrder.ARABIC,"bidi mark skipped");
        check(DrawerOrder.group(" Keep",DrawerOrder.start(" Keep"))==DrawerOrder.LATIN,"leading space skipped");
        check(DrawerOrder.compareNames("App 2","App 10")<0,"natural numbers on tie");
        check(DrawerOrder.compareNames("2 Apps","10 Apps")<0,"numbers by value");
        check(DrawerOrder.compareNames("٢ تطبيق","10 Apps")<0,"arabic-indic 2 < 10");
        check(DrawerOrder.compareNames("amazon","Amazon")!=0,"case tie broken");
        check(DrawerOrder.compare("Telegram","k13","Telegram","k14")<0,"identity tie-break");
        check(DrawerOrder.compareNames("Ärzte","Zoom")<0,"accent A before Z");
        check(DrawerOrder.compareNames("Zoom","أمازون")<0,"latin before arabic");
        check(DrawerOrder.compareNames("أمازون","Ωmega")<0,"arabic before other script");
        check(DrawerOrder.compareNames("Ωmega","#hash")<0,"other before symbols");
        check(DrawerOrder.compareNames("123 App","#hash")<0,"numbers before special");
        for(String[] a:apps)for(String[] b:apps){int r1=DrawerOrder.compare(a[0],a[1],b[0],b[1]),r2=DrawerOrder.compare(b[0],b[1],a[0],a[1]);check(Integer.signum(r1)==-Integer.signum(r2),"antisymmetry "+a[1]+" "+b[1]);if(a!=b)check(r1!=0,"no ties "+a[1]+" "+b[1]);}
        for(String[] a:apps)for(String[] b:apps)for(String[] c:apps){if(DrawerOrder.compare(a[0],a[1],b[0],b[1])<0&&DrawerOrder.compare(b[0],b[1],c[0],c[1])<0)check(DrawerOrder.compare(a[0],a[1],c[0],c[1])<0,"transitive "+a[1]+b[1]+c[1]);}
        System.out.println(fails==0?"ALL PASS":"FAILURES: "+fails);
        System.exit(fails==0?0:1);
    }
}
