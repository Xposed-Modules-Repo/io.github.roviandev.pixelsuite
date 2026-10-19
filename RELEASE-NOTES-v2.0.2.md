# Pixel Suite v2.0.2

This update brings Super Dark-Mode, better drawer-folder movement, and the folder/menu fixes from the latest rounds of testing. Thanks to everyone who helped test it.

## 🌑 Super Dark-Mode

- **New Themes section:** Find Super Dark-Mode under Themes in Pixel Suite. It starts disabled.
- **Black backgrounds:** Supported Settings, notification shade, quick settings, lock-screen PIN/pattern backgrounds, launcher, Google search and Gboard surfaces use black or near-black colors. Cards remain distinct from the background; apps with their own palettes can look different.
- **Follows your theme:** Switching to light mode restores the normal light theme. Switching back to dark reapplies Super Dark, including with a dark-theme schedule.
- **Solid folder menus:** Folder panels, their three-dot menus and app shortcut menus share the same near-black tone under Super Dark. With blur disabled, menu backgrounds are opaque so text and icons behind them do not show through. App shortcut menus also follow Super Dark when other launcher customizations are off.
- **Applies after reboot:** The saved setting is available early in System UI startup. Targeted lock-screen color hooks apply without restarting System UI or sending you back to the lock screen.
- Window-level blur is disabled while Super Dark is active in dark mode. It is restored in light mode or when the option is disabled, provided Pixel Suite was the feature that disabled it. This reduces blur work; no measured battery-saving percentage is claimed.

## 🗂️ Better drawer-folder movement

- Drag a folder among the folder rows and nearby folders slide aside to show its new position.
- Moving down among regular apps restores the previous positions; folders remain together in their own rows.
- Fixed folders jumping or shaking after a drop, duplicate refreshes and stale folder positions.
- Carrying a drawer folder onto the home screen shows **Cancel** instead of **Remove** until it is placed.

## ✨ Folder menus and drag polish

- **Sort** in a held folder menu folds open **Auto (A–Z)** and **Custom**, with matching icons and animation. Auto asks for confirmation; Custom keeps your own ordering.
- Menus fit their longest labels and give text and check marks room, including with larger system fonts.
- Opening the three-dot menu no longer lifts the folder and causes a stutter when it closes.
- **Remove**, **Cancel** and **Uninstall** fade in after a drag begins so they do not appear cut off.

## 🔄 Restart when you’re ready

A small **Restart** shortcut appears after relevant Pixel Launcher or Files settings change. Tap it to restart the affected app, or hold it to dismiss.

Feature demonstrations have also been refined: clearer two-finger gestures, selecting several apps and moving them home, separate folder creation/sorting examples, and grid changes that start after the selection tap. Previews pause between loops and stop when hidden or when you leave the app.

## 📥 Update and setup

Install **PixelSuite-v2.0.2.apk** over your current official build, keep the recommended LSPosed scopes, and reboot. No uninstall is needed.

For Super Dark-Mode, select **System UI**, **Pixel Launcher**, **Google**, **Gboard** and **Settings** in Pixel Suite’s LSPosed scope. Keep the existing scopes for the other features you use. Open Pixel Suite after updating to save the startup setting if you already had Super Dark enabled in a preview.

**Tested on a Pixel 11 Pro. It should also work on other Pixel devices.** Individual features depend on the installed Android and app versions.

[GitHub download](https://github.com/RovianDev/PixelSuite/releases/tag/v2.0.2) · [Xposed download](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite/releases/tag/117-2.0.2) · [Full changelog](https://github.com/RovianDev/PixelSuite/blob/main/CHANGELOG.md) · [Telegram](https://t.me/RovianDev)

Made by **RovianDev**