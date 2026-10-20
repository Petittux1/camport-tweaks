#!/data/data/com.termux/files/usr/bin/bash
# mega8: round-2 FakeRaw 容器封装 — g8 落盘时用兄弟 JpgConvert 链把武装成片包成 Legend.M10R 容器
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH
W=/data/data/com.termux/files/home/work
CAM=$W/camport
SDK=$W/sdk/android-35/android.jar
BT=$W/sdkbt/android-14
B=$W/tmp/instant/build
LOG=$W/mega8_log.txt
exec > "$LOG" 2>&1
echo "== mega8 start $(date '+%H:%M:%S') =="

BASE=$W/tmp/patch/current-working-patched.apk
[ -f "$BASE" ] || BASE=$W/tmp/patch/legendfix-68-zoomcycle.apk
OUT=$W/instant-experimental.apk
KS=$CAM/camport.keystore
BRO=/storage/emulated/0/Download/QQ/InstantCamera-0.5.6-m10r-bggr-cfa-fix.apk.1
LI=$CAM/legend_src/com/pudding/camport/hooks/LegendInstant.java
LJ=$CAM/legend_src/com/pudding/legendjudge/JudgeHook.java
for f in "$BASE" "$KS" "$SDK" "$BT/r8.jar" "$BT/lib/apksigner.jar" "$BRO" "$LI" "$LJ"; do
  [ -f "$f" ] || { echo "PRECHECK MISSING: $f"; echo MEGA_ABORT; exit 1; }
done
grep -q 'g9-watermark' "$LI" || { echo "LI SOURCE NOT NEW-INSTALLER"; echo MEGA_ABORT; exit 1; }
grep -q 'wrapContainer' "$LI" || { echo "LI SOURCE MISSING g10 WRAP (patch not applied?)"; echo MEGA_ABORT; exit 1; }
echo "prechecks OK"

# ---------- 1) stubs ----------
STUB=$W/tmp/instant/stubfull
[ -f "$STUB/de/robv/android/xposed/XposedBridge.java" ] || { echo "STUB SRC MISSING"; echo MEGA_ABORT; exit 1; }
rm -rf $B/stubs; mkdir -p $B/stubs
javac --release 8 -nowarn -cp "$SDK" -d $B/stubs $(find $STUB -name '*.java') 2>$B/stub_err.txt
SC=$(find $B/stubs -name '*.class' | wc -l)
echo "stub classes=$SC"
[ "$SC" -ge 7 ] || { echo "STUB COMPILE FAIL"; tail -20 $B/stub_err.txt; echo MEGA_ABORT_stub; exit 1; }

# ---------- 2) 编译 LegendInstant (源码树直编) ----------
rm -rf $B/classes; mkdir -p $B/classes
javac --release 8 -nowarn -cp "$SDK:$B/stubs" -d $B/classes "$LI" "$LJ" 2>$B/cerr.txt
rc=$?
echo "javac rc=$rc errors=$(grep -c 'error:' $B/cerr.txt)"
if [ $rc -ne 0 ]; then cat $B/cerr.txt; echo MEGA_ABORT_compile; exit 1; fi
CC=$(find $B/classes -name '*.class' | wc -l)
echo "classes: $CC"

# ---------- 3) d8 -> classes4.dex ----------
rm -rf $B/dex; mkdir -p $B/dex
java -cp "$BT/r8.jar" com.android.tools.r8.D8 --release --lib "$SDK" --min-api 26 --output $B/dex $(find $B/classes -name '*.class') 2>$B/d8err.txt
[ -f $B/dex/classes.dex ] || { tail -10 $B/d8err.txt; echo MEGA_ABORT_d8; exit 1; }
echo "our dex: $(stat -c %s $B/dex/classes.dex) bytes"

# ---------- 4) 兄弟 dex 原封提取 -> classes5.dex ----------
rm -rf $B/bro; mkdir -p $B/bro
unzip -o -q "$BRO" classes.dex -d $B/bro || { echo MEGA_ABORT_bro; exit 1; }
[ -f $B/bro/classes.dex ] || { echo MEGA_ABORT_bro2; exit 1; }
echo "his dex: $(stat -c %s $B/bro/classes.dex) bytes"

# ---------- 5) stage ----------
rm -rf $B/stage; mkdir -p $B/stage
unzip -o -q "$BRO" 'assets/libinstant_m10r.so' 'assets/templates/legend-M10R*' 'assets/templates/leica-M9*' -d $B/stage || { echo MEGA_ABORT_assets; exit 1; }
SO=$(find $B/stage -name 'libinstant_m10r.so' | wc -l)
TPL=$(find $B/stage -name '*.jpg' | wc -l)
echo "assets: so=$SO templates=$TPL"
[ "$SO" -ge 1 ] && [ "$TPL" -ge 4 ] || { echo MEGA_ABORT_assets2; exit 1; }

unzip -o -q "$BASE" assets/xposed_init -d $B/stage || { echo MEGA_ABORT_xi; exit 1; }
python3 - $B/stage/assets/xposed_init <<'PYEOF'
import sys
p = sys.argv[1]
lines = open(p, encoding='utf-8').read().splitlines()
for entry in ('com.pudding.camport.hooks.LegendInstant',
              'com.pudding.legendjudge.JudgeHook'):
    if entry not in lines:
        lines.append(entry)
open(p, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('xposed_init lines:', len(lines))
PYEOF

cp $B/dex/classes.dex $B/stage/classes4.dex
cp $B/bro/classes.dex $B/stage/classes5.dex
rm -f "$OUT"; cp "$BASE" "$OUT"
( cd $B/stage && zip -q "$OUT" classes4.dex classes5.dex $(find assets -type f) ) || { echo MEGA_ABORT_zip; exit 1; }
echo "apk staged: $(stat -c %s "$OUT") bytes"

# ---------- 6) sign + verify ----------
java -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:camport --key-pass pass:camport "$OUT" || { echo MEGA_ABORT_sign; exit 1; }
java -jar "$BT/lib/apksigner.jar" verify "$OUT" && echo "SIG=OK" || { echo MEGA_ABORT_sigverify; exit 1; }
DEXOK=OK
for d in classes.dex classes2.dex classes3.dex; do
  a=$(unzip -p "$BASE" $d | md5sum | cut -d' ' -f1)
  b=$(unzip -p "$OUT" $d | md5sum | cut -d' ' -f1)
  [ "$a" = "$b" ] || DEXOK="MISMATCH:$d"
done
echo "base dex md5: $DEXOK"
echo "c4=$(unzip -l "$OUT" | grep -c classes4.dex) c5=$(unzip -l "$OUT" | grep -c classes5.dex)"
echo "c4_g10_marker=$(unzip -p "$OUT" classes4.dex | grep -ac 'wrapContainer')"

# ---------- 7) install ----------
cp "$OUT" /sdcard/instant-experimental.apk || { echo MEGA_ABORT_copy; exit 1; }
cat > $W/tmp/instant/do_install.sh <<'EOS'
cp /sdcard/instant-experimental.apk /data/local/tmp/ie.apk
pm install -r -t /data/local/tmp/ie.apk
rm -f /data/local/tmp/ie.apk
EOS
INST=$(su -c 'sh /data/data/com.termux/files/home/work/tmp/instant/do_install.sh' </dev/null 2>&1)
echo "pm install: $INST"
case "$INST" in *Success*) ;; *) echo MEGA_ABORT_install; exit 1 ;; esac

# ---------- 8) config DE + CE 双写 ----------
cat > $W/tmp/instant/setcfg.sh <<'EOS'
for D in /data/user_de/0/com.android.camera/files/camport /data/data/com.android.camera/files/camport; do
  mkdir -p "$D"
  CFG="$D/config.conf"
  if [ -f "$CFG" ]; then
    sed -i '/^legend.instant=/d' "$CFG"
    echo "legend.instant=1" >> "$CFG"
    sed -i '/^legend.color=/d' "$CFG"
    echo "legend.color=1" >> "$CFG"
  else
    printf 'legend.instant=1\nlegend.color=1\n' > "$CFG"
  fi
  echo "$CFG: $(grep -E '^(legend.instant|legend.color)=' "$CFG" | tr '\n' ' ')"
done
EOS
CFGV=$(su -c 'sh /data/data/com.termux/files/home/work/tmp/instant/setcfg.sh' </dev/null 2>&1)
echo "config: $CFGV"

# ---------- 9) restart camera + capture ----------
ZLOG=/sdcard/Android/data/com.android.camera/files/camport/zoomcycle.log
PREV=$(su -c "wc -l < $ZLOG" </dev/null 2>/dev/null | tr -d '\r')
PREV=${PREV:-0}
echo "zlog lines before: $PREV"
su -c 'logcat -c' </dev/null 2>/dev/null
su -c 'am force-stop com.android.camera' </dev/null 2>/dev/null
sleep 1
su -c 'monkey -p com.android.camera -c android.intent.category.LAUNCHER 1' </dev/null 2>&1 | grep -i 'Events injected'
echo "camera launched, waiting 15s..."
sleep 15
logcat -d -s CamPort NativeM9Hook > $W/run_logcat.txt 2>/dev/null
su -c "tail -n +$((PREV+1)) $ZLOG" </dev/null > $W/run_zlog.txt 2>/dev/null
PROCS=$(su -c 'ps -A | grep -c com.android.camera' </dev/null 2>/dev/null)
echo "camera proc lines: $PROCS"
FATAL=$(logcat -d 2>/dev/null | grep -ac 'FATAL\|NativeM9Hook.*Failed installing')
echo "fatal/native-fail lines: $FATAL"

echo "== SUMMARY =="
echo "javac: rc=$rc classes=$CC"
echo "our dex classes4: $(stat -c %s $B/dex/classes.dex 2>/dev/null) bytes"
echo "apk: $(stat -c %s "$OUT" 2>/dev/null) bytes base_dex=$DEXOK"
echo "install: $INST"
echo "config: $CFGV"
echo "--- new logcat (CamPort/NativeM9Hook) ---"
cat $W/run_logcat.txt
echo "--- new zoomcycle.log segment ---"
cat $W/run_zlog.txt
echo "== mega8 end $(date '+%H:%M:%S') =="
