#!/usr/bin/env bash
# Builds TabAOD without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
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

rm -rf build && mkdir -p build/classes
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$JAR" -A assets build/res.zip
javac -source 17 -target 17 -Xlint:-options -encoding UTF-8 \
  -classpath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8.bat" --min-api 33 --lib "$JAR" --output build $(find build/classes -name '*.class')
python -I -c "import zipfile; zipfile.ZipFile('build/unsigned.apk','a').write('build/classes.dex','classes.dex')"
"$BT/zipalign.exe" -p -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --out build/TabAOD.apk build/aligned.apk
"$BT/apksigner.bat" verify build/TabAOD.apk && echo "built build/TabAOD.apk"
