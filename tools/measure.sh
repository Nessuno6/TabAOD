#!/system/bin/sh
# Average battery current over N samples (2 s apart). Run as root.
B=/sys/class/power_supply/battery
N=${1:-10}
echo "status=$(cat $B/status) wakefulness=$(dumpsys power | grep -m1 -oE 'mWakefulness=[A-Za-z]+')"
sum=0; i=0
while [ $i -lt $N ]; do
  c=$(cat $B/current_now); echo -n "$c "
  sum=$((sum + c)); i=$((i + 1)); sleep 2
done
echo; echo "avg current_now: $((sum / N))"
