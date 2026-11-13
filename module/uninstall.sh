#!/system/bin/sh
# Undo the settings the module added. The overlay mount disappears on reboot.
settings delete global hidden_api_blacklist_exemptions
cmd notification disallow_listener dev.deang.tabaod/dev.deang.tabaod.NotifService >/dev/null 2>&1
