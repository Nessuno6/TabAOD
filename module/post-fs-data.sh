#!/system/bin/sh
# Before Android starts: lay a copy of /system_ext/overlay (plus TabAOD's doze
# overlay) over the original. In memory only; nothing on /system_ext is written.
MODDIR=${0%/*}
SRC=/system_ext/overlay
DIR=$MODDIR/overlay_mnt
LOG=$MODDIR/tabaod.log

echo "$(date '+%m-%d %H:%M:%S') post-fs-data" > "$LOG"
[ -d "$SRC" ] || { echo "! $SRC missing" >> "$LOG"; exit 0; }
[ -f "$SRC/TabAodDozeOverlay.apk" ] && { echo "- already mounted" >> "$LOG"; exit 0; }

rm -rf "$DIR" && mkdir -p "$DIR"
cp -p "$SRC"/* "$DIR"/ 2>/dev/null
cp "$MODDIR/overlay/TabAodDozeOverlay.apk" "$DIR/"
chown -R 0:0 "$DIR"; chmod 755 "$DIR"; chmod 644 "$DIR"/*
chcon -R u:object_r:vendor_overlay_file:s0 "$DIR"

# Mount in init's namespace so every process sees it.
nsenter -t 1 -m -- mount -o bind "$DIR" "$SRC" 2>/dev/null || mount -o bind "$DIR" "$SRC"
if [ -f "$SRC/TabAodDozeOverlay.apk" ]; then
  echo "- overlay mounted" >> "$LOG"
else
  echo "! overlay mount failed" >> "$LOG"
fi
