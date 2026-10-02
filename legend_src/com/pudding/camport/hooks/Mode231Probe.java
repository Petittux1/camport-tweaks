package com.pudding.camport.hooks;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.FileWriter;
import java.lang.reflect.Method;

/**
 * 实况运镜（mode 231）闪退诊断探针。
 *
 * 症状：切进 231 后 getActualOpenCameraId 算出 cameraId = -1，
 *       openCamera 连续 3 次 Failure: 231 -> auto recover fail -> Activity 自己退出。
 *       不是 Java 崩溃（logcat 无 FATAL），是相机自我保护退出。
 *
 * 231 的 cameraId 由 B2.c.c(int bogusId, int mode, boolean persist) 计算，
 * 这个方法上千行、按 mode 分了几十个分支，其中
 *       case 230: case 231: break;      // 相机根本没给 231 写镜头逻辑
 * 静态追分支收益太低，这里一次性把 231 用到的所有输入 dump 出来，靠前后两次调用的
 * 差异（.630 返回 5 / .638 返回 -1）反推是哪个输入把结果变成了 -1。
 *
 * 只在 mode==231（或任何模式算出 -1）时打日志，正常模式零噪音。
 */
public final class Mode231Probe implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "运镜: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    private static final String C_GETID = "B2.c";
    private static final String C_LENS = "p181v6.f";
    private static final String C_DATA_I = "com.android.camera.data.data.i";
    private static final String C_DATA_N = "com.android.camera.data.data.n";
    private static final String C_DATA_S = "com.android.camera.data.data.s";
    private static final String C_DATA_F = "com.android.camera.data.data.F";
    private static final String C_PEB = "Pe.b";

    private static final int MODE_231 = 231;

    private static ClassLoader sCl;
    private static boolean sInstalled;
    private static FileWriter sWriter;
    private static String sLastError;

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
        log("probe installed proc=" + lpparam.processName);

        // getActualOpenCameraId 的实际实现（b() 只是转调它）
        try {
            XposedHelpers.findAndHookMethod(C_GETID, sCl, "c",
                    Integer.TYPE, Integer.TYPE, Boolean.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int bogus = ((Integer) param.args[0]).intValue();
                                int mode = ((Integer) param.args[1]).intValue();
                                boolean persist = ((Boolean) param.args[2]).booleanValue();
                                if (mode != MODE_231) {
                                    return;
                                }
                                // 必须在调用【之前】读输入：c() 自己会改镜头表/组件状态，
                                // 事后读会把两次调用读成一模一样，抓不到差异。
                                dump(bogus, mode, persist);
                            } catch (Throwable th) {
                                err("c/in", th);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int mode = ((Integer) param.args[1]).intValue();
                                int ret = ((Integer) param.getResult()).intValue();
                                if (mode != MODE_231 && ret != -1) {
                                    return;
                                }
                                log("out c() mode=" + mode + " persist=" + param.args[2]
                                        + " -> " + ret);
                            } catch (Throwable th) {
                                err("c/out", th);
                            }
                        }
                    });
        } catch (Throwable th) {
            err("hook c", th);
        }

        // 镜头 id 重映射（IMMUNESYS）。
        // cand 就是 c() 内部算好的 iE3 —— 打出调用行号可区分
        //   line 267  = g() 路径（#not support aux camera）
        //   line 1348 = 末尾 iE3 = a(i9, iE3, i10)，iE3 是 5 还是 -1 就看这里
        try {
            XposedHelpers.findAndHookMethod(C_GETID, sCl, "a",
                    Integer.TYPE, Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int mode = ((Integer) param.args[2]).intValue();
                                int ret = ((Integer) param.getResult()).intValue();
                                if (mode != MODE_231) {
                                    return;
                                }
                                log("a() cand=" + param.args[1] + " -> " + ret
                                        + "  @" + callerLine());
                            } catch (Throwable th) {
                                err("a", th);
                            }
                        }
                    });
        } catch (Throwable th) {
            err("hook a", th);
        }
    }

    /** 取栈里第一层 B2.c.c(...) 的源码行号，用来定位是哪个调用点 */
    private static String callerLine() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 0; i < st.length; i++) {
                if ("B2.c".equals(st[i].getClassName())
                        && "c".equals(st[i].getMethodName())) {
                    return "B2.c.c:" + st[i].getLineNumber();
                }
            }
        } catch (Throwable th) {
        }
        return "?";
    }

    private static void dump(int bogus, int mode, boolean persist) {
        StringBuilder sb = new StringBuilder();
        sb.append("in c() bogus=").append(bogus).append(" mode=").append(mode)
                .append(" persist=").append(persist);

        // 镜头表：-1 表示这颗镜头在本机不存在（伪装机型后最可能的根因）。
        // 注意 p181v6 是 jadx 给「默认包」合成的包名，运行时类在默认包里（descriptor Lf;）。
        sb.append(" | lens: ");
        try {
            Object lens = XposedHelpers.callStaticMethod(findLens(), "R");
            sb.append("init=").append(call(lens, "isInitialized"));
            sb.append(" main=").append(call(lens, "e"));
            sb.append(" ultra=").append(call(lens, "j"));
            sb.append(" tele=").append(call(lens, "q"));
            sb.append(" stand=").append(call(lens, "K"));
            sb.append(" macro=").append(call(lens, "n"));
            sb.append(" m=").append(call(lens, "m"));
            sb.append(" z=").append(call(lens, "z"));
            sb.append(" L=").append(call(lens, "L"));
            sb.append(" r=").append(call(lens, "r"));
            sb.append(" u=").append(call(lens, "u"));
            sb.append(" x=").append(call(lens, "x"));
            sb.append(" i=").append(call(lens, "i"));
        } catch (Throwable th) {
            sb.append("ERR ").append(th);
        }

        // 注意：这里【不能】调 B2.c.* 的 helper（e/g/f/h/i/j/k/m）。
        // 诊断阶段调过，它们内部有 M1()/getPersistValue() 之类的写副作用，
        // 每次 dump 都会污染 c() 随后的计算 —— 这很可能就是「两次输入读起来完全一样」
        // 的元凶。镜头表类名 v6.f 已破解，直接读裸镜头表即可，纯读无副作用。

        // ★ e(0) 返回 -1 的唯一死路：
        //     if (!Sa.b.b()) { ... return f.R().e(); }   // 正常：主摄 5
        //     else if (Kr.c.d()) return -1;              // ← 死路
        // Sa.b.b() = marketname.contains(Build.DEVICE) && !j   ← 伪装机型的直接嫌疑
        sb.append(" | gate: SaB=").append(st("Sa.b", "b"));
        try {
            sb.append(" j=").append(staticField(XposedHelpers.findClass("Sa.b", sCl), "j"));
        } catch (Throwable th) {
            sb.append(" j=?");
        }
        sb.append(" KrC.d=").append(st("Kr.c", "d"));
        sb.append(" KrC.e=").append(st("Kr.c", "e"));
        try {
            sb.append(" market=").append(
                    staticField(XposedHelpers.findClass("Pe.c", sCl), "h"));
            sb.append(" dev=").append(android.os.Build.DEVICE);
        } catch (Throwable th) {
            sb.append(" market=?");
        }

        // 当前 231 的镜头角色组件值（U3.r 用它决定用哪颗镜头）
        sb.append(" | role=").append(st(C_DATA_N, "h", Integer.valueOf(mode)));
        sb.append(" zoom=").append(st(C_DATA_I, "O", Integer.valueOf(mode)));

        // 分支条件们
        sb.append(" | M0=").append(st(C_DATA_I, "M0", Integer.valueOf(mode)));
        sb.append(" z1=").append(st(C_DATA_I, "z1", Integer.valueOf(mode)));
        sb.append(" N0=").append(st(C_DATA_I, "N0"));
        sb.append(" m0=").append(st(C_DATA_N, "m0", Integer.valueOf(mode)));
        sb.append(" t0=").append(st(C_DATA_N, "t0", Integer.valueOf(mode)));
        sb.append(" nE=").append(st(C_DATA_N, "E", Integer.valueOf(mode)));
        sb.append(" FT=").append(st(C_DATA_F, "T", Integer.valueOf(mode)));
        sb.append(" sp=").append(st(C_DATA_S, "p", Integer.valueOf(mode)));
        sb.append(" so=").append(st(C_DATA_S, "o"));

        // 设备能力门（镜头/变焦策略开关）
        sb.append(" | dev: ");
        try {
            Object dev = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(C_PEB, sCl), "D");
            // P5 实际挂在 dev.e 上（e 是字段），直接调 dev.P5() 会失败
            try {
                sb.append("P5=").append(XposedHelpers.callMethod(
                        XposedHelpers.getObjectField(dev, "e"), "P5"));
            } catch (Throwable th) {
                sb.append("P5=?");
            }
            sb.append(" I2=").append(call(dev, "I2"));
            sb.append(" J2=").append(call(dev, "J2"));
            sb.append(" X1=").append(call(dev, "X1"));
            sb.append(" T1=").append(call(dev, "T1"));
            sb.append(" I1=").append(call(dev, "I1"));
            sb.append(" u0=").append(call(dev, "u0"));
            sb.append(" F=").append(XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(C_PEB, sCl), "F"));
        } catch (Throwable th) {
            sb.append("ERR ").append(th);
        }

        log(sb.toString());
    }

    /**
     * 镜头表类。
     * jadx 把包名 v6 改写成了 p181v6（加了 p181 前缀），class 简单名 f 没变 ——
     * 所以运行时 Class.forName("p181v6.f") 和 "f" 都必然失败（日志里那个
     * ClassNotFoundException: f 就是这么来的）。真实 descriptor = Lv6/f;，
     * 用 dex method_ids 解析验证过：R() 返回自身 + isInitialized() 都在 Lv6/f; 上。
     */
    private static Class<?> findLens() {
        String[] candidates = {"v6.f", "p181v6.f", "f"};
        Throwable last = null;
        for (int i = 0; i < candidates.length; i++) {
            try {
                return XposedHelpers.findClass(candidates[i], sCl);
            } catch (Throwable th) {
                last = th;
            }
        }
        throw new RuntimeException(String.valueOf(last));
    }

    /** 反射调实例方法，失败返回 "?" 而不是抛异常 */
    private static Object call(Object obj, String method) {
        try {
            return XposedHelpers.callMethod(obj, method);
        } catch (Throwable th) {
            return "?";
        }
    }

    /** 反射调静态方法，失败返回 "?" */
    private static Object st(String cls, String method, Object... args) {
        try {
            return XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(cls, sCl), method, args);
        } catch (Throwable th) {
            return "?";
        }
    }

    /** 读静态字段，失败返回 "?" */
    private static Object staticField(Class<?> cls, String name) {
        try {
            java.lang.reflect.Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable th) {
            return "?";
        }
    }

    private static synchronized void log(String msg) {
        String line = HEAD + msg;
        try {
            Log.i(TAG, line);
        } catch (Throwable th) {
        }
        try {
            if (sWriter == null) {
                java.io.File f = new java.io.File(
                        "/sdcard/Android/data/com.android.camera/files/camport",
                        "camport_motion.log");
                java.io.File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                        java.util.Locale.US).format(new java.util.Date()) + " I/运镜: "
                        + msg + "\n");
                sWriter.flush();
            }
        } catch (Throwable th) {
            sWriter = null;
        }
    }

    private static synchronized void err(String where, Throwable th) {
        String msg = String.valueOf(where) + " -> " + th;
        if (msg.equals(sLastError)) {
            return;
        }
        sLastError = msg;
        log("ERROR " + msg);
    }
}
