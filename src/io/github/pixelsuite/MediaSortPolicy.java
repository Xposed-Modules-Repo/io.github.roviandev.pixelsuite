package io.github.pixelsuite;
import java.util.List;
import java.util.regex.Pattern;
final class MediaSortPolicy {
    static final Pattern KEYSET=Pattern.compile("(?i)\\b(?:_display_name|_id|date_modified)\\b\\s*[<>]");
    static final Pattern GROUP=Pattern.compile("(?i)\\b(?:GROUP\\s+BY|HAVING|DISTINCT)\\b");
    static final Pattern INLINE_PAGE=Pattern.compile("(?i)\\b(?:LIMIT|OFFSET)\\b");
    static String order(String authority,List<String> path,String[] projection,boolean distinct,
                        String selection,boolean grouped,String requested){
        if(!"media".equals(authority)||distinct||grouped||projection==null||requested==null||requested.trim().isEmpty())return null;
        boolean files=path.size()==2&&"file".equals(path.get(1));
        boolean downloads=path.size()==2&&"downloads".equals(path.get(1));
        boolean media=path.size()==3&&"media".equals(path.get(2))&&("images".equals(path.get(1))||"video".equals(path.get(1))||"audio".equals(path.get(1)));
        if(!files&&!downloads&&!media)return null;
        boolean id=false,time=false,name=false;
        for(String column:projection){id|="_id".equals(column);time|="date_modified".equals(column);name|="_display_name".equals(column)||"_data".equals(column);}
        if(!id||!time||!name)return null;
        if(selection!=null&&(KEYSET.matcher(selection).find()||GROUP.matcher(selection).find()))return null;
        if(INLINE_PAGE.matcher(requested).find())return null;
        return files?"(format = 12289) DESC, date_modified DESC, _id DESC":"date_modified DESC, _id DESC";
    }
}
