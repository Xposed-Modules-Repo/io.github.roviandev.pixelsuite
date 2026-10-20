package io.github.pixelsuite;
import android.content.ComponentName;
import android.os.UserHandle;
import java.lang.reflect.Field;
import java.util.*;
final class LauncherSearchAliases {
    static final Map<Class<?>,List<Field>> fields=new java.util.concurrent.ConcurrentHashMap<Class<?>,List<Field>>();
    static List<Field> fields(Class<?> type){
        List<Field> found=fields.get(type);if(found!=null)return found;
        found=new ArrayList<Field>();
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Field f:c.getDeclaredFields()){
            if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;
            if(f.getType().getName().equals("android.app.search.SearchTarget")||List.class.isAssignableFrom(f.getType()))try{f.setAccessible(true);found.add(f);}catch(Throwable ignored){}
        }
        fields.put(type,found);return found;
    }
    static final class App {
        final String key;final ComponentName component;final Object user,info;
        App(Object info){this.info=info;key=LauncherFeatures.identity(info);component=(ComponentName)LauncherFeatures.call(info,"getTargetComponent");user=LauncherFeatures.get(info,"user");}
    }
    static void install(){
        Xp.hookAllMethods(Xp.findClass("com.google.android.apps.nexuslauncher.search.SearchEditText",LauncherFeatures.loader),"onSearchResult",new Xp.Callback(){protected void beforeHookedMethod(Xp.Param p){
            if(!LauncherFeatures.on(LauncherFeatures.RENAME)||p.args.length<2||!(p.args[0] instanceof String)||!(p.args[1] instanceof ArrayList))return;
            String query=LauncherFeatures.searchText(((String)p.args[0]).trim());if(query.isEmpty())return;
            final List<App> index=new ArrayList<App>();Map<String,Object> additions=new LinkedHashMap<String,Object>();
            final Class<?> adapterItem=LauncherFeatures.type("allapps.BaseAllAppsAdapter$AdapterItem");
            for(Object info:LauncherFeatures.apps()){
                App app=new App(info);if(app.key==null)continue;index.add(app);
                String alias=LauncherFeatures.data.alias(app.key);
                if(!alias.isEmpty()&&LauncherFeatures.searchText(alias).contains(query)){
                    Object row=Xp.newInstance(adapterItem,2);LauncherFeatures.set(row,"itemInfo",info);additions.put(app.key,row);
                }
            }
            p.args[1]=SearchResultMerge.merge((List<Object>)p.args[1],additions,new SearchResultMerge.Keys<Object>(){
                public boolean ordinary(Object row){return row.getClass()==adapterItem;}
                public Set<String> keys(Object row){return rowKeys(row,index);}
            });
        }});
        // Native search actions may supply their own title after app binding.
        Xp.hookAllMethods(Xp.findClass("com.google.android.apps.nexuslauncher.allapps.SearchResultIcon",LauncherFeatures.loader),"b",new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
            android.view.View view=(android.view.View)p.thisObject;Object info=view.getTag();
            if(info!=null&&LauncherFeatures.type("model.data.ItemInfoWithIcon").isInstance(info)){
                LauncherFeatures.label(info);
                if(((Number)LauncherFeatures.get(info,"itemType")).intValue()==0){LauncherFeatures.call(view,"applyIconAndLabel",info);((android.widget.TextView)view).setText((CharSequence)LauncherFeatures.get(info,"title"));}
            }
        }});
    }
    static Set<String> rowKeys(Object row,List<App> index){
        Set<String> result=new HashSet<String>();
        try{Object info=LauncherFeatures.get(row,"itemInfo");if(info!=null&&!LauncherFeatures.privateItem(info)&&((Number)LauncherFeatures.get(info,"itemType")).intValue()==0){String key=LauncherFeatures.identity(info);if(key!=null)result.add(key);}}catch(Throwable ignored){}
        for(Field f:fields(row.getClass())){
            try{
                if(f.getType().getName().equals("android.app.search.SearchTarget")){f.setAccessible(true);addTarget(f.get(row),index,result);}
                else if(List.class.isAssignableFrom(f.getType())){f.setAccessible(true);Object value=f.get(row);if(value instanceof List)for(Object child:(List<?>)value)if(child!=null&&child.getClass().getName().equals("android.app.search.SearchTarget"))addTarget(child,index,result);}
            }catch(Throwable ignored){}
        }
        return result;
    }
    static void addTarget(Object target,List<App> index,Set<String> result){
        if(target==null)return;
        try{
            if(((Number)LauncherFeatures.call(target,"getResultType")).intValue()!=1||LauncherFeatures.call(target,"getShortcutInfo")!=null)return;
            String layout=(String)LauncherFeatures.call(target,"getLayoutType");if(layout!=null&&layout.contains("header"))return;
            String pkg=(String)LauncherFeatures.call(target,"getPackageName");Object user=LauncherFeatures.call(target,"getUserHandle");
            android.os.Bundle extras=(android.os.Bundle)LauncherFeatures.call(target,"getExtras");String cls=extras==null?null:extras.getString("class");
            ComponentName component=cls==null||cls.isEmpty()?null:new ComponentName(pkg,cls);
            String single=null;int count=0;
            for(App app:index)if(app.user.equals(user)&&app.component.getPackageName().equals(pkg)){
                if(component!=null&&app.component.equals(component)){result.add(app.key);return;}
                single=app.key;count++;
            }
            if(component==null&&count==1)result.add(single);
        }catch(Throwable ignored){}
    }
}
