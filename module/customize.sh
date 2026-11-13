ui_print " "
ui_print "*********************************"
ui_print "  TabAOD Always On Display"
ui_print "*********************************"
[ -f "$MODPATH/overlay/TabAodDozeOverlay.apk" ] || abort "! overlay APK missing from zip"
[ -f "$MODPATH/app/TabAOD.apk" ] || abort "! TabAOD.apk missing from zip"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for s in post-fs-data.sh service.sh uninstall.sh action.sh; do set_perm "$MODPATH/$s" 0 0 0755; done
ui_print "- Reboot (or your soft reboot) to activate"
ui_print "- Toggle with the AOD tile in Quick Settings"
