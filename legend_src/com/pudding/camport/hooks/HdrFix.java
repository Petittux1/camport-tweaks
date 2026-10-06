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
import java.lang.ref.Reference;
import java.lang.reflect.Field;

/**
 * AI帮拍「镜头对着特别亮的地方卡住几秒」 —— 挡掉运行时自动 HDR 触发的流水线重配。
 *
 * ============================================================
 * 一、现象（logcat 全量，12 场一次例外都没有）
 * ============================================================
 * 现象：AI帮拍 拍照后画面定住约 4.8s（照片其实已存进相册），然后自动恢复。
 *
 * 每一场的时序都是同一条链，且 HDR 那一步到 HAL 报错只有几十~几百毫秒：
 *
 *   13:11:19.163  enterMutexMode: hdrType = 1
 *   13:11:19.163  setHDR status: 0x1                （同一毫秒）
 *   13:11:19.219  PrepareForRecovery  (+56ms)
 *   13:11:19.590  PrepareForRecovery  (每次 ≈380ms，共 5~6 次)
 *   13:11:21.136  raising sigabort   → vendor.qti.camera.provider SIGABRT
 *   13:11:22.xxx  onCameraException mid=168 → PostProc/BG service binder failed ×7
 *   13:11:26.xxx  onModeSelected 0xa8→0xa8 重建会话 → firstFrame ≈ 100ms
 *
 * native 侧：
 *   chxextensionmodule.cpp  PrepareForRecovery() Usecase:6 cameraId:7 is in bad state ×6
 *   → FATAL: Consecutive 6 recovery detected ... stop trying recovery, raising sigabort.
 *   → cameraserver: Camera provider has died; removing it
 *
 * ============================================================
 * 二、统计证据（cap4.log，10-02 全天）
 * ============================================================
 *   · 12 场 sigabort，**100% 发生在 mode 0xa8（AI帮拍，index=168）**；其它模式 0 场。
 *   · 12 场的紧邻前置 **全部** 是 enterMutexMode: hdrType = 1 + setHDR status: 0x1。
 *   · AI帮拍 里 hdrType = 5（超分HDR）出现多次 → PrepareForRecovery 0 次、崩溃 0 次。
 *   · hdrType = 1 在普通拍照(0xa3) 里也出现多次 → 同样 0 次崩溃。
 *   → **「AI帮拍 + 运行时进入普通HDR(hdrType=1)」是崩溃的必要条件（12/12）。**
 *
 *   · AI帮拍 第一次被打开是 13:09:08.522；第一次 sigabort 是 13:11:19.219。
 *     13:09 之前全天没有 AI帮拍，也就全天没有 sigabort —— 这就是「以前没这个问题」。
 *
 * ============================================================
 * 三、hdrType 是怎么算出来的
 * ============================================================
 * com.android.camera.module.Camera2Module$c.a(int)：
 *
 *     int i10 = (Pe.b.b.a.m2() && r9.isSuperResolutionHDR()) ? 5 : 1;   // 5=超分HDR 1=普通HDR
 *     if (r9.mHdrManager.f15607f)                 i10 |= 2;
 *     if (i10 == 1 && isMultipleRawHdrSupported()) i10 |= 8;
 *     Log.d(TAG, "enterMutexMode: hdrType = " + i10);
 *     r9.mCameraManager.K0().M(new c5.m(i10));       // ← 下发给 HAL，切 usecase 6
 *
 * 而 isSuperResolutionHDR()：
 *     HashMap m = l9.h.C0(caps);
 *     if (m != null) return Kr.i.q(camera, m, zoom);
 *     return zoom > 1.0f && "auto".equals(HDR组件值);   // 兜底要求 zoom>1.0
 * 本机 l9.h.C0() 能力表为 null，1x 焦段下兜底恒 false → hdrType 只能是 1。
 *
 * ============================================================
 * 四、触发入口（挂点）
 * ============================================================
 * p6.b.onHdrSceneChanged(boolean)   —— HDRManager，进入 HDR 互斥的唯一入口：
 *
 *     if (r9 == null || r9.getModuleState().s() || g(z8)) return;
 *     j(z8);                                   // 打 "mAutoHDRTargetState:true"
 *     if (this.f15606e == z8) return;
 *     ...
 *     if (z8) {                                 // ← 进入
 *         if (!b()) { ...return; }
 *         if (mutexModePicker.b == 0 || mutexModePicker.b()) mutexModePicker.e(1);
 *     } else if (mutexModePicker.b == 1 || ...) {   // ← 退出（正常路径，安全）
 *         mutexModePicker.d();
 *     }
 *
 * 12 场崩溃的调用栈都是：
 *     setMutexMode 1, caller: p6.b.onHdrSceneChanged(SourceFile:152),
 *                          s6.O.consumeResultOnMainThreadIfDataChanged(SourceFile:182)
 *
 * ============================================================
 * config: ai.hdrfix = 0|1|2
 * ============================================================
 *   0 = 关（默认，不干预）
 *   1 = 只挡「进入」：mode==168 时把 onHdrSceneChanged 的 true 改成 false。
 *       mutexModePicker 永远停在 0 → 不会 enterMutexMode → 不会 setHDR0x1
 *       → 不会切 usecase → 不会 bad state / sigabort。
 *       退出分支照常放行（且因为从没进入，实际不会走到）。
 *       代价：AI帮拍 在亮场不合并 HDR（单帧），高光可能过曝。
 *   2 = 不挡进入，改成强制走超分HDR：isSuperResolutionHDR() → true
 *       → hdrType = 5（日志里在 AI帮拍 出现过、0 崩溃的那条路），HDR 仍然生效。
 *       注意：它同时会让 updateSRAndMFNR() 多下发一个 hdrType=4，
 *             属于较激进的档位，先用 1 验证因果，再试 2。
 *
 * 只改配置不用重装： su -c am force-stop com.android.camera
 *
 * ============================================================
 * dex 类名对照（已在原始 dex 里 grep 过 descriptor）
 * ============================================================
 *   dex Lp6/b;              == jadx 反编译成 p130p6/b.java（jadx 因包名冲突改了名）
 *                            .onHdrSceneChanged(boolean)  → HDRManager 进/出 HDR 互斥
 *                            里面唯一一个指向 module 的字段是 WeakReference，
 *                            jadx 叫 f15603a、dex 真名未知 → 这里用「扫 Reference
 *                            字段 + 试调 getModuleIndex()」来取，取到就缓存。
 *   dex Lcom/android/camera/module/Camera2Module;
 *                            .isSuperResolutionHDR() → boolean（未混淆，栈里可验证）
 *   AI帮拍 mode index = 168 = 0xa8
 */
public final class HdrFix implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "hdrfix: ";

    private static final String C_CAMERA_PKG = "com.android.camera";

    /** dex Lp6/b; —— HDRManager（jadx 里是 p130p6.b） */
    private static final String C_HDRMGR = "p6.b";
    private static final String M_ON_HDR = "onHdrSceneChanged";

    /** dex Lcom/android/camera/module/Camera2Module; —— 未混淆 */
    private static final String C_C2M = "com.android.camera.module.Camera2Module";
    private static final String M_SRHDR = "isSuperResolutionHDR";

    /** AI帮拍 mode index = 168 = 0xa8 */
    private static final int MODE_AI = 168;

    private static ClassLoader sCl;
    private static boolean sInstalled;

    // ---- config ----
    private static volatile boolean sCfgDone;
    private static volatile int sLevel;      // 0..2

    /** 缓存 p6.b 里那个指向 module 的 Reference 字段（dex 真名未知，只扫一次） */
    private static Field sRefField;
    private static boolean sRefGiveUp;

    private static int sBudget = 8;

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
        log("installed ai.hdrfix=" + sLevel
                + (sLevel <= 0 ? "  (关，不干预)" : ""));
        if (sLevel <= 0) {
            return;
        }

        // ① =1：只挡「进入」（10-02 的 12/12 enterMutexMode→sigabort 主档）
        // ② =2：不挡进入，强制 isSuperResolutionHDR() → hdrType=5（10-02 数据里
        //       AI帮拍出现过、0 崩溃的那条路，HDR 仍然生效）；若强制挂点失败
        //       则退回 ① 的阻断兜底。10-06 实测 level1 下仍偶发
        //       CSLMapBufferHW fd=0 → PrepareForRecovery×6 → SIGABRT，故试 2。
        if (sLevel == 1) {
            hookHdrSceneChanged();
        } else if (sLevel >= 2) {
            if (!hookSuperResolutionHdr()) {
                log("level2 强制超分HDR 失败 → 退回阻断（level1 语义）");
                hookHdrSceneChanged();
            }
        }
    }

    /** 挡住 p6.b.onHdrSceneChanged(true)：只在 AI帮拍 里、只挡进入 */
    private static void hookHdrSceneChanged() {
        try {
            XposedHelpers.findAndHookMethod(C_HDRMGR, sCl, M_ON_HDR, Boolean.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                if (p.args == null || p.args.length < 1) {
                                    return;
                                }
                                // 只挡「进入」(true)，退出(false) 照常放行
                                if (!Boolean.TRUE.equals(p.args[0])) {
                                    return;
                                }
                                int mode = moduleIndexOf(p.thisObject);
                                if (mode != MODE_AI) {
                                    return;
                                }
                                p.args[0] = Boolean.FALSE;
                                budget("阻断 AI帮拍 进入自动HDR互斥"
                                        + " (isInHdr true -> false, 不会 enterMutexMode)");
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook " + C_HDRMGR + "#" + M_ON_HDR + " ok");
        } catch (Throwable th) {
            log("!! hook " + C_HDRMGR + "#" + M_ON_HDR + " 失败 " + th);
        }
    }

    /** 强制 isSuperResolutionHDR() → true，让 hdrType 走 5（超分HDR）；挂点成功返回 true */
    private static boolean hookSuperResolutionHdr() {
        try {
            XposedHelpers.findAndHookMethod(C_C2M, sCl, M_SRHDR, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        if (Boolean.TRUE.equals(p.getResult())) {
                            return;
                        }
                        Object mode = XposedHelpers.callMethod(p.thisObject, "getModuleIndex");
                        if (!(mode instanceof Integer)
                                || ((Integer) mode).intValue() != MODE_AI) {
                            return;
                        }
                        p.setResult(Boolean.TRUE);
                        budget("isSuperResolutionHDR false -> true (hdrType 1 -> 5)");
                    } catch (Throwable th) {
                        err(th);
                    }
                }
            });
            log("hook " + C_C2M + "#" + M_SRHDR + " ok");
            return true;
        } catch (Throwable th) {
            log("!! hook " + C_C2M + "#" + M_SRHDR + " 失败 " + th);
            return false;
        }
    }

    /**
     * 从 HDRManager 实例里取到它引用的 module，再取 mModuleIndex。
     * HDRManager 只有一个 WeakReference 字段，但 dex 里的真名未知（jadx 叫 f15603a），
     * 所以按类型扫一遍，找到那个能响应 getModuleIndex() 的就缓存下来。
     * 取不到返回 Integer.MIN_VALUE（调用方会当成「不是 AI帮拍」放行，最坏是不生效）。
     */
    private static int moduleIndexOf(Object hdrMgr) {
        try {
            if (hdrMgr == null) {
                return Integer.MIN_VALUE;
            }
            if (sRefGiveUp) {
                return Integer.MIN_VALUE;
            }
            Field f = sRefField;
            if (f == null) {
                Field[] fs = hdrMgr.getClass().getDeclaredFields();
                for (int i = 0; i < fs.length; i++) {
                    Field c = fs[i];
                    if (!Reference.class.isAssignableFrom(c.getType())) {
                        continue;
                    }
                    try {
                        c.setAccessible(true);
                        Integer r = readIndex(c.get(hdrMgr));
                        if (r != null) {
                            sRefField = c;
                            return r.intValue();
                        }
                    } catch (Throwable ignore) {
                    }
                }
                // 这次没找到不代表永远没有（可能目标已被 GC），下回再试
                return Integer.MIN_VALUE;
            }
            f.setAccessible(true);
            Integer r = readIndex(f.get(hdrMgr));
            return (r == null) ? Integer.MIN_VALUE : r.intValue();
        } catch (Throwable th) {
            return Integer.MIN_VALUE;
        }
    }

    /** 解引用后调 getModuleIndex()；字段名/方法名对不上就返回 null */
    private static Integer readIndex(Object refHolder) {
        Object target = (refHolder instanceof Reference)
                ? ((Reference) refHolder).get()
                : refHolder;
        if (target == null) {
            return null;
        }
        Object v = XposedHelpers.callMethod(target, "getModuleIndex");
        return (v instanceof Integer) ? (Integer) v : null;
    }

    /** 限流打日志：HDR 场景判定在亮场会高频触发，不能刷爆 logcat */
    private static void budget(String msg) {
        synchronized (HdrFix.class) {
            if (sBudget <= 0) {
                return;
            }
            sBudget--;
            log(msg + (sBudget == 0 ? "  (后续同类日志已静音)" : ""));
        }
    }

    /** 读 config.conf 的 ai.hdrfix；只读一次 */
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
                    if ("ai.hdrfix".equals(k)) {
                        sLevel = Integer.parseInt(v);
                    }
                } catch (Throwable ignore) {
                }
            }
            if (sLevel < 0) {
                sLevel = 0;
            }
            if (sLevel > 2) {
                sLevel = 2;
            }
            log("cfg=" + chosen + " ai.hdrfix=" + sLevel);
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
                        + " I/hdrfix: " + msg + "\n");
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
