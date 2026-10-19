## v2.0.3

## What's new in 2.0.3:

🏠 **Home-screen menu:** removed the redundant “Apps list” option—you can already open the app drawer by swiping up.

🌗 **App launch:** fixed the white flash when opening Pixel Suite in dark theme.

✏️ **Doodles redesign:** a wider, shorter menu with aligned shapes, colors and controls. Tap More shapes or the rainbow icon to expand for more shapes and colors. Tap the arrow on the right to fold it back. Drag or flick the menu up or down to reposition it.

📁 **Drawer folders:** the Back gesture closes the folder first; the next Back gesture leaves the drawer. Fixed accidental taps during transitions and icon/name jumps after reordering.

🗂️ **Home folders:** smoother closing after moving apps, with stock spring animations preserved. Auto (A–Z) and Custom sorting now open inside the same menu.

✂️ **Pixel Studio:** fixed, and should be much harder to break after future updates.

🧹 **Recents:** fixed a rare issue where Clear all became disabled after restarting.

🌑 **Super Dark:** fixed a few places where the dark theme wasn’t applying and made theme handling more efficient.

✨ **Cleaner interface:** compact, consistent prompts and shorter descriptions throughout Pixel Suite. Animated previews and essential setup instructions remain.

Install over the current official version and reboot.

---

## v2.0.2

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

---

## v2.0.1

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

---

## v2.0.0

### 🏠 Open Pixel Suite from your home screen

Touch and hold an empty area of the home screen, then tap **Pixel Suite settings**. Your controls are one long press away.

After **45 preview updates**, v2.0.0 brings the launcher work, screenshot tools and redesigned settings together. Thanks to everyone who tested, reported problems and helped refine the small details.

### 🎨 A redesigned settings app

- **Everything in one place:** Buttons, Gestures, Pixel Launcher and Files have their own sections. Each feature has a separate control, icon and explanation.
- **Light and dark themes:** Cleaner spacing and consistent menus follow your system theme.
- **Animated demonstrations:** See how a feature works before using it. Animations pause between loops and stop when you leave.
- **In-app changelog:** Read release notes without opening a browser.

### 📱 Launcher and app drawer

- **📁 Drawer folders:** Create folders inside the drawer, rename them, add apps, sort their contents or remove them. Released apps return to an alphabetically sorted drawer.
- **🔲 Separate folder grids:** Pick **3×3, 4×4 or 5×5** independently for home-screen and drawer folders, with refined icon spacing and transitions.
- **↗️ Drag to home:** Move apps within the drawer, then drag to **Add to home screen** when you want to place one on the home screen.
- **✏️ Rename apps instantly:** Names update across home, folders, drawer and both app searches without a manual refresh. Restore the original name at any time.
- **🖼️ Change icons:** Choose an image through **Photos** or **Files**. It follows the launcher's icon shape and updates across the same places. A reset control restores the original.
- **🔤 Icon label size:** Five optional text sizes let you fit more of an app name without resizing its icon. Disabled by default.
- **🧩 Hide app shortcuts:** Hide app-provided drawer actions while keeping basic launcher and Pixel Suite options.
- **🧹 Clear all in recents:** Moves from the stock far-left location to an always-visible position alongside **Select** and **Screenshot**.
- **✨ Folder and menu polish:** Refined opening/closing transitions, dropdown placement and local frosted backgrounds. Blur stays inside the panel frame.

### 📸 New screenshot editing tools

- **✂️ Free crop:** Make tighter screenshot crops in supported Pixel Studio versions, with easier-to-grab edges for narrow selections.
- **🎨 Doodles:** Add arrows, lines, rectangles, circles, triangles, stars and speech bubbles. Adjust color, thickness and fill, then move or resize your shapes.
- **↩️ Undo and Redo:** Use the editor's controls for Doodles on supported versions. Shapes are included when you save or share the screenshot.

### 👆 Buttons and gestures

Your existing shortcuts remain available with individual controls in Pixel Suite:

- **🔦 Power-button flashlight:** Double-press power to toggle the torch.
- **📷 Volume-down camera:** Double-press volume down on the always-on display to open the camera, with media and call safeguards.
- **🖐️ Three-finger screenshots:** Swipe down with three fingers to capture the screen.
- **🌙 Double-tap to sleep:** Lock the phone from empty home-screen space, with configurable lock-screen behavior too.
- **☀️ Tap to wake:** Choose single- or double-tap wake and adjust lock-screen interactions in one place.

### 🗂️ Files, newest first

**Newest-first sorting** now covers Files by Google and supported Android file pickers. Folders stay above files, and files sort from newest to oldest. Turn the option off to restore the apps' normal sorting.

### 📥 Update and setup

Install **PixelSuite-v2.0.0.apk** over the previous version and reboot. No uninstall is needed when updating with the same release signature.

Check **LSPosed → Pixel Suite → Scope**. Include **Google** for home-screen search changes and **Pixel Studio** for screenshot tools. All controls now live in the Pixel Suite app.

**Tested on a Pixel 11 Pro. It should also work on other Pixel devices. Feedback is welcome.** Testing has used Android 17 and LSPosed API 102; other Pixel models have not yet been confirmed. Pixel Studio tools target **2.3.001 (192285)**. Compatibility depends on your system and app versions. Wake reliability and battery use depend on device state and movement; no measured overnight percentage is available.

[Source and issues](https://github.com/RovianDev/PixelSuite) · [Xposed listing](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite) · [Telegram](https://t.me/RovianDev)

[Patreon](https://www.patreon.com/c/RovianDev) · [Buy Me a Coffee](https://buymeacoffee.com/roviandev)

APK SHA-256: `467c6d31c4091639c50c06fb7ae0d171379b75e37fac45fc05e4273902881f5c`

## v1.1.3 · Published release
Pixel Suite v1.1.3

Improved double-tap wake that adapts to how the phone is resting, with better handling of soft taps, pickup and missed attempts.

What changed

- Better wake detection in your hand or on your lap. The held/still check also recognizes a phone resting on your thigh. While held, the touchscreen stays touch-active on the always-on display so both taps can be real touches.
- Bounded held-touch periods. Held mode runs for up to 90 seconds per stretch, with a regular four-minute budget per lock. After a missed tap, a short recovery can re-arm touch while the phone is held.
- Low-power behavior when resting still. On a desk or nightstand, the always-on display returns to low-power mode and motion readings help detect the second tap. An extra tap within three seconds is a backup if a quick double tap misses.
- Softer second taps are detected more readily on a still phone.
- Pickup and charging fixes. Better handling of taps just after pickup and small shifts while charging.
- Lockscreen interaction fix. Delayed touch events are handled to prevent a double tap from opening lockscreen customization unexpectedly.

Why this approach is better

Touch detection is used where motion-based detection is less dependable, such as a held phone or one resting on a lap. When the phone is still, the display can return to low-power operation instead of keeping the touchscreen touch-active throughout standby.

Movement, very soft taps and a vibrating car mount can still affect detection. The held-mode budget also limits how long touch stays active; this is not a guarantee that every double tap will register in every position.

Testing and battery

Tested on my Pixel 11 Pro with Android 17 and LSPosed API 102. It is working well in my testing so far, and I have not noticed additional battery drain. No measured overnight percentage is available; this is not a claim of zero power use. Other devices remain untested.

Install / update

Install PixelSuite-v1.1.3.apk over the previous public release, then reboot. No uninstall is needed when updating with the same signing key.

Source: RovianDev/PixelSuite (https://github.com/RovianDev/PixelSuite) Â· Xposed listing (https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite)

Buy me a coffee (https://buymeacoffee.com/roviandev) Â· Patreon (https://www.patreon.com/c/RovianDev)


APK SHA-256: 602379d748b67c8c5834108176eb2532e1c411bb1e85474718c0f1f6e4c56709

## v1.1.2 · Published release
Pixel Suite v1.1.2

What's new

- Tap or Double tap to wake is now one setting. Double tap the lock screen to sleep is part of it, and with *Double tap to wake* one double tap now works on the always-on display too (the separate "One double tap on Always On Display" switch is gone). Keeping the touchscreen awake there uses a little extra battery while the always-on display is showing (none while charging), and it pauses itself in a pocket, with Battery Saver on, or when the battery is low.
- New lock screen choice in Settings → System → Gestures → Tap or Double Tap to check phone, replacing the old "Lock screen shortcuts" switch:
  - Modded behavior (recommended): the alarm, weather and date under the clock act like empty space and lock screen taps don't vibrate, so a double tap to sleep never opens anything by mistake.
  - Stock behavior: the lock screen stays as Google made it, with tappable cards and tap vibrations. A double tap that lands on a card can open it instead of turning the screen off.
- Fixed: unlocking with your fingerprint right after locking could pull the notification shade down.
- Fixed: a double tap to wake right after locking could be missed while the lock screen was still coming up.
- Lighter on battery: the lock screen touch listener now only runs while the lock screen can show (before, it saw every touch all day), the three-finger screenshot listener only runs while that feature is on, the flashlight shares Pixel Suite's background thread instead of keeping its own, and internal lookups and Files sorting are cached.
- Fixed: in the module's settings screen, a switch flipped while the screen was reloading could jump back.

Install / update

Install the APK over the previous version (no uninstall needed) and reboot. Tested on a Pixel 11 Pro running Android 17 with LSPosed (API 102).

## v1.1.1 · Published release
Pixel Suite v1.1.1

Tap or Double tap to wake is now one setting. Everything lives in Settings › System › Gestures › Tap or Double Tap to check phone.

What's new
- Master toggle "Tap or Double tap to wake" with all options on one page: tap to wake, double tap to wake, double tap on the lock screen to sleep, and lock screen shortcuts.
- Lock screen shortcuts toggle (off by default):
  - Off: tapping the clock, alarm, weather or other lock screen cards no longer launches apps, and tapping them no longer vibrates.
  - On: stock behavior, including the stock vibration.
  - The clock still expands as normal.
- "Tap or Double Tap to check phone" now sits above "Double tap to sleep" in the Gestures list.

Fixes
- Alarm and weather cards no longer launch apps after a double tap and unlock.
- Double tap no longer gets disarmed by cancelled swipes or by long-pressing into Customize lock screen.
- Stray vibrations on the lock screen after waking, swiping back or tapping repeatedly are gone.
- Unlock haptics are unaffected.

Install
LSPosed → Modules → enable Pixel Suite → scope System Framework → reboot.
Android 17 (Pixel 11 Pro tested).

SHA-256: 34adb27a4c2029d9e742463a524d62a98ad4463eafc3fa693564417ca1112b8e

## v1.1.0 · Published release
What's new
- New: "Tap or Double Tap to check phone". The Settings > System > Gestures page now offers two options, Tap to wake and Double tap to wake. Turning one on turns the other off.
- New: "Double tap lock screen to sleep". Double tap anywhere on the lock screen to turn the screen off. It never triggers while the PIN pad is open.
- Both options can also be toggled in the Pixel Suite app.

Install
Install over v1.0.0 (no uninstall needed), then reboot.

Notes
- The Settings page rename only matches the English text.
- This feature relies on private Android framework names, so a future Android update may break it.

## v1.0.0 · Published release
Features

Pixel Suite v1.0.0: first public release.

- Always-visible Clear all in recents — The "Clear all" button stays visible in Pixel Launcher's recents screen, so there's no need to scroll to the last page to find it.

- Double-press power for flashlight — Tap the power button twice to toggle the flashlight instantly, from anywhere on your device.

- Double-press Volume Down on AOD for camera — Open Pixel Camera instantly from the always-on display with a quick double-press of the volume down button. Won't launch if media is playing or during an active call.

- Three-finger swipe down for screenshot — Swipe down with three fingers to take a screenshot, inspired by Chinese phones like Xiaomi, OPPO and Vivo.

- Double-tap home screen to lock the screen — Double-tap an empty spot on the home screen to lock your phone instantly. Also inspired by Chinese phones.

- Files by Google: folders sorted newest-first — By default, Files by Google sorts folders alphabetically. This feature sorts them by newest first, making recently downloaded files easy to find when you need them.

- Settings screen in LSPosed to toggle each feature — Open the module's settings screen in LSPosed to enable or disable any feature you want. Full control in your hands.

Tested on Pixel 11 Pro only. Feedback and ideas welcome in Issues.

