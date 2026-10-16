# Pixel Suite v2.0.1

This update brings together the recent folder and drag improvements, a cleaner recording sheet, and fixes across the launcher. Thanks to everyone who helped test the builds along the way.

## ✌️ Move several apps together

- **Build a stack:** Hold and drag an app, then tap others with a second finger. A counter shows your selection; drop the group onto the home screen or into a folder.
- **Navigate while dragging:** Swipe with a second finger to change home or folder pages, or scroll the drawer. Touch again to stop the drawer’s momentum.
- **Remove shortcuts together:** Drop several home-screen shortcuts on Remove without uninstalling their apps.
- Selections inside folders stay within the source folder, and the drawer behind an open folder stays still.

## 🗂️ Better folder controls

- **Live reordering:** In Custom mode, drawer-folder apps make room under your finger and selected apps land together, including across pages.
- **Sort your way:** Choose Auto (A–Z) or Custom from the three-dot menu. Switching to alphabetical sorting asks for confirmation. Home folders also have Rename and Sort in their long-press menu.
- **Take apps out:** A Remove target beside Add to home screen returns selected apps from a drawer folder to the drawer without uninstalling them.
- Folder frames fit their contents, with smoother page changes, exits and removal transitions. Removing a folder returns its apps to the alphabetical drawer.
- Choose 3×3, 4×4 or 5×5 inside each folder; redundant default-grid settings have been removed. Home and drawer folder choices remain separate.

## 🎥 A cleaner screen-recording prompt

The optional **Screen recording sheet** control places X and Start beneath the recording options and removes the long warning row and unused space. Recording still starts only when you press Start. Turn it off to restore the stock prompt; supported System UI versions are required.

## ✨ Launcher fixes and polish

- **Reset icon** reliably restores the original icon, including after changing icon shapes.
- **Home-screen long press** keeps the menu near your finger instead of jumping far down, while preserving its stock look and animation.
- **Taller shortcut menus** fit their actions inside the panel, including Rename and Bubble, while respecting the Add to home screen target.
- Custom icons follow the shape selected in Wallpaper & style. Live name and icon updates continue to work across home, drawer and both searches.
- Fixed invisible apps after dragging, duplicate drag images, stuck or flashing folders, and a crash when moving several apps out of a home folder.
- Reduced unnecessary redraws and removed temporary diagnostics.

## 🔔 Updates inside Pixel Suite

- An **Updates** page adds automatic checks, Check now, notification controls and **Download and install**.
- An update appears only after a newer stable release is published on **both GitHub and Xposed**, with matching APK name, size and SHA-256 hash. Drafts, previews and local builds do not trigger notices.
- Downloads stay inside the app and show percentage progress. Pixel Suite verifies the APK and release signature, then opens Android’s installation confirmation—no browser or manual file picker.
- Checks are periodic, scheduled by Android, rather than instant push notifications. Notification permission is needed for alerts; Android may also ask you to allow installs from Pixel Suite. Installation still requires your confirmation.

## 🎨 Settings and previews

- Clearer feature descriptions and matching animated demonstrations for the new recording, sorting and drag controls.
- Previews pause between loops and stop when hidden or when you leave the app. System animation preferences and battery-saving behavior remain respected.
- Popups match the settings page colors in light and dark modes. The header has equal-sized Telegram, Patreon and Coffee actions, with **Your Pixel. Your Way.** beneath the title.
- The in-app changelog shows only the latest update under **What’s new:**, with a link to the full release history on GitHub.

## 📥 Install

Install **PixelSuite-v2.0.1.apk** over the current official build, keep the recommended LSPosed scope selected, then reboot. No uninstall is needed.

**Tested on a Pixel 11 Pro. It should also work on other Pixel devices. Feedback is welcome.** Testing has used Android 17, LSPosed API 102 and Pixel Launcher 17. Individual features depend on the installed system and app versions.

[GitHub download](https://github.com/RovianDev/PixelSuite/releases/tag/v2.0.1) · [Xposed download](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite/releases/tag/92-2.0.1) · [Telegram](https://t.me/RovianDev)

Made by **RovianDev**