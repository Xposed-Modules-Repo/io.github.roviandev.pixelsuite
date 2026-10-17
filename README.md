[![Pixel Suite — Your Pixel. Your Way.](assets/pixelsuite-banner-v2.png)](https://github.com/RovianDev/PixelSuite/releases/tag/v2.0.2)

# Pixel Suite 2.0.2

Your Pixel. Your Way.

**Open Pixel Suite straight from your home screen:** touch and hold an empty area, then tap **Pixel Suite settings**—no need to find the app first.

Made by **RovianDev**. Pixel Suite adds launcher customization, gestures, screenshot tools and file-sorting controls through LSPosed.

**New in v2.0.2:** Super Dark-Mode follows the system theme with black backgrounds and matching solid menus. Drawer folders slide aside as you reorder them, folder menus fit better, drag targets appear cleanly, and a Restart shortcut lets you choose when to reload Pixel Launcher or Files.

[See everything new in v2.0.2](RELEASE-NOTES-v2.0.2.md) · [Full changelog](CHANGELOG.md)

[Download](https://github.com/RovianDev/PixelSuite/releases/tag/v2.0.2) · [Release notes](https://github.com/RovianDev/PixelSuite/blob/main/RELEASE-NOTES-v2.0.2.md) · [Xposed listing](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite) · [Telegram](https://t.me/RovianDev)

## 📱 Launcher and app drawer

- 📁 **Drawer folders:** Create folders inside the drawer. Drag folders among their rows to reorder them as nearby folders slide aside. Rename folders, add apps, choose Auto (A–Z) or Custom ordering, or remove them to return their apps to the alphabetical drawer. Sorting and removal ask for confirmation.
- 🔲 **Folder grids:** Choose **3×3, 4×4 or 5×5** folder grids. Choose the grid inside each folder; home-screen and drawer choices are separate. Folder frames fit their contents. Menus use local frosted backgrounds, confined to their frames.
- ✌️ **Move several apps:** Hold and drag an app, then tap others with a second finger to build a counted stack. Move the group to a folder or home screen, or remove home shortcuts together without uninstalling apps.
- 🖐️ **Two-finger navigation:** While holding an app, swipe with another finger to change home/folder pages or scroll the drawer. Custom drawer-folder ordering makes room under your finger as you move apps.
- ↗️ **Drag to home:** Move apps within the drawer. Drag to the **Add to home screen** target to continue onto the home screen.
- ✏️ **Rename apps** and 🖼️ **change icons** using images from Photos or Files. Changes update across home, folders, drawer, drawer search and Google app search without a manual refresh. Restore the original name or icon when needed. Google must be in the module scope for its search integration.
- 🔤 Optional **five-level icon label sizing** changes text beneath icons without resizing icons. Smaller text fits more letters; long labels can still truncate. System fonts and Bold text remain respected. This option starts disabled.
- 🧩 **Hide app shortcuts** removes app-provided drawer actions while keeping basic launcher/module actions. Home-screen shortcuts are unchanged.
- 🧹 **Clear all** moves from the stock far-left location to an always-visible position alongside **Select** and **Screenshot** in recents.

## 🌑 Themes: Super Dark-Mode

An optional true-black take on the system dark theme, found under **Themes** in Pixel Suite. Supported Settings, System UI, launcher, Google and Gboard surfaces follow it; apps with independent palettes may look different.

- **Automatic light/dark changes:** follows the quick settings theme tile and dark-theme schedules, restoring stock light colors when the phone switches to light mode.
- **Matching folder and menu surfaces:** near-black folder frames, three-dot menus and app shortcut menus share the same tone. When blur is off, solid backgrounds keep the text and icons behind them hidden.
- **Lock-screen colors:** supported PIN/pattern backgrounds update through targeted hooks, without restarting System UI. The saved setting is available during startup after reboot.
- **Blur restoration:** disables window-level blur while active in dark mode, then restores it only if Pixel Suite disabled it. A blur setting already disabled elsewhere stays disabled.
- **Disabled by default.** Requires the System UI, Pixel Launcher, Google, Gboard and Settings scopes. Compatibility depends on installed system/app versions; battery savings have not been quantified.

## 👆 Buttons and gestures

- 🔦 **Flashlight:** Double-press power.
- 📷 **Camera:** Double-press volume down on the always-on display, with media/call safeguards.
- 🖐️ **Screenshot:** Swipe down with three fingers.
- 🌙 **Sleep:** Double-tap empty home-screen space.
- ☀️ **Wake and lock-screen controls:** Choose tap or double-tap wake, double-tap sleep, and stock/modded interactions.

Wake behavior adapts to whether the phone is held or resting still. Movement, soft taps and device state can affect detection and power use. There is no measured overnight battery percentage for this release.

## 📸 Screenshots and files

✂️ **Free crop:** Tighter screenshot crops in supported Pixel Studio versions.

🎨 **Doodles:** Add shapes, choose colors, thickness and fill, move or resize your marks, and use supported Undo/Redo controls. Shapes are included in saved/shared edits. Doodles history is handled before native edits; the eraser does not erase shapes. These hooks target **Pixel Studio 2.3.001 (192285)**; another version may differ.

**Newest first:** keeps folders above files and sorts files newest-to-oldest in Files by Google and supported Android file pickers. This overrides their normal sort choice while enabled; turn it off to restore native sorting.

🎥 **Screen recording sheet:** An optional cleaner prompt places X and Start below the options and removes the long warning row. Recording still requires Start. Disable it to restore the stock prompt; supported System UI versions are required.

## 🔄 Restart shortcut

After relevant Pixel Launcher or Files settings change, a small **Restart** shortcut lets you reload the affected app when convenient. Tap to restart, or hold to dismiss.

## 🎨 Settings that stay together

All controls now live in **Pixel Suite**, with separate feature pages, explanations, themed icons and an offline changelog showing the latest update and a link to the full release history. Light and dark modes follow the system. Animated demonstrations pause between loops and stop when the page or app is inactive; system animation settings, Battery Saver and accessibility preferences are respected.

Long-press an empty area of the home screen to return through **Pixel Suite settings**. Android Settings > System > Gestures keeps its stock menus.

## 🔔 In-app updates

Pixel Suite checks for newer stable releases published on **both GitHub and Xposed** with a matching APK. Optional notifications let you know when one is available. Open **Updates → Download and install** for an in-app download with percentage progress, APK verification and Android’s installation prompt.

Checks run periodically when Android allows them; they are not instant push notifications. You can also use **Check now**. Android requires notification permission for alerts and may require allowing installs from Pixel Suite. You confirm installation yourself. No GitHub account or root installer is needed for this flow.

## 📥 Requirements and setup

**Tested on a Pixel 11 Pro. It should also work on other Pixel devices. Feedback is welcome.** Testing has used Android 17 with LSPosed API 102; other Pixel models have not yet been confirmed. Compatibility still depends on the installed system and app versions. The APK's minimum Android API is 26; this does not imply feature compatibility with all Android versions.

1. Use a rooted device with LSPosed API 102.
2. Install **PixelSuite-v2.0.2.apk**, enable Pixel Suite in LSPosed and allow the module app root access to save settings.
3. Select the recommended scope: **System Framework**, **System UI**, **Pixel Launcher**, **Files by Google**, the installed **Files / DocumentsUI** picker, **Google**, **Gboard**, **Settings**, and **Pixel Studio** for screenshot tools.
4. Reboot, then open Pixel Suite and choose your features.

Google package: `com.google.android.googlequicksearchbox`. Pixel Studio: `com.google.android.apps.pixel.creativeassistant`. The file picker may be `com.google.android.documentsui` or `com.android.documentsui`; select the one installed on your device. For Super Dark-Mode, keep Settings and Gboard selected alongside System UI, Pixel Launcher and Google. Gboard package: `com.google.android.inputmethod.latin`; Settings: `com.android.settings`.

For an update, install over the existing version and reboot; no uninstall is needed with the same release signature. A few controls restart their affected app when toggled; save current screenshot edits before changing Pixel Studio options.

## Source and feedback

[Source / bug reports](https://github.com/RovianDev/PixelSuite) · [Xposed repository](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite) · [Community and support](https://t.me/RovianDev)

When reporting a problem, include your device, Android version, module/app versions and reproduction steps. Check logs for personal information before sharing them.

The source builds an unsigned APK. Release signing keys and local signing configuration are not distributed. See BUILD.md in the source package for build requirements and THIRD-PARTY-NOTICES.md for artwork credits.

## 💬 Community and support
[![Join RovianDev on Telegram](assets/telegram-card.png)](https://t.me/RovianDev)

[![Support RovianDev on Patreon](assets/patreon-card.png)](https://www.patreon.com/c/RovianDev)

[![Buy RovianDev a Coffee](assets/coffee-card.png)](https://buymeacoffee.com/roviandev)

[Telegram](https://t.me/RovianDev) · [Patreon](https://www.patreon.com/c/RovianDev) · [Buy Me a Coffee](https://buymeacoffee.com/roviandev)
## License and credits

GPL-3.0. Thanks to LSPosed and the libxposed API. Artwork credits are included in THIRD-PARTY-NOTICES.md. Pixel Suite is an independent project and is not affiliated with Google.
