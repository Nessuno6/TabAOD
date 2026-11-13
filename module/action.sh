#!/system/bin/sh
# KernelSU "Action" button: show status and the last boot log.
MODDIR=${0%/*}
PKG=dev.deang.tabaod
echo "overlay mounted: $([ -f /system_ext/overlay/TabAodDozeOverlay.apk ] && echo yes || echo no)"
echo "doze service: $(dumpsys dreams | grep -o 'getDozeComponent()=.*')"
echo "app: $(dumpsys package $PKG 2>/dev/null | grep -m1 -oE 'versionName=[^ ]+')"
echo "notification access: $(settings get secure enabled_notification_listeners | tr ':' '\n' | grep -c $PKG)"
echo "--- boot log ---"
cat "$MODDIR/tabaod.log" 2>/dev/null
