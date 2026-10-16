package io.github.libxposed.api;

public abstract class XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface {
    public XposedModule() {}
    @Override public void onModuleLoaded(ModuleLoadedParam param) {}
    @Override public void onPackageLoaded(PackageLoadedParam param) {}
    @Override public void onPackageReady(PackageReadyParam param) {}
    @Override public void onSystemServerStarting(SystemServerStartingParam param) {}
    @Override public boolean onHotReloading(HotReloadingParam param) { return false; }
    @Override public void onHotReloaded(HotReloadedParam param) {}
}
