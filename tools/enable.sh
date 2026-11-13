#!/system/bin/sh
# Same steps as the app's "Enable" button. Run as root.
# The doze overlay itself is mounted separately (mount_overlay.sh / KernelSU module).
PKG=dev.deang.tabaod
settings put global hidden_api_blacklist_exemptions 'Landroid/service/dreams/DreamService;'
cmd notification allow_listener $PKG/$PKG.NotifService
F=/data/system/users/0/wallpaper_lock_images/wallpaper_lock
[ -f $F ] || F=/data/system/users/0/wallpaper
D=/data/data/$PKG/files; mkdir -p $D
cp $F $D/wallpaper.img && U=$(stat -c %u /data/data/$PKG)
chown $U:$U $D $D/wallpaper.img; chmod 600 $D/wallpaper.img; restorecon -R $D
echo "wallpaper copied from $F"
echo "doze service: $(dumpsys dreams | grep -o 'getDozeComponent()=.*')"
echo "notification access: $(settings get secure enabled_notification_listeners | tr ':' '\n' | grep -c $PKG)"
