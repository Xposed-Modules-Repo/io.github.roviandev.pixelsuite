## v2.0.8

A polish and bug-fix update for the screenshot editor, launcher folders and recents, including fixes for bugs in Pixel Studio and Pixel Launcher themselves.

## ✨ New
- **Crop blur (Pixel Studio):** cropping now blurs the part you're cutting away, live as you drag, instead of just dimming it. Drag back out and it's sharp again.
- **Outlined crop corners:** the four corner marks have an outline, so they're easy to see on light and dark screenshots, in your theme's colour.
- **Undo for drawer folders:** removing a drawer folder now shows "Item removed  Undo", like the home screen. Undo puts it back with its apps, name, grid, sort and lock.

## 🎨 Polish
- Pixel Studio, Frosted Glass (light theme): the tool bar, pen size bar, font bar, trash button and Doodles menu now share one frost, and open with their final look, with no shade shift or blink.
- Pixel Studio: Undo stays faded until there's something to undo. Redo is always shown clearly.
- Pixel Studio: tapping another tool (pen, highlighter, eraser, crop, text) closes Doodles at once.
- Pixel Studio: the Doodles menu fades only while your finger is near it.
- Google Photos: the photo editor's "Discard changes?" dialog now follows your style: frosted with Frosted Glass, light on a light phone, black with the soft rim under Super Dark.
- Landscape recents: "App is locked", "Two apps locked", "App remains locked" and "Cannot dismiss locked apps" read the right way up, centred in the blurred strip below the cards.

## 🐞 Bug fixes
- Recents: the status bar flickered while scrolling past apps last used in landscape (Android 17 turns their cards), and again after long use.
- Recents: memory leak. Pixel Launcher kept a picture of every split-screen card until it restarted.
- Home screen (Frosted Glass): an app dragged onto a folder went behind the folder once it opened, so it couldn't be dropped in.
- An app dragged onto Remove and then off it stayed see-through.
- A locked drawer folder dragged to the home screen came out unlocked.
- Pixel Studio: on a fresh screenshot, Undo looked available and could undo the previous screenshot's doodles.
- Pixel Studio: with Doodles open, dragging a crop corner sometimes drew an arrow instead of cropping.
- Pixel Studio: with a pen open, Doodles drew pen strokes instead of shapes.
- Pixel Studio: the Doodles menu flickered on every drag.
- Pixel Studio: after cropping, the Doodles menu couldn't be dragged back to its place above the tool bar.
- Pixel Studio: every crop change made the Doodles menu vanish and pop back.

## 🛠️ Google & Pixel bugs fixed
Bugs in Google's own apps that Pixel Suite now fixes:
- **Pixel Studio:** the top bar (Delete, Undo, Redo, Share, Save) blinked while you dragged the crop corners. It stays put now, and ✓ / X work as usual.
- **Pixel Studio:** the crop button changed a moment after the editor opened (its highlight and bolder icon came in late). It looks the same from the first frame now (Frosted Glass, light theme).
- **Pixel Launcher:** after split screen in landscape, Screenshot / Select / Clear all sat lower in portrait recents, then snapped into place. They're in their place from the first frame now.
- **Pixel Launcher:** Undo after removing a home folder didn't bring the folder back. Now it grows back into its place (with its page, if it was alone there), and a locked folder gets its lock back.


## Setup
Update and reboot. Keep the recommended LSPosed scope (with Pixel Studio and Google Photos).
Tested on Pixel 11 Pro, Android 17, Pixel Launcher 17.


## v2.0.7

🐛 **Fixes**

- Screenshot previews no longer go blank after the first screenshot with Reduce screenshot delay on.
- The first screenshot after a restart is instant again.
- Turning Reduce screenshot delay off no longer crashes the screenshot service.
- The status bar no longer flashes in recents when the phone is sideways with auto-rotate off.

## v2.0.6

🔒 **Privacy & Security**

- **Lock apps and folders** with your fingerprint or phone PIN. Open the app or folder’s long-press menu and choose **Lock**. Choose **Unlock** from the same menu to remove its lock.
- **Protect locked apps:** require authentication before uninstalling, disabling or pausing them, including uninstalling through the **Play Store**, or removing a locked folder. Cancel a folder’s removal, and it pops back into place.
- **Fingerprint prompt:** shows the locked app’s own icon and name, with a **Pin** button to switch to your PIN.
- **Private recents previews:** protected apps show a blurred preview and can’t be opened in split screen from recents.
- **Relock timing:** lock apps again as soon as you leave and folders when you close them, or enable **Keep unlocked for 1 minute**.
- **Animated padlocks** identify locked apps and folders on the home screen, in the drawer and in Google search. They animate when you lock or unlock.
- Deleting a locked folder or uninstalling a locked app removes its lock, so new folders and reinstalled apps start unlocked.
- A folder lock protects opening the folder. Its apps can still open from elsewhere unless you lock them individually.

**Setup:** select **System Framework, Pixel Launcher and System UI** in Pixel Suite’s LSPosed scope, then reboot. Your phone needs a screen lock configured.

🗂️ **Recent Apps**

- **Lock cards in recents:** choose **Lock** from a card’s menu to keep it when swiping or using **Clear all**. Tap its padlock badge to unlock it.
- **Separate split-screen locks:** lock either app independently. Clearing the pair removes unlocked apps while keeping locked ones.
- Recents locks survive a reboot. They protect the card from dismissal; Android can still close the app in the background.
- **Clear all:** locked cards fade back in, each removal gives a light tick, and the action row sits lower to make room for lock badges. Split pairs dim **Screenshot** and **Select** instead of hiding them.
- **Landscape view:** app names stay centered above their cards, and the status bar fades out. It returns when you rotate back or leave recents.
- **Always show Clear all** and **Lock apps in recents** now share the **Recent Apps** settings page.

🖐️ **Gestures**

- **Three-finger screenshot** responds faster and triggers more reliably.

⚙️ **Miscellaneous**

- **Reduce screenshot delay:** keeps the screenshot service ready after a real screenshot, making later captures appear instantly. The first screenshot after a restart runs at normal speed. Uses a little extra memory while enabled.
- **Allow screenshots of secure screens:** take screenshots and recordings in apps that normally block them.
- **Hide accessibility from apps:** choose which apps cannot see your enabled accessibility services. The searchable picker includes user/system filters, and checked apps move to the top immediately.
- **Keyboard bar tweak:** hide the globe and move the dismiss arrow to the right in the bar beneath your keyboard (**Gboard and FUTO Keyboard**). Requires gesture navigation and restarts your keyboard when changed.
- **Hide Developer Options:** regular apps can’t tell Developer options, USB debugging or wireless debugging are on, so apps that refuse to open with them on work normally. Settings and debugging keep working as usual.
- **Developer options shortcut:** tap the animated gear to open Developer options. The gear works independently of the hiding switch; tapping the rest of the row toggles the option.

**Setup:** select **System Framework** for the screenshot, accessibility and Developer options controls. Select your keyboard for the keyboard bar tweak. Reopen apps that were already running after changing **Hide Developer Options**.

Remove **DisableFlagSecure** before enabling secure screenshots, and **DoNotTryAccessibility** before enabling accessibility hiding. Credits are shown under their respective options.

🎨 **Improvements**

- **Custom icon editor:** move and zoom your selected image before applying it, with **Reset** to restore its position and zoom.
- **Consistent icon shapes:** app icons and folder previews follow your chosen shape, including icons in Google search.
- **Themed icons:** now color every app in the drawer and the icons you drag together.
- **Adaptive folder grids:** folders resize to fit their contents. Grid choices appear from **3 apps**, with **4×4** available from **10 apps** and **5×5** from **17 apps**. Your larger grid choice returns as the folder fills.
- **Single-app folders:** folders can keep one app and still open and close normally.
- **Drag to home screen:** the pill lights up and gives a light vibration as soon as the icon touches it.
- **Split-screen previews:** improved how app previews fill their cards after leaving split screen.
- **Shortcut menus:** long-press menus stay beside the app instead of covering its icon.
- **Super Dark inside Pixel Suite:** the app now uses matching black backgrounds, cards, menus and dialogs when **Super Dark-Mode** and the phone’s dark theme are enabled.
- **Clearer settings:** revised descriptions, improved feature previews and updated setup notes.

🐛 **Fixes**

- Fixed apps disappearing after moving them between folders and the drawer.
- Fixed outdated folder previews and incorrect drag targets after moving apps.
- Fixed icon reset, thin edges around custom icons and inconsistent icon shapes.
- Fixed themed icons briefly flashing their original colors.
- Fixed app icons flickering in **Google search** while typing, and apps with the same name now show their own icons.
- Fixed the **Item removed / Undo** pill appearing in the wrong place, and it now follows **Super Dark**.
- Fixed a blue-grey flash when opening the **Google feed or Google app** with Super Dark on, and made **Settings search** fully black.
- Fixed missing **Super Dark** styling in uninstall dialogs.

**Install over the current official build, then reboot.**

Root + **LSPosed API 102** required.

## v2.0.5

## 🐛 Bug fixes

- Fixed moving apps inside **drawer folders** not working correctly.

- Fixed the first app shrinking away and leaving an empty spot when creating a **drawer folder** from several held apps.

- Fixed a square background appearing behind the scatter animation when removing a **drawer folder**.

- Fixed folder icons briefly showing the old app order after reordering. The preview now shows the new order as soon as the folder closes.

**Install over the current official build and reboot.**

## v2.0.4

🎨 **New Frosted Glass Slider!**

- New slider under **Themes** changes the look of supported folders, shortcut menus, toast messages, Google Photos menus and dialogs.

  - **Blur Off:** turns off blur, including the phone’s window blur. White in light mode, tinted black in dark mode.

  - **Stock:** keeps Google’s surfaces, while preserving your icon sizes and menu positions.

  - **Frosted Glass (Default):** blurs the background behind these surfaces with Pixel Suite’s tint.

- **Super Dark** keeps surfaces black with blur off. While the phone is in dark mode, turn off **Super Dark-Mode** before changing surface styles. The slider works normally in light mode.

- A looping preview shows each style. Move the slider to try it instantly.

📸 **Doodles in Google Photos**

⚠️ **For Doodles in Google Photos:** select **Google Photos** in Pixel Suite’s LSPosed scope, then restart Photos or reboot. Enable it under **Doodles → Google Photos**.

- New **Doodles** tab beside **Crop**, with shapes, arrows, colors, thickness, fill and delete controls.

- Fully expanded toolbar with scrolling colors and an animated entrance and exit. The menu stays black in light and dark modes.

- Save your drawings, then reopen the photo to move, resize, recolor or delete its shapes. **Revert** removes saved Doodles.

- Shapes stay aligned while cropping, zooming or rotating. Drag the photo edges to crop with Doodles open.

- Supports Ultra HDR output and its gain map.

🔒 **Works completely offline.** Doodles runs directly inside Google Photos, on your phone. Your photos stay private—all Doodles processing happens locally.

**Pixel Suite adds the Doodles tab inside Google Photos and draws your shapes over the photo while you edit. When you tap Save, Photos’ own editor saves the result. The editable shapes stay in Photos’ private storage so you can adjust them later.**

**It uses Photos’ existing editor without changing your account, subscription or device identity. Your backup settings stay as they are.**

✨ **Enhancements**

- **Doodles:** center snapping with guide lines and light vibration feedback. Lines and arrows snap horizontally or vertically when close.

- **Google Photos:** matching frosted bottom tab bar and search button. The **trash/Undo** pill sits just above the bar with matching width.

- **Toast messages:** app messages now appear at the top in compact pills, following your surface style and theme.

- **Super Dark-Mode:** pure-black Doodles menus with a subtle border, plus matching dark editor controls and dialogs.

⚡ **Less background work**

- Drawing effects now run only while the screens that need them are open, with fewer repeated calculations and redraws.

🐛 **Bug fixes**

- **App shortcut menus:** fixed split backgrounds and menus getting cut off inside drawer folders. Menus now use one matching panel, with fading edges when there’s more to scroll.

- **Google Photos messages:** fixed the wrong pill colors in **Stock** and **Blur Off**. They now follow light and dark mode.

- Dragging an app directly from a drawer folder to the **Uninstall** button now also removes it from that folder, leaving no leftover icon.

- Shortcut menus inside home-screen folders now blur the folder behind them, within the menu frame.

- Fixed Pixel Studio’s **Back**, **X** and **Done** controls getting stuck after switching tools.

- Fixed Google Photos’ selection and confirmation panels not following Super Dark-Mode.

**Install over the current official build and reboot.**

## v2.0.3

## What's new in 2.0.3:

This update brings a redesigned Doodles menu, more reliable folder interactions, fixes for Pixel Studio and Super Dark, and cleaner settings throughout Pixel Suite.

### 🏠 Home-screen menu

- Removed the redundant **Apps list** option—you can already open the app drawer by swiping up.

### 🌗 App launch

- Fixed the white flash when opening Pixel Suite in dark theme. The launch screen now follows the system's light or dark theme from the start.

### ✏️ Doodles redesign

- A wider, shorter menu with neatly aligned shapes, colors, Delete and Done controls.
- Tap **More shapes** or the **rainbow icon** to expand for more shapes and colors. Tap the arrow on the right to fold it back.
- Drag or flick the menu vertically **up or down** to reposition it. It stays within the editing area and clear of the navigation bar.
- Selection outlines and handles hide while drawing, moving, resizing or pinching, then return when you release. Smaller handles keep their original touch area.
- Hidden controls no longer catch taps while the menu is folded.

### 📁 Drawer folders

- The **Back gesture** from either edge closes the open folder first; the next Back gesture leaves the drawer. Canceling the gesture keeps the folder open.
- Fixed quick taps during opening and closing accidentally launching an app behind the folder.
- Fixed icon, size and label jumps after reordering folder contents. Folder previews update once the folder has settled.

### 🗂️ Home folders and menus

- Smoother folder closing after moving apps, with the stock spring animations preserved.
- **Auto (A–Z)** and **Custom** sorting expand inside the same Sort menu, matching drawer folders.
- More compact Rename, Remove, Sort, Change icon and Add apps prompts, with consistent light, dark and Super Dark styling.

### ✂️ Pixel Studio

- Fixed, and should be much harder to break after future updates.

### 🧹 Recents

- Fixed a rare issue where **Clear all** became disabled after restarting, including when a task had no thumbnail.
- **Select** and **Screenshot** keep their normal behavior.

### 🌑 Super Dark

- Fixed a few places where the dark theme wasn't applying, including supported System UI dialogs and Pixel Launcher's Home settings.
- Made theme handling more efficient by reducing repeated background-color processing.
- Keeps automatic light/dark schedules and restores blur when appropriate.

### ✨ Cleaner Pixel Suite settings

- Shorter descriptions across feature pages, focused on what each option does and how to use it.
- Kept animated previews, essential setup instructions, reset guidance and the system font-settings location.
- The in-app changelog shows the latest update, with a link to the full release history on GitHub.

**Install over the current official version and reboot.** No uninstall is needed.

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

