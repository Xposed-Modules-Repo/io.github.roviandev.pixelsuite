# Pixel Suite

LSPosed module with Pixel quality-of-life tweaks:

- Always-visible "Clear all" in Pixel Launcher recents
- Double-press power button to toggle the flashlight
- Double-press volume down to launch camera from always-on display (won't launch if media is playing or a phone call is active)
- Tap or double tap to wake, plus double-tap the lock screen to sleep
  - Tap to wake or double tap to wake
  - One double tap also works on the always-on display (uses a little extra battery while it shows, none while charging)
  - Double-tap the lock screen to turn the screen off (never while the PIN pad is open, no vibration)
  - Lock screen behavior:
    - Modded (recommended): the clock, alarm, weather and date act like empty space, so a double tap to sleep never opens anything by mistake
    - Stock: the lock screen stays as Google made it, with tappable cards and vibrations. A double tap on a card can open it instead
  - Found in the module settings and in Settings → System → Gestures → Tap or Double Tap to check phone
- Three-finger swipe down for a screenshot
- Double-tap an empty home screen spot to lock the screen
- Files by Google folders sorted newest-first

Every feature has its own toggle in the module settings.

Tested on Pixel 11 Pro, Android 17. Requires LSPosed API 102.

Source code and full instructions: https://github.com/RovianDev/PixelSuite
