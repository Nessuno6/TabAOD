#!/system/bin/sh
# After boot: make sure the TabAOD app is installed and has what it needs.
MODDIR=${0%/*}
PKG=dev.deang.tabaod
LOG=$MODDIR/tabaod.log
APK=$MODDIR/app/TabAOD.apk
# The bundled app has the module's version (module/build.sh sets both from the manifest).
BUNDLED_VC=$(grep -m1 '^versionCode=' "$MODDIR/module.prop" | cut -d= -f2)

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 10

# Install or update the bundled app (streamed, since system_server can't read /data/adb).
VC=$(dumpsys package $PKG 2>/dev/null | grep -m1 -oE 'versionCode=[0-9]+' | cut -d= -f2)
if [ -z "$VC" ] || [ "$VC" -lt "$BUNDLED_VC" ]; then
  R=$(cat "$APK" | pm install -r -S "$(stat -c %s "$APK")" 2>&1)
  log "- app install: $R"
fi

settings put global hidden_api_blacklist_exemptions 'Landroid/service/dreams/DreamService;'
cmd notification allow_listener $PKG/$PKG.NotifService >/dev/null 2>&1
# Lets TabAOD read which widgets the lock screen shows (to copy them).
pm grant $PKG android.permission.DUMP >/dev/null 2>&1
# For the "update available" notification.
pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1

log "- doze service: $(dumpsys dreams | grep -o 'getDozeComponent()=.*')"
log "- notification access: $(settings get secure enabled_notification_listeners | tr ':' '\n' | grep -c $PKG)"
if ! dumpsys dreams | grep -q "getDozeComponent()=.*$PKG"; then
  log "! doze still points elsewhere: the overlay must be mounted before Android starts (needs the soft reboot or a full boot with the module active)"
fi
