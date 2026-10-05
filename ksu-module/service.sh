#!/system/bin/sh
# 开机兜底：确认绑定在 init ns 里还在，掉了就重新挂
MODDIR=${0%/*}
LOG="$MODDIR/apply.log"
SAT_SRC="$MODDIR/files/satsettings.json"
ZA_SRC="$MODDIR/files/miZA_params.json"
SAT_DST="/odm/etc/camera/xiaomi/satsettings.json"
ZA_DST="/odm/etc/camera/miZA_params.json"

sleep 5
echo "==== $(date) service ====" >> "$LOG"

initns() { nsenter -t 1 -m -- "$@" 2>/dev/null; }

ensure() {
    _src="$1"; _dst="$2"; _marker="$3"; _name="$4"
    if [ "$(grep -c "$_name" /proc/1/mountinfo 2>/dev/null)" -ge 1 ] \
       && grep -q "$_marker" "$_dst" 2>/dev/null; then
        echo "  OK   $_dst" >> "$LOG"
        return 0
    fi
    umount "$_dst" 2>/dev/null
    initns umount "$_dst" 2>/dev/null
    chcon u:object_r:vendor_configs_file:s0 "$_src" 2>/dev/null
    initns mount -o bind "$_src" "$_dst"
    if [ "$(grep -c "$_name" /proc/1/mountinfo 2>/dev/null)" -ge 1 ] \
       && grep -q "$_marker" "$_dst" 2>/dev/null; then
        echo "  修复 $_dst" >> "$LOG"
    else
        echo "  FAIL $_dst" >> "$LOG"
    fi
}

ensure "$SAT_SRC" "$SAT_DST" 'zoomAnimationSwitch":2' 'modules/camzoom/files/satsettings.json'
ensure "$ZA_SRC"  "$ZA_DST"  '"flag_iqat": 1'         'modules/camzoom/files/miZA_params.json'
