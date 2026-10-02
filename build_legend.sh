#!/data/data/com.termux/files/usr/bin/bash
# 传奇一瞬 hook 的免 Gradle 构建：javac + d8(合并原 dex) + zip + apksigner
set -euo pipefail

export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH

HOME_D=/data/data/com.termux/files/home
CAM=$HOME_D/work/camport
SDK=$HOME_D/work/sdk/android-35/android.jar
BT=$HOME_D/work/sdkbt/android-14
STUB=$HOME_D/work/ICBCWaterMod/xposedstub
B=/data/data/com.termux/files/usr/tmp/opencode/legend
SRC=$CAM/legend_src
OUT=$CAM/legendfix.apk
KS=$CAM/camport.keystore

rm -rf "$B"
mkdir -p "$B/stubs" "$B/classes" "$B/dex" "$B/apk" "$B/stage/assets"

echo "== 1/5 编译 Xposed 桩（仅编译期用，不进 dex）"
javac --release 8 -nowarn -d "$B/stubs" $(find "$STUB" -name '*.java')

echo "== 2/5 编译 hook"
javac --release 8 -nowarn -cp "$SDK:$B/stubs" -d "$B/classes" $(find "$SRC" -name '*.java')

echo "== 3/5 d8 合并（原 classes.dex + 新 class）"
unzip -o -q "$CAM/module.apk" classes.dex -d "$B/apk"
java -cp "$BT/r8.jar" com.android.tools.r8.D8 --release --lib "$SDK" --min-api 26 \
    --output "$B/dex" $(find "$B/classes" -name '*.class') "$B/apk/classes.dex"

echo "== 4/5 打包"
printf 'com.pudding.camport.MainHook\ncom.pudding.camport.hooks.LegendaryColor\ncom.pudding.camport.hooks.Mode231Probe\ncom.pudding.camport.hooks.Mode231Fix\ncom.pudding.camport.hooks.MasterLiveTune\n' \
    > "$B/stage/assets/xposed_init"
rm -f "$OUT"
cp "$CAM/module.apk" "$OUT"
zip -q -d "$OUT" classes.dex assets/xposed_init \
    'META-INF/CAMPORT.SF' 'META-INF/CAMPORT.RSA' 'META-INF/MANIFEST.MF' || true
(cd "$B/dex" && zip -q "$OUT" classes.dex)
(cd "$B/stage" && zip -q "$OUT" assets/xposed_init)

echo "== 5/5 签名"
java -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-pass pass:camport --key-pass pass:camport \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --min-sdk-version 24 "$OUT"
java -jar "$BT/lib/apksigner.jar" verify --min-sdk-version 24 "$OUT" >/dev/null

echo "== 安装（同签名可 -r 直接更新，LSPosed 状态不受影响）"
su -c "pm install -r -t $OUT"
su -c "am force-stop com.android.camera"
echo "OK -> $OUT"
