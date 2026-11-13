#!/system/bin/sh
# Same steps as the app's "Disable AOD" button. Run as root.
ID=$(cmd overlay list android | grep -o '[^ ]*:TabAodDoze')
[ -n "$ID" ] && cmd overlay disable --user 0 "$ID"
settings delete global hidden_api_blacklist_exemptions
echo "lookup: $(cmd overlay lookup android android:string/config_dozeComponent)"
