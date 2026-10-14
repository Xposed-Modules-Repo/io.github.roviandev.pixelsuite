# Pixel Suite v1.1.3

Improved double-tap wake that adapts to how the phone is resting, with better handling of soft taps, pickup and missed attempts.

## What changed

- **Better wake detection in your hand or on your lap.** The held/still check also recognizes a phone resting on your thigh. While held, the touchscreen stays touch-active on the always-on display so both taps can be real touches.
- **Bounded held-touch periods.** Held mode runs for up to 90 seconds per stretch, with a regular four-minute budget per lock. After a missed tap, a short recovery can re-arm touch while the phone is held.
- **Low-power behavior when resting still.** On a desk or nightstand, the always-on display returns to low-power mode and motion readings help detect the second tap. An extra tap within three seconds is a backup if a quick double tap misses.
- **Softer second taps** are detected more readily on a still phone.
- **Pickup and charging fixes.** Better handling of taps just after pickup and small shifts while charging.
- **Lockscreen interaction fix.** Delayed touch events are handled to prevent a double tap from opening lockscreen customization unexpectedly.

## Why this approach is better

Touch detection is used where motion-based detection is less dependable, such as a held phone or one resting on a lap. When the phone is still, the display can return to low-power operation instead of keeping the touchscreen touch-active throughout standby.

Movement, very soft taps and a vibrating car mount can still affect detection. The held-mode budget also limits how long touch stays active; this is not a guarantee that every double tap will register in every position.

## Testing and battery

Tested on my Pixel 11 Pro with Android 17 and LSPosed API 102. It is working well in my testing so far, and I have not noticed additional battery drain. No measured overnight percentage is available; this is not a claim of zero power use. Other devices remain untested.

## Install / update

Install `PixelSuite-v1.1.3.apk` over the previous public release, then reboot. No uninstall is needed when updating with the same signing key.

Source: [RovianDev/PixelSuite](https://github.com/RovianDev/PixelSuite) · [Xposed listing](https://github.com/Xposed-Modules-Repo/io.github.roviandev.pixelsuite)

[Buy me a coffee](https://buymeacoffee.com/roviandev) · [Patreon](https://www.patreon.com/c/RovianDev)
