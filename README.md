[![Pixel Suite — Your Pixel. Your Way.](assets/pixelsuite-banner-v2.png)](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite/releases/tag/221-2.0.5)

# Pixel Suite 2.0.5

Your Pixel. Your Way.

**Open Pixel Suite from your home screen:** touch and hold an empty area, then choose **Pixel Suite settings**.

Made by **RovianDev**. A free, open-source LSPosed module for Pixel customization, gestures, screenshot tools and file sorting. Each feature has its own control.

## 🐛 What’s fixed in 2.0.5

- Fixed moving apps inside **drawer folders** not working correctly.

- Fixed the first app shrinking away and leaving an empty spot when creating a **drawer folder** from several held apps.

- Fixed a square background appearing behind the scatter animation when removing a **drawer folder**.

- Fixed folder icons briefly showing the old app order after reordering. The preview now shows the new order as soon as the folder closes.

## Frosted Glass Slider

Choose the look of supported folders, shortcut menus, toast messages, Google Photos menus and dialogs under **Themes**:

- **Blur Off:** solid surfaces, with window blur turned off. White in light mode, tinted black in dark mode.
- **Google’s own look (Stock):** Google’s surfaces, keeping your icon sizes and menu positions.
- **Frosted Glass (Modern):** blurred backgrounds with Pixel Suite’s tint. The default style.

**Super Dark-Mode** keeps surfaces black with blur off. When both Super Dark and the phone’s dark theme are active, turn off Super Dark before changing styles. Light mode remains unrestricted. The animated preview lets you see the styles without changing your settings.

## 📸 Doodles in Google Photos

**New scope:** select **Google Photos** in Pixel Suite’s LSPosed scope, restart Photos or reboot, then enable **Doodles → Google Photos**.

- New **Doodles** tab beside **Crop**, with shapes, arrows, colors, thickness, fill and delete controls.
- Save drawings and reopen the photo to move, resize, recolor or delete individual shapes. **Revert** removes saved Doodles.
- Shapes stay aligned while cropping, zooming and rotating. Crop from the photo edges with Doodles open.
- Supports **Ultra HDR** output and its gain map.

🔒 **Doodles works completely offline.** It runs inside Google Photos on your phone; all Doodles processing happens locally.

**Pixel Suite adds the Doodles tab and draws shapes over your photo while you edit. Photos’ own editor saves the result. Editable shapes remain in Photos’ private storage so you can adjust them later. Your account, subscription, device identity and backup settings stay as they are.**

**Also in 2.0.4:** Doodles snapping guides, themed Photos bars and message pills, matching shortcut-menu panels, folder uninstall cleanup and Pixel Studio control fixes. Drawing effects do less background work and only run while the screens that need them are open.

[📥 Download 2.0.5](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite/releases/tag/221-2.0.5) · [Full 2.0.5 release notes](RELEASE-NOTES-v2.0.5.md) · [Changelog](CHANGELOG.md) · [GitHub](https://github.com/RovianDev/PixelSuite) · [Xposed](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite/releases/tag/221-2.0.5)

## 📱 Launcher and folders

- 📁 **Drawer folders:** drag apps together, add apps, reorder folders, and choose **Auto (A–Z)** or **Custom** sorting.
- 🔲 **Folder grids:** choose **3×3, 4×4 or 5×5** from each folder’s menu, separately for home and drawer folders.
- 📦 **Move several apps:** select a stack and move it to a folder or home screen. Remove home shortcuts together without uninstalling their apps.
- ↔️ **Two-finger navigation:** hold an app while another finger scrolls the drawer or changes home/folder pages.
- ↗️ **Drag to home:** use **Add to home screen** to continue a drawer drag onto the home screen.
- ✏️ **Rename apps** and 🖼️ **change/reset icons:** updates apply instantly across homescreen, drawer, folders and the Google Search widget. Include **Google** in the LSPosed scope for its search integration. Private Space is excluded.
- 🔤 **Label size:** five text sizes without resizing icons; system fonts and Bold text are respected.
- 🧩 **Hide app shortcuts:** hide app-provided drawer actions while keeping the launcher’s basic controls.
- 🧹 **Clear all:** always visible in recents alongside **Select** and **Screenshot**.

## 🌑 Super Dark-Mode

Optional black and near-black surfaces across supported Settings, System UI, Pixel Launcher, Google and Gboard screens. Follows your dark-theme tile and schedule, restoring normal light-mode colors when the phone switches back. **Disabled by default.**

## ⚡ Buttons and gestures

- 🔦 Double-press **Power** for the flashlight.
- 📷 Double-press **Volume down** on the always-on display for the camera, with media/call safeguards.
- 📸 Swipe down with three fingers for a screenshot.
- 🌙 Double-tap empty home-screen space to sleep.
- ☀️ Choose tap or double-tap wake, plus lock-screen sleep and stock/modded interactions.

## ✂️ Editors, files and recording

- **Pixel Studio:** free screenshot crop and Doodles with shapes, colors, thickness, fill and Undo/Redo. Tap the shapes or rainbow icon to expand; use the arrow to fold back. Drag or flick the toolbar vertically.
- **Newest first:** folders stay above files; files sort newest-to-oldest in Files by Google and the supported file picker.
- **Screen recording:** a cleaner prompt keeps **X** and **Start** under the options. Recording still requires **Start**.

## ⚙️ Inside Pixel Suite

Separate feature pages, short descriptions, light/dark themes, animated previews and a latest-update changelog. Previews pause between loops and stop when the page or app is inactive.

The **Restart** shortcut lets you reload Pixel Launcher or Files after relevant changes. Optional update notifications appear for newer stable releases verified on **both GitHub and Xposed**. **Updates → Download and install** downloads inside Pixel Suite with progress, verification and Android’s installation prompt.

## 📥 Install and scope

**Root + LSPosed API 102 required.** Tested on a Pixel 11 Pro; it should also work on other Pixel devices.

1. Install **PixelSuite-v2.0.5.apk** over the current official version.
2. Enable Pixel Suite in LSPosed and grant the module app root access to save settings.
3. Keep the recommended scopes: **System Framework, System UI, Pixel Launcher, Settings, Google, Gboard, Files by Google**, your installed **Files / DocumentsUI** picker, and **Pixel Studio** for screenshot tools. **Add Google Photos for Photos Doodles.**
4. Reboot, open Pixel Suite and choose your features.

Save editor work before changing its feature toggle. **Some features require compatible Android and Google app versions.**

## 💬 Community and support

[![Join RovianDev on Telegram](assets/telegram-card.png)](https://t.me/RovianDev)

[![Become a patron](assets/patreon-card.png)](https://www.patreon.com/c/RovianDev)

[![Buy RovianDev a Coffee](assets/coffee-card.png)](https://buymeacoffee.com/roviandev)

[Telegram](https://t.me/RovianDev) · [Become a patron](https://www.patreon.com/c/RovianDev) · [Coffee](https://buymeacoffee.com/roviandev) · [Bug reports](https://github.com/RovianDev/PixelSuite/issues)

## Source and credits

GPL-3.0. Build instructions are in **BUILD.md**; artwork credits are in **THIRD-PARTY-NOTICES.md**. Release signing keys and private signing files are not distributed. Pixel Suite is an independent project and is not affiliated with Google.
