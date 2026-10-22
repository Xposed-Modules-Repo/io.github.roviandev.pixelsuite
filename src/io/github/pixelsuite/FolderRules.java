package io.github.pixelsuite;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.List;

/**
 * 2.0.6-preview20 (owner's design): what an open folder offers depends on how many apps it holds.
 * - 1 app: no name inside (the three dots sit in the middle of the footer; the name shows under the icon and is
 *   changed from the held folder's menu), and the menu has only "Add apps".
 * - 2 apps: the name is back and editable; the menu has "Add apps" and "Sort"; no grid choice (3x3).
 * - 3 apps and more: "Add apps", "Sort" and the grid sizes that matter: 3x3 always, 4x4 once a 3x3 page is full
 *   (10 apps and more), 5x5 once a 4x4 page is full (17 and more). A larger size chosen earlier is shown as 3x3 (or
 *   4x4) until the folder grows into it again.
 * Drawer and home screen folders alike.
 */
final class FolderRules {
    private FolderRules() {}

    /** Apps in the folder (0 for a folder being made). */
    static int count(Object info) {
        try { Object c = LauncherFeatures.get(info, "contents"); return c instanceof List ? ((List<?>) c).size() : 0; } catch (Throwable t) { return 0; }
    }
    /** The largest grid a folder of this many apps may use. */
    static int largestGrid(int count) { return count > 16 ? 5 : count > 9 ? 4 : 3; }
    /** The grid a folder shows: the chosen one, capped by what its apps need. */
    static int cap(Object info, int chosen) { return chosen <= 0 ? chosen : Math.min(chosen, largestGrid(count(info))); }

    static void install() {
        try {
            // The folder is open and an app has just left or arrived: the footer follows (the launcher closes a folder that
            // goes down to one app, so this mostly covers apps arriving).
            Xp.Callback follow = new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { if (Boolean.TRUE.equals(LauncherFeatures.get(p.thisObject, "mIsOpen"))) layoutFooter(p.thisObject, true); } catch (Throwable ignored) { }
                }
            };
            Xp.hookAllMethods(LauncherFeatures.type("folder.Folder"), "removeFolderContent", follow);
            Xp.hookAllMethods(LauncherFeatures.type("folder.Folder"), "onDrop", follow);
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder rules unavailable", error); }
    }

    /** The footer for the folder's app count: name and dots, or the dots alone in the middle. */
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
            // a quick crossfade: the footer fades, changes, and fades back
            footer.animate().cancel();
            footer.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() { public void run() {
                apply.run();
                footer.animate().alpha(1f).setDuration(160).start();
            } }).start();
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder footer unavailable", error); }
    }
}
