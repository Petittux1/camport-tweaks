#!/data/data/com.termux/files/usr/bin/bash
# dex 二分构建：定位「卡」到底来自 d8 重编原 dex，还是我们新增的类
#
#   ./build_ab.sh a   变体A：原 classes.dex 字节不动 + 新类放 classes2.dex   ← 保留全部功能
#   ./build_ab.sh b   变体B：只把原 dex 过 d8，不带新类                     ← 纯 d8 对照
#   ./build_ab.sh c   变体C：d8 合并（原 build_legend.sh 行为，本次卡住的那个）
#
# 只有一个变量在动，判据：
#   A 干净 + C 卡  -> 凶手是 d8 重编，且 A 就是最终修法（功能一个不少）
#   A 还卡        -> 跟 d8 无关，是新增的类；再拆（先去掉 SmartCompFix）
#   B 卡          -> d8 重编本身确实会搞坏原模块
set -euo pipefail

MODE="${1:-c}"

export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH

HOME_D=/data/data/com.termux/files/home
CAM=$HOME_D/work/camport
SDK=$HOME_D/work/sdk/android-35/android.jar
BT=$HOME_D/work/sdkbt/android-14
STUB=$HOME_D/work/ICBCWaterMod/xposedstub
B=/data/data/com.termux/files/usr/tmp/opencode/legend_ab
SRC=$CAM/legend_src
OUT=$CAM/legendfix.apk
KS=$CAM/camport.keystore

# 原模块 xposed_init 只有一行；我们追加 6 个 hook
INIT_ORIG='com.pudding.camport.MainHook'
INIT_NEW='com.pudding.camport.MainHook
com.pudding.camport.hooks.LegendaryColor
com.pudding.camport.hooks.Mode231Probe
com.pudding.camport.hooks.Mode231Fix
com.pudding.camport.hooks.MasterLiveTune
com.pudding.camport.hooks.SmartCompFix
com.pudding.camport.hooks.HdrFix'

rm -rf "$B"
mkdir -p "$B/stubs" "$B/classes" "$B/dex" "$B/apk" "$B/stage/assets"
unzip -o -q "$CAM/module.apk" classes.dex assets/xposed_init -d "$B/apk"

echo "== 1/4 编译 Xposed 桩（仅编译期）"
javac --release 8 -nowarn -d "$B/stubs" $(find "$STUB" -name '*.java')

echo "== 2/4 编译 hook"
javac --release 8 -nowarn -cp "$SDK:$B/stubs" -d "$B/classes" $(find "$SRC" -name '*.java')

echo "== 3/4 生成 dex（变体 $MODE）"
mkdir -p "$B/out" "$B/t"
case "$MODE" in
  a)
    # ★ 原 dex 字节不动；新类单独走 d8 -> classes2.dex
    cp "$B/apk/classes.dex" "$B/out/classes.dex"
    java -cp "$BT/r8.jar" com.android.tools.r8.D8 --release --lib "$SDK" --min-api 26 \
        --output "$B/t" $(find "$B/classes" -name '*.class')
    mv "$B/t/classes.dex" "$B/out/classes2.dex"
    ;;
  b)
    # ★ 只把原 dex 过 d8，不掺新类
    java -cp "$BT/r8.jar" com.android.tools.r8.D8 --release --lib "$SDK" --min-api 26 \
        --output "$B/t" "$B/apk/classes.dex"
    mv "$B/t/classes.dex" "$B/out/classes.dex"
    ;;
  c)
    java -cp "$BT/r8.jar" com.android.tools.r8.D8 --release --lib "$SDK" --min-api 26 \
        --output "$B/t" $(find "$B/classes" -name '*.class') "$B/apk/classes.dex"
    mv "$B/t/classes.dex" "$B/out/classes.dex"
    ;;
  *)
    echo "未知变体: $MODE（a|b|c）" >&2; exit 2;;
esac

echo "== 4/4 打包 + 签名"
if [ "$MODE" = "b" ]; then printf '%s\n' "$INIT_ORIG" > "$B/stage/assets/xposed_init"; else
    printf '%s\n' "$INIT_NEW" > "$B/stage/assets/xposed_init"; fi

rm -f "$OUT"
cp "$CAM/module.apk" "$OUT"
zip -q -d "$OUT" classes.dex assets/xposed_init \
    'META-INF/CAMPORT.SF' 'META-INF/CAMPORT.RSA' 'META-INF/MANIFEST.MF' || true

(cd "$B/out" && zip -q "$OUT" classes.dex)
if [ -f "$B/out/classes2.dex" ]; then (cd "$B/out" && zip -q "$OUT" classes2.dex); fi
(cd "$B/stage" && zip -q "$OUT" assets/xposed_init)

java -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-pass pass:camport --key-pass pass:camport \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --min-sdk-version 24 "$OUT"
java -jar "$BT/lib/apksigner.jar" verify --min-sdk-version 24 "$OUT" >/dev/null

echo "-- 产物 dex 清单"
unzip -l "$OUT" | grep -E 'classes|\.dex'
echo "-- md5: $(md5sum "$OUT" | cut -d' ' -f1)"

if [ "${2:-}" = "install" ]; then
    echo "== 安装"
    su -c "pm install -r -t $OUT"
    su -c "am force-stop com.android.camera"
fi
echo "OK 变体 $MODE -> $OUT"
