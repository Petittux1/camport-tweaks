package com.pudding.camport.hooks;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * 实况运镜（mode 231）成片观感的两个旋钮：**运镜动画时长** 与 **快门后 AF 冻结**。
 *
 * ============================================================
 * 一、为什么要把动画缩短（legend.zoomdur）
 * ============================================================
 * 用 wscan.py 逐 5 帧窗对成片做独立 NCC k 扫描，自由运镜实测：
 *      f00->f05  k=1.000 (fit 0.090，全场最佳)
 *      f05->f10  k=0.980 (0.106)
 *      f10->f15  k=1.010 (0.251)      而指令要求 1.036 / 1.054 / 1.051
 *      f30->f35  k=1.050 (0.193)      指令 1.043
 *      f35->f40  k=1.050 (0.218)      指令 1.041
 *      f40->f45  k=1.050 (0.121)      指令 1.039
 * 即：成片**前 0.5 秒完全没 zoom，1.0 秒之后与指令严丝合缝**。
 *
 * 也就是说「成片第 p 秒」显示的画面，是「真实时间 p - 0.5 秒」拍到的内容
 * （f0 的内容在快门之前，也印证了这一点）。
 * 成片长 1.63s，所以它能覆盖的真实时间只有 [0, 1.13]（以 startAutoZoom 为 0）。
 *
 * 而 startMasterLiveFeatureZoom 的动画是硬编码 3.0f（自由/红毯）/ 2.0f（主角）
 * —— 成片只装得下前 1.13s，也就是**只推到 38% 就结束**，
 * 这正是「自由运镜 zoom 断断续续、推不到位」的成因。
 *
 * 把时长压到 1.1s（< 1.13s 覆盖上限，且在 0.32~0.5s 滞后不确定区间内都成立），
 * 成片就能完整看完一次推拉。
 *
 * 只改 startAutoZoom 的 **fromEvent == 2** 那一路：
 *   2 = startMasterLiveFeatureZoom（快门触发的运镜）
 *   1 = 手势/其他，3 = 另一路手势 —— 这两路绝不能碰。
 *
 * ============================================================
 * 二、为什么要把 AF 冻结（legend.aflock）
 * ============================================================
 * 成片 +0.50s 那次「糊 -> 清」硬跳（清晰度 14.8 -> 31.5、帧体积 19.6K -> 163.8K）
 * 是快门后 AF 被重扫造成的。logcat 两次拍摄时间轴完全一致：
 *      +0ms   onShutterButtonClick
 *      +15ms  still 请求: applyAfMode: focusMode=4 + applyAfTrigger: 0
 *      +111ms startAutoZoom
 *      +145ms 重复请求重建: applyAfMode: focusMode=4 + applyAfTrigger: 0
 *      +312ms afState 2 -> 0   INACTIVE（硬复位）
 *      +429ms afState 0 -> 1   PASSIVE_SCAN（镜头开始找焦 = 画面变糊）
 *      +594ms afState 1 -> 2   PASSIVE_LOCKED（清晰 = 成片里那次「啪」）
 * 对照拍摄**之前**的 AF：2->1->2 每轮扫描只有 67ms；
 * 快门后多了一段 118ms 的 INACTIVE，扫描拉长到 165ms —— 同一病，量化清楚了。
 *
 * 两种介入方式（config 选）：
 *   1 = 跳过「值没变」的重复 applyAfMode 下发。最小侵入；若重下发本身就是
 *       HAL 复位的原因，这一刀就够，且零副作用。
 *   2 = 窗口内把 applyAfMode 强制为 1（CONTROL_AF_MODE_AUTO）。
 *       **不选 AF_MODE_OFF(0)**：OFF 模式下镜头跟 LENS_FOCUS_DISTANCE 走，
 *       没有显式设置就是默认 0.0 = 无穷远，8.6x 长焦下超焦距可达几十米，
 *       主体必糊；而 AUTO 模式没有触发就不动镜头也不扫描 —— 既冻住了 AF，
 *       又完全不需要回写焦距，没有对焦跑掉的风险。
 *
 * ============================================================
 * dex 类名对照（jadx 改过名，已在原始 dex 里 grep 验证过 descriptor）
 * ============================================================
 *   jadx p081l9.C1429o0  ==  dex Ll9/o0;      （CaptureRequestBuilder）
 *        .j(int, Builder)  -> applyAfMode: focusMode=...
 *        .k(int, Builder)  -> applyAfTrigger: ...
 *   MasterLiveModule 未被混淆，startAutoZoom 是 public。
 */
public final class MasterLiveTune implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "运镜tune: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    private static final String C_ZOOM = "com.android.camera.features.mode.masterlive"
            + ".MasterLiveModule";
    /** dex Ll9/o0; （jadx 叫 p081l9.C1429o0） */
    private static final String C_RQB = "l9.o0";

    private static ClassLoader sCl;
    private static boolean sInstalled;

    // ---- config ----
    private static volatile boolean sCfgDone;
    private static volatile float sZoomDur;       // 0 = 不改
    private static volatile int sAfLock;          // 0 关 / 1 跳过重下发 / 2 冻结为 AUTO
    private static volatile float sAfWin = 2.0f;  // 秒

    // ---- 窗口状态 ----
    private static volatile long sWinEnd;         // SystemClock.uptimeMillis()
    private static volatile int sLogBudget;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !C_CAMERA_PKG.equals(lpparam.packageName)) {
            return;
        }
        sCl = lpparam.classLoader;
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        ensureCfg();
        log("installed zoomdur=" + sZoomDur + " aflock=" + sAfLock + " afwin=" + sAfWin);

        // ① 运镜动画时长 + 开 AF 窗口
        try {
            XposedHelpers.findAndHookMethod(C_ZOOM, sCl, "startAutoZoom",
                    Float.TYPE, Float.TYPE, Float.TYPE, Integer.TYPE, Boolean.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int ev = ((Integer) param.args[3]).intValue();
                                if (ev != 2) {
                                    return;   // 只动快门触发的那一路
                                }
                                float old = ((Float) param.args[2]).floatValue();
                                if (sZoomDur > 0f && Math.abs(old - sZoomDur) > 0.001f) {
                                    param.args[2] = Float.valueOf(sZoomDur);
                                    log("zoomdur " + old + " -> " + sZoomDur + "s (ev=2)");
                                }
                                if (sAfLock != 0) {
                                    sWinEnd = android.os.SystemClock.uptimeMillis()
                                            + (long) (sAfWin * 1000f);
                                    sLogBudget = 6;
                                    log("afwin open " + sAfWin + "s mode=" + sAfLock);
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook startAutoZoom ok");
        } catch (Throwable th) {
            log("!! hook startAutoZoom 失败 " + th);
        }

        // ② applyAfMode
        try {
            XposedHelpers.findAndHookMethod(C_RQB, sCl, "j",
                    Integer.TYPE, android.hardware.camera2.CaptureRequest.Builder.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int mode = sAfLock;
                                if (mode == 0 || !winOpen()) {
                                    return;
                                }
                                Object raw = param.args[0];
                                android.hardware.camera2.CaptureRequest.Builder b =
                                        (android.hardware.camera2.CaptureRequest.Builder) param.args[1];
                                if (raw == null || b == null) {
                                    return;
                                }
                                int want = ((Integer) raw).intValue();
                                if (want == -1) {
                                    return;   // 原方法：-1 = 不下发
                                }
                                if (mode == 1) {
                                    Object cur = b.get(
                                            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE);
                                    if (cur instanceof Integer
                                            && ((Integer) cur).intValue() == want) {
                                        param.setResult(null);   // 跳过原方法
                                        budget("afmode 跳过重复下发 " + want);
                                    }
                                } else {
                                    if (want == 1) {
                                        return;   // 已经是 AUTO
                                    }
                                    param.args[0] = Integer.valueOf(1);   // 冻结
                                    budget("afmode " + want + " -> 1 (AUTO 冻结)");
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook applyAfMode ok");
        } catch (Throwable th) {
            log("!! hook applyAfMode 失败 " + th);
        }
    }

    private static boolean winOpen() {
        long e = sWinEnd;
        return e != 0L && android.os.SystemClock.uptimeMillis() < e;
    }

    /** applyAfMode 会在窗口内被高频调用，限流打日志 */
    private static void budget(String msg) {
        int b = sLogBudget;
        if (b > 0) {
            sLogBudget = b - 1;
            log(msg);
            if (b == 1) {
                log("(后续同类日志已静音)");
            }
        }
    }

    /** 读 config.conf 的 legend.zoomdur / legend.aflock / legend.afwin；只读一次 */
    private static synchronized void ensureCfg() {
        if (sCfgDone) {
            return;
        }
        sCfgDone = true;
        String[] paths = {
                "/data/data/com.android.camera/files/camport/config.conf",
                "/sdcard/Android/data/com.android.camera/files/camport/config.conf",
                "/storage/emulated/0/Android/data/com.android.camera/files/camport/config.conf",
                "/data/local/tmp/camport/config.conf"};
        String chosen = null;
        for (int i = 0; i < paths.length; i++) {
            File f = new File(paths[i]);
            if (f.isFile() && f.canRead()) {
                chosen = paths[i];
                break;
            }
        }
        if (chosen == null) {
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(chosen));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.length() == 0 || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                try {
                    if ("legend.zoomdur".equals(k)) {
                        sZoomDur = Float.parseFloat(v);
                    } else if ("legend.aflock".equals(k)) {
                        sAfLock = Integer.parseInt(v);
                    } else if ("legend.afwin".equals(k)) {
                        sAfWin = Float.parseFloat(v);
                    }
                } catch (Throwable ignore) {
                }
            }
            if (sAfWin <= 0f) {
                sAfWin = 2.0f;
            }
            log("cfg=" + chosen + " zoomdur=" + sZoomDur
                    + " aflock=" + sAfLock + " afwin=" + sAfWin);
        } catch (Throwable th) {
            err(th);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static FileWriter sWriter;
    private static String sLastError;

    private static void log(String msg) {
        try {
            Log.i(TAG, HEAD + msg);
        } catch (Throwable ignore) {
        }
        try {
            if (sWriter == null) {
                File f = new File(
                        "/sdcard/Android/data/com.android.camera/files/camport",
                        "camport_fix.log");
                File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                        java.util.Locale.US).format(new java.util.Date())
                        + " I/运镜tune: " + msg + "\n");
                sWriter.flush();
            }
        } catch (Throwable th) {
            sWriter = null;
        }
    }

    private static void err(Throwable th) {
        String msg = String.valueOf(th);
        if (msg.equals(sLastError)) {
            return;
        }
        sLastError = msg;
        log("ERROR " + msg);
    }
}
