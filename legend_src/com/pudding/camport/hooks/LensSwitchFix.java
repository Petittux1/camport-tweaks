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
    /**
     * telefix 的作用域。
     *
     * 10-10 实测教训：`v6.f.q()` 是**全局**角色表，直接把它从 -1 改成 4 会连带
     * 让拍照模式的 `K7()` 拿到 `z3 = q()>0`、`LensSwitchZoomBounds` 含 5.0 ——
     * 拍照第一次开始走 close→open 切镜（log 里 `w9 放行 mode=163` 全天只在
     * telefix 打开后出现），5x/0.7 各糊半秒、2.6 档位不稳，是明确回归。
     *
     * 所以默认改成**只在专业模式的切镜作用域内**生效：
     *   J9 (= switchLensInPro，只有专业 ZoomManager j9.B.L8 调它)
     *     进入 → sInJ9 = true；退出 → false。
     *   期间读到的 q() 才补丁，其余任何时刻 q() 原样返回 -1。
     *   拍照模式走的是 K7，全程碰不到这个补丁。
     *
     * legend.telefix_global=1  = 恢复旧的全局补丁（会带上面那个回归，别开）
     * legend.telefix_modes=167 = 作用域内的模式集
     */
    private static volatile boolean sTeleGlobal = false;
    private static volatile String sTeleModes = "167";
    /** 正在 J9(switchLensInPro) 内且模式命中 → q() 补丁才生效 */
    private static volatile boolean sInJ9;
    /**
     * 10-12：q() 补丁的**反向作用域** —— 除了 K7 之外都补丁（默认开）。
     *
     * 上一版用「只在 J9 内补丁」，结果长焦还是切不过去。原因现在确定了：
     * J9 里选中 "tele" 之后，组件 "tele" 要在**重启时**解析回相机，
     * 而那次解析发生在 w9 → Ph.b.e → i8 的 close→open 流程里，
     * **在 J9 作用域之外** → q() 又变回 -1 → 没相机可开 → 回落 wide。
     * （铁证：日志里 telefix: q() -1->4 是在 `w9 放行` 之后才打的。）
     *
     * 10-10 telefix 全局开时长焦是真的能切的 —— 就因为重启那次解析也拿到 4。
     * 而当初收窄作用域，是因为全局会让拍照模式的 K7 也看到 z3=true
     * （铁证：w9 放行 mode=163 ×7，且首现于 telefix 生效之后）。
     *
     * 于是正确作用域是反过来的：**排除 K7 就够了**（那是拍照的切镜判定），
     * 其余地方（含重启解析）一律补丁 → 专业拿到真长焦、拍照不受影响。
     *
     * legend.telefix_notk7=0 → 退回旧的「只在 J9 内」作用域。
     */
    private static volatile boolean sTeleNotK7 = true;
    /** 正在 K7(switchCameraLens，拍照模式的切镜判定) 内 → 绝不补丁 */
    private static volatile boolean sInK7;
    /**
     * 强制打开专业模式的 SAT 无缝切镜（legend.satseamless，默认关）。
     *
     * 10-12 找到的「为什么不能像拍照模式一样」的真正答案：
     *   拍照模式 0.6x/5x 真的换了镜头，却一次都没调 w9（全天 163=7 次
     *   全部来自 10-10 那次回归）——因为它走的是 **SAT 无缝变焦**：
     *   框架自己切物理相机，ProModule/Camera2Module 只在
     *   onSATMasterIdChanged(masterId) 里被动同步组件，全程无 close→open。
     *
     * 而这条路径的总开关就是 i.n1(167)：
     *   J9:            if (z) { setComponentValue(...); if (!n1) { w9(); } }
     *   onSATMasterIdChanged: if (n1) { setComponentValue(同步组件) }
     * n1 为真 → 改组件但完全不重启（正是拍照模式的行为）。
     *
     * 本机 n1(167) 返回 false → 退回 close→open → 半秒糊。
     * 打开本开关会强制 n1(167)=true，同时把「是哪个条件挡的」打进日志。
     *
     * 风险与回退：若 SAT 在专业模式下并不真的切相机，0.7/5x 会停在原镜头
     * （HAL clamp）。此时把 legend.satseamless=0 + force-stop 相机即可回退。
     */
    private static volatile boolean sSat = false;
    private static volatile int sSatLog;
    /**
     * J9 本次的目标倍率（args[1]）。用来把 SAT 无缝只开给超广角。
     *
     * 10-12 实测：强制 n1(167)=true 之后，1↔0.7 完美（SAT 接管超广角），
     * 但 2.6→5「毫无反应」——因为 J9 返回 true → y0 早退、zoom 不下发，
     * 而框架对长焦又没接管 → 既不换镜头也不变焦。
     * 也就是说这台机器的 SAT 只覆盖超广角，长焦仍要走 close→open。
     */
    private static volatile float sJ9Target = -1f;
    /** J9 本次的起始倍率（args[0]）。5→2.6 时 from=5.0 也涉及长焦，必须走 w9。 */
    private static volatile float sJ9From = -1f;
    /**
     * 「涉及长焦」的倍率门槛（默认 4.5）：J9 任一端 ≥ 此值 → 不强制 n1 →
     * 走 w9 的 close→open 真切长焦（**会模糊半秒**）；两端都 &lt; 此值 →
     * 强制 n1=true → 无缝，不 w9（5→2.6、1↔0.7 走这条，所以完美）。
     *
     * 10-12 实测本机的不对称性：
     *   关长焦（tele→wide）无缝，因为 wide 是常驻主机，关掉副机不重启；
     *   开长焦（wide→tele）必须 close→open，所以 2.6→5 必糊半秒。
     *
     * legend.tele_gate=99 → 2.6→5 也强制 n1=true，不 w9：**不模糊**，
     * 但镜头不重启，5x 可能落在 wide 上（数字裁切）——效果需实测判定。
     * =4.5（默认）→ 保持真切长焦。
     */
    private static volatile float sTeleGate = 4.5f;
    /**
     * J9 把组件设成 "tele" 时改写成 "Standalone"（默认开）。
     *
     * 10-12 实测：本机 q()（tele 角色）恒为 -1，真正存在的是 K() = 4 = ultraTele。
     * telefix 把 J9 里的 q() 顶成 K() 之后，J9 会选 str="tele"，可组件 "tele"
     * 重启时要回落到 R().q() = -1 —— 没相机可开 → 回落 wide。于是
     * 「close→open 卡半秒」+「长焦没到手」两头落空（铁证：5→2.6 时 c0(i2)
     * 为真 = 那次重启后 i2 仍是 wide）。
     * 而 J9 的 switch 里本来就有接住真 5x 的分支：
     *     case "Standalone": T0(f.R().M(f.R().K()));
     * "Standalone" 也是 app 的一等镜头类型（data/data/i.java：
     * wide=0 / ultra=2 / tele=4 / Standalone=5）。
     *
     * kill switch：legend.telefix_standalone=0 → 不改写。
     */
    private static volatile boolean sTeleStandalone = true;
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
        hookSat();
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
        // ① 先把作用域挂上：J9 = switchLensInPro，只有专业模式的 ZoomManager
        //    （j9.B.L8）会调它，所以置位期间必然在专业切镜里。
        //    注意 ProZoomAnimFix 也 hook J9 并可能在渐变窗口内 setResult(false)：
        //    那种情况下 Xposed 会跳过本 before 钩子、但仍然跑 after 钩子，
        //    sInJ9 保持 false → q() 不补丁 → 无副作用。
        if (!sTeleGlobal) {
            try {
                XposedHelpers.findAndHookMethod("i9.e", sCl, "J9",
                        Float.TYPE, Float.TYPE, Integer.TYPE, Integer.TYPE,
                        XposedHelpers.findClass(
                                "com.android.camera.module.a0", sCl),
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(
                                    MethodHookParam p) throws Throwable {
                                // 只记起始/目标倍率，供 n1 钩子判断是否涉及长焦。
                                // 不依赖 getModuleIndex（那个抛异常会把整段跳过）。
                                try {
                                    sJ9From = ((Float) p.args[0]).floatValue();
                                    sJ9Target = ((Float) p.args[1]).floatValue();
                                } catch (Throwable ignore) {
                                }
                                try {
                                    sInJ9 = teleWanted((Integer) XposedHelpers
                                            .callMethod(p.args[4],
                                                    "getModuleIndex"));
                                } catch (Throwable ignore) {
                                    sInJ9 = false;
                                }
                            }

                            @Override
                            protected void afterHookedMethod(
                                    MethodHookParam p) throws Throwable {
                                sInJ9 = false;
                            }
                        });
                log("hook i9.e.J9 作用域 ok（记 sInJ9，配合 telefix_notk7）modes="
                        + sTeleModes);
            } catch (Throwable th) {
                // J9 挂不上 → 退回旧行为，宁可有回归也不要「以为修好了」
                sTeleGlobal = true;
                log("!! hook J9 作用域失败，telefix 退回全局 " + th);
            }
        }
        // K7 = switchCameraLens，拍照模式的切镜判定。
        // 作用域是「除了 K7 之外都补丁」，所以这个 hook 是整个方案的关键：
        // 挂不上就必须退回全局，宁可有拍照回归也不要「以为修好了」。
        try {
            XposedHelpers.findAndHookMethod("i9.e", sCl, "K7",
                    Float.TYPE, Float.TYPE,
                    XposedHelpers.findClass(
                            "com.android.camera.module.a0", sCl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            sInK7 = true;
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            sInK7 = false;
                        }
                    });
            log("hook i9.e.K7 ok（拍照切镜判定内不补丁 q()）");
        } catch (Throwable th) {
            sTeleGlobal = true;
            log("!! hook K7 失败，telefix 退回全局（会有拍照回归） " + th);
        }
        try {
            XposedHelpers.findAndHookMethod(C_CFG, sCl, "q", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (sInK7 && !sTeleGlobal) {
                        // K7 = 拍照模式的切镜判定。顶成有长焦会让 163 也去切镜
                        //（铁证：w9 放行 mode=163 ×7）——这一处必须放行原值
                        return;
                    }
                    if (!sTeleGlobal && !sTeleNotK7 && !sInJ9) {
                        return;   // 旧的窄作用域：只在 J9 内（长焦切不过去，别用）
                    }
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
                    log("telefix: v6.f.q() " + orig + " -> " + kv
                            + "  (roleId23 ultraTele 5x)  scope="
                            + (sTeleGlobal ? "global" : "J9/167"));
                }
            });
            log("hook " + C_CFG + ".q() telefix ok  global=" + sTeleGlobal
                    + " modes=" + sTeleModes);
        } catch (Throwable th) {
            log("!! hook 失败 " + C_CFG + ".q() -> " + th);
        }
        hookStandalone();
    }

    /**
     * 见 sTeleStandalone 上的说明：把专业切镜里 setComponentValue(mode,"tele")
     * 改写成 "Standalone"，重启才会真的落到那颗 5x（K()=ultraTele）上。
     */
    private static void hookStandalone() {
        if (!sTeleStandalone) {
            log("telefix_standalone 关闭 (legend.telefix_standalone=0)");
            return;
        }
        try {
            Class<?> c0cls = XposedHelpers.findClass("r2.C0", sCl);
            java.lang.reflect.Method decl = null;
            // setComponentValue 声明在父类（实测是 r2.A），逐层找，
            // 找到哪个类就 hook 哪个类（XposedHelpers 不会向上遍历）
            for (Class<?> c = c0cls; c != null; c = c.getSuperclass()) {
                try {
                    decl = c.getDeclaredMethod("setComponentValue",
                            Integer.TYPE, String.class);
                    break;
                } catch (NoSuchMethodException ignore) {
                }
            }
            if (decl == null) {
                log("!! 找不到 setComponentValue(int,String) —— tele→Standalone 不生效");
                return;
            }
            String owner = decl.getDeclaringClass().getName();
            XposedHelpers.findAndHookMethod(owner, sCl, "setComponentValue",
                    Integer.TYPE, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                if (!sTeleGlobal && !sInJ9) {
                                    return;   // 只在专业切镜里改写，别处不碰
                                }
                                if (!"tele".equals(p.args[1])) {
                                    return;
                                }
                                if (!teleWanted(
                                        ((Number) p.args[0]).intValue())) {
                                    return;
                                }
                                p.args[1] = "Standalone";
                                log("telefix: setComponentValue(mode="
                                        + p.args[0] + ", \"tele\") → "
                                        + "\"Standalone\"（本机无 tele 角色，"
                                        + "真 5x = K() ultraTele）");
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook " + owner
                    + ".setComponentValue ok（tele→Standalone）");
        } catch (Throwable th) {
            log("!! hook setComponentValue 失败 " + th);
        }
    }

    /**
     * 见 sSat 上的说明：i.n1(167) 是专业模式 SAT 无缝切镜的总开关。
     * 这里①按需强制它为 true，②把原始判定和四个条件值打出来，
     * 好知道本机到底是被哪一条挡的。
     */
    private static void hookSat() {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.camera.data.data.i", sCl, "n1",
                    Integer.TYPE, Boolean.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = ((Number) p.args[0]).intValue();
                                if (mode != 167 || !sSat) {
                                    return;
                                }
                                // 用 sInJ9 区分两个调用点（n1 被调时 sInJ9 必然为 true，
                                // 因为 J9 body 执行了才调得到；onSATMasterIdChanged
                                // 调用时 sInJ9 必然为 false）：
                                //   J9 内：涉及长焦（任一端 ≥4.5）→ 不强制，走 w9
                                //          不涉及长焦 → 强制 true，HAL 自动切 ultra
                                //   J9 外（onSATMasterIdChanged）：始终强制 true，
                                //          否则组件不同步 → 状态错乱、数字不正确
                                if (sInJ9 && (sJ9From >= sTeleGate
                                        || sJ9Target >= sTeleGate)) {
                                    return;
                                }
                                p.setResult(Boolean.TRUE);
                                log("satseamless: n1(167) 强制 true → "
                                        + (sInJ9 ? "J9 内，目标 " + sJ9Target
                                                : "onSATMasterIdChanged 同步组件"));
                            } catch (Throwable ignore) {
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                if (((Number) p.args[0]).intValue() != 167) {
                                    return;
                                }
                                if (sSatLog >= 4) {
                                    return;   // 一次开相机最多 4 条
                                }
                                sSatLog++;
                                Object r = p.getResult();
                                boolean cur = (r instanceof Boolean)
                                        && ((Boolean) r).booleanValue();
                                // 四个条件：!G3() || l0() || p0(167,N) || (J3(N)&&a0(167))
                                log("n1(167) 原值=" + cur + " 强制=" + sSat
                                        + "  条件: G3=" + satCond("l9.h", "G3")
                                        + " l0=" + satCond(
                                                "com.android.camera.data.data.n",
                                                "l0"));
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook com.android.camera.data.data.i.n1(int,boolean) ok（SAT无缝）"
                    + " 强制=" + sSat);
        } catch (Throwable th) {
            log("!! hook n1 失败 " + th);
        }
    }

    private static String satCond(String cls, String mtd) {
        try {
            return String.valueOf(XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(cls, sCl), mtd));
        } catch (Throwable th) {
            return "?";
        }
    }

    private static boolean teleWanted(int mode) {
        for (String t : sTeleModes.split(",")) {
            if (t.trim().equals(String.valueOf(mode))) {
                return true;
            }
        }
        return false;
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
                    } else if ("legend.telefix_global".equals(k)) {
                        sTeleGlobal = "1".equals(v);
                    } else if ("legend.telefix_modes".equals(k)) {
                        sTeleModes = v;
                    } else if ("legend.telefix_standalone".equals(k)) {
                        sTeleStandalone = !"0".equals(v);
                    } else if ("legend.telefix_notk7".equals(k)) {
                        sTeleNotK7 = !"0".equals(v);
                    } else if ("legend.satseamless".equals(k)) {
                        sSat = "1".equals(v);
                    } else if ("legend.tele_gate".equals(k)) {
                        try {
                            sTeleGate = Float.parseFloat(v.trim());
                        } catch (Throwable ignored) {
                        }
                    } else if ("legend.lensswitch_minzoom".equals(k)) {
                        try {
                            sMinZoom = Float.parseFloat(v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                log("cfg=" + f.getPath() + " enabled=" + sEnabled + " modes=" + sModes
                        + " tele=" + sTele + " teleGlobal=" + sTeleGlobal
                        + " teleModes=" + sTeleModes
                        + " notK7=" + sTeleNotK7
                        + " standalone=" + sTeleStandalone
                        + " sat=" + sSat
                        + " teleGate=" + sTeleGate
                        + " minzoom=" + sMinZoom);
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
