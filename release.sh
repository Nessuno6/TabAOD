#!/usr/bin/env bash
# Builds the app, the doze overlay and the module, and writes update.json for the version in
# AndroidManifest.xml. With --publish it also creates the GitHub release v<version> with both
# files. Commit and push update.json after publishing: the app and the KernelSU / Magisk
# manager read it from the main branch to find updates.
set -euo pipefail
cd "$(dirname "$0")"
REPO=Nessuno6/TabAOD
VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
VC=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
TAG="v$VER"

bash build.sh
bash overlay/build.sh
bash module/build.sh
mkdir -p dist
APK="dist/TabAOD-$TAG.apk"
ZIP="dist/TabAOD_module_$TAG.zip"
cp build/TabAOD.apk "$APK"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
DL="https://github.com/$REPO/releases/download/$TAG"

cat > update.json <<JSON
{
  "version": "$TAG",
  "versionCode": $VC,
  "zipUrl": "$DL/TabAOD_module_$TAG.zip",
  "changelog": "https://raw.githubusercontent.com/$REPO/main/CHANGELOG.md",
  "apkVersionCode": $VC,
  "apkUrl": "$DL/TabAOD-$TAG.apk",
  "apkSha256": "$SHA"
}
JSON
echo "wrote update.json for $TAG (apk sha256 $SHA)"

if [ "${1:-}" = "--publish" ]; then
  # Release notes: this version's section of CHANGELOG.md.
  NOTES=$(awk -v t="## $TAG" '$0==t{f=1;next} /^## /{f=0} f' CHANGELOG.md)
  gh release create "$TAG" "$APK" "$ZIP" --repo "$REPO" --title "TabAOD $TAG" --notes "$NOTES"
fi
