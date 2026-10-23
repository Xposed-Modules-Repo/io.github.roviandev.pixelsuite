# Pixel Suite v2.0.8

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
