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
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * 专业模式(167) 点「2.6」后选中项弹回「1」的索引错乱（问题二）。
 *
 * ============================================================
 * 病因（2026-10-08 实测闭环：key.log + sc_two.log pro45 窗口）
 * ============================================================
 * 点击 child2 → showZoomChildView(2, action=-1)
 *   → setZoomRatio(BY_LENS, i=2, z=2.6, ti=2)        ← ti=2 说明 remap 后按值算没错
 *   → ZoomRatioToggleView.X(18)：
 *        · i==18 的忽略条件是 i.n1(167,false)——pandora 上恒 false
 *          （isSupportProSAT 等设备位与源机不同）→ 不忽略，要重算索引；
 *        · this.g0 = (mode==167 && !n.l0()) && !n1(167) = true
 *          → 重算走 getLensZoomIndex()（镜头索引），不是按 zoom 值。
 *   → getLensZoomIndex()：镜头组件 data.n.h(167) 实测一直是 "wide"
 *        （switchLensInPro 因 f2 < 本机 tele 门槛 i.h() 从不把组件切成 tele，
 *          日志里没有 setComponentValue mode=167, lensType= 行），
 *        wide 分支 D()=false（isSupportOpticalZoom: false）→ fI=1.0 → index=1。
 *   → showZoomChildView(targetChildIndex=1, action=18) → this.o=1，
 *        而 z=2.6 / ti=2 → setZoomRatio i=1, z=2.6, ti=2 索引错乱循环。
 * 实测日志（17:44:45.909 点 2.6）：
 *   setZoomRatio a=...BY_LENS, i=2, z=2.6, ti=2
 *   getLensZoomIndex() index = 1            ← 错
 *   showZoomChildView(): targetChildIndex：1 ... action：18   ← 弹回
 *   setZoomRatio a=...BY_LENS, i=1, z=2.6, ti=2
 * 拍照(163) 不受影响：g0=false 且 action=0 被 X() 开头直接忽略。
 * 相机侧 zoom 下发本身正常（applyZoomRatio-R cameraId 2 zoomRatio 2.6）。
 *
 * ============================================================
 * 修法（最小侵入：只改 getLensZoomIndex 一个方法）
 * ============================================================
 * mode 命中（默认 167）时，getLensZoomIndex() 改按「当前 zoom 值」算索引：
 *     o(this.a, false, this.p, this.q)
 * ——与 g0=false 分支（X():2176、M():764/822、P():1708）同源同参。
 *   p=2.6 → 2（与 setZoomRatio 的 ti 实测一致）→ showZoomChildView(2) 不再弹回；
 *   0.7/1.0 值索引与镜头索引本来就相同（实测 0/1），行为不变；
 *   焊死后不碰 n1() 的其它调用点（N6/w、N6/q、Q4/G 等组件选择逻辑）。
 *
 * config.conf：
 *   legend.prolens=0        关（默认开）
 *   legend.prolens_modes=167  逗号分隔，默认 167
 */
public final class ProLensFix implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "Pro镜头索引: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    private static final String C_ZOOM_VIEW =
            "com.android.camera.ui.zoom.ZoomRatioToggleView";

    private static ClassLoader sCl;
    private static boolean sInstalled;

    private static volatile boolean sCfgDone;
    private static volatile boolean sEnabled = true;
    private static volatile LinkedHashSet<Integer> sModes = modes("167");

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam)
            throws Throwable {
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
            log("已关闭 (legend.prolens=0)");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(C_ZOOM_VIEW, sCl,
                    "getLensZoomIndex", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param)
                                throws Throwable {
                            try {
                                apply(param);
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook " + C_ZOOM_VIEW
                    + ".getLensZoomIndex() ok  modes=" + sModes);
        } catch (Throwable th) {
            log("!! hook 失败 " + th);
        }
    }

    private static void apply(XC_MethodHook.MethodHookParam param) {
        Object self = param.thisObject;
        int mode = XposedHelpers.getIntField(self, "q");
        if (!sModes.contains(Integer.valueOf(mode))) {
            return;
        }
        float p = ((Float) XposedHelpers.getObjectField(self, "p")).floatValue();
        Object origObj = param.getResult();
        int orig = (origObj instanceof Integer) ? ((Integer) origObj).intValue()
                : -1;
        if (p <= 0f || orig < 0) {
            return;   // 视图还没初始化 zoom：保持原样
        }
        // 与 g0=false 分支同源：o(this.a, false, this.p, this.q)
        boolean a = ((Boolean) XposedHelpers.getObjectField(self, "a")).booleanValue();
        Object fixObj = XposedHelpers.callMethod(self, "o",
                Boolean.valueOf(a), Boolean.FALSE,
                Float.valueOf(p), Integer.valueOf(mode));
        int fix = (fixObj instanceof Integer) ? ((Integer) fixObj).intValue()
                : -1;
        if (fix < 0 || fix == orig) {
            return;
        }
        param.setResult(Integer.valueOf(fix));
        log("mode=" + mode + " getLensZoomIndex 镜头=" + orig
                + " -> 按值=" + fix + " (p=" + p + ")  已修正");
    }

    private static LinkedHashSet<Integer> modes(String csv) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (String t : csv.split(",")) {
            try {
                set.add(Integer.valueOf(t.trim()));
            } catch (Throwable ignore) {
            }
        }
        return set.isEmpty() ? modes("167") : set;
    }

    /** 读 config.conf 的 legend.prolens*；只读一次 */
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
                    if ("legend.prolens".equals(k)) {
                        sEnabled = !v.equals("0");
                    } else if ("legend.prolens_modes".equals(k)) {
                        sModes = modes(v);
                    }
                } catch (Throwable ignore) {
                }
            }
            log("cfg=" + chosen + " enabled=" + sEnabled + " modes=" + sModes);
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
                        "prolens.log");
                File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(String.format(Locale.US, "%s I/Pro镜头索引: %s%n",
                        new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                                Locale.US).format(new java.util.Date()), msg));
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
