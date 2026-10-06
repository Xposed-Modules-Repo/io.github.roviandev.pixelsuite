package io.github.pixelsuite;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * Pixel Suite entry class (libxposed API 102).
 *
 * Lifecycle, as implemented by LSPosed 2.2 (framework.dex): the framework creates this class
 * with its public no-arg constructor, calls attachFramework(), then onModuleLoaded(). From then
 * on the module object itself is the Xposed API (XposedModule extends XposedInterfaceWrapper).
 * system_server hooks go in onSystemServerStarting; app hooks in onPackageReady, which hands
 * over the app's final class loader.
 */
public class Module extends XposedModule {

    static final String TAG = "PixelSuite";

    private static final String SETTINGS = "com.android.settings";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String FILES = "com.google.android.apps.nbu.files";

    public Module() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.attach(this);
        log("loaded in " + param.getProcessName());
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        ClassLoader cl = param.getClassLoader();
        Flashlight.installSystem(cl);
        VolumeCamera.installSystem(cl);
        ThreeFinger.installSystem(cl);
        DoubleTapSleep.installSystem(cl);
        DoubleTapWake.installSystem(cl);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        ClassLoader cl = param.getClassLoader();
        String pkg = param.getPackageName();
        if (SETTINGS.equals(pkg)) {
            Flashlight.installSettings(cl);
            GesturePages.install(cl);
            DoubleTapWake.installSettings(cl);
        } else if (SYSTEMUI.equals(pkg)) {
            VolumeCamera.installSystemUi(cl);
        } else if (LAUNCHER.equals(pkg)) {
            ClearAll.installLauncher(cl);
        } else if (FILES.equals(pkg)) {
            FilesSort.install(cl);
        }
    }

    static void log(String msg) { Xp.log(msg); }
    static void log(String msg, Throwable t) { Xp.log(msg, t); }
}
