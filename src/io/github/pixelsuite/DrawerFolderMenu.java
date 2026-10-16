package io.github.pixelsuite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Toast;

/**
 * The menu of a held drawer folder: exactly Rename, Sort and Remove. It opens directly under
 * the folder icon with the approved dropdown frost. Sort and Remove ask Yes/No first; No (or
 * dismissing the prompt) changes nothing. Moving the finger, or holding 1.5 s, closes it and
 * starts moving the folder (DrawerFolders hold/drag).
 */
final class DrawerFolderMenu {
    private static PopupWindow open;

    private DrawerFolderMenu() {}

    static void show(final View icon) {
        dismiss();
        final Object info = icon.getTag();
        if (!DrawerFolders.isFolder(info) || DrawerFolders.record(info) == null) return;
        Context c = icon.getContext();
        LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, FrostedSurface.dp(c, 6), 0, FrostedSurface.dp(c, 6));
        ScrollView menu = new ScrollView(c); menu.addView(card);
        menu.setBackground(FrostedSurface.windowDrawable(c, 20)); menu.setClipToOutline(true);
        // Not focusable: the finger that opened it is still down and may go on to move the folder.
        final PopupWindow window = new PopupWindow(menu, FrostedSurface.dp(c, 164), -2, false);
        window.setBackgroundDrawable(new ColorDrawable(0)); window.setOutsideTouchable(true);
        window.setElevation(FrostedSurface.dp(c, 8)); window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        fill(card, info, false);

        Rect frame = new Rect(); icon.getWindowVisibleDisplayFrame(frame);
        Rect visible = new Rect();
        if (!icon.getGlobalVisibleRect(visible)) return;
        int margin = FrostedSurface.dp(c, 12), gap = FrostedSurface.dp(c, 6);
        int width = Math.min(frame.width() - 2 * margin, FrostedSurface.dp(c, 164 * Math.max(1f, c.getResources().getConfiguration().fontScale)));
        int height = FrostedSurface.dp(c, 12 + 48 * 5);   // room for the opened Sort group, so it never has to scroll
        window.setWidth(width);
        int left = Math.max(frame.left + margin, Math.min(visible.centerX() - width / 2, frame.right - width - margin));
        int top = visible.bottom + gap;                                   // directly under the folder icon
        if (top + height > frame.bottom - margin && visible.top - gap - height >= frame.top + margin) top = visible.top - gap - height;
        window.setHeight(-2);
        menu.setVerticalScrollBarEnabled(false); menu.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final View.OnAttachStateChangeListener lifecycle = new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { if (open == window) dismiss(); }
        };
        icon.addOnAttachStateChangeListener(lifecycle);
        window.setOnDismissListener(new PopupWindow.OnDismissListener() {
            public void onDismiss() { icon.removeOnAttachStateChangeListener(lifecycle); if (open == window) open = null; }
        });
        window.showAtLocation(icon.getRootView(), Gravity.TOP | Gravity.LEFT, left, top);
        FrostedSurface.popupWindow(menu, 20);
        menu.setAlpha(0); menu.animate().alpha(1).setDuration(150).start();
        open = window;
    }

    /** Rename, Sort (opens Auto / Custom in place) and Remove. */
    private static void fill(final LinearLayout card, final Object info, final boolean sortOpen) {
        card.removeAllViews();
        final LauncherData.Folder f = DrawerFolders.record(info);
        FolderOptions.row(card, "Rename", 1, false, new Runnable() { public void run() { dismiss(); rename(info); } });
        FolderOptions.row(card, "Sort", 9, false, new Runnable() { public void run() { fill(card, info, !sortOpen); PopupWindow w = open; if (w != null) try { w.update(w.getWidth(), -2); } catch (Throwable ignored) { } } });
        if (sortOpen && f != null) {
            final int id = f.id;
            FolderOptions.row(card, "   Auto (A\u2013Z)", 9, f.sort == FolderSort.AUTO, new Runnable() { public void run() {
                dismiss(); LauncherData.Folder now = DrawerFolders.record(info);
                if (now != null && now.sort != FolderSort.AUTO) sort(info);
            }});
            FolderOptions.row(card, "   Custom", 9, f.sort != FolderSort.AUTO, new Runnable() { public void run() {
                dismiss(); LauncherData.Folder now = DrawerFolders.record(info);
                if (now != null && now.sort == FolderSort.AUTO) FolderSort.drawerCustom(id);
            }});
        }
        FolderOptions.row(card, "Remove", 8, false, new Runnable() { public void run() { dismiss(); remove(info); } });
    }

    static void dismiss() {
        PopupWindow window = open; open = null;
        if (window != null) try { window.dismiss(); } catch (Throwable ignored) { }
    }

    static void rename(final Object info) {
        final LauncherData.Folder f = DrawerFolders.record(info); final Activity a = LauncherFeatures.activity.get();
        if (f == null || a == null) return;
        final EditText edit = new EditText(a); edit.setSingleLine(true); edit.setText(f.name); edit.selectAll();
        edit.setPadding(FrostedSurface.dp(a, 24), FrostedSurface.dp(a, 16), FrostedSurface.dp(a, 24), FrostedSurface.dp(a, 16));
        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle("Rename folder").setView(edit)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        FrostedSurface.show(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View button) {
                String name = edit.getText().toString().trim();
                if (name.length() == 0 || name.length() > 80) { edit.setError("Use a name between 1 and 80 characters"); return; }
                if (DrawerFolders.renameTo(f.id, name)) dialog.dismiss();
                else Toast.makeText(a, "Could not save the name", Toast.LENGTH_SHORT).show();
            }
        });
    }

    static void sort(final Object info) {
        final LauncherData.Folder f = DrawerFolders.record(info); if (f == null) return;
        confirm("Sort apps?", "Sort the apps in “" + f.name + "” from A to Z?", new Runnable() {
            public void run() { DrawerFolders.sortFolder(f.id); }
        });
    }

    static void remove(final Object info) {
        final LauncherData.Folder f = DrawerFolders.record(info); if (f == null) return;
        confirm("Remove folder?", "Remove “" + f.name + "”? Its apps go back to the app drawer. No apps are uninstalled.", new Runnable() {
            public void run() { DrawerFolders.removeFolder(info); }
        });
    }

    /** Yes/No prompt with the module frost. Only Yes runs the action. */
    static void confirm(String title, String message, final Runnable yes) {
        Activity a = LauncherFeatures.activity.get(); if (a == null) return;
        FrostedSurface.show(new AlertDialog.Builder(a).setTitle(title).setMessage(message)
                .setNegativeButton("No", null)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int which) { yes.run(); } })
                .create());
    }
}
