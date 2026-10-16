package io.github.pixelsuite;
final class AliasBridgeAccess {
    static boolean allows(String[] packages,boolean write){
        if(packages!=null)for(String pkg:packages){
            if("com.google.android.apps.nexuslauncher".equals(pkg))return true;
            if(!write&&"com.google.android.googlequicksearchbox".equals(pkg))return true;
        }
        return false;
    }
}
