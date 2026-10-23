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
        PrivacyLock.installSystem(cl);
        ScreenshotWarm.installSystem(cl);
        MiscSystem.installSystem(cl);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        ClassLoader cl = param.getClassLoader();
        String pkg = param.getPackageName();
        if (SYSTEMUI.equals(pkg) && screenshotProcess()) {
            SuperDark.installSystemUi(cl);
            SystemToasts.install(cl);
            ScreenshotWarm.installScreenshotProcess(cl);
        } else if (SYSTEMUI.equals(pkg)) {
            VolumeCamera.installSystemUi(cl);
            ScreenRecordTidy.install(cl);
            SuperDark.installSystemUi(cl);
            StatusBarFade.installSystemUi(cl);
            SystemToasts.install(cl);
            PromptStyle.install(cl);
        } else if (LAUNCHER.equals(pkg)) {
            ClearAll.installLauncher(cl);
            RecentsLock.install(cl);
            RecentsLandscape.install(cl);
            PrivacyRecents.install(cl);
            PrivacyFolders.install(cl);
            PrivacyBadges.install(cl);
            PrivacyGuard.install(cl);
            FeedBackdrop.install();
            OneAppFolders.install(cl);
            LauncherFeatures.install(cl);
            FolderRules.install();
            SuperDarkApps.installLauncher(cl);
            SuperDarkApps.installLauncherSettings(cl);
        } else if (FILES.equals(pkg)) {
            FilesSort.install(cl);
        } else if (PhotosEditor.PACKAGE.equals(pkg)) {
            PhotosEditor.install(cl);
            PhotosDarkPanel.install();
            PhotosToasts.install(cl);
            ComposeFrost.install("Google Photos editors", null, true, "VideoEditorActivity|PhotoEditorNextActivity");
        } else if (ScreenshotEditor.PACKAGE.equals(pkg)) {
            ScreenshotEditor.install(cl, param.getApplicationInfo());
        } else if (DocumentsSort.GOOGLE.equals(pkg) || DocumentsSort.AOSP.equals(pkg)) {
            DocumentsSort.install(cl);
        } else if (AliasProvider.GOOGLE.equals(pkg)) {
            GoogleAliases.install();
            SuperDarkApps.install(cl, pkg);
        } else if (SuperDarkApps.GBOARD.equals(pkg) || SETTINGS.equals(pkg) || SuperDarkApps.SETTINGS_SEARCH.equals(pkg)
                || "com.google.android.packageinstaller".equals(pkg) || "com.android.packageinstaller".equals(pkg)) {
            SuperDarkApps.install(cl, pkg);
            if (SuperDarkApps.GBOARD.equals(pkg)) KeyboardBarTweak.install(pkg);
        } else {
            KeyboardBarTweak.install(pkg);
            DevOptionsHide.install(pkg);
        }
    }

    private static boolean screenshotProcess() {
        try { String name = (String) android.app.Application.class.getMethod("getProcessName").invoke(null); return name != null && name.endsWith(":screenshot"); }
        catch (Throwable t) { return false; }
    }

    static void log(String msg) { Xp.log(msg); }
    static void log(String msg, Throwable t) { Xp.log(msg, t); }
}
