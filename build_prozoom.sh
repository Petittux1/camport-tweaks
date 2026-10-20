#!/data/data/com.termux/files/usr/bin/bash
# 专业模式循环实验钩子：底包=当前工作补丁unified，仅追加 classes4.dex + xposed_init 一行
# 只构建+校验，不安装
set -euo pipefail

export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH

HOME_D=/data/data/com.termux/files/home
CAM=$HOME_D/work/camport
SDK=$HOME_D/work/sdk/android-35/android.jar
BT=$HOME_D/work/sdkbt/android-14
STUB=$HOME_D/work/ICBCWaterMod/xposedstub
B=/data/data/com.termux/files/usr/tmp/opencode/prozoom
SRC=$CAM/legend_src
BASE=$HOME_D/work/tmp/patch/current-working-patched.apk
OUT=$HOME_D/work/tmp/patch/prozoom-experimental.apk
KS=$CAM/camport.keystore

rm -rf "$B"
mkdir -p "$B/stubs" "$B/classes" "$B/dex" "$B/stage"

echo "== 1/5 编译 Xposed 桩（仅编译期，不进 dex）"
javac --release 8 -nowarn -d "$B/stubs" $(find "$STUB" -name '*.java')

echo "== 2/5 编译 ProZoomCycle（只这一个，产出独立 classes4）"
javac --release 8 -nowarn -cp "$SDK:$B/stubs" -d "$B/classes" \
    "$SRC/com/pudding/camport/hooks/ProZoomCycle.java"
echo "   产出 class: $(find "$B/classes" -name '*.class' | wc -l) 个"

echo "== 3/5 d8 出独立 dex（--lib android.jar，桩不打包）"
java -cp "$BT/r8.jar" com.android.tools.r8.D8 --release --lib "$SDK" --min-api 26 \
    --output "$B/dex" $(find "$B/classes" -name '*.class')
mv "$B/dex/classes.dex" "$B/stage/classes4.dex"
echo "   classes4.dex: $(stat -c%s "$B/stage/classes4.dex") bytes"

echo "== 4/5 打包（保留 classes/2/3 字节不动，只加 classes4 + 改 xposed_init）"
# 抽出原 xposed_init（保留 assets/ 路径），追加 ProZoomCycle
(cd "$B/stage" && unzip -o -q "$BASE" 'assets/xposed_init')
grep -q 'hooks.ProZoomCycle' "$B/stage/assets/xposed_init" \
    || echo 'com.pudding.camport.hooks.ProZoomCycle' >> "$B/stage/assets/xposed_init"
echo "   xposed_init 行数: $(wc -l < "$B/stage/assets/xposed_init")"

rm -f "$OUT"
cp "$BASE" "$OUT"
zip -q -d "$OUT" 'assets/xposed_init' || true
(cd "$B/stage" && zip -q "$OUT" classes4.dex assets/xposed_init)

echo "== 5/5 签名 + 校验"
java -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-pass pass:camport --key-pass pass:camport \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --min-sdk-version 24 "$OUT"
java -jar "$BT/lib/apksigner.jar" verify --min-sdk-version 24 "$OUT" >/dev/null
echo "   签名校验 OK"

echo "===== 校验：classes/2/3 是否字节级未变 ====="
for d in classes.dex classes2.dex classes3.dex; do
    a=$(unzip -p "$BASE" "$d" | md5sum | cut -d' ' -f1)
    b=$(unzip -p "$OUT"  "$d" | md5sum | cut -d' ' -f1)
    [ "$a" = "$b" ] && echo "   $d 一致 $a" || echo "   !! $d 不一致 $a vs $b"
done

echo "===== 校验：条目 ====="
unzip -l "$OUT" | grep -aE "classes[0-9]*\.dex|xposed_init" | awk '{print "   "$NF"  "$1}'

echo "===== 校验：xposed_init 末3行 ====="
tail -3 "$B/stage/assets/xposed_init" | sed 's/^/   /'

echo "OK -> $OUT"
