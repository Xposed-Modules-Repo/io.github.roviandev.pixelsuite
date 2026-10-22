# Building Pixel Suite

The source package contains the Android module, resources, compile-only API 102 stubs and logic tests. Compile-only stubs must never be packaged into the APK.

The supplied build.sh uses a Unix shell (Linux or WSL), a JDK supporting javac --release 8, an Android compile jar, aapt, dalvik-exchange, zip and zipalign. Set ANDROID_JAR to your compile jar if it is not in the script's default SDK location. The current source is compiled against API 23 with compatibility stubs; the manifest minimum is API 26 and target is API 35.

Run bash build.sh from the source directory. Its output is out/aligned.apk, which is unsigned. Sign it using your own Android release keystore and apksigner, then verify the signature and ZIP alignment. The official release is named PixelSuite-v2.0.7.apk.

The maintainer's signing key, passwords, local paths and private signing script are not included. Using a different certificate cannot update an already installed official build in place.

Current metadata: versionName 2.0.7, versionCode 314, LSPosed minimum/target API 102. The Xposed repository release tag is 314-2.0.7; the source repository tag is v2.0.7.
## Test builds

`bash build.sh --fast` uses D8 without shrinking or obfuscation. Its compiler intermediates use WSL/Linux temporary storage and are removed afterward. Official builds keep the existing dx compiler. Both modes print step timings and use a 6 GB dex heap; override it with `PIXELSUITE_DEX_HEAP=4g` if needed.

Fast mode requires Google's pinned D8 9.5.28 compiler in `$HOME/.cache/pixelsuite-build-tools/r8-9.5.28.jar`, or set `D8_JAR` to its location. Obtain it from https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.5.28/r8-9.5.28.jar . SHA-256: `38a9ffaaebbd17d1514e3ecbff5a85b989bb13c551b9e224c7409514eea84ff5`.

The maintainer's PowerShell wrapper accepts `-Fast` and adds `-fast` to the signed test APK filename. Omit it for official releases; signing still uses `--v4-signing-enabled false`.
