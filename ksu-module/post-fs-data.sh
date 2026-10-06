#!/system/bin/sh
# CamZoom Smooth: 把本模块的两份相机配置 bind 到 /odm
# 关键：必须挂进 init(pid 1) 的 mount ns，否则 HAL 根本看不到。
MODDIR=${0%/*}
LOG="$MODDIR/apply.log"
SAT_SRC="$MODDIR/files/satsettings.json"
ZA_SRC="$MODDIR/files/miZA_params.json"
SAT_DST="/odm/etc/camera/xiaomi/satsettings.json"
ZA_DST="/odm/etc/camera/miZA_params.json"
CX_SRC="$MODDIR/files/camxoverridesettings.txt"
CX_DST="/odm/etc/camera/camxoverridesettings.txt"

echo "==== $(date) post-fs-data ====" >> "$LOG"

# ---- 机型白名单 --------------------------------------------------------
# 这三份配置是 Xiaomi 17 Pro (pandora) 的原厂文件改出来的，其中
# camxoverridesettings.txt 是整机 HAL 配置，直接覆盖到别的机型会
# 让 CHI 配流失败 -> 相机打不开。
# devices.txt：每行一个 ro.product.device，# 开头为注释；写一行 all 放行全部。
DEV=$(getprop ro.product.device)
WL="$MODDIR/devices.txt"
ALLOW=0
if [ -f "$WL" ]; then
    while IFS= read -r line; do
        case "$line" in ''|\#*) continue ;; esac
        [ "$line" = "all" ] && { ALLOW=1; break; }
        [ "$line" = "$DEV" ] && { ALLOW=1; break; }
    done < "$WL"
fi
if [ "$ALLOW" != "1" ]; then
    echo "  跳过: 机型 $DEV 不在 $WL 白名单内（不改动任何 /odm 配置）" >> "$LOG"
    echo "  要在这台机器上启用：把 $DEV 追加进 devices.txt 再重启" >> "$LOG"
    exit 0
fi
echo "  机型 $DEV 已放行" >> "$LOG"

initns() { nsenter -t 1 -m -- "$@" 2>/dev/null; }

prep() {
    [ -f "$1" ] || { echo "  缺文件 $1" >> "$LOG"; return 1; }
    chown root:root "$1" 2>/dev/null
    chmod 0644 "$1" 2>/dev/null
    # 编辑后 SELinux 标签会丢，强制补回 vendor 配置标签，否则 HAL 读不到、配置静默失效
    chcon u:object_r:vendor_configs_file:s0 "$1" 2>/dev/null
}

do_bind() {
    _src="$1"; _dst="$2"; _marker="$3"; _name="$4"
    [ -e "$_dst" ] || { echo "  目标不存在 $_dst" >> "$LOG"; return 1; }
    [ -f "$_src" ] || { echo "  源不存在 $_src" >> "$LOG"; return 1; }

    # 幂等：先摘掉可能已存在的挂载（两个 ns 都清）
    umount "$_dst" 2>/dev/null
    initns umount "$_dst" 2>/dev/null

    initns mount -o bind "$_src" "$_dst"

    _mounted=$(grep -c "$_name" /proc/1/mountinfo 2>/dev/null)
    _content=$(grep -q "$_marker" "$_dst" 2>/dev/null && echo yes || echo no)
    if [ "$_mounted" -ge 1 ] && [ "$_content" = "yes" ]; then
        echo "  OK   $_dst (initns mountinfo=$_mounted)" >> "$LOG"
    else
        echo "  FAIL $_dst (mountinfo=$_mounted content=$_content)" >> "$LOG"
    fi
}

# 等 /odm 挂好
i=0
while [ ! -e "$SAT_DST" ] && [ "$i" -lt 50 ]; do
    sleep 0.1
    i=$((i + 1))
done

prep "$SAT_SRC"
prep "$ZA_SRC"
prep "$CX_SRC"

do_bind "$SAT_SRC" "$SAT_DST" 'zoomAnimationSwitch' 'modules/camzoom/files/satsettings.json'
do_bind "$ZA_SRC"  "$ZA_DST"  '"flag_iqat": 1'         'modules/camzoom/files/miZA_params.json'
do_bind "$CX_SRC"  "$CX_DST"  'satZoomTime='            'modules/camzoom/files/camxoverridesettings.txt'
