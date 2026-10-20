package io.github.pixelsuite;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

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
        SuiteLog.hooked = true;
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
        if (SYSTEMUI.equals(pkg)) {
            VolumeCamera.installSystemUi(cl);
            ScreenRecordTidy.install(cl);
            SuperDark.installSystemUi(cl);
            SystemToasts.install(cl);
        } else if (LAUNCHER.equals(pkg)) {
            ClearAll.installLauncher(cl);
            LauncherFeatures.install(cl);
            SuperDarkApps.installLauncher(cl);
            SuperDarkApps.installLauncherSettings(cl);
        } else if (FILES.equals(pkg)) {
            FilesSort.install(cl);
        } else if (PhotosEditor.PACKAGE.equals(pkg)) {
            PhotosEditor.install(cl);
            PhotosDarkPanel.install();
            PhotosToasts.install(cl);
            ComposeFrost.install("Google Photos video editor", null, true, "VideoEditorActivity");
        } else if (ScreenshotEditor.PACKAGE.equals(pkg)) {
            ScreenshotEditor.install(cl, param.getApplicationInfo());
        } else if (DocumentsSort.GOOGLE.equals(pkg) || DocumentsSort.AOSP.equals(pkg)) {
            DocumentsSort.install(cl);
        } else if (AliasProvider.GOOGLE.equals(pkg)) {
            GoogleAliases.install();
            SuperDarkApps.install(cl, pkg);
        } else if (SuperDarkApps.GBOARD.equals(pkg) || SETTINGS.equals(pkg)) {
            SuperDarkApps.install(cl, pkg);
        }
    }

    static void log(String msg) { Xp.log(msg); }
    static void log(String msg, Throwable t) { Xp.log(msg, t); }
}
