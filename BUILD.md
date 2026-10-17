# Building Pixel Suite

The source package contains the Android module, resources, compile-only API 102 stubs and logic tests. Compile-only stubs must never be packaged into the APK.

The supplied build.sh uses a Unix shell (Linux or WSL), a JDK supporting javac --release 8, an Android compile jar, aapt, dalvik-exchange, zip and zipalign. Set ANDROID_JAR to your compile jar if it is not in the script's default SDK location. The current source is compiled against API 23 with compatibility stubs; the manifest minimum is API 26 and target is API 35.

Run bash build.sh from the source directory. Its output is out/aligned.apk, which is unsigned. Sign it using your own Android release keystore and apksigner, then verify the signature and ZIP alignment. The official release is named PixelSuite-v2.0.2.apk.

The maintainer's signing key, passwords, local paths and private signing script are not included. Using a different certificate cannot update an already installed official build in place.

Current metadata: versionName 2.0.2, versionCode 117, LSPosed minimum/target API 102. The Xposed repository release tag is 117-2.0.2; the source repository tag is v2.0.2.