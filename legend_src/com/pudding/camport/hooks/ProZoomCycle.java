package com.pudding.camport.hooks;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
 * 专业模式(167)「重复点按当前档位」改成循环放大（与拍照 5→10→20 一致）。
 *
 * onClick 里专业(167)重按拐进 L39e，它有两条子路：
 *   满足条件 -> H4.m0.M.Ks(tag, 0)
 *   否则     -> H4.m0.M.Ws()
 * 两条都会弹变焦轮盘。拍照(163)重按则走 L32d: n() -> setComponentValue -> N.Ps()。
 *
 * 本 hook：onClick 捕获视图/重按判定(mode==idx?)，再同时钩 Ks/Ws，
 * 命中「专业重按」就把「弹轮盘」换成「循环+复用相机 N.Ps 放大」。
 * 失败模式安全：任何字段/方法不符 -> 抛异常被 catch -> 不 setResult -> 退回原轮盘。
 *
 * 复用 legend.zoomcycle*（无新键）：zoomcycle=0 全关；modes 不含167 则专业不循环；
 * min 以下不介入(保留低倍轮盘)。
 */
public final class ProZoomCycle implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "专业变焦循环: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    private static final String C_TOGGLE = "com.android.camera.ui.zoom.ZoomRatioToggleView";
    private static final String C_WHEEL = "H4.m0";
    private static final String C_Y0 = "v2.y0";
    private static final int PRO_MODE = 167;

    private static ClassLoader sCl;
    private static boolean sInstalled;

    private static volatile View sView;
    private static volatile boolean sInClick;
    private static volatile boolean sRetap;
    private static volatile int sIdx = -1;
    private static volatile int sMode = -1;

    private static volatile boolean sCfgDone;
    private static volatile boolean sEnabled = true;
    private static volatile float sMin = 3.0f;
    private static volatile LinkedHashSet<Integer> sModes = modes("163,167");

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
            log("已关闭 (legend.zoomcycle=0)");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(C_TOGGLE, sCl, "onClick", View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                ViewGroup parent = (ViewGroup) param.thisObject;
                                View clicked = (View) param.args[0];
                                sView = parent;
                                sIdx = parent.indexOfChild(clicked);
                                sMode = XposedHelpers.getIntField(parent, "q");
                                int sel = XposedHelpers.getIntField(parent, "o");
                                sRetap = (sIdx >= 0 && sIdx == sel);
                                sInClick = true;
                                log("click mode=" + sMode + " idx=" + sIdx
                                        + " sel=" + sel + " retap=" + sRetap);
                            } catch (Throwable th) {
                                sRetap = false;
                                sInClick = false;
                                err(th);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sInClick = false;
                        }
                    });
            // Ks(int,int) —— L39e 的一条子路
            XposedHelpers.findAndHookMethod(C_WHEEL, sCl, "Ks",
                    Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sInClick) {
                                return;
                            }
                            try {
                                log("Ks flag=" + param.args[1] + " retap=" + sRetap
                                        + " mode=" + sMode);
                                if (sRetap && sMode == PRO_MODE && cycle()) {
                                    param.setResult(null);
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            // Ws() —— L39e 的另一条子路（无参）
            XposedHelpers.findAndHookMethod(C_WHEEL, sCl, "Ws", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sInClick) {
                        return;
                    }
                    try {
                        log("Ws retap=" + sRetap + " mode=" + sMode);
                        if (sRetap && sMode == PRO_MODE && cycle()) {
                            param.setResult(null);
                        }
                    } catch (Throwable th) {
                        err(th);
                    }
                }
            });
            log("hook onClick+Ks+Ws ok  modes=" + sModes + " min=" + sMin);
        } catch (Throwable th) {
            log("!! hook 失败 " + th);
        }
    }

    /** 专业重按的循环；成功返回 true（调用方据此抑制轮盘）。任何异常向上抛由调用方 catch。 */
    private static boolean cycle() {
        if (!sModes.contains(Integer.valueOf(PRO_MODE))) {
            return false;
        }
        View v = sView;
        if (v == null) {
            return false;
        }
        // 先取 N（高风险字段），失败就不动数值
        Object n = XposedHelpers.getObjectField(v, "N");
        Object registry = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("g2.a", sCl), "j");
        Object y0 = XposedHelpers.callMethod(registry, "x",
                XposedHelpers.findClass(C_Y0, sCl));
        String cur = (String) XposedHelpers.callMethod(y0,
                "getComponentValue", Integer.valueOf(PRO_MODE));
        if (cur == null) {
            return false;
        }
        float f;
        try {
            f = Float.parseFloat(cur.trim());
        } catch (Throwable th) {
            return false;
        }
        if (f < sMin) {
            return false;
        }
        String next = (String) XposedHelpers.callMethod(y0, "n",
                Integer.valueOf(PRO_MODE));   // ZoomCycleFix 的循环钩子算下一档
        if (next == null || next.equals(cur)) {
            return false;
        }
        XposedHelpers.callMethod(y0, "setComponentValue",
                Integer.valueOf(PRO_MODE), next);
        XposedHelpers.callMethod(n, "Ps",
                Float.valueOf(Float.parseFloat(next)), Integer.valueOf(sIdx));
        log("mode=" + PRO_MODE + " idx=" + sIdx + " cur=" + cur + " -> " + next);
        return true;
    }

    private static LinkedHashSet<Integer> modes(String csv) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (String t : csv.split(",")) {
            try {
                set.add(Integer.valueOf(t.trim()));
            } catch (Throwable ignore) {
            }
        }
        return set.isEmpty() ? modes("163,167") : set;
    }

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
                    if ("legend.zoomcycle".equals(k)) {
                        sEnabled = !v.equals("0");
                    } else if ("legend.zoomcycle_min".equals(k)) {
                        sMin = Float.parseFloat(v);
                    } else if ("legend.zoomcycle_modes".equals(k)) {
                        sModes = modes(v);
                    }
                } catch (Throwable ignore) {
                }
            }
            log("cfg=" + chosen + " enabled=" + sEnabled + " modes=" + sModes
                    + " min=" + sMin);
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
                        "zoomcycle.log");
                File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(String.format(Locale.US, "%s I/专业变焦循环: %s%n",
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
