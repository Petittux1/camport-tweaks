package com.pudding.camport.hooks;

import android.animation.ValueAnimator;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 问题二诊断探针（纯观察，不改任何返回值）。
 *
 * 目的：把「点击变焦档位」在本机实际走的分支抓出来。
 * 静态结论（已用 DEX 字节码证实）：
 *   - p0.Ns() 是唯一「非线性平滑」路径，它的门控 p0.As() = (Kr.i.f != null)
 *   - Kr.i.e / Kr.i.f 在全部 10 个 dex 里**没有任何 sput-object** -> 从未被赋值
 *     -> As() 恒 false -> Ns() 任何设备上都不会走
 *   - 那么平滑只可能来自 p0.Ms()（ValueAnimator 步进，update 里逐帧 Js()）
 *     或 p0.Js()（直发，硬切），二者时长/分支由一组静态开关决定：
 *       F.f0()  F.P()  g().N()  Pe.b.E()/F()/Y1()  K2.b.b0()
 * 本 hook 就是去实测这些开关 + 实际调用序列 + 动画真实时长。
 *
 * config.conf:
 *   legend.zoompath=1   0=关
 */
public final class ZoomPathProbe implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "变焦路径: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    /** jadx H4/p0 -> dex LH4/p0;（方法名无 renamed from，Ds/Is/Ms/Js/Ns 均为 dex 真名） */
    private static final String C_P0 = "H4.p0";
    /** dex LKr/i; */
    private static final String C_ZOOM_UTIL = "Kr.i";
    private static final String C_FEAT = "com.android.camera.data.data.F";
    private static final String C_DEV = "Pe.b";
    private static final String C_G = "p053g2.a";
    private static final String C_LENS = "com.android.camera.data.data.s";
    /** jadx p059i9.e -> dex i9.e（pNNNxxx -> xxx 规律） */
    private static final String C_ZM = "i9.e";
    /** jadx AbstractC0853u -> dex com.android.camera.module.u（jadx super/* 注释真名） */
    private static final String C_MOD_BASE = "com.android.camera.module.u";
    private static final String C_MOD_CAPTURE =
            "com.android.camera.features.mode.capture.CaptureModule";
    private static final String C_N = "com.android.camera.data.data.n";
    private static final String C_I = "com.android.camera.data.data.i";
    private static final String C_S = C_LENS;
    /** dex LQg/c; —— 可用 camera id 列表 */
    private static final String C_CAMIDS = "Qg.c";
    /** jadx p181v6.f -> dex v6.f —— 相机配置（含 f0/c0/Z/e0 谓词与 q/K/j/M） */
    private static final String C_CFG = "v6.f";

    private static ClassLoader sCl;
    private static boolean sInstalled;
    private static volatile boolean sCfgDone;
    private static volatile boolean sEnabled = true;
    /**
     * 强制 app 变焦动画时长（毫秒）。0 = 不干预。
     * 用于「夸张测试」确认 Kr.i.m/n 造出的 ValueAnimator 是否就是肉眼看到的动画。
     */
    private static volatile long sDurMs;

    /** 最近一次由 Kr.i.m / Kr.i.n 造出来的动画，用来在 Ms/Ns 返回后读真实 duration */
    private static volatile ValueAnimator sLast;
    private static volatile String sLastTag = "-";

    private static volatile boolean sGatesDumped;
    private static volatile String sLastError;

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
            log("已关闭 (legend.zoompath=0)");
            return;
        }

        hookPoint(C_P0, "Ds", new Object[]{Integer.TYPE, Integer.TYPE},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        dumpGates("Ds");
                        log("Ds(idx=" + p.args[0] + ", src=" + p.args[1] + ")");
                    }
                });
        hookPoint(C_P0, "Is", new Object[]{Float.TYPE, Integer.TYPE},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        dumpGates("Is");
                        log("Is(cur=" + p.args[0] + ", idx=" + p.args[1] + ")");
                    }
                });
        hookPoint(C_P0, "Ms", new Object[]{Float.TYPE, Float.TYPE},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        sLastTag = "Ms";
                        log("Ms(from=" + p.args[0] + ", to=" + p.args[1] + ")");
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        logDur("Ms");
                    }
                });
        hookPoint(C_P0, "Js", new Object[]{Float.TYPE, Integer.TYPE},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        log("Js(ratio=" + p.args[0] + ", src=" + p.args[1] + ")");
                    }
                });
        hookPoint(C_P0, "Ns", new Object[]{Float.TYPE, Integer.TYPE, Boolean.TYPE},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        sLastTag = "Ns";
                        log("!! Ns(ratio=" + p.args[0] + ", src=" + p.args[1]
                                + ", anim=" + p.args[2] + ")   <-- 非线性平滑路径");
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        logDur("Ns");
                    }
                });
        hookPoint(C_P0, "As", new Object[]{},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        log("As() = " + p.getResult());
                    }
                });
        hookPoint(C_P0, "vs", new Object[]{Integer.TYPE},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        log("vs(mode=" + p.args[0] + ") = " + p.getResult());
                    }
                });

        hookPoint(C_ZOOM_UTIL, "m", new Object[]{Float.TYPE, Float.TYPE}, animFactory("m"));
        hookPoint(C_ZOOM_UTIL, "n", new Object[]{Float.TYPE, Float.TYPE}, animFactory("n"));

        hookPoint(C_FEAT, "f0", new Object[]{}, simpleGate(C_FEAT + ".f0"));
        hookPoint(C_FEAT, "P", new Object[]{Integer.TYPE}, simpleGate(C_FEAT + ".P"));
        hookPoint(C_LENS, "p", new Object[]{Integer.TYPE}, simpleGate(C_LENS + ".p"));
        hookPoint(C_DEV, "E", new Object[]{}, simpleGate(C_DEV + ".E"));
        hookPoint(C_DEV, "F", new Object[]{}, simpleGate(C_DEV + ".F"));
        hookPoint(C_DEV, "Y1", new Object[]{Integer.TYPE}, simpleGate(C_DEV + ".Y1"));

        // ---- 换镜头门控（问题二核心）----
        // jadx p059i9.e -> dex i9.e
        hookPoint(C_ZM, "L8", new Object[]{Integer.TYPE, Float.TYPE, Float.TYPE},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        log("!! L8/onInterceptZooming(act=" + p.args[0] + ", from="
                                + p.args[1] + ", to=" + p.args[2] + ") = " + p.getResult());
                    }
                });
        hookPoint(C_ZM, "V0", new Object[]{}, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                log("LensSwitchZoomBounds V0() = " + p.getResult());
            }
        });
        hookPoint(C_ZM, "U0", new Object[]{}, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                log("U0() = " + p.getResult());
            }
        });
        // jadx AbstractC0853u -> dex com.android.camera.module.u（jadx super/* 注释已给出真名）
        hookPoint(C_MOD_BASE, "isCameraSwitchingDuringZoomingAllowed", new Object[]{},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        log("!! base.isCameraSwitchingDuringZoomingAllowed() = "
                                + p.getResult() + "  mode=" + modeOf(p.thisObject));
                    }
                });
        hookPoint(C_MOD_CAPTURE, "isCameraSwitchingDuringZoomingAllowed", new Object[]{},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        log("!! CaptureModule.isCameraSwitchingDuringZoomingAllowed() = "
                                + p.getResult());
                    }
                });
        hookPoint(C_N, "l0", new Object[]{}, simpleGate(C_N + ".l0"));
        hookPoint(C_I, "M0", new Object[]{Integer.TYPE}, simpleGate(C_I + ".M0"));
        hookPoint(C_S, "i", new Object[]{Integer.TYPE}, simpleGate(C_S + ".i"));

        // ---- 长焦边界缺失诊断（LensSwitchZoomBounds 只有 [0.7,1.0]）----
        // Kr.i.e() = LensSwitchZoomBounds：对 Qg.c.b() 每个 id
        //   f0(id)->j() / c0(id)->1.0 / Z(id)->h() / e0(id)->i()
        hookPoint(C_ZOOM_UTIL, "e", new Object[]{}, simpleGate("Kr.i.e(bounds)"));
        hookPoint(C_ZOOM_UTIL, "f", new Object[]{}, simpleGate("Kr.i.f(zoomable)"));
        hookPoint(C_ZOOM_UTIL, "h", new Object[]{}, simpleGate("Kr.i.h(teleH)"));
        hookPoint(C_ZOOM_UTIL, "i", new Object[]{}, simpleGate("Kr.i.i(teleI)"));
        hookPoint(C_ZOOM_UTIL, "j", new Object[]{}, simpleGate("Kr.i.j(uw)"));
        hookPoint(C_CAMIDS, "b", new Object[]{}, simpleGate("Qg.c.b(cameraIds)"));
        hookPoint(C_CFG, "f0", new Object[]{Integer.TYPE}, simpleGate("v6.f.f0(isUw)"));
        hookPoint(C_CFG, "c0", new Object[]{Integer.TYPE}, simpleGate("v6.f.c0(isMain)"));
        hookPoint(C_CFG, "Z", new Object[]{Integer.TYPE}, simpleGate("v6.f.Z(isTeleZ)"));
        hookPoint(C_CFG, "e0", new Object[]{Integer.TYPE}, simpleGate("v6.f.e0(isTeleE0)"));

        log("探针已挂: " + C_P0 + "{Ds,Is,Ms,Js,Ns,As,vs} + " + C_ZOOM_UTIL + "{m,n} + 开关"
                + " {F.f0,F.P,s.p,Pe.b.E,Pe.b.F,Pe.b.Y1}"
                + " + 换镜头 {L8,V0,base/Capture.isCameraSwitching,l0,M0,h,j,s.i}");
    }

    private static String modeOf(Object module) {
        try {
            return String.valueOf(XposedHelpers.getIntField(module, "mModuleIndex"));
        } catch (Throwable th) {
            return "?";
        }
    }

    private static XC_MethodHook animFactory(final String which) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
            Object r = p.getResult();
            if (r instanceof ValueAnimator) {
                ValueAnimator va = (ValueAnimator) r;
                sLast = va;
                long raw = va.getDuration();
                String extra = "";
                if (sDurMs > 0 && raw != sDurMs) {
                    try {
                        va.setDuration(sDurMs);
                        extra = "  -> 强制 " + va.getDuration() + "ms (legend.zoomdur_ms)";
                    } catch (Throwable th) {
                        extra = "  -> 强制失败 " + th;
                    }
                }
                log("Kr.i." + which + "() 造出动画 rawDuration=" + raw + "ms" + extra);
            }
            }
        };
    }

    private static XC_MethodHook simpleGate(final String name) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                StringBuilder sb = new StringBuilder(name).append('(');
                for (int i = 0; i < p.args.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(p.args[i]);
                }
                sb.append(") = ").append(p.getResult());
                log(sb.toString());
            }
        };
    }

    private static void logDur(String which) {
        ValueAnimator a = sLast;
        if (a == null) {
            log(which + " 之后 sLast=null（Kr.i.m/n 未命中）");
            return;
        }
        long d;
        try {
            d = a.getDuration();
        } catch (Throwable th) {
            log(which + " getDuration 失败 " + th);
            return;
        }
        log(which + " 实际 duration = " + d + "ms  (last=" + sLastTag + ")");
    }

    /** 静态开关一次就够，但每次都补一行 As/Kr.i.f 状态便于对齐时间线 */
    private static void dumpGates(String via) {
        if (sGatesDumped) {
            return;
        }
        sGatesDumped = true;
        log("--- 门控实测 (via " + via + ") ---");
        log("  F.f0()            = " + call(C_FEAT, "f0"));
        log("  K2.b.b0()         = " + call("K2.b", "b0"));
        log("  F.g0()            = " + call(C_FEAT, "g0"));
        log("  F.Z()             = " + call(C_FEAT, "Z"));
        log("  Pe.b.E()          = " + call(C_DEV, "E"));
        log("  Pe.b.F()          = " + call(C_DEV, "F"));
        log("  Pe.b.Y1(163)      = " + call(C_DEV, "Y1", Integer.valueOf(163)));
        log("  Pe.b.M1()         = " + call(C_DEV, "M1"));
        try {
            Object g = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(C_G, sCl), "g");
            log("  g().N()           = " + XposedHelpers.callMethod(g, "N"));
            log("  g().R()           = " + XposedHelpers.callMethod(g, "R"));
            log("  g().L()           = " + XposedHelpers.callMethod(g, "L"));
        } catch (Throwable th) {
            log("  g() 读取失败 " + th);
        }
        log("  Kr.i.f            = " + staticField(C_ZOOM_UTIL, "f"));
        log("  Kr.i.e            = " + staticField(C_ZOOM_UTIL, "e"));
        log("  --------------------------------");
    }

    private static String call(String cls, String m, Object... args) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, sCl);
            return String.valueOf(XposedHelpers.callStaticMethod(c, m, args));
        } catch (Throwable th) {
            return "err:" + th;
        }
    }

    private static String staticField(String cls, String name) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, sCl);
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(null);
            if (v == null) {
                return "null";
            }
            return v.getClass().getName() + "@" + Integer.toHexString(v.hashCode());
        } catch (Throwable th) {
            return "err:" + th;
        }
    }

    private static void hookPoint(String cls, String m, Object[] sig, XC_MethodHook cb) {
        try {
            Object[] full = new Object[sig.length + 1];
            System.arraycopy(sig, 0, full, 0, sig.length);
            full[sig.length] = cb;
            XposedHelpers.findAndHookMethod(cls, sCl, m, full);
        } catch (Throwable th) {
            log("!! hook 失败 " + cls + "." + m + " -> " + th);
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
                    if ("legend.zoompath".equals(k)) {
                        sEnabled = !v.equals("0");
                    } else if ("legend.zoomdur_ms".equals(k)) {
                        try {
                            sDurMs = Long.parseLong(v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                log("cfg=" + f.getPath() + " enabled=" + sEnabled + " durMs=" + sDurMs);
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

    private static void log(String msg) {
        try {
            Log.i(TAG, HEAD + msg);
        } catch (Throwable ignore) {
        }
        try {
            if (sWriter == null) {
                File f = new File(
                        "/sdcard/Android/data/com.android.camera/files/camport",
                        "zoompath.log");
                File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(String.format(Locale.US, "%s I/变焦路径: %s%n",
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
