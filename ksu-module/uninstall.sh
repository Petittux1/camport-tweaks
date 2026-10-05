#!/system/bin/sh
# 卸载时摘掉绑定，让 /odm 回到出厂文件
umount /odm/etc/camera/xiaomi/satsettings.json 2>/dev/null
umount /odm/etc/camera/miZA_params.json 2>/dev/null
nsenter -t 1 -m -- umount /odm/etc/camera/xiaomi/satsettings.json 2>/dev/null
nsenter -t 1 -m -- umount /odm/etc/camera/miZA_params.json 2>/dev/null
exit 0
