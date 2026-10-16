# Pixel Suite 2.0.0

your pixel your way

**Open Pixel Suite straight from your home screen:** touch and hold an empty area, then tap **Pixel Suite settings**—no need to find the app first.

Made by **RovianDev**. Pixel Suite adds launcher customization, gestures, screenshot tools and file-sorting controls through LSPosed.

After **45 preview updates**, v2.0.0 brings the work together into one release: a redesigned settings app, more capable folders and drawer interactions, immediate app customization, and refined menus and transitions.

[Download](https://github.com/RovianDev/PixelSuite/releases/tag/v2.0.0) · [Release notes](https://github.com/RovianDev/PixelSuite/blob/main/RELEASE-NOTES-v2.0.0.md) · [Xposed listing](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite) · [Telegram](https://t.me/RovianDev)

## Launcher and app drawer

- Create folders inside the drawer. Rename folders, add apps, sort their contents A–Z, or remove them to return their apps to the alphabetical drawer (English, Arabic, then numbers and special characters). Sorting and removal ask for confirmation.
- Choose **3×3, 4×4 or 5×5** folder grids. Home-screen and drawer choices are separate. Menus use local frosted backgrounds, confined to their frames.
- Move apps within the drawer. Drag to the **Add to home screen** target to continue onto the home screen.
- **Rename apps** and **change icons** using images from Photos or Files. Changes update across home, folders, drawer, drawer search and Google app search without a manual refresh. Restore the original name or icon when needed. Google must be in the module scope for its search integration.
- Optional **five-level icon label sizing** changes text beneath icons without resizing icons. Smaller text fits more letters; long labels can still truncate. System fonts and Bold text remain respected. This option starts disabled.
- **Hide app shortcuts** removes app-provided drawer actions while keeping basic launcher/module actions. Home-screen shortcuts are unchanged.
- **Clear all** moves from the stock far-left location to an always-visible position alongside **Select** and **Screenshot** in recents.

## Buttons and gestures

- Double-press power for the flashlight.
- Double-press volume down on the always-on display for the camera, with media/call safeguards.
- Three-finger swipe down for a screenshot.
- Double-tap empty home-screen space to sleep.
- Configurable tap or double-tap wake, lock-screen double-tap sleep, and stock/modded lock-screen interactions.

Wake behavior adapts to whether the phone is held or resting still. Movement, soft taps and device state can affect detection and power use. There is no measured overnight battery percentage for this release.

## Screenshots and files

**Pixel Studio:** free screenshot cropping and Doodles with shapes, colors, thickness, fill, moving/resizing and supported Undo/Redo integration. Shapes are included in saved/shared edits. Doodles history is handled before native edits; the eraser does not erase shapes. These hooks target **Pixel Studio 2.3.001 (192285)**; another version may differ.

**Newest first:** keeps folders above files and sorts files newest-to-oldest in Files by Google and supported Android file pickers. This overrides their normal sort choice while enabled; turn it off to restore native sorting.

## Settings that stay together

All controls now live in **Pixel Suite**, with separate feature pages, explanations, themed icons and an offline changelog. Light and dark modes follow the system. Animated demonstrations pause between loops and stop when the page or app is inactive; system animation settings, Battery Saver and accessibility preferences are respected.

Long-press an empty area of the home screen to return through **Pixel Suite settings**. Android Settings > System > Gestures keeps its stock menus.

## Requirements and setup

Testing has focused on **Pixel 11 Pro running Android 17 with LSPosed API 102**. Other devices, system releases and app versions are unverified. The APK's minimum Android API is 26; this does not imply feature compatibility with all Android versions.

1. Use a rooted device with LSPosed API 102.
2. Install **PixelSuite-v2.0.0.apk**, enable Pixel Suite in LSPosed and allow the module app root access to save settings.
3. Select the recommended scope: **System Framework**, **System UI**, **Pixel Launcher**, **Files by Google**, the installed **Files / DocumentsUI** picker, **Google**, and **Pixel Studio** for screenshot tools.
4. Reboot, then open Pixel Suite and choose your features.

Google package: `com.google.android.googlequicksearchbox`. Pixel Studio: `com.google.android.apps.pixel.creativeassistant`. The file picker may be `com.google.android.documentsui` or `com.android.documentsui`; select the one installed on your device. Settings is no longer a required scope.

For an update, install over the existing version and reboot; no uninstall is needed with the same release signature. A few controls restart their affected app when toggled; save current screenshot edits before changing Pixel Studio options.

## Source and feedback

[Source / bug reports](https://github.com/RovianDev/PixelSuite) · [Xposed repository](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite) · [Community and support](https://t.me/RovianDev)

When reporting a problem, include your device, Android version, module/app versions and reproduction steps. Check logs for personal information before sharing them.

The source builds an unsigned APK. Release signing keys and local signing configuration are not distributed. See BUILD.md in the source package for build requirements and THIRD-PARTY-NOTICES.md for artwork credits.

## Community and support
[![Join RovianDev on Telegram](assets/telegram-card.png)](https://t.me/RovianDev)

[![Support RovianDev on Patreon](assets/patreon-card.png)](https://www.patreon.com/c/RovianDev)

[![Buy RovianDev a Coffee](assets/coffee-card.png)](https://buymeacoffee.com/roviandev)

[Telegram](https://t.me/RovianDev) · [Patreon](https://www.patreon.com/c/RovianDev) · [Buy Me a Coffee](https://buymeacoffee.com/roviandev)
## License and credits

GPL-3.0. Thanks to LSPosed and the libxposed API. Artwork credits are included in THIRD-PARTY-NOTICES.md. Pixel Suite is an independent project and is not affiliated with Google.
