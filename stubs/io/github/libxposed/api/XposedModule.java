package io.github.libxposed.api;

/**
 * Compile-only stub matching framework.dex: public abstract, extends XposedInterfaceWrapper,
 * implements XposedModuleInterface, only a no-arg constructor. The module object itself is the
 * API once the framework has called attachFramework().
 */
public abstract class XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface {
    public XposedModule() {}
    @Override public void onModuleLoaded(ModuleLoadedParam param) {}
    @Override public void onPackageLoaded(PackageLoadedParam param) {}
    @Override public void onPackageReady(PackageReadyParam param) {}
    @Override public void onSystemServerStarting(SystemServerStartingParam param) {}
    @Override public boolean onHotReloading(HotReloadingParam param) { return false; }
    @Override public void onHotReloaded(HotReloadedParam param) {}
}
