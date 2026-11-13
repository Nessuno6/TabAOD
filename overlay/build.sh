#!/usr/bin/env bash
# Builds the static overlay that sets config_dozeComponent to TabAOD.
set -euo pipefail
cd "$(dirname "$0")"

# Android SDK and a JDK: ANDROID_HOME / JAVA_HOME, else Android Studio's default locations.
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(cygpath -u "${LOCALAPPDATA:-}" 2>/dev/null)/Android/Sdk}}"
JBR="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
export JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH"

rm -rf build && mkdir -p build
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$JAR" build/res.zip
"$BT/zipalign.exe" -p -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --out build/TabAodDozeOverlay.apk build/aligned.apk 2>/dev/null
"$BT/aapt2.exe" dump resources build/TabAodDozeOverlay.apk | grep -A1 config_dozeComponent
echo "built build/TabAodDozeOverlay.apk"
