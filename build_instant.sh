#!/data/data/com.termux/files/usr/bin/bash
# 传奇一瞬（真一瞬）核心移植构建：底包=当前工作补丁unified，追加 classes4.dex + xposed_init 一行 + assets(.so/模板)
# 只构建+校验，不安装
set -euo pipefail

export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH

HOME_D=/data/data/com.termux/files/home
CAM=$HOME_D/work/camport
SDK=$HOME_D/work/sdk/android-35/android.jar
BT=$HOME_D/work/sdkbt/android-14
STUB=$HOME_D/work/ICBCWaterMod/xposedstub
B=/data/data/com.termux/files/usr/tmp/opencode/instant
SRC=$CAM/legend_src
BASE=$HOME_D/work/tmp/patch/current-working-patched.apk
OUT=$HOME_D/work/tmp/patch/instant-experimental.apk
KS=$CAM/camport.keystore
ASSET=$HOME_D/work/tmp/instant/assets_x

rm -rf "$B"
mkdir -p "$B/stubs" "$B/classes" "$B/dex" "$B/stage/assets/templates"

echo "== 1/6 编译 Xposed 桩（仅编译期，不进 dex）"
javac --release 8 -nowarn -d "$B/stubs" $(find "$STUB" -name '*.java')

echo "== 2/6 编译一瞬移植集（leicainstant.core + m10r + LegendInstant）"
FILES=$(find "$SRC/com/leicainstant" "$SRC/com/nezha" -name '*.java' ! -name 'NativeM9Hook.java'; echo "$SRC/com/pudding/camport/hooks/LegendInstant.java")
javac --release 8 -nowarn -cp "$SDK:$B/stubs" -d "$B/classes" $FILES
echo "   产出 class: $(find "$B/classes" -name '*.class' | wc -l) 个"

echo "== 3/6 d8 -> classes4.dex"
java -cp "$BT/r8.jar" com.android.tools.r8.D8 --release --lib "$SDK" --min-api 26 \
    --output "$B/dex" $(find "$B/classes" -name '*.class')

echo "== 4/6 组装：classes4.dex + xposed_init + assets"
cp "$BASE" "$OUT"
unzip -o -q "$BASE" assets/xposed_init -d "$B/stage" || true
printf 'com.pudding.camport.hooks.LegendInstant\n' >> "$B/stage/assets/xposed_init"
cp "$B/dex/classes.dex" "$B/stage/classes4.dex"
# assets：native .so + M10R 模板
cp "$ASSET/assets/libinstant_m10r.so" "$B/stage/assets/libinstant_m10r.so"
cp "$ASSET/assets/templates/leica-M9-landscape.jpg" "$B/stage/assets/templates/"
cp "$ASSET/assets/templates/leica-M9-portrait.jpg" "$B/stage/assets/templates/"
(cd "$B/stage" && zip -q "$OUT" classes4.dex assets/xposed_init \
    assets/libinstant_m10r.so assets/templates/leica-M9-landscape.jpg assets/templates/leica-M9-portrait.jpg)

echo "== 5/6 重签名（v2+v3，同钥）"
java -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:camport --key-pass pass:camport "$OUT"
java -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT" 2>&1 | grep -iE "SHA-256 digest" | head -1

echo "== 6/6 校验：classes.dex/2/3 必须与底包字节一致（md5），否则=改坏了现役功能"
for d in classes.dex classes2.dex classes3.dex; do
    A=$(unzip -p "$BASE" "$d" | md5sum | cut -d' ' -f1)
    Bm=$(unzip -p "$OUT" "$d" | md5sum | cut -d' ' -f1)
    [ "$A" = "$Bm" ] && echo "   OK $d 一致" || { echo "   !!! $d 不一致"; exit 1; }
done
echo "   classes4.dex 存在: $(unzip -l "$OUT" classes4.dex 2>/dev/null | grep -c classes4.dex)"
echo "   assets: $(unzip -l "$OUT" | grep -cE 'libinstant_m10r.so|leica-M9')"
echo "   xposed_init 行数: $(unzip -p "$OUT" assets/xposed_init | wc -l) (含新增 LegendInstant: $(unzip -p "$OUT" assets/xposed_init | grep -c LegendInstant))"
ls -la "$OUT"
echo "构建完成: $OUT"
