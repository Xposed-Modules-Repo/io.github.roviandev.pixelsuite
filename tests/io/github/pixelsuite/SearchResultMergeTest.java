package io.github.pixelsuite;
import java.util.*;
public class SearchResultMergeTest {
    static final class Row{final boolean plain;final Set<String> ids;Row(boolean plain,String...ids){this.plain=plain;this.ids=new HashSet<String>(Arrays.asList(ids));}}
    static final SearchResultMerge.Keys<Row> keys=new SearchResultMerge.Keys<Row>(){public Set<String> keys(Row r){return r.ids;}public boolean ordinary(Row r){return r.plain;}};
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        String keep="0|keep/Main",work="10|keep/Main",other="0|keep/Other";
        Row nativeKeep=new Row(false,keep),alias=new Row(true,keep),shortcut=new Row(false),web=new Row(false),group=new Row(false,keep,other);
        Map<String,Row> additions=new LinkedHashMap<String,Row>();additions.put(keep,alias);
        List<Row> r=SearchResultMerge.merge(Arrays.asList(nativeKeep),additions,keys);check(r.size()==1&&r.get(0)==nativeKeep,"Native Keep result must replace alias injection");
        r=SearchResultMerge.merge(Arrays.asList(alias,nativeKeep,web,shortcut),additions,keys);check(r.size()==3&&r.contains(web)&&r.contains(shortcut)&&!r.contains(alias),"Remove synthetic duplicate without removing web/shortcut rows");
        r=SearchResultMerge.merge(Arrays.asList(group),additions,keys);check(r.size()==1&&r.get(0)==group,"A grouped native result already covers Keep");
        additions.put(work,new Row(true,work));r=SearchResultMerge.merge(Arrays.asList(nativeKeep),additions,keys);check(r.size()==2,"Work profile and personal Keep must remain distinct");
        additions.put(other,new Row(true,other));r=SearchResultMerge.merge(Arrays.asList(nativeKeep),additions,keys);check(r.size()==3,"Different activity in same package must remain distinct");
        r=SearchResultMerge.merge(Arrays.asList(alias,alias,web),Collections.<String,Row>emptyMap(),keys);check(r.size()==2&&r.contains(web),"Repeated ordinary app row deduplication");
        check(!AliasBridgeAccess.allows(new String[]{"com.example.reader"},false),"Unrelated app cannot read");
        check(!AliasBridgeAccess.allows(new String[]{"com.google.android.googlequicksearchbox"},true),"Google cannot write");
        check(AliasBridgeAccess.allows(new String[]{"com.google.android.googlequicksearchbox"},false),"Google can read");
        check(AliasBridgeAccess.allows(new String[]{"com.google.android.apps.nexuslauncher"},true),"Launcher can publish");
        check(!AliasBridgeAccess.allows(new String[]{"com.google.android.apps.nexuslauncher.fake"},true),"Prefix spoof rejected");
        check(!AliasBridgeAccess.allows(null,false),"Unknown caller rejected");
        System.out.println("PASS: 6 search merge cases and 6 alias bridge caller-access cases");
    }
}
