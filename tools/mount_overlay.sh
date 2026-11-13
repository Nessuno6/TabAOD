#!/system/bin/sh
# In-memory only: lays a copy of /system_ext/overlay (plus TabAOD's doze overlay)
# over the original. Nothing on /system_ext is written; a reboot removes it.
SRC=/system_ext/overlay
DIR=/data/adb/tabaod_overlay
APK=/data/local/tmp/TabAodDozeOverlay.apk

g() { nsenter -t 1 -m -- "$@"; }

if [ "$1" = "undo" ]; then
  # Lazy unmount: the system server keeps overlay files open.
  while g grep -q " $SRC " /proc/mounts; do g umount -l "$SRC" || break; done
  echo "unmounted"; exit 0
fi

g grep -q " $SRC " /proc/mounts && g umount "$SRC"
rm -rf "$DIR" && mkdir -p "$DIR"
cp -p "$SRC"/* "$DIR"/
cp "$APK" "$DIR/TabAodDozeOverlay.apk"
chown -R 0:0 "$DIR"; chmod 755 "$DIR"; chmod 644 "$DIR"/*
chcon -R u:object_r:vendor_overlay_file:s0 "$DIR"
g mount -o bind "$DIR" "$SRC" && echo "mounted"
settings put global hidden_api_blacklist_exemptions 'Landroid/service/dreams/DreamService;'
ls -laZ "$SRC"
