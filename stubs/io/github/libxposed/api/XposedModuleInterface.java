package io.github.libxposed.api;

/** Compile-only stub. PackageReadyParam extends PackageLoadedParam, as in framework.dex. */
public interface XposedModuleInterface {
    interface ModuleLoadedParam { String getProcessName(); boolean isSystemServer(); }
    interface PackageLoadedParam {
        String getPackageName();
        ClassLoader getDefaultClassLoader();
        android.content.pm.ApplicationInfo getApplicationInfo();
        boolean isFirstPackage();
    }
    interface PackageReadyParam extends PackageLoadedParam {
        ClassLoader getClassLoader();
        android.app.AppComponentFactory getAppComponentFactory();
    }
    interface SystemServerStartingParam { ClassLoader getClassLoader(); }
    interface HotReloadingParam {}
    interface HotReloadedParam {}
    void onModuleLoaded(ModuleLoadedParam param);
    void onPackageLoaded(PackageLoadedParam param);
    void onPackageReady(PackageReadyParam param);
    void onSystemServerStarting(SystemServerStartingParam param);
    boolean onHotReloading(HotReloadingParam param);
    void onHotReloaded(HotReloadedParam param);
}
