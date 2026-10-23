#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
AJ="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
MODE="${1:-release}"
case "$MODE" in release|--fast) ;; *) echo "Usage: bash build.sh [--fast]" >&2; exit 2 ;; esac
HEAP="${PIXELSUITE_DEX_HEAP:-6g}"
D8_JAR="${D8_JAR:-$HOME/.cache/pixelsuite-build-tools/r8-9.5.28.jar}"
if [[ ! -f "$D8_JAR" ]]; then
    echo "Missing d8 compiler: $D8_JAR. Install the pinned compiler before building." >&2
    exit 1
fi
now_ms() {
    # Monotonic time: WSL can correct its wall clock after Windows resumes.
    local uptime rest fraction
    read -r uptime rest < /proc/uptime
    fraction="${uptime#*.}00"
    printf '%s' "$(( ${uptime%.*} * 1000 + 10#${fraction:0:3} ))"
}
elapsed() { awk -v ms="$1" 'BEGIN { printf "%.2f", ms/1000 }'; }
TOTAL_START=$(now_ms)
# Keep thousands of compiler intermediates off the Windows-mounted drive in both modes.
CLASS_OUT=$(mktemp -d /tmp/pixelsuite-build.XXXXXXXX)
trap 'rm -rf -- "$CLASS_OUT"' EXIT
step() {
    local label="$1" start end
    shift
    start=$(now_ms)
    "$@"
    end=$(now_ms)
    printf '%s: %s s\n' "$label" "$(elapsed "$((end-start))")"
}
prepare() { rm -rf out && mkdir -p out/stubs out/classes "$CLASS_OUT/stubs" "$CLASS_OUT/classes"; }
compile() {
    # Compile-only stubs stay off the APK's program-input path.
    javac -nowarn --release 8 -cp "$AJ" -d "$CLASS_OUT/stubs" $(find stubs -name '*.java')
    javac -nowarn --release 8 -cp "$CLASS_OUT/stubs:$AJ" -d "$CLASS_OUT/classes" $(find src -name '*.java')
}
dex() {
    local dex_mode=--release
    if [[ "$MODE" == --fast ]]; then dex_mode=--debug; fi
    jar cf "$CLASS_OUT/program.jar" -C "$CLASS_OUT/classes" .
    java "-Xmx$HEAP" -cp "$D8_JAR" com.android.tools.r8.D8 \
        "$dex_mode" --min-api 26 --lib "$AJ" --classpath "$CLASS_OUT/stubs" --output "$CLASS_OUT" "$CLASS_OUT/program.jar"
    if [[ -f "$CLASS_OUT/classes2.dex" ]]; then
        echo 'Build exceeded one dex file; refusing to package an incomplete APK.' >&2
        return 1
    fi
    cp "$CLASS_OUT/classes.dex" out/classes.dex
}
resources() { aapt package -f -M AndroidManifest.xml -S res -I "$AJ" -0 arsc -F out/unsigned.apk; }
package_apk() {
    (
        cd out
        zip -q -0 unsigned.apk classes.dex
        mkdir -p META-INF/xposed
        cp ../meta/xposed/java_init.list ../meta/xposed/module.prop ../meta/xposed/scope.list META-INF/xposed/
        zip -q -X unsigned.apk META-INF/xposed/java_init.list META-INF/xposed/module.prop META-INF/xposed/scope.list
    )
    zipalign -f -p 4 out/unsigned.apk out/aligned.apk
}
printf 'Dex heap: %s\n' "$HEAP"
step 'Preparation' prepare
step 'javac (stubs + app)' compile
if [[ "$MODE" == --fast ]]; then step 'd8 (test mode; no shrinking or obfuscation)' dex; else step 'd8 (release mode; no shrinking or obfuscation)' dex; fi
step 'Resources' resources
step 'Packaging + zipalign' package_apk
printf 'Build total: %s s\n' "$(elapsed "$(( $(now_ms)-TOTAL_START ))")"
