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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 问题二：点档位跨镜头时「硬切/闪跳」-> 应该无感切换。
 *
 * ============================================================
 * 真机实测根因（2026-10-04，17 Pro / mode=163）
 * ============================================================
 * 每一帧变焦都会调 ZoomManager.L8() = onInterceptZoomingEvent()，
 * 但结果恒 false：
 *   MCAM_ZoomManager: onInterceptZoomingEvent():
 *       current status not support switch camera lens.
 *   !! base.isCameraSwitchingDuringZoomingAllowed() = false  mode=163
 *   !! CaptureModule.isCameraSwitchingDuringZoomingAllowed() = false
 *   !! L8/... = false          （每个动画帧都是 false）
 *   logcat 里 **一条 switchCameraLens(): 都没有**
 *
 * 于是 K7()/w9() 从不执行，跨镜头时 app 不接管，
 * 直接把倍率灌给 HAL -> HAL 自己硬切 -> 肉眼可见的跳变。
 *
 * 链路（CaptureModule.isCameraSwitchingDuringZoomingAllowed）：
 *   if (K2.g.y() && mCameraManager.T() != null)
 *         return T().a != f.R().u();
 *   if (!n.l0() || mCameraManager.b0()) return super;   // <-- 本机走这里
 *   return true;
 * 本机实测：
 *   n.l0() = false          -> 分辨率组件 C1120e0 非空但值是 VALUE_OFF/VALUE_AUTO
 *                              （M() = !(OFF||AUTO)，拍照默认 AUTO）
 *   super = AbstractC0853u：
 *       if (s.i(mode)) { ... }   s.i(163) = e.v4() && mode==173  -> **false**
 *       ... mModuleIndex != 175 -> return false
 *   => 恒 false
 *
 * 18 Pro Max 上走的是 switchCameraLens() 分支（switchCameraLens():
 *    t->w / w->t / uw->w ... + LensSwitchZoomBounds）。
 *
 * ============================================================
 * 本 hook 做什么
 * ============================================================
 * 只在拍照模式（legend.lensswitch_modes，默认 163）把
 * CaptureModule.isCameraSwitchingDuringZoomingAllowed() 的结果强制为 true，
 * 让 L8 -> K7() -> w9() 走 app 自己的换镜头流程。
 * 其它模块 / 其它模式一律原样放行。
 *
 * config.conf:
 *   legend.lensswitch=1        0=关
 *   legend.lensswitch_modes=163
 *   legend.lensswitch_minzoom=1.5   低于此倍率不干预（0=全开）
 *
 * ============================================================
 * 按焦段放行（2026-10-05 实测）
 * ============================================================
 * 全开时：
 *   2.6->5  不模糊，跳动减轻（长焦方向有效）
 *   0.7<->1 半秒模糊完全不可接受（超广角方向有害）
 * 超广角模糊的原因是 app 的 switchCameraLens() 会 close->open，
 * HAL 报 Device error 4/5 -> 预览重启。长焦不走这条路径。
 * 故只在倍率 >= legend.lensswitch_minzoom（默认 1.5，正好卡在
 * 1x 与 2x 之间）时强制 true；0.7<->1 恒 <=1.0 一律原样放行。
 */
public final class LensSwitchFix implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "换镜头: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    /** 拍照模块（jadx com.android.camera.features.mode.capture.CaptureModule） */
    private static final String C_CAPTURE = "com.android.camera.features.mode.capture.CaptureModule";
    /** 基类（jadx AbstractC0853u -> dex com.android.camera.module.u） */
    private static final String C_BASE = "com.android.camera.module.u";
    private static final String M_ALLOWED = "isCameraSwitchingDuringZoomingAllowed";
    /** jadx p181v6.f -> dex v6.f（相机角色表，q()/K()/j()/e()） */
    private static final String C_CFG = "v6.f";

    private static ClassLoader sCl;
    private static boolean sInstalled;
    private static volatile boolean sCfgDone;
    private static volatile boolean sEnabled = true;
    private static volatile String sModes = "163";
    private static volatile boolean sTele = true;
    /** 低于此倍率不干预（默认 1.5，正好落在 1x 与 2x 之间） */
    private static volatile float sMinZoom = 1.5f;
    private static float sLoggedZoom = Float.NaN;
    private static int sZLog;

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
        if (!sEnabled) {
            log("已关闭 (legend.lensswitch=0)");
            return;
        }
        hook(C_CAPTURE, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                int mode = modeOf(p.thisObject);
                if (!wanted(mode)) {
                    return;
                }
                if (!allowByZoom(p.thisObject, "CaptureModule")) {
                    return;
                }
                p.setResult(Boolean.TRUE);
                log("CaptureModule.isCameraSwitchingDuringZoomingAllowed() "
                        + "false -> true  mode=" + mode);
            }
        });
        hook(C_BASE, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                int mode = modeOf(p.thisObject);
                if (!wanted(mode)) {
                    return;
                }
                if (!allowByZoom(p.thisObject, "base")) {
                    return;
                }
                p.setResult(Boolean.TRUE);
                log("base.isCameraSwitchingDuringZoomingAllowed() false -> true mode=" + mode);
            }
        });
        log("hook " + C_CAPTURE + " + " + C_BASE + "." + M_ALLOWED + "() ok  modes=" + sModes);
        hookTele();
    }

    /**
     * 问题二补充：长焦边界缺失。
     *
     * 实测本机 roleId 映射（p181v6.e.f18025h）：
     *   roleId 0  -> 2   主摄    (e()  -> R().e())
     *   roleId 21 -> 3   超广角  (j()  -> R().j())
     *   roleId 23 -> 4   长焦 5x (K()  -> R().K())   M(4).E() = 5.0
     *   roleId 20 -> -1  aux     (q()  -> R().q())   **本机没有**
     *
     * 但换镜头链路三处都只认 roleId 20：
     *   Qg.c.b()  : if ((V1() || A1()) && R().q() >= 0) add(R().q())   -> [3,2]，缺长焦
     *   Kr.i.e()  : Z(id) = (id == R().q())                            -> 恒 false
     *              => LensSwitchZoomBounds = [0.7, 1.0]，没有 5.0
     *   K7()      : boolean z3 = f.R().q() > 0;                        -> 恒 false
     *              => 进长焦 / 从长焦回主摄的两条分支全被跳过
     *
     * 结果：跨 5x 时 switchCameraLens() 一次都不触发 -> HAL 硬切。
     *
     * 本 hook：只在 q() 原值 < 0 且 K() >= 0 时，让 q() 回落到 K()。
     * 这样 q()=4 -> Qg.c.b() 含 4 -> Z(4)=true -> e() 加入 h()=5.0
     *        -> z3=true -> w->t / t->w 走 app 的换镜头流程。
     * q() 原值 >= 0 时完全不动（有 aux 的机型不受影响）。
     *
     * config.conf:
     *   legend.telefix=1        0=关
     */
    private static void hookTele() {
        if (!sTele) {
            log("telefix 关闭 (legend.telefix=0)");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(C_CFG, sCl, "q", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Object r = p.getResult();
                    int orig = (r instanceof Number) ? ((Number) r).intValue() : -1;
                    if (orig >= 0) {
                        return;
                    }
                    Object k = XposedHelpers.callMethod(p.thisObject, "K");
                    int kv = (k instanceof Number) ? ((Number) k).intValue() : -1;
                    if (kv < 0) {
                        return;
                    }
                    p.setResult(Integer.valueOf(kv));
                    log("telefix: v6.f.q() " + orig + " -> " + kv + "  (roleId23 ultraTele 5x)");
                }
            });
            log("hook " + C_CFG + ".q() telefix ok");
        } catch (Throwable th) {
            log("!! hook 失败 " + C_CFG + ".q() -> " + th);
        }
    }

    /**
     * 按倍率决定是否干预。返回 true = 允许强制为 true。
     * sMinZoom<=0 时全开；拿不到倍率时按允许处理（不改变原有行为）。
     */
    private static boolean allowByZoom(Object module, String who) {
        if (sMinZoom <= 0f) {
            return true;
        }
        float z = zoomOf(module);
        if (sZLog < 4) {
            sZLog++;
            log("zoomOf=" + String.format(Locale.US, "%.3f", z)
                    + " minzoom=" + sMinZoom + " allow=" + (z < 0f || z >= sMinZoom));
        }
        if (z < 0f) {
            return true;
        }
        if (z < sMinZoom) {
            // 每次跨越只记一条，避免刷屏
            if (Float.isNaN(sLoggedZoom) || (sLoggedZoom >= sMinZoom)) {
                sLoggedZoom = z;
                log("低于 minzoom=" + sMinZoom + " 不干预 " + who + " zoom="
                        + String.format(Locale.US, "%.3f", z));
            }
            return false;
        }
        sLoggedZoom = z;
        return true;
    }

    /** 当前预览倍率（ZoomManager.v()）；失败返回 -1 */
    private static float zoomOf(Object module) {
        try {
            Object zm = XposedHelpers.callMethod(module, "getZoomManager");
            if (zm == null) {
                onceZoomErr("getZoomManager()==null");
                return -1f;
            }
            Object v = XposedHelpers.callMethod(zm, "v");
            if (v instanceof Number) {
                return ((Number) v).floatValue();
            }
            onceZoomErr("ZoomManager.v() 非数值: " + v);
        } catch (Throwable th) {
            onceZoomErr(String.valueOf(th));
        }
        return -1f;
    }

    private static String sZoomErr;

    private static void onceZoomErr(String m) {
        if (sZoomErr == null) {
            sZoomErr = m;
            log("zoomOf 失败: " + m);
        }
    }

    private static boolean wanted(int mode) {
        for (String t : sModes.split(",")) {
            if (t.trim().equals(String.valueOf(mode))) {
                return true;
            }
        }
        return false;
    }

    private static int modeOf(Object module) {
        try {
            return XposedHelpers.getIntField(module, "mModuleIndex");
        } catch (Throwable th) {
            try {
                return XposedHelpers.getIntField(module, "i");
            } catch (Throwable th2) {
                return Integer.MIN_VALUE;
            }
        }
    }

    private static void hook(String cls, XC_MethodHook cb) {
        try {
            XposedHelpers.findAndHookMethod(cls, sCl, M_ALLOWED, cb);
        } catch (Throwable th) {
            log("!! hook 失败 " + cls + "." + M_ALLOWED + " -> " + th);
        }
    }

    private static void ensureCfg() {
        if (sCfgDone) {
            return;
        }
        sCfgDone = true;
        String[] paths = {
                "/data/data/com.android.camera/files/camport/config.conf",
                "/sdcard/Android/data/com.android.camera/files/camport/config.conf",
                "/storage/emulated/0/Android/data/com.android.camera/files/camport/config.conf",
                "/data/local/tmp/camport/config.conf"};
        for (int i = 0; i < paths.length; i++) {
            File f = new File(paths[i]);
            if (!f.isFile() || !f.canRead()) {
                continue;
            }
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(f));
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
                    if ("legend.lensswitch".equals(k)) {
                        sEnabled = !v.equals("0");
                    } else if ("legend.lensswitch_modes".equals(k)) {
                        sModes = v;
                    } else if ("legend.telefix".equals(k)) {
                        sTele = !v.equals("0");
                    } else if ("legend.lensswitch_minzoom".equals(k)) {
                        try {
                            sMinZoom = Float.parseFloat(v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                log("cfg=" + f.getPath() + " enabled=" + sEnabled + " modes=" + sModes
                        + " tele=" + sTele + " minzoom=" + sMinZoom);
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
            return;
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
                        "lensswitch.log");
                File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(String.format(Locale.US, "%s I/换镜头: %s%n",
                        new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                                .format(new Date()),
                        msg));
                sWriter.flush();
            }
        } catch (Throwable th) {
            sWriter = null;
        }
    }

    private static void err(Throwable th) {
        String m = String.valueOf(th);
        if (m.equals(sLastError)) {
            return;
        }
        sLastError = m;
        log("ERROR " + m);
    }
}
