package io.github.pixelsuite;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.List;

final class FolderRules {
    private FolderRules() {}

    static int count(Object info) {
        try { Object c = LauncherFeatures.get(info, "contents"); return c instanceof List ? ((List<?>) c).size() : 0; } catch (Throwable t) { return 0; }
    }
    static int largestGrid(int count) { return count > 16 ? 5 : count > 9 ? 4 : 3; }
    static int cap(Object info, int chosen) { return chosen <= 0 ? chosen : Math.min(chosen, largestGrid(count(info))); }

    static void install() {
        try {
            Xp.Callback follow = new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { if (Boolean.TRUE.equals(LauncherFeatures.get(p.thisObject, "mIsOpen"))) layoutFooter(p.thisObject, true); } catch (Throwable ignored) { }
                }
            };
            Xp.hookAllMethods(LauncherFeatures.type("folder.Folder"), "removeFolderContent", follow);
            Xp.hookAllMethods(LauncherFeatures.type("folder.Folder"), "onDrop", follow);
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder rules unavailable", error); }
    }

    static void layoutFooter(Object folder, boolean animate) {
        try {
            final View name = (View) LauncherFeatures.get(folder, "mFolderName");
            final LinearLayout footer = (LinearLayout) LauncherFeatures.get(folder, "mFooter");
            final View menu = footer == null ? null : footer.findViewWithTag("pixelsuite_folder_menu");
            if (name == null || menu == null) return;
            final boolean alone = count(LauncherFeatures.get(folder, "mInfo")) <= 1;
            final boolean wasAlone = menu.getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT;
            if (alone == wasAlone && (alone ? name.getVisibility() == View.GONE : name.getVisibility() == View.VISIBLE)) return;
            Runnable apply = new Runnable() { public void run() {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) menu.getLayoutParams();
                lp.width = alone ? ViewGroup.LayoutParams.MATCH_PARENT : LauncherFeatures.dp(menu.getContext(), 48);
                menu.setLayoutParams(lp);
                name.setVisibility(alone ? View.GONE : View.VISIBLE);
                if (!alone) name.setAlpha(1f);
            } };
            if (!animate) { apply.run(); return; }
            footer.animate().cancel();
            footer.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() { public void run() {
                apply.run();
                footer.animate().alpha(1f).setDuration(160).start();
            } }).start();
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder footer unavailable", error); }
    }
}
