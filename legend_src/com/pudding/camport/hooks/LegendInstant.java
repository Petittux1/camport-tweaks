package com.pudding.camport.hooks;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 传奇一瞬（真一瞬）17Pro 原生安装器。
 *
 * 兄弟的 NativeM9Hook.install() 是 17U001770 专用的全有全无绑定（他的类名在 17Pro002090
 * 已漂移: module.u->t, Sh.s->Sh.t, l7.j->k, l9.o0->n0, l9.y0->z0, q7.g 消失, l1.a.e 消失），
 * 任一 NoSuchMethodException 会把他全部钩子 unhook 掉（真机已证实 0 生效）。
 *
 * 本类按 17Pro 真值表自装 9 组钩子（每组独立 try/catch, 失败只跳过该组并落日志），
 * 钩子体内只用他与相机版本无关的运行时类（NativeM9Routing / NativeM9Container，纯静态逻辑）。
 * 兄弟 dex 原封保留在 classes5, 不改他任何代码。
 *
 * legend.instant=1 启用, 默认 0 关, 不碰任何现有功能。
 */
public class LegendInstant implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "LI: ";
    private static final String PKG = "com.android.camera";
    private static final String[] CFG = {
        "/data/user_de/0/com.android.camera/files/camport/config.conf",
        "/data/user_de/0/com.android.camera/files/camport/legend.conf",
        "/data/data/com.android.camera/files/camport/config.conf",
        "/data/data/com.android.camera/files/camport/legend.conf",
    };
    private static final String LOGP =
        "/sdcard/Android/data/com.android.camera/files/camport/zoomcycle.log";
    private static final int MODE = 256; // LensSwitchHook.MODE_LEICA_INSTANT
    private static boolean sDone = false;
    private static boolean sEnabled = false;

    // 兄弟运行时（我们的 classes5）里与相机版本无关的静态方法
    private static Method sPipe;      // NativeM9Routing.pipelineValue(String,boolean,int)
    private static Method sCfg;       // rememberConfig(Object,boolean)
    private static Method sPipeIn;    // enterPipeline()
    private static Method sPipeOut;   // exitPipeline()
    private static Method sTask;      // rememberTask(Object,boolean)
    private static Method sCopy;      // copyTask(Object,Object)
    private static Method sTitle;     // rememberTitle(Object,int,String)
    private static Method sForTitle;  // selectedForTitle(String)
    private static Method sForTask;   // selectedForTask(Object,int)
    private static Method sForCfg;    // selectedForConfig(Object,int)
    private static Method sEv;        // oneEvBias(int,int,int,int,int)
    private static Method sIdIn;      // enterIdentity(boolean)
    private static Method sIdOut;     // exitIdentity()
    private static Method sIdSel;     // identitySelected()
    private static Method sConvert;   // NativeM9Container.convert(byte[],boolean)

    private static ClassLoader sCamCl;
    private static Class<?> sR2B;      // 配置组件 r2.B
    private static Method sGetComp;    // r2.B.getComponentValue(int)
    private static Class<?> sTaskCls;  // 任务数据 Sh.t(17Pro)/Sh.s(17U)
    private static int sHooks = 0;
    private static int sSelFail = 0;
    private static final boolean[] sLogged = new boolean[16];
    // mega5 探针计数(诊断用, 收敛后可移除)
    private static int sP1 = 0;   // g1 getComponentValue(256) 前3火
    private static int sP1d = 0;  // g1 getDefaultValue(256) 前2火
    private static int sPSel = 0; // readSelected 原值前3
    private static int sP2 = 0;   // g2 T3.c.d 前2火
    private static int sP4 = 0;   // g4 任务构造前2火
    private static int sP7 = 0;   // g7 ImageSaver 前3火
    private static int sP8 = 0;   // g8 存储写前4火

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !PKG.equals(lp.packageName)) {
            return;
        }
        if (!PKG.equals(lp.processName)) {
            return;
        }
        if (sDone) {
            return;
        }
        sDone = true;
        try {
            loadConfig();
            log("loaded enabled=" + sEnabled + " proc=" + lp.processName);
            if (!sEnabled) {
                return;
            }
            setModulePath();
            installAsync(lp.classLoader);
            log("handle returns, async install running");
        } catch (Throwable e) {
            log("handle ERR " + e);
        }
    }

    private static String findOurApk() {
        // 1) 模块类加载器字符串里的 /data/app/...base.apk
        try {
            String s = String.valueOf(LegendInstant.class.getClassLoader());
            int i = s.indexOf("/data/app/");
            int j = i >= 0 ? s.indexOf(".apk", i) : -1;
            if (i >= 0 && j > i) {
                String p = s.substring(i, j + 4);
                if (new File(p).exists()) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) /proc/self/maps(本进程一定可读, 模块 apk 必然映射)
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.indexOf("com.pudding.camport") >= 0 && line.indexOf(".apk") >= 0) {
                    int i = line.indexOf("/data/app/");
                    if (i >= 0) {
                        String p = line.substring(i).trim();
                        if (new File(p).exists()) {
                            br.close();
                            return p;
                        }
                    }
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
        // 3) pathList 反射(沿类层级找)
        try {
            ClassLoader c = LegendInstant.class.getClassLoader();
            Class<?> k = c.getClass();
            while (k != null) {
                try {
                    Field pf = k.getDeclaredField("pathList");
                    pf.setAccessible(true);
                    Object pl = pf.get(c);
                    Field ef = pl.getClass().getDeclaredField("dexElements");
                    ef.setAccessible(true);
                    Object[] els = (Object[]) ef.get(pl);
                    if (els != null) {
                        for (int e = 0; e < els.length; e++) {
                            String[] names = {"path", "file", "zip"};
                            for (int n = 0; n < names.length; n++) {
                                try {
                                    Field f2 = els[e].getClass().getDeclaredField(names[n]);
                                    f2.setAccessible(true);
                                    Object v = f2.get(els[e]);
                                    String p = (v instanceof File)
                                        ? ((File) v).getAbsolutePath()
                                        : String.valueOf(v);
                                    if (p != null && p.indexOf(".apk") >= 0 && new File(p).exists()) {
                                        return p;
                                    }
                                } catch (Throwable ignored2) {
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                k = k.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        // 4) 兜底: 当前已知安装路径
        try {
            String p = "/data/app/~~-pOqLykqwMlCyZzUkacUVA==/com.pudding.camport-vRTMnv0Ln-Kz6Rt7AUyJxQ==/base.apk";
            if (new File(p).exists()) {
                return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void setModulePath() {
        String path = findOurApk();
        log("our apk=" + path);
        if (path == null) {
            log("sModulePath SKIP (no apk path, 将走兄弟代码自身 fallback)");
            return;
        }
        try {
            Class<?> e = Class.forName("com.nezha.camera.adapter.Camera68HookEntry", false,
                LegendInstant.class.getClassLoader());
            Field f = e.getField("sModulePath");
            f.set(null, path);
            log("sModulePath set OK");
        } catch (Throwable t) {
            log("sModulePath set ERR " + t);
        }
    }

    private static void installAsync(final ClassLoader camCl) {
        final boolean[] done = {false};
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                log("w enter (native installer, 17Pro bindings)");
                try {
                    bindRuntime();
                } catch (Throwable t) {
                    log("bind ERR " + t);
                    done[0] = true;
                    return;
                }
                sCamCl = camCl;
                step("g1-config", new Runnable() { public void run() { g1(); } });
                step("g2-request", new Runnable() { public void run() { g2(); } });
                step("g3-module", new Runnable() { public void run() { g3(); } });
                step("g4-task", new Runnable() { public void run() { g4(); } });
                step("g5-exposure", new Runnable() { public void run() { g5(); } });
                step("g6-identity", new Runnable() { public void run() { g6(); } });
                step("g7-titles", new Runnable() { public void run() { g7(); } });
                step("g8-storage", new Runnable() { public void run() { g8(); } });
                step("g9-watermark", new Runnable() { public void run() { g9(); } });
                step("g11-stream", new Runnable() { public void run() { g11(); } });
                step("g13-polaroid", new Runnable() { public void run() { g13(); } });
                step("g14-support", new Runnable() { public void run() { g14(); } });
                log("install done hooks=" + sHooks);
                done[0] = true;
            }
        }, "LI-inst");
        w.setDaemon(true);
        w.start();
        log("w started");
        Thread wd = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(7000);
                } catch (InterruptedException ignored) {
                }
                if (!done[0]) {
                    log("w HANG: 7s not finished (install stuck)");
                }
            }
        }, "LI-watchdog");
        wd.setDaemon(true);
        wd.start();
    }

    private static void step(String name, Runnable r) {
        try {
            r.run();
            log(name + " OK");
        } catch (Throwable t) {
            log(name + " SKIP " + t);
        }
    }

    private static void bindRuntime() throws Exception {
        ClassLoader my = LegendInstant.class.getClassLoader();
        Class<?> rt = Class.forName("com.nezha.camera.m10r.NativeM9Routing", false, my);
        sPipe = rt.getMethod("pipelineValue", String.class, boolean.class, int.class);
        sCfg = rt.getMethod("rememberConfig", Object.class, boolean.class);
        sPipeIn = rt.getMethod("enterPipeline");
        sPipeOut = rt.getMethod("exitPipeline");
        sTask = rt.getMethod("rememberTask", Object.class, boolean.class);
        sCopy = rt.getMethod("copyTask", Object.class, Object.class);
        sTitle = rt.getMethod("rememberTitle", Object.class, int.class, String.class);
        sForTitle = rt.getMethod("selectedForTitle", String.class);
        sForTask = rt.getMethod("selectedForTask", Object.class, int.class);
        sForCfg = rt.getMethod("selectedForConfig", Object.class, int.class);
        sEv = rt.getMethod("oneEvBias", int.class, int.class, int.class, int.class, int.class);
        sIdIn = rt.getMethod("enterIdentity", boolean.class);
        sIdOut = rt.getMethod("exitIdentity");
        sIdSel = rt.getMethod("identitySelected");
        Class<?> ct = Class.forName("com.nezha.camera.m10r.NativeM9Container", false, my);
        sConvert = ct.getMethod("convert", byte[].class, boolean.class);
        log("bind OK (15 routing + convert)");
    }

    // ---------- 通用反射工具 (等价兄弟 resolve/inherited/field) ----------

    private static Class<?> res(String... names) {
        for (String n : names) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(n, sCamCl);
                if (c != null) {
                    return c;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method inh(Class<?> c, String name, Class<?>... ps) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, ps);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        throw new RuntimeException("NoSuchMethod " + c.getName() + "." + name);
    }

    private static Object fld(Object o, String... names) {
        if (o == null) {
            return null;
        }
        for (String n : names) {
            try {
                return XposedHelpers.getObjectField(o, n);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object callO(Object o, String name) {
        return XposedHelpers.callMethod(o, name);
    }

    private static int iv(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static void once(int i, String m) {
        if (!sLogged[i]) {
            sLogged[i] = true;
            log(m);
        }
    }

    private static String stk() {
        try {
            StackTraceElement[] s = new Throwable().getStackTrace();
            StringBuilder b = new StringBuilder();
            for (int i = 1; i < Math.min(6, s.length); i++) {
                b.append(s[i].getClassName()).append('.')
                    .append(s[i].getMethodName()).append(" <- ");
            }
            return b.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 当前拍照配置是否为原生传奇一瞬(M10R) —— g2.a.a().x(r2.B) 的 getComponentValue(256)。 */
    private static boolean readSelected() {
        try {
            Class<?> g2 = res("g2.a", "p220g2.a");
            if (g2 == null || sR2B == null || sGetComp == null) {
                return false;
            }
            Object cfg = XposedHelpers.callStaticMethod(g2, "a");
            Object v = XposedHelpers.callMethod(cfg, "x", sR2B);
            Object raw = sR2B.isInstance(v)
                ? XposedBridge.invokeOriginalMethod(
                    sGetComp, v, new Object[]{Integer.valueOf(MODE)})
                : null;
            boolean sel = sR2B.isInstance(v) && "M10R".equals(raw);
            if (sPSel < 3) {
                sPSel++;
                log("readSelected raw=" + raw + " sel=" + sel
                    + " v=" + (v == null ? "null" : v.getClass().getName()));
            }
            if (sel) {
                log("GATE OPEN raw=M10R @" + stk());
            }
            return sel;
        } catch (Throwable t) {
            if (sSelFail++ == 0) {
                log("readSelected ERR " + t);
            }
            return false;
        }
    }

    private static boolean selCfg(Object o) {
        Object v = fld(o, "K3");
        if (!(v instanceof Number)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                sForCfg.invoke(null, o, Integer.valueOf(((Number) v).intValue())));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean selTask(Object o) {
        Object v = fld(fld(o, "b", "auxiliaryData"), "g", "f17747g", "currentModuleIndex");
        if (!(v instanceof Number)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                sForTask.invoke(null, o, Integer.valueOf(((Number) v).intValue())));
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- g1 配置门: 拍照取值时 M10R->M9 降级 (核心伪装) ----------
    // 17Pro: r2.B.getComponentValue 继承自 com.android.camera.data.data.c; getDefaultValue 在 r2.B 本类
    private static void g1() {
        sR2B = res("r2.B", "p618r2.B");
        if (sR2B == null) {
            throw new IllegalStateException("r2.B missing");
        }
        sGetComp = inh(sR2B, "getComponentValue", Integer.TYPE);
        XposedBridge.hookMethod(sGetComp, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!p.hasThrowable() && sR2B.isInstance(p.thisObject)
                            && p.getResult() instanceof String
                            && Integer.valueOf(MODE).equals(p.args[0])) {
                        Object orig = p.getResult();
                        Object nv = sPipe.invoke(null, (String) orig,
                            Boolean.TRUE, Integer.valueOf(MODE));
                        if (sP1 < 3) {
                            sP1++;
                            log("g1 read256 orig=" + orig + " -> " + nv
                                + " @" + stk());
                        }
                        if (!String.valueOf(orig).equals(String.valueOf(nv))) {
                            log("g1 SWAP 256 " + orig + " -> " + nv);
                        }
                        p.setResult(nv);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        // mega6: 撤掉 getDefaultValue(256) 覆写。17Pro 原生默认=M10R(未选风格态),
        // 实测覆写成 M9 会让 readSelected 的门永远关不上(raw 全是假 M9)。
        // 恢复原生默认 => 未选风格 => raw=M10R => sel=true => 链武装。
        sHooks++;
    }

    // ---------- g2 请求管线: T3.c.d(k6.l) 记配置 + 进出管线深度 ----------
    // 17Pro: T3.c 声明 d(Lk6/l;)V ✓; k6.l.K0()->l9.i0, 字段 a:l9.j0 = 配置对象
    private static void g2() {
        Class<?> t3 = res("T3.c");
        if (t3 == null) {
            throw new IllegalStateException("T3.c missing");
        }
        Set<XC_MethodHook.Unhook> us = XposedBridge.hookAllMethods(t3, "d", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (sP2 < 2) {
                    sP2++;
                    log("g2 d fire arg0=" + (p.args.length > 0 && p.args[0] != null
                        ? p.args[0].getClass().getName() : "null") + " sel=" + readSelected());
                }
                if (p.args.length == 1 && p.args[0] != null) {
                    try {
                        sCfg.invoke(null, fld(callO(p.args[0], "K0"), "a"),
                            Boolean.valueOf(readSelected()));
                    } catch (Throwable t) {
                        once(1, "g2 bind cfg ERR " + t);
                    }
                }
                try {
                    sPipeIn.invoke(null);
                } catch (Throwable ignored) {
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    sPipeOut.invoke(null);
                } catch (Throwable ignored) {
                }
            }
        });
        if (us.isEmpty()) {
            throw new IllegalStateException("T3.c.d empty");
        }
        sHooks += us.size();
    }

    // ---------- g3 模块态: updateModuleRelated 记配置 ----------
    // 17Pro BaseModule = module.t (17U 是 module.u); 声明 updateModuleRelated()V,
    // 字段 mCameraManager:Lk6/l, mModuleIndex:I ✓
    private static void g3() {
        Class<?> mod = res("com.android.camera.module.t", "com.android.camera.module.u");
        if (mod == null) {
            throw new IllegalStateException("module missing");
        }
        Method umr = inh(mod, "updateModuleRelated");
        XposedBridge.hookMethod(umr, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object mgr = fld(p.thisObject, "mCameraManager");
                    Object idx = fld(p.thisObject, "mModuleIndex");
                    if (mgr != null) {
                        boolean z = Integer.valueOf(MODE).equals(idx) && readSelected();
                        sCfg.invoke(null, fld(callO(mgr, "K0"), "a"), Boolean.valueOf(z));
                    }
                } catch (Throwable t) {
                    once(2, "g3 bind ERR " + t);
                }
            }
        });
        sHooks++;
    }

    // ---------- g4 任务跟踪: 任务类构造后 rememberTask/copyTask ----------
    // 17Pro 任务数据 = Sh.t (17U = Sh.s); <init>()V 与 <init>(Sh.t;)V 拷贝构造都在
    private static void g4() {
        sTaskCls = res("Sh.t", "Sh.s");
        if (sTaskCls == null) {
            throw new IllegalStateException("task class missing");
        }
        Set<XC_MethodHook.Unhook> us = XposedBridge.hookAllConstructors(sTaskCls,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) {
                        return;
                    }
                    try {
                        if (sP4 < 2) {
                            sP4++;
                            log("g4 Sh.t ctor args=" + p.args.length
                                + " sel=" + readSelected());
                        }
                        sTask.invoke(null, p.thisObject, Boolean.valueOf(readSelected()));
                        if (p.args.length == 1 && sTaskCls.isInstance(p.args[0])) {
                            sCopy.invoke(null, p.args[0], p.thisObject);
                        }
                    } catch (Throwable t) {
                        once(3, "g4 task ERR " + t);
                    }
                }
            });
        if (us.isEmpty()) {
            throw new IllegalStateException("task ctors empty");
        }
        sHooks += us.size();
    }

    // ---------- g5 曝光/WB: +1EV 与自动 WB (M10R 观感) ----------
    // 17Pro 分发器 = l9.n0 (17U = l9.o0): m1(I,g,j0)I, W(I,Builder,g,j0)V,
    //                g(I,Builder,g)V, L(I,Builder,g)V, f(Builder,Z)V 全部精确命中;
    //                l9.z0.L1(Builder) (17U = l9.y0); z0.E:l9.i0 -> .a:l9.j0 = 配置对象
    private static void g5() {
        Class<?> caps = res("l9.g");
        Class<?> set = res("l9.j0");
        Class<?> disp = res("l9.n0", "l9.o0");
        final Class<?> builder = res("android.hardware.camera2.CaptureRequest$Builder");
        if (caps == null || set == null || disp == null || builder == null) {
            throw new IllegalStateException("AE classes missing c=" + (caps != null)
                + " s=" + (set != null) + " d=" + (disp != null) + " b=" + (builder != null));
        }
        Method m1 = inh(disp, "m1", Integer.TYPE, caps, set);
        XposedBridge.hookMethod(m1, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || !(p.getResult() instanceof Integer)
                        || !selCfg(p.args[2])) {
                    return;
                }
                try {
                    Object w = callO(p.args[1], "w");
                    Object v = callO(p.args[1], "v");
                    p.setResult(sEv.invoke(null,
                        Integer.valueOf(((Integer) p.getResult()).intValue()),
                        Integer.valueOf(iv(callO(w, "getNumerator"))),
                        Integer.valueOf(iv(callO(w, "getDenominator"))),
                        Integer.valueOf(iv(callO(v, "getLower"))),
                        Integer.valueOf(iv(callO(v, "getUpper")))));
                } catch (Throwable t) {
                    once(4, "g5 ev ERR " + t);
                }
            }
        });
        final java.util.Map<Object, Object> awb =
            Collections.synchronizedMap(new WeakHashMap<Object, Object>());
        Method Wm = inh(disp, "W", Integer.TYPE, builder, caps, set);
        XposedBridge.hookMethod(Wm, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable() && p.args[1] != null) {
                    awb.put(p.args[1], new Awb(p.args[2], selCfg(p.args[3])));
                }
            }
        });
        final Method gm = inh(disp, "g", Integer.TYPE, builder, caps);
        final Method Lm = inh(disp, "L", Integer.TYPE, builder, caps);
        final Method fm = inh(disp, "f", builder, Boolean.TYPE);
        Method bd = inh(builder, "build");
        XposedBridge.hookMethod(bd, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Object o = awb.get(p.thisObject);
                if (!(o instanceof Awb) || !((Awb) o).t) {
                    return;
                }
                try {
                    Awb a = (Awb) o;
                    XposedBridge.invokeOriginalMethod(gm, null,
                        new Object[]{Integer.valueOf(1), p.thisObject, a.caps});
                    XposedBridge.invokeOriginalMethod(Lm, null,
                        new Object[]{Integer.valueOf(0), p.thisObject, a.caps});
                    XposedBridge.invokeOriginalMethod(fm, null,
                        new Object[]{p.thisObject, Boolean.FALSE});
                } catch (Throwable t) {
                    once(5, "g5 wb ERR " + t);
                }
            }
        });
        sHooks += 5;
        Class<?> hz = res("l9.z0", "l9.y0");
        if (hz != null) {
            try {
                Method l1 = inh(hz, "L1", builder);
                XposedBridge.hookMethod(l1, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            sIdIn.invoke(null, Boolean.valueOf(selCfg(fld(fld(p.thisObject, "E"), "a"))));
                        } catch (Throwable t) {
                            try {
                                sIdIn.invoke(null, Boolean.FALSE);
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            sIdOut.invoke(null);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                sHooks++;
            } catch (Throwable t) {
                log("g5 L1 skip " + t);
            }
        } else {
            log("g5 L1 skip (l9.z0 missing)");
        }
    }

    // ---------- g6 身份: 图像处理器 a(任务) 进出 identity 栈 ----------
    // 17Pro q7 包: c(d多出 e(Sh.t,OutputStream) 写流) 与 a/b/e/f 全有 a(Sh.t)V;
    // identity 是栈(List push/pop), 多处理器嵌套安全
    private static void g6() {
        if (sTaskCls == null) {
            throw new IllegalStateException("task class missing");
        }
        String[] procs = {"q7.c", "q7.a", "q7.b", "q7.e", "q7.f", "q7.d", "q7.g"};
        int n = 0;
        StringBuilder hit = new StringBuilder();
        for (int i = 0; i < procs.length; i++) {
            Class<?> pc = res(procs[i]);
            if (pc == null) {
                continue;
            }
            try {
                Method a = inh(pc, "a", sTaskCls);
                XposedBridge.hookMethod(a, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            sIdIn.invoke(null, Boolean.valueOf(selTask(p.args[0])));
                        } catch (Throwable ignored) {
                            try {
                                sIdIn.invoke(null, Boolean.FALSE);
                            } catch (Throwable ignored2) {
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            sIdOut.invoke(null);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
                sHooks++;
                hit.append(' ').append(procs[i]);
            } catch (Throwable ignored) {
            }
        }
        if (n == 0) {
            throw new IllegalStateException("no q7 a(task)");
        }
        log("g6 processors=" + n + " [" + hit.toString().trim() + "]");
    }

    // ---------- g7 标题: ImageSaver 记 title/savePath (g8 转换的门) ----------
    // 17Pro ImageSaver = l7.k (17U = l7.j); y,p,w,D,E,o,onProcessorJpegFinish 七名全在
    private static void g7() {
        if (sTaskCls == null) {
            throw new IllegalStateException("task class missing");
        }
        Class<?> sv = res("l7.k", "l7.j");
        if (sv == null) {
            throw new IllegalStateException("ImageSaver missing");
        }
        String[] ns = {"y", "p", "w", "D", "E", "o", "onProcessorJpegFinish"};
        Set<XC_MethodHook.Unhook> us = new HashSet<XC_MethodHook.Unhook>();
        for (int i = 0; i < ns.length; i++) {
            us.addAll(XposedBridge.hookAllMethods(sv, ns[i], new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sP7 < 3) {
                        sP7++;
                        StringBuilder ab = new StringBuilder("g7 fire ")
                            .append(p.method.getName()).append(" args=[");
                        for (Object o : p.args) {
                            ab.append(o == null ? "null" : o.getClass().getName()).append(',');
                        }
                        log(ab.append(']').toString());
                    }
                    try {
                        for (Object o : p.args) {
                            if (o == null || !sTaskCls.isInstance(o)) {
                                continue;
                            }
                            Object aux = fld(fld(o, "b", "auxiliaryData"),
                                "g", "f17747g", "currentModuleIndex");
                            Object stor = fld(o, "k", "f17813k", "storageData");
                            int mi = aux instanceof Number ? ((Number) aux).intValue() : 0;
                            String[][] prs = {{"b", "title"}, {"g", "f17710g", "savePath"}};
                            for (String[] pr : prs) {
                                Object v = fld(stor, pr);
                                if (v instanceof String) {
                                    sTitle.invoke(null, o, Integer.valueOf(mi), (String) v);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }));
        }
        if (us.isEmpty()) {
            throw new IllegalStateException("ImageSaver names empty");
        }
        sHooks += us.size();
    }

    // ---------- g8 落盘转换: l7.N 存储写入点 ByteBuffer->convert(M9->M10R) ----------
    private static void g8() {
        Class<?> st = res("l7.N");
        if (st == null) {
            throw new IllegalStateException("Storage missing");
        }
        int n = 0;
        Method[] ms = st.getDeclaredMethods();
        for (int i = 0; i < ms.length; i++) {
            Method m = ms[i];
            String nm = m.getName();
            if (!("a".equals(nm) || "b".equals(nm) || "A".equals(nm)
                    || "J".equals(nm) || "B".equals(nm) || "F".equals(nm))) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            int bi = -1;
            int si = -1;
            for (int k = 0; k < pt.length; k++) {
                if (pt[k] == ByteBuffer.class) {
                    bi = k;
                }
                if (pt[k] == String.class && si < 0) {
                    si = k;
                }
            }
            if (bi < 0 || si < 0) {
                continue;
            }
            final int fbi = bi;
            final int fsi = si;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object tt = p.args.length > fsi ? p.args[fsi] : null;
                    boolean ft = false;
                    try {
                        ft = Boolean.TRUE.equals(sForTitle.invoke(null, tt));
                    } catch (Throwable ignored) {
                    }
                    if (ft) {
                        log("g8 ARMED title=" + tt);
                    }
                    if (sP8 < 4) {
                        sP8++;
                        log("g8 write title=" + tt + " forTitle=" + ft);
                    }
                    try {
                        if (!(p.args[fbi] instanceof ByteBuffer)
                                || !(p.args[fsi] instanceof String)) {
                            return;
                        }
                        if (!Boolean.TRUE.equals(sForTitle.invoke(null, (String) p.args[fsi]))) {
                            return;
                        }
                        ByteBuffer d = ((ByteBuffer) p.args[fbi]).duplicate();
                        d.position(0);
                        byte[] b = new byte[d.remaining()];
                        d.get(b);
                        if (b.length > 1) {
                            log("g10w m=" + (p.method instanceof Method ? ((Method) p.method).getName() : "?")
                                    + " len=" + b.length
                                    + " head=" + String.format("%02x%02x", b[0] & 255, b[1] & 255)
                                    + " str=" + p.args[fsi]);
                        }
                        byte[] c = (byte[]) sConvert.invoke(null, b, Boolean.TRUE);
                        if (c != b) {
                            p.args[fbi] = ByteBuffer.wrap(c);
                            log("g8 convert " + p.args[fsi] + " " + b.length + "->" + c.length);
                        } else if (b.length > 4 && (b[0] & 255) == 255 && (b[1] & 255) == 216
                                && !hasMarker(b, "Legend.")
                                && (((String) p.args[fsi]).indexOf('/') < 0
                                        || ((String) p.args[fsi]).indexOf("/DCIM/") >= 0)) {
                            long t10 = System.currentTimeMillis();
                            byte[] cont = wrapContainer(b, (String) p.args[fsi]);
                            if (cont != null) {
                                p.args[fbi] = ByteBuffer.wrap(cont);
                                log("g10 CONTAINER " + b.length + "->" + cont.length
                                        + " in " + (System.currentTimeMillis() - t10) + "ms");
                                final String fp = (String) p.args[fsi];
                                Thread probe = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        long[] marks = {2000, 5000, 9000};
                                        for (int i = 0; i < marks.length; i++) {
                                            try {
                                                Thread.sleep(marks[i] - (i > 0 ? marks[i - 1] : 0));
                                            } catch (InterruptedException ignored) {
                                            }
                                            try {
                                                File f = new File(fp);
                                                if (!f.exists()) {
                                                    log("probe t=" + marks[i] + " GONE");
                                                    continue;
                                                }
                                                java.io.FileInputStream in = new java.io.FileInputStream(f);
                                                java.io.ByteArrayOutputStream bo =
                                                        new java.io.ByteArrayOutputStream((int) f.length());
                                                byte[] bb = new byte[65536];
                                                int r;
                                                while ((r = in.read(bb)) > 0) {
                                                    bo.write(bb, 0, r);
                                                }
                                                in.close();
                                                byte[] all = bo.toByteArray();
                                                log("probe t=" + marks[i] + " size=" + all.length
                                                        + " marker=" + hasMarker(all, "Legend.")
                                                        + " head=" + String.format("%02x%02x",
                                                                all[0] & 255,
                                                                (all.length > 1 ? all[1] : 0) & 255));
                                            } catch (Throwable t) {
                                                log("probe ERR " + t);
                                            }
                                        }
                                    }
                                }, "LI-probe");
                                probe.setDaemon(true);
                                probe.start();
                            }
                        }
                    } catch (Throwable t) {
                        once(6, "g8 convert ERR " + t);
                    }
                }
            });
            n++;
            sHooks++;
        }
        if (n == 0) {
            throw new IllegalStateException("no storage writer");
        }
    }

    // ---------- g11: 兄弟 OutputStream 双卡点(l1.a.e / q7.c.e) = 全分辨率真写入 ----------
    private static final java.util.Map<Object, Object> sSwap =
            Collections.synchronizedMap(new java.util.IdentityHashMap<Object, Object>());

    private static boolean taskArmed(Object task) {
        try {
            Object idx = fld(fld(task, "b", "auxiliaryData"), "g", "f17747g", "currentModuleIndex");
            if (idx instanceof Number && sForTask != null) {
                return Boolean.TRUE.equals(sForTask.invoke(null, task, ((Number) idx).intValue()));
            }
        } catch (Throwable t) {
            log("g11 task ERR " + t);
        }
        return false;
    }

    private static byte[] streamPass(String tag, byte[] src) {
        if (src == null || src.length < 4) {
            return src;
        }
        log("g11 cap " + tag + " len=" + src.length
                + " head=" + String.format("%02x%02x", src[0] & 255, src[1] & 255));
        if ((src[0] & 255) == 255 && (src[1] & 255) == 216 && !hasMarker(src, "Legend.")) {
            long t10 = System.currentTimeMillis();
            byte[] cont = wrapContainer(src, tag);
            if (cont != null) {
                log("g11 CONTAINER " + tag + " " + src.length + "->" + cont.length
                        + " in " + (System.currentTimeMillis() - t10) + "ms");
                return cont;
            }
        }
        return src;
    }

    private static void g11() {
        // 卡点A: l1.a.e(OutputStream) — TaskDataStorageWriter (兄弟: 真流换 BAOS 截获)
        try {
            Class<?> l1a = res("l1.a");
            Method m1 = l1a == null ? null : inh(l1a, "e", java.io.OutputStream.class);
            if (m1 != null) {
                XposedBridge.hookMethod(m1, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object task = fld(p.thisObject, "b");
                            boolean armed = task != null && taskArmed(task);
                            log("g11 l1.a.e armed=" + armed);
                            if (!armed || p.args.length < 1
                                    || !(p.args[0] instanceof java.io.OutputStream)) {
                                return;
                            }
                            sSwap.put(p, p.args[0]);
                            p.args[0] = new java.io.ByteArrayOutputStream(65536);
                        } catch (Throwable t) {
                            log("g11 l1.a before ERR " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object real = sSwap.remove(p);
                            if (real == null) {
                                return;
                            }
                            byte[] src = ((java.io.ByteArrayOutputStream) p.args[0]).toByteArray();
                            p.args[0] = real;
                            if (p.hasThrowable()) {
                                return;
                            }
                            ((java.io.OutputStream) real).write(streamPass("l1a", src));
                        } catch (Throwable t) {
                            log("g11 l1.a after ERR " + t);
                        }
                    }
                });
                sHooks++;
                log("g11 l1.a OK");
            } else {
                log("g11 l1.a MISS");
            }
        } catch (Throwable t) {
            log("g11 l1.a ERR " + t);
        }
        // 卡点B: q7.c.e(?, OutputStream) — JPEG 处理器 (兄弟: selectedTask(args[0]))
        try {
            Class<?> q7c = res("q7.c");
            Method m2 = null;
            if (q7c != null) {
                for (Class<?> k = q7c; k != null && m2 == null; k = k.getSuperclass()) {
                    for (Method mm : k.getDeclaredMethods()) {
                        if ("e".equals(mm.getName()) && mm.getParameterCount() == 2
                                && mm.getParameterTypes()[1] == java.io.OutputStream.class) {
                            mm.setAccessible(true);
                            m2 = mm;
                            break;
                        }
                    }
                }
            }
            if (m2 != null) {
                XposedBridge.hookMethod(m2, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            boolean armed = p.args.length > 0 && taskArmed(p.args[0]);
                            log("g11 q7.c.e armed=" + armed);
                            if (!armed || p.args.length < 2
                                    || !(p.args[1] instanceof java.io.OutputStream)) {
                                return;
                            }
                            sSwap.put(p, p.args[1]);
                            p.args[1] = new java.io.ByteArrayOutputStream(65536);
                        } catch (Throwable t) {
                            log("g11 q7 before ERR " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object real = sSwap.remove(p);
                            if (real == null) {
                                return;
                            }
                            byte[] src = ((java.io.ByteArrayOutputStream) p.args[1]).toByteArray();
                            p.args[1] = real;
                            if (p.hasThrowable()) {
                                return;
                            }
                            ((java.io.OutputStream) real).write(streamPass("q7e", src));
                        } catch (Throwable t) {
                            log("g11 q7 after ERR " + t);
                        }
                    }
                });
                sHooks++;
                log("g11 q7.c OK");
            } else {
                log("g11 q7.c MISS");
            }
        } catch (Throwable t) {
            log("g11 q7.c ERR " + t);
        }
    }

    // ---------- g10 round-2: FakeRaw 容器封装(JpgConvert 链, 兄弟框架) ----------
    private static byte[] sTplLand;
    private static byte[] sTplPort;

    private static boolean hasMarker(byte[] b, String marker) {
        byte[] m = new byte[marker.length()];
        for (int i = 0; i < m.length; i++) {
            m[i] = (byte) marker.charAt(i);
        }
        outer:
        for (int i = 0; i + m.length <= b.length; i++) {
            for (int j = 0; j < m.length; j++) {
                if (b[i + j] != m[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] readZipEntry(String name) {
        try {
            String apk = findOurApk();
            if (apk == null) {
                once(13, "g10 no module apk path");
                return null;
            }
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
            java.util.zip.ZipEntry ze = zf.getEntry(name);
            if (ze == null) {
                log("g10 zip miss " + name);
                zf.close();
                return null;
            }
            long sz = ze.getSize();
            java.io.InputStream is = zf.getInputStream(ze);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(
                    sz > 0 && sz < (1 << 26) ? (int) sz : 1 << 24);
            byte[] buf = new byte[65536];
            int r;
            while ((r = is.read(buf)) > 0) {
                o.write(buf, 0, r);
            }
            is.close();
            zf.close();
            log("g10 zip " + name + " " + o.size() + " bytes");
            return o.toByteArray();
        } catch (Throwable t) {
            log("g10 zip ERR " + name + " " + t);
            return null;
        }
    }

    private static byte[] scaleRotateJpeg(byte[] jpeg, int deg, int tw, int th) {
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (b == null) {
            return jpeg;
        }
        if (((deg % 360) + 360) % 360 != 0) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(deg);
            android.graphics.Bitmap r = android.graphics.Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) {
                b.recycle();
            }
            b = r;
        }
        android.graphics.Bitmap s = (tw > 0 && th > 0 && (b.getWidth() != tw || b.getHeight() != th))
                ? android.graphics.Bitmap.createScaledBitmap(b, tw, th, true) : b;
        if (s != b) {
            b.recycle();
        }
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(Math.max(65536, s.getWidth() * s.getHeight() / 6));
        s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, o);
        s.recycle();
        return o.toByteArray();
    }

    private static Object scalerProxy() throws ClassNotFoundException {
        return java.lang.reflect.Proxy.newProxyInstance(LegendInstant.class.getClassLoader(),
                new Class<?>[]{Class.forName("com.leicainstant.core.Pipeline$PreviewScaler")},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] a) {
                        String nm = m.getName();
                        if ("scaleAndRotate".equals(nm) && a != null && a.length == 4) {
                            return scaleRotateJpeg((byte[]) a[0], ((Integer) a[1]).intValue(),
                                    ((Integer) a[2]).intValue(), ((Integer) a[3]).intValue());
                        }
                        if ("gainmapPixels".equals(nm) && a != null && a.length == 3) {
                            byte[] src = (byte[]) a[0];
                            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(src, 0, src.length);
                            if (b == null) {
                                return new int[0];
                            }
                            int gw = a[1] instanceof Integer ? ((Integer) a[1]).intValue() : b.getWidth();
                            int gh = a[2] instanceof Integer ? ((Integer) a[2]).intValue() : b.getHeight();
                            android.graphics.Bitmap s = (gw > 0 && gh > 0 && (b.getWidth() != gw || b.getHeight() != gh))
                                    ? android.graphics.Bitmap.createScaledBitmap(b, gw, gh, true) : b;
                            if (s != b) {
                                b.recycle();
                            }
                            int[] px = new int[s.getWidth() * s.getHeight()];
                            s.getPixels(px, 0, s.getWidth(), 0, 0, s.getWidth(), s.getHeight());
                            s.recycle();
                            return px;
                        }
                        if ("toString".equals(nm)) {
                            return "LegendPreviewScaler";
                        }
                        if ("hashCode".equals(nm)) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        }
                        if ("equals".equals(nm)) {
                            return Boolean.valueOf(proxy == a[0]);
                        }
                        throw new UnsupportedOperationException("scaler." + nm);
                    }
                });
    }

    private static Object encoderProxy() throws ClassNotFoundException {
        return java.lang.reflect.Proxy.newProxyInstance(LegendInstant.class.getClassLoader(),
                new Class<?>[]{Class.forName("com.leicainstant.core.JpgConvert$PreviewEncoder")},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] a) {
                        String nm = m.getName();
                        if ("encode".equals(nm) && a != null && a.length == 3) {
                            int[] px = (int[]) a[0];
                            int w = ((Integer) a[1]).intValue();
                            int h = ((Integer) a[2]).intValue();
                            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
                            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(Math.max(65536, w * h / 6));
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, o);
                            bmp.recycle();
                            return o.toByteArray();
                        }
                        if ("toString".equals(nm)) {
                            return "LegendPreviewEncoder";
                        }
                        if ("hashCode".equals(nm)) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        }
                        if ("equals".equals(nm)) {
                            return Boolean.valueOf(proxy == a[0]);
                        }
                        throw new UnsupportedOperationException("encoder." + nm);
                    }
                });
    }

    private static byte[] wrapContainer(byte[] jpeg, String title) {
        try {
            if (sTplLand == null) {
                sTplLand = readZipEntry("assets/templates/leica-M9-landscape.jpg");
            }
            if (sTplPort == null) {
                sTplPort = readZipEntry("assets/templates/leica-M9-portrait.jpg");
            }
            if (sTplLand == null || sTplPort == null) {
                return null;
            }
            try {
                Class<?> nmc = Class.forName("com.nezha.camera.m10r.NativeM9Container");
                boolean nl = false;
                try {
                    Field nf = nmc.getDeclaredField("sNativeLoaded");
                    nf.setAccessible(true);
                    nl = Boolean.TRUE.equals(nf.get(null));
                } catch (Throwable ignored) {
                }
                if (!nl) {
                    byte[] so = readZipEntry("assets/libinstant_m10r.so");
                    if (so != null && so.length > 1000) {
                        File soF = new File("/data/user/0/com.android.camera/cache", "g10_instant.so");
                        soF.getParentFile().mkdirs();
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(soF);
                        fos.write(so);
                        fos.close();
                        System.load(soF.getAbsolutePath());
                        log("g10 native loaded by us " + so.length + " bytes");
                    }
                }
            } catch (Throwable t) {
                log("g10 native ERR " + t);
            }
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) {
                once(11, "g10 bitmap decode failed");
                return null;
            }
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] argb = new int[w * h];
            bmp.getPixels(argb, 0, w, 0, 0, w, h);
            bmp.recycle();
            Class<?> inC = Class.forName("com.leicainstant.core.JpgConvert$Input");
            Object in = inC.getDeclaredConstructor().newInstance();
            inC.getField("argb").set(in, argb);
            inC.getField("width").set(in, Integer.valueOf(w));
            inC.getField("height").set(in, Integer.valueOf(h));
            inC.getField("original").set(in, jpeg);
            int orient = 1;
            try {
                Object o2 = Class.forName("com.leicainstant.core.Preview")
                        .getMethod("exifOrientation", byte[].class).invoke(null, jpeg);
                if (o2 instanceof Integer) {
                    orient = ((Integer) o2).intValue();
                }
            } catch (Throwable ignored) {
            }
            inC.getField("exifOrientation").set(in, Integer.valueOf(orient));
            Class<?> jsC = Class.forName("com.leicainstant.core.JpgSettings");
            Object js = jsC.getDeclaredConstructor().newInstance();
            Object cfg = jsC.getField("base").get(js);
            cfg.getClass().getField("mode").set(cfg, "default"); // default=M9 (合法choice字面值); resolvedMode()→M9; "M9"会挂validate
            java.util.function.Consumer<String> cons = new java.util.function.Consumer<String>() {
                @Override
                public void accept(String s) {
                    log("JPC " + s);
                }
            };
            Method run = Class.forName("com.leicainstant.core.JpgConvert").getMethod("run",
                    inC, jsC, String.class, byte[].class, byte[].class,
                    Class.forName("com.leicainstant.core.Pipeline$PreviewScaler"),
                    Class.forName("com.leicainstant.core.JpgConvert$PreviewEncoder"),
                    java.util.function.Consumer.class);
            Object out = run.invoke(null, in, js, title == null ? "instant" : title,
                    sTplPort, sTplLand, scalerProxy(), encoderProxy(), cons);
            Object res = out.getClass().getField("result").get(out);
            byte[] cont = (byte[]) res.getClass().getField("file").get(res);
            if (cont == null || cont.length < 100 || (cont[0] & 255) != 255 || (cont[1] & 255) != 216) {
                once(12, "g10 bad container output");
                return null;
            }
            return cont;
        } catch (Throwable t) {
            log("g10 ERR " + t);
            return null;
        }
    }

    // ---------- g9 水印身份: 传奇一瞬成片显示 18 Pro Max ----------
    private static final WeakHashMap<String, int[]> sG13 = new WeakHashMap<>();

    private static String argSig(XC_MethodHook.MethodHookParam p) {
        StringBuilder sb = new StringBuilder();
        Object[] a = p.args;
        if (a != null) {
            for (Object o : a) {
                sb.append('|');
                if (o == null) {
                    sb.append("null");
                } else if (o instanceof byte[]) {
                    sb.append("byte[").append(((byte[]) o).length).append(']');
                } else if (o instanceof String || o instanceof Number || o instanceof Boolean) {
                    String s = String.valueOf(o);
                    sb.append(o.getClass().getSimpleName());
                    if (s.length() > 48) {
                        s = s.substring(0, 48) + "..";
                    }
                    sb.append('=').append(s);
                } else {
                    sb.append(o.getClass().getName());
                }
            }
        }
        return sb.toString();
    }

    private static void g13() {
        // g13 polaroid(一瞬)全方法只读追踪: 定位"删除腿跑/渲染腿不跑"的断点, 不改任何行为
        String[] cls = {
            "com.android.camera.features.mode.polaroid.PolaroidModule",
            "com.android.camera.features.mode.polaroid.PolaroidModule$a",
            "com.android.camera.features.mode.polaroid.PolaroidModule$b",
            "com.android.camera.features.mode.polaroid.PolaroidModuleEntry",
            "com.android.camera.features.mode.polaroid.a",
            "com.android.camera.features.mode.polaroid.a$a",
            "com.android.camera.features.mode.polaroid.b",
            "com.android.camera.features.mode.polaroid.ui.ActivityInstantPhoto",
            "com.android.camera.features.mode.polaroid.InstantPhotoUtil",
            "com.android.camera.features.mode.polaroid.InstantPhotoImageObserver",
        };
        for (final String cn : cls) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, sCamCl);
                HashSet<String> names = new HashSet<>();
                for (Method m : c.getDeclaredMethods()) {
                    names.add(m.getName());
                }
                int n = 0;
                for (final String mn : names) {
                    try {
                        XposedBridge.hookAllMethods(c, mn, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                String k = cn + "#" + p.method.getName();
                                int[] c13 = sG13.get(k);
                                if (c13 == null) {
                                    c13 = new int[1];
                                    sG13.put(k, c13);
                                }
                                c13[0]++;
                                if (c13[0] <= 40 && (c13[0] <= 3 || c13[0] % 20 == 0)) {
                                    log("g13 " + k + " #" + c13[0] + argSig(p));
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                Object r = p.getResult();
                                if (r instanceof Boolean || r instanceof Integer) {
                                    String k = cn + "#" + p.method.getName();
                                    int[] c13 = sG13.get(k);
                                    if (c13 != null && c13[0] <= 40
                                            && (c13[0] <= 3 || c13[0] % 20 == 0)) {
                                        log("g13 " + k + " -> " + r);
                                    }
                                }
                            }
                        });
                        n++;
                    } catch (Throwable ignored) {
                    }
                }
                log("g13 hooked " + cn + " names=" + n);
            } catch (Throwable t) {
                log("g13 " + cn + " ERR " + t);
            }
        }
    }

    private static void g14() {
        // g14 撬门: legend.instant=1 时 PolaroidModuleEntry.support -> true,
        // 让相机一瞬服务腿(上传 i.ai.mi.com 渲染)越过准入检查; 默认关, 独立可回退
        try {
            Class<?> entry = XposedHelpers.findClass(
                "com.android.camera.features.mode.polaroid.PolaroidModuleEntry", sCamCl);
            XposedBridge.hookAllMethods(entry, "support", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(Boolean.TRUE);
                    if (!sLogged[12]) {
                        sLogged[12] = true;
                        log("g14 support -> true (spoofed)");
                    }
                }
            });
            log("g14 hooked");
        } catch (Throwable t) {
            log("g14 ERR " + t);
        }
    }

    private static void g9() {
        Class<?> pe = res("Pe.b");
        if (pe != null) {
            try {
                Method a = inh(pe, "A");
                XposedBridge.hookMethod(a, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!p.hasThrowable() && Boolean.TRUE.equals(sIdSel.invoke(null))) {
                                p.setResult("18 Pro Max");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                sHooks++;
            } catch (Throwable t) {
                log("g9 Pe.b skip " + t);
            }
        } else {
            log("g9 Pe.b missing");
        }
        Class<?> wm = res("com.xiaomi.cam.watermark.a");
        if (wm != null) {
            try {
                Method j0 = inh(wm, "J0", String.class, String.class, Boolean.TYPE);
                XposedBridge.hookMethod(j0, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (Boolean.TRUE.equals(sIdSel.invoke(null))) {
                                p.args[0] = "Xiaomi";
                                p.args[1] = "18 Pro Max";
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                sHooks++;
            } catch (Throwable t) {
                log("g9 watermark skip " + t);
            }
        } else {
            log("g9 watermark missing");
        }
    }

    private static final class Awb {
        final Object caps;
        final boolean t;

        Awb(Object caps, boolean t) {
            this.caps = caps;
            this.t = t;
        }
    }

    private static void loadConfig() {
        String pick = null;
        for (int i = 0; i < CFG.length; i++) {
            File f = new File(CFG[i]);
            if (f.isFile() && f.canRead()) {
                pick = CFG[i];
                break;
            }
        }
        if (pick == null) {
            log("no config, off");
            return;
        }
        try {
            BufferedReader br = new BufferedReader(new FileReader(pick));
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) continue;
                if ("legend.instant".equals(s.substring(0, eq).trim())) {
                    String v = s.substring(eq + 1).trim();
                    sEnabled = "1".equals(v) || "true".equalsIgnoreCase(v);
                }
            }
            br.close();
        } catch (Throwable t) {
            log("cfg ERR " + t);
        }
    }

    private static void log(String m) {
        Log.i(TAG, HEAD + m);
        try {
            FileWriter fw = new FileWriter(LOGP, true);
            fw.write(System.currentTimeMillis() + " " + m + "\n");
            fw.close();
        } catch (Throwable ignored) {
        }
    }
}
