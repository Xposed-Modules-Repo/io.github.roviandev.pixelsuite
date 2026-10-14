# Pixel Suite

A small LSPosed module that adds Pixel features Google never shipped. Every feature has its own on/off switch in the module settings.

> **Tested only on a Pixel 11 Pro.** It may work on other devices. Feedback is welcome.

---

## Features

🧹 **Always-visible "Clear all"**
The Clear all button stays on screen in Pixel Launcher's recents, so you don't have to scroll to find it.

🔦 **Double-press power for flashlight**
Double-press the power button to toggle the flashlight.

📷 **Double-press volume down for camera**
Double-press volume down on the always-on display to open Pixel Camera. It won't open while media is playing or a call is active.

👆 **Tap or double tap to wake**
One setting, found in **Settings → System → Gestures → Tap or Double Tap to check phone**:
- **Tap to wake** or **Double tap to wake**. While the phone is held or rests on your lap, the touchscreen stays touch-active on the always-on display so both taps can be real touches. Held mode uses up to 90 seconds per stretch, with a regular four-minute budget per lock; a missed-tap recovery can briefly re-arm touch. When resting still on a desk or nightstand, the display returns to low-power mode and motion readings help detect the second tap. If a quick double tap misses, an extra tap within three seconds is a backup. Movement, very soft taps and vibrating car mounts can still affect detection.
- **Double-tap the lock screen to sleep**: double-tap anywhere on the lock screen to turn the screen off. It never works while the PIN pad is open.
- **Lock screen behavior**:
  - **Modded** (recommended): the clock, alarm, weather and date act like empty space, so a double tap never opens anything by mistake.
  - **Stock**: the lock screen works as Google made it. A double tap that lands on a card can open it instead of turning the screen off.

📸 **Three-finger screenshot**
Swipe down with three fingers to take a screenshot.

🔒 **Double-tap to lock**
Double-tap an empty spot on the home screen to lock the phone.

📁 **Newest-first in Files by Google**
Sorts folders so the newest files appear first.

---

## Why v1.1.3 improves wake behavior

The wake method adapts to the phone's position: it uses touch detection while held or on a lap, where soft taps are harder to detect from motion, and returns to low-power display behavior when the phone is still. This improves pickup, missed-tap recovery and soft second taps without keeping the touchscreen touch-active throughout still standby.

**Tested on my Pixel 11 Pro with Android 17 and LSPosed API 102.** It is working well in my testing so far, with no noticeable additional battery drain. There is no measured overnight percentage yet, and this is not a claim of zero power use. Other devices remain untested.

See the [v1.1.3 release notes](https://github.com/RovianDev/PixelSuite/releases/tag/v1.1.3) for the changes and limitations.

## Requirements

- A rooted device (KernelSU / KernelSU-Next, Magisk or APatch)
- [LSPosed](https://lsposed.org) with API 102
- Android 17

## Installation

1. Download the latest APK from the [Releases](https://github.com/RovianDev/PixelSuite/releases) page and install it.
2. Grant it root access in your root manager.
3. Open **LSPosed → Modules → Pixel Suite** and enable it.
4. Select the scopes: **System Framework, System UI, Settings, Pixel Launcher, Files and Files by Google**.
5. Reboot, then open the module settings and choose your features.

**Updating:** install the new APK over the old one (no uninstall needed) and reboot.

**Note:** the first time you open **System → Gestures** after changing a toggle, Settings may crash. This is a Google bug that happens with stock gestures too. Reopen Settings after boot and wait for everything to load.

## Building from Source

See [BUILD.md](https://github.com/RovianDev/PixelSuite/blob/main/src/BUILD.md). Quick start on Linux or macOS in the [source repository](https://github.com/RovianDev/PixelSuite):

```bash
cd src
bash build.sh
```

The APK will be in `src/out/PixelSuite.apk`.

## Feedback

Found a bug or have an idea? Open an [issue](https://github.com/RovianDev/PixelSuite/issues). For bugs, include your device, Android version, root method, LSPosed version and an LSPosed log (Logs → Save).

## Support the project

Free and open source. If it helps you, you can [buy me a coffee ☕](https://buymeacoffee.com/roviandev) or [support on Patreon](https://www.patreon.com/c/RovianDev). Starring the repo helps too.

## Credits

- [LSPosed](https://lsposed.org) and the libxposed module API

## Disclaimer

Modules that hook system components can cause instability. Use at your own risk and keep a backup. Pixel Suite is an independent project, not affiliated with or endorsed by Google LLC. "Pixel", "Pixel Launcher", "Google" and "Files by Google" are trademarks of Google LLC, used only to describe compatibility.

## License

Released under the [GPL-3.0 License](https://github.com/RovianDev/PixelSuite/blob/main/LICENSE).

---

Made by **Rovian.Dev**
