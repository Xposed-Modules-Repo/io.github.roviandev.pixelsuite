# Pixel Suite 2.0.6

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
