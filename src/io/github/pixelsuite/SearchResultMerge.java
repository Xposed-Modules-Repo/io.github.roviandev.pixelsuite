package io.github.pixelsuite;
import java.util.*;
/** Component/profile identities, never display labels, decide whether an app is present. */
final class SearchResultMerge {
    interface Keys<T>{Set<String> keys(T row);boolean ordinary(T row);}
    static <T> ArrayList<T> merge(List<T> rows,Map<String,T> additions,Keys<T> keys){
        Set<String> rich=new HashSet<String>(),seen=new HashSet<String>();
        for(T row:rows)if(!keys.ordinary(row))rich.addAll(keys.keys(row));
        ArrayList<T> result=new ArrayList<T>();
        for(T row:rows){
            Set<String> ids=keys.keys(row);
            if(keys.ordinary(row)&&!ids.isEmpty()){
                boolean duplicate=false;for(String id:ids)if(rich.contains(id)||seen.contains(id))duplicate=true;
                if(duplicate)continue;
            }
            result.add(row);seen.addAll(ids);
        }
        int at=0;for(Map.Entry<String,T> entry:additions.entrySet())if(seen.add(entry.getKey()))result.add(at++,entry.getValue());
        return result;
    }
}
