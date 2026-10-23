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
        PrivacyLock.installSystem(cl);   // 2.0.6-preview56: app lock
        ScreenshotWarm.installSystem(cl);   // 2.0.6-preview69: the screenshot process kept ready
        MiscSystem.installSystem(cl);   // 2.0.6-preview79: Miscellaneous (secure screenshots, hide accessibility, settings for the keyboard bar)
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        ClassLoader cl = param.getClassLoader();
        String pkg = param.getPackageName();
        if (SYSTEMUI.equals(pkg) && screenshotProcess()) {
            // preview69 (owner: screenshots were slow): System UI's screenshot process starts when a screenshot is
            // taken; only what shows there is loaded (Super Dark's colours, the toasts), so it is ready sooner.
            SuperDark.installSystemUi(cl);
            SystemToasts.install(cl);
            ScreenshotWarm.installScreenshotProcess(cl);   // preview90: Pixel Suite's keep-ready binding stays out of the service
        } else if (SYSTEMUI.equals(pkg)) {
            VolumeCamera.installSystemUi(cl);
            ScreenRecordTidy.install(cl);
            SuperDark.installSystemUi(cl);
            StatusBarFade.installSystemUi(cl);   // recents sideways: the status bar fades (asked by Pixel Launcher)
            SystemToasts.install(cl);
            PromptStyle.install(cl);   // 2.0.6-preview57: the app lock's fingerprint sheet (Pin pill, the app's icon)
        } else if (LAUNCHER.equals(pkg)) {
            ClearAll.installLauncher(cl);
            RecentsLock.install(cl);
            RecentsLandscape.install(cl);   // landscape recents: centred chips, status bar faded away
            PrivacyRecents.install(cl);   // 2.0.6-preview59: app lock in recents (blur, tap asks, no split)
            PrivacyFolders.install(cl);   // 2.0.6-preview60: locked folders
            PrivacyBadges.install(cl);   // 2.0.6-preview63: padlock badge on locked apps and folders
            PrivacyGuard.install(cl);   // 2.0.6-preview68: removing a locked app / folder asks first
            FeedBackdrop.install();   // Super Dark: the feed's grey panel (launcher side)
            OneAppFolders.install(cl);
            LauncherFeatures.install(cl);
            FolderRules.install();   // after LauncherFeatures (its class loader)
            SuperDarkApps.installLauncher(cl);
            SuperDarkApps.installLauncherSettings(cl);
        } else if (FILES.equals(pkg)) {
            FilesSort.install(cl);
        } else if (PhotosEditor.PACKAGE.equals(pkg)) {
            PhotosEditor.install(cl);
            PhotosDarkPanel.install();
            PhotosToasts.install(cl);
            // build 197: the video editor's "Saving video..." dialog (opened from the recording preview's Edit) follows the phone's theme and the Themes slider
            // preview21 (owner): the photo editor's dialogs too ("Discard changes?"): frost, light phone, Super Dark
            ComposeFrost.install("Google Photos editors", null, true, "VideoEditorActivity|PhotoEditorNextActivity");
        } else if (ScreenshotEditor.PACKAGE.equals(pkg)) {
            ScreenshotEditor.install(cl, param.getApplicationInfo());
        } else if (DocumentsSort.GOOGLE.equals(pkg) || DocumentsSort.AOSP.equals(pkg)) {
            DocumentsSort.install(cl);
        } else if (AliasProvider.GOOGLE.equals(pkg)) {
            GoogleAliases.install();
            SuperDarkApps.install(cl, pkg);
        } else if (SuperDarkApps.GBOARD.equals(pkg) || SETTINGS.equals(pkg) || SuperDarkApps.SETTINGS_SEARCH.equals(pkg)
                || "com.google.android.packageinstaller".equals(pkg) || "com.android.packageinstaller".equals(pkg)) {   // preview77: the "Uninstall this app?" dialog too
            SuperDarkApps.install(cl, pkg);
            if (SuperDarkApps.GBOARD.equals(pkg)) KeyboardBarTweak.install(pkg);   // preview79: Miscellaneous > Keyboard bar
        } else {
            // preview79: any other app Pixel Suite is enabled for in LSPosed (FUTO Keyboard in the scope, or one the owner
            // ticked: a keyboard, a banking app): Miscellaneous > Keyboard bar and Hide Developer Options (both off by
            // default; framework classes only)
            KeyboardBarTweak.install(pkg);
            DevOptionsHide.install(pkg);   // preview80: Miscellaneous > Hide Developer Options (apps the owner ticks)
        }
    }

    /** This is System UI's screenshot process ("com.android.systemui:screenshot"). */
    private static boolean screenshotProcess() {
        try { String name = (String) android.app.Application.class.getMethod("getProcessName").invoke(null); return name != null && name.endsWith(":screenshot"); }
        catch (Throwable t) { return false; }
    }

    static void log(String msg) { Xp.log(msg); }
    static void log(String msg, Throwable t) { Xp.log(msg, t); }
}
