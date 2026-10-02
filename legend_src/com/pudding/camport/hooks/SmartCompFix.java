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
import java.util.ArrayList;
import java.util.List;

/**
 * AI帮拍「智能构图」开关点了没反应 —— 把被 HAL 缺失能力卡死的四道门按需抬开。
 *
 * ============================================================
 * 一、现象与根因（logcat 全量统计，一次例外都没有）
 * ============================================================
 * 现象：AI帮拍面板里点「智能构图」没有任何反应；而「效果推荐」「姿势引导」都正常。
 *
 * 点击处理器 **是执行了的**（MCAM_FragmentAi: onFeatureSmartComposition 抓到 2 次），
 * 但它读当前状态用的是 x.t0(mode)，而：
 *
 *     x.t0(mode)  ==  H.isSwitchOn(mode)
 *     H.isSwitchOn(int i) {
 *         if (this.f17374a) return "ON".equals(getComponentValue(i));
 *         return false;                      // ← 连刚写进去的 pref 都不读
 *     }
 *     H.isSupportMode(int i) { return this.f17374a; }
 *
 * f17374a 由 H.R()（= ComponentGlobalSmartComposition reInit）依次判四道门，
 * 第一道就死了：
 *
 *     if (!h.U3(caps)) { log("is support Capabilities: false"); f17374a = false; return; }
 *
 *     public static boolean U3(l9.g caps) {
 *         Integer v = (Integer) E0.i(caps.d, w0.V4, 57005);   // 57005 = 0xDEAD
 *         ...                       // w0.V4.b() == "com.xiaomi.camera.autoCrop.autoCropVersion"
 *         return (v == null ? 0 : v) == 2;
 *     }
 *
 * 于是：
 *      SupportSmartCompositionVersion      252/252 全 null
 *      "is support Capabilities: false"     76/76
 *      applySmartCompositionEnable         168/168 全 "false,isTagDefined:false"
 *      updateSmartCompositionFromASD         0 次     ← 构图引擎从没启动过
 *
 * 开关永远读到 OFF → 每次点击都写 ON（没人读）→ updateView:2935 又 zt(x.t0()) 把图标拨回，
 * 视觉上就是「按了没反应」。另外 13:11:39/40 还有 3 次 onClick ignore while analyzing，
 * 那是 AI 分析未完成时点击被直接丢弃，是第二个独立的「没反应」来源。
 *
 * 为什么「效果推荐 / 姿势引导」能用：它们走 C1249c.isSwitchOn()（另一个 component，
 * 不碰 f17374a）+ 云端 AiAgentRequestManager / AiPoseRequest，完全不依赖 HAL。
 *
 * ============================================================
 * 二、这台机器（小米17 Pro / pandora）到底缺了什么
 * ============================================================
 * 智能构图的三个 vendor tag 都在 com.xiaomi.camera.autoCrop.* 这一节：
 *      com.xiaomi.camera.autoCrop.autoCropVersion      ← U3 读的能力版本
 *      com.xiaomi.camera.autoCrop.autoCropSupportSize  ← t0 读的支持比例
 *      com.xiaomi.camera.autoCrop.autoCropEnable       ← A0.y4，下发给 HAL 的开关
 *
 * 而 chi override 注册的 com.xiaomi.camera.* section 一共 21 个，**没有 autoCrop**：
 *      VideoAinr / WbData / afinfo / algoup / asd / bokehCaps / bokehConfig /
 *      bugHunter / captureSat / catchlog / dualcal / expfeedback / extension /
 *      quicksnapshot / realSquare / sdk / sensorpolicy / smoothTransition /
 *      supportedfeatures / videoFilter / videosat
 * 全系统（/odm /vendor /system_ext /system /apex）搜 autoCropVersion / autoCropSupportSize
 * 一无所获；只有 autoCropEnable 一个字符串孤零零躺在 /system_ext/lib64/libcammsger.so。
 *
 * isTagDefined 查的那张表 s0 也不是能造假的：
 *      l9.g 构造器里  this.f  ←  CameraCharacteristics.getAvailableCaptureRequestKeys()
 *                              ←  或 getAllVendorKeys()   —— HAL 自报的权威清单
 *
 * ============================================================
 * 四道门（H.R() 依次判，全过才 f17374a = true）
 * ============================================================
 *   ① h.U3(caps)          autoCropVersion == 2        ← 这台机器恒 null
 *   ② intent type == 0
 *   ③ mode ∈ {163, 168}   AI帮拍 = 168                ← 本来就过
 *   ④ caps.y() == 1       LENS_FACING                 ← 未知，日志会告诉我们
 *   （m() 里另有比例门：h.t0(caps).contains(当前比例)，autoCropSupportSize 为 null 时
 *     默认只认 ["4x3"]，而日志里出现过 "not support ratio: 3x2"）
 *
 * ============================================================
 * config: ai.smartcomp = 0|1|2|3|4
 * ============================================================
 *   0 = 关（默认，不干预）
 *   1 = 抬能力门    hook l9.h.U3  → true
 *                   ★ 外科手术档：同时把 A3.g.w()（submitAiComposition）做成 no-op，
 *                     见 ai.smartcomp.submit。
 *   2 = 1 + 比例门  hook l9.h.t0  → 原结果 ∪ 常见比例
 *   3 = 2 + 下发门  hook l9.g.S0  → 对 autoCropEnable 返回 true
 *                  （E0.m 全程 try/catch 且 E0.e 的默认码是 57005，
 *                    a() 只对 51966 抛异常、47806 打警告，57005 两个都不是
 *                    → 静默吞掉，最坏是 no-op，不会崩；
 *                    且 m() 里有 "trySetValue: key=..." 可查有没有真下发）
 *   4 = 3 + 兜底门  u2.H.R() 之后把 f17374a 字段强制为 true
 *                  （绕过 ②④，字段 dex 真名是 a，jadx 改叫 f17374a）
 *
 * config: ai.smartcomp.submit = 0|1   （只有 ai.smartcomp >= 1 时才读）
 * ============================================================
 *   0 = 挡掉 submitAiComposition（默认）。A3.g.w() 变 no-op，只挡这一条，
 *       x()=submitAiPose / y()=submitAiTuning 在它之前已经发完，不受影响。
 *       挡的理由：U3 抬门后开关会锁住 → x.t0(mode) 变 true → w() 会被触发，
 *       AI 分析不回来时 FragmentAi 的 "onClick ignore while analyzing"
 *       会丢掉后续点击，退化成另一种「点了没反应」。
 *   1 = 放行，观察 submitAiComposition 发出去之后会怎样。
 * 改完 force-stop 相机生效，不用重装。
 *
 * 只改配置不用重装： su -c am force-stop com.android.camera
 *
 * ============================================================
 * dex 类名对照（jadx 改过名，全部已在原始 dex 里 grep 验证 descriptor）
 * ============================================================
 *   jadx p081l9.C1414h  ==  dex Ll9/h;    （renamed from: l9.h, case insensitive filesystem）
 *        .U3(l9.g) -> boolean     SupportSmartCompositionVersion 门
 *        .t0(l9.g) -> List        SupportSmartCompositionSize 门（唯一调用方
 *                                 u2 之外只有 r6.W.ka()，注解 key="isSupportSmartCompositon"）
 *   jadx p081l9.C1412g  ==  dex Ll9/g;    （renamed from: l9.g）
 *        .S0(String) -> boolean   "这个 vendor tag 在 HAL 自报表里吗"
 *   jadx p168u2.H       ==  dex Lu2/H;    （未改名）
 *        .R(com.android.camera.data.data.C)  ComponentGlobalSmartComposition reInit
 *        字段 f17374a  dex 真名 = a（renamed from: a, collision with root package name）
 *   jadx A3.g           ==  dex LA3/g;    ← AiFeatureSubmitHelper，注意大小写！
 *        dex 里 LA3/g; 与 La3/g; 两个包并存（method_ids 直读核对：
 *        LA3/g; 有 w/x/y，La3/g; 只有 Ak/registerProtocol/unRegisterProtocol）。
 *        jadx 输出文件系统不分大小写，把 a3 改名成 p004a3 保留了 A3。
 *        .w()   -> void  submitAiComposition   ← 本类要 no-op 的那个
 *        .x(int) -> void  submitAiPose          （先发自己的，最后才 if(x.t0(mode)) w()）
 *        .y(int) -> void  submitAiTuning
 */
public final class SmartCompFix implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "构图fix: ";

    private static final String C_CAMERA_PKG = "com.android.camera";

    /** dex Ll9/h; —— U3 能力门 / t0 比例门 */
    private static final String C_H = "l9.h";
    /** dex Ll9/g; —— S0(String) vendor tag 自报表 */
    private static final String C_G = "l9.g";
    /** dex Lu2/H; —— ComponentGlobalSmartComposition */
    private static final String C_COMP = "u2.H";
    /** dex 字段真名；jadx 里叫 f17374a */
    private static final String F_SUPPORT = "a";
    private static final String F_SUPPORT_JADX = "f17374a";
    /** H.R 的参数：com.android.camera.data.data.C（该包 jadx 没加前缀） */
    private static final String P_DATAC = "com.android.camera.data.data.C";

    private static final String TAG_ENABLE = "com.xiaomi.camera.autoCrop.autoCropEnable";

    /**
     * dex LA3/g; —— AiFeatureSubmitHelper（submitAiComposition / submitAiPose / submitAiTuning）。
     * 注意不是 La3/g;：dex 里 A3 和 a3 两个包并存，jadx 因为输出文件系统不分大小写，
     * 把 a3 改名成了 p004a3（DualVideoRecorderProtocol）而保留了 A3。
     * 已用 dex method_ids 直读核对：LA3/g; 有 w/x/y，La3/g; 只有 Ak/registerProtocol。
     */
    private static final String C_SUBMIT = "A3.g";

    /** t0 缺省只给 ["4x3"]，这里补齐常见比例，让 contains() 能过 */
    private static final String[] RATIOS = {
            "4x3", "3x2", "16x9", "9x16", "1x1", "full", "18x9", "20x9", "21x9"};

    private static ClassLoader sCl;
    private static boolean sInstalled;
    private static boolean sSubmitLogDone;

    // ---- config ----
    private static volatile boolean sCfgDone;
    private static volatile int sLevel;      // 0..4
    private static volatile int sSubmit;     // 0=挡 submitAiComposition  1=放行

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
        log("installed ai.smartcomp=" + sLevel
                + " submit=" + sSubmit
                + (sLevel <= 0 ? "  (关，不干预)" : ""));
        if (sLevel <= 0) {
            return;
        }

        // ① 能力门：autoCropVersion == 2
        if (sLevel >= 1) {
            try {
                XposedHelpers.findAndHookMethod(C_H, sCl, "U3", C_G, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try {
                            Object orig = p.getResult();
                            p.setResult(Boolean.TRUE);
                            budget("U3 " + orig + " -> true  (抬 autoCropVersion 门)");
                        } catch (Throwable th) {
                            err(th);
                        }
                    }
                });
                log("hook l9.h.U3 ok");
            } catch (Throwable th) {
                log("!! hook l9.h.U3 失败 " + th);
            }

            // ⑤ 构图请求闸门：A3.g.w() == submitAiComposition
            //    它只被本类的 x()(submitAiPose) / y()(submitAiTuning) 内部调用，
            //    全局没有外部调用者 —— 挡它不会影响姿势引导 / 效果推荐那两条。
            //    x() / y() 是先把自己的请求发完，最后才 if (x.t0(mode)) w()。
            //    默认挡住：U3 抬门之后开关会锁住 → x.t0(mode) 变 true → w() 就会被触发，
            //    而 AI 分析一旦不回来，FragmentAi 的 "onClick ignore while analyzing"
            //    会把后续点击全丢掉，退化成另一种「点了没反应」。
            if (sSubmit <= 0) {
                try {
                    XposedHelpers.findAndHookMethod(C_SUBMIT, sCl, "w", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                p.setResult(null);   // void 方法：跳过原方法体 = no-op
                                if (!sSubmitLogDone) {
                                    sSubmitLogDone = true;
                                    log("A3.g.w() 拦截 → submitAiComposition 不下发"
                                            + " (ai.smartcomp.submit=0，后续静音)");
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
                    log("hook A3.g.w ok (submitAiComposition 已挡)");
                } catch (Throwable th) {
                    log("!! hook A3.g.w 失败 " + th);
                }
            } else {
                log("submitAiComposition 放行 (ai.smartcomp.submit=1)");
            }
        }

        // ② 比例门：t0 返回 原结果 ∪ 常见比例
        if (sLevel >= 2) {
            try {
                XposedHelpers.findAndHookMethod(C_H, sCl, "t0", C_G, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try {
                            List<String> out = new ArrayList<String>();
                            Object orig = p.getResult();
                            if (orig instanceof List) {
                                for (Object o : (List) orig) {
                                    if (o != null) {
                                        out.add(String.valueOf(o));
                                    }
                                }
                            }
                            StringBuilder added = new StringBuilder();
                            for (int i = 0; i < RATIOS.length; i++) {
                                if (!out.contains(RATIOS[i])) {
                                    out.add(RATIOS[i]);
                                    if (added.length() > 0) {
                                        added.append(',');
                                    }
                                    added.append(RATIOS[i]);
                                }
                            }
                            p.setResult(out);
                            budget("t0 " + out + "  (补入: " + added + ")");
                        } catch (Throwable th) {
                            err(th);
                        }
                    }
                });
                log("hook l9.h.t0 ok");
            } catch (Throwable th) {
                log("!! hook l9.h.t0 失败 " + th);
            }
        }

        // ③ 下发门：让 applySmartCompositionEnable 的 isTagDefined 变 true
        if (sLevel >= 3) {
            try {
                XposedHelpers.findAndHookMethod(C_G, sCl, "S0", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                try {
                                    Object arg = p.args[0];
                                    if (!(arg instanceof String)
                                            || !TAG_ENABLE.equals(arg)) {
                                        return;   // 只放过这一个 tag，别的 capability 一概不碰
                                    }
                                    Object orig = p.getResult();
                                    p.setResult(Boolean.TRUE);
                                    budget("S0 " + TAG_ENABLE + " " + orig + " -> true  (放行下发)");
                                } catch (Throwable th) {
                                    err(th);
                                }
                            }
                        });
                log("hook l9.g.S0 ok (只认 autoCropEnable)");
            } catch (Throwable th) {
                log("!! hook l9.g.S0 失败 " + th);
            }
        }

        // ④ 兜底门：reInit 结束后强制 f17374a = true（绕过 intent type / facing）
        if (sLevel >= 4) {
            try {
                XposedHelpers.findAndHookMethod(C_COMP, sCl, "R", P_DATAC, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try {
                            boolean before = readSupport(p.thisObject);
                            writeSupport(p.thisObject, true);
                            boolean after = readSupport(p.thisObject);
                            budget("R() 后 f17374a " + before + " -> " + after);
                        } catch (Throwable th) {
                            err(th);
                        }
                    }
                });
                log("hook u2.H.R ok (强制 f17374a=true)");
            } catch (Throwable th) {
                log("!! hook u2.H.R 失败 " + th);
            }
        }
    }

    /** 读 f17374a：先试 dex 真名 a，再试 jadx 名，都失败返回 false */
    private static boolean readSupport(Object o) {
        try {
            return Boolean.TRUE.equals(XposedHelpers.getObjectField(o, F_SUPPORT));
        } catch (Throwable ignore) {
        }
        try {
            return Boolean.TRUE.equals(XposedHelpers.getObjectField(o, F_SUPPORT_JADX));
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static void writeSupport(Object o, boolean v) {
        try {
            XposedHelpers.setObjectField(o, F_SUPPORT, Boolean.valueOf(v));
            return;
        } catch (Throwable ignore) {
        }
        XposedHelpers.setObjectField(o, F_SUPPORT_JADX, Boolean.valueOf(v));
    }

    /** 门①③④ 都是低频的，② 也是；统一限流防刷屏 */
    private static synchronized void budget(String msg) {
        int b = sBudget;
        if (b > 0) {
            sBudget = b - 1;
            log(msg);
            if (b == 1) {
                log("(后续同类日志已静音)");
            }
        }
    }

    /** 读 config.conf 的 ai.smartcomp；只读一次 */
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
                    if ("ai.smartcomp".equals(k)) {
                        sLevel = Integer.parseInt(v);
                    } else if ("ai.smartcomp.submit".equals(k)) {
                        sSubmit = Integer.parseInt(v);
                    }
                } catch (Throwable ignore) {
                }
            }
            if (sLevel < 0) {
                sLevel = 0;
            }
            if (sLevel > 4) {
                sLevel = 4;
            }
            if (sSubmit < 0) {
                sSubmit = 0;
            }
            if (sSubmit > 1) {
                sSubmit = 1;
            }
            log("cfg=" + chosen + " ai.smartcomp=" + sLevel
                    + " ai.smartcomp.submit=" + sSubmit);
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
                        + " I/构图fix: " + msg + "\n");
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
