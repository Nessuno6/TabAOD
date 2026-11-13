#!/usr/bin/env bash
# Packs the KernelSU / Magisk module zip from the built app and overlay into ../dist.
# Version and versionCode come from the app's AndroidManifest.xml.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=..
VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -1)
VC=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -1)
OUT="$ROOT/dist/TabAOD_module_v$VER.zip"

rm -rf stage && mkdir -p stage/overlay stage/app "$ROOT/dist"
cp post-fs-data.sh service.sh uninstall.sh action.sh customize.sh NOTICE stage/
sed -e "s/^version=.*/version=v$VER/" -e "s/^versionCode=.*/versionCode=$VC/" module.prop > stage/module.prop
cp -r META-INF stage/
touch stage/skip_mount
cp "$ROOT/overlay/build/TabAodDozeOverlay.apk" stage/overlay/
cp "$ROOT/build/TabAOD.apk" stage/app/
python -I - "$OUT" <<'PY'
import os, sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk("stage"):
        for f in sorted(files):
            p = os.path.join(root, f)
            a = os.path.relpath(p, "stage").replace(os.sep, "/")
            zi = zipfile.ZipInfo.from_file(p, a)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (0o100755 if a.endswith(".sh") or a.endswith("update-binary") else 0o100644) << 16
            with open(p, "rb") as fh:
                data = fh.read()
            if a.endswith((".sh", ".prop", "updater-script", "update-binary")):
                data = data.replace(b"\r\n", b"\n")  # scripts must have Unix line endings
            z.writestr(zi, data)
for i in zipfile.ZipFile(out).infolist():
    print(f"{i.file_size:>8}  {i.filename}")
PY
rm -rf stage
echo "built ${OUT#$ROOT/}"
