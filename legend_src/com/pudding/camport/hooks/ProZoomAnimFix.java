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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 专业模式点按焦段的渐变动画（修「换镜头没有动画，直接切换」）。
 *
 * ============================================================
 * 根因（log + 源码双证据，sc_two.log 17:40/17:44 对比）
 * ============================================================
 * 拍照点按：showZoomChildView → presenter Ds → Ns(from,to)
 *   （"Start Zoom Process Animator"，时长 = |e.b(to)-e.b(from)|，
 *     实测 1.0→2.0 走了 8 帧缓动 → 预览平滑推过去）。
 *
 * 专业点按：showZoomChildView → presenter Ds → Ms(from,to)
 *   H4.p0.Ms() 里写死：
 *       if (Pe.b...m4() || Pe.b.F()) {
 *           if (mode == 167 || mode == 180) setDuration(0);   // ← 0ms！
 *           else                            setDuration(100);
 *       }
 *   duration=0 → "Start Zoom Animator" → onAnimationUpdate 只有一帧
 *   （log: 17:44:45.911 单帧 setZoomRatio(): 2.6）→ 预览瞬间硬切。
 *
 *   第二层坑（实测：duration 改成 100 后仍硬切）：
 *   Ms 的 update 监听器 b(f10, z8, zN)：
 *       if ((f9<=0 || duration!=0) && |value-f9|>=eps && this.b)
 *           Js(interpolated);   // 逐帧插值 → 平滑
 *       else Js(f9);            // 只应用最终值 → 硬切
 *   专业分支里 z8 被写死 false（只有 pad/fold 的 150ms 分支才 true），
 *   所以每帧都走 else 把最终目标直接下发——duration 再大也没用。
 *
 * ============================================================
 * 修法（两刀，都在 Ms 执行期间的线程本地标记作用域内）：
 *   ① 拦 ValueAnimator.setDuration(long)：mode∈proanim_modes 且入参==0
 *      → 改写为 proanim_ms（默认 100，与本方法其它模式一致）。
 *   ② 拦监听器 H4.p0$b 的构造：把 z8 参数 false→true，
 *      update 逐帧下发插值（与 pad 分支/拍照 Ns 同款行为）。
 * 不碰 Ms 其余逻辑（插值器 I2.f.f、监听器 c、By.a.n 都原样），
 * 其它模式/其它动画器一概不受影响（标记线程本地且随 Ms 返回清除）。
 *
 * ============================================================
 * 第三层坑（v3，修「只有 0.7↔1 不行 / 切广角模糊半秒」）
 * ============================================================
 * Ds（点按派发，H4/p0.java:567）里跨 1.0 边界有两条「直跳」路：
 *   · else 分支（:748-757）先查 n0 阈值（f17≈1.0）：
 *       跨界 → Js(fM) 直跳；随后才 Ms(f1905l, fM)（若 Js 已把
 *       f1905l 同步成目标，Ms 变零区间空转 → 看不见渐变）。
 *   · As() 分支（:741-744）命中 zs(k1) 等条件时直接 Js，压根不走 Ms。
 * 两条都表现为：点 0.7/1 → 瞬移 + 换镜头模块重启的 blur 半秒。
 *
 * v3 做法（第三、四刀，作用域 = Ds 执行栈，线程本地 DsState）：
 *   ③ 拦 H4.p0.Ds(int,int)：进栈读 mode/mCurrentMode、当前倍率
 *      （dex 真名字段 "l"，jadx 叫 f1905l）；出栈时若发现直跳被拦
 *      且原方法没走 Ms（或走了但零区间），补调 Ms(cur, target)
 *      ——Ms 落在本修复①②的改写范围内 → 100ms 逐帧渐变。
 *   ④ 拦静态 H4.p0.Js(float,int)：仅当在 mode∈proanim_modes 的
 *      Ds 栈内才 setResult(null) 拦掉直跳（记录 target/action）；
 *      动画帧里的 Js（b 监听器每帧下发）sInDs 已清 → 原样放行。
 * 逐次点按全量落盘 proanim.log（Ds 进栈/直跳拦截/Ms 入口），
 * 便于实机对账（logcat 会被 HAL 刷屏冲掉）。
 *
 * ============================================================
 * 第四层坑（v4，修「渐变被换镜头重启掐死 + 半秒模糊」）
 * ============================================================
 * 实测（pro08.log 00:31 窗口）：渐变逐帧 → i9.e.J9 (= switchLensInPro,
 * dex 真名，classes3.dex 含字符串 "switchLensInPro: ") 每帧都收到
 * (prev, curr)；跨 1.0 的那一帧执行 setComponentValue(wide/ultra)
 * → setupCamera resetType=128（模块重启 = need blur true = 半秒模糊）：
 *   · 下行 1.0→0.7：第 1 帧(0.952)就触发重启 → 重启重置 UI/动画器，
 *     渐变被掐死，zoom 永远停在 0.952（到不了 0.7）。
 *   · 上行 →1.0：渐变走完最后一帧才触发 → 渐变可见，但收尾模糊半秒。
 * 而当前会话 ZoomRange=[0.7,10]、applyTargetZoom(0.7) 早已下发，
 * HAL 侧本来就能自己完成（拍照模式正是不干预才平滑）——app 的
 * 组件强制+重启是多余的破坏。
 *
 * v4 做法（第五刀）：Ms 起算 sRampUntil = now + proanim_ms + 150ms，
 * 窗口内拦 i9.e.J9(float,float,int,int,module.a0) 直接 setResult(false)
 * （= 「没切换」语义，与同侧帧的自然返回一致，调用方继续正常下发），
 * setComponentValue/重启不触发 → 渐变走完、无模糊。
 * 窗口外（捏合/其它模式）J9 原样放行。
 * 副作用研究：组件锁停在进入窗口前的值；下行后 ratio<1 而组件没切，
 * 若 HAL 拒绝 <1 请求（clamp）渐变会视觉上不动——待实测验证。
 *
 * ============================================================
 * 第五层坑（v5，修「专业 0.7 失效，点了还是 1」——上面的副作用已实测坐实）
 * ============================================================
 * 实测（proanim.log 18:40:13）：Ds(idx=0) cur=1.0 → Ms(1.0→0.7)，
 * 动画帧 1.0→0.949→…→0.7 全部执行、app 侧 f1905l 已到 0.7，
 * 但每一帧的 J9(…→<1.0) 都被 v4 拦掉 → setComponentValue("ultra")
 * 从不执行 → 组件一直停在主摄 → HAL clamp <1.0 → 预览永远 1x。
 * （J9 里 str="ultra" 只在 f2<1.0 那几帧成立，拦掉 = 彻底没有切镜时机。）
 *
 * v5 做法（第六刀）：拦还是拦，但**涉及 <1.0 的那次 J9 记账不丢**：
 *   窗口内 J9 且 (prev<1.0 || curr<1.0) → 存 sPf/sPf2/sPi/sPi2/sPa0，
 *   并安排 sRampUntil+60ms 后在主线程补发一次 callStaticMethod(J9)。
 *   补发时窗口已过 → 原样放行 → 切超广角重启，此时 app zoom 已是 0.7 →
 *   重启落地即 0.7。渐变期间组件不切（v4 的无模糊渐变保住），
 *   渐变结束后才切（多 ~200ms + 一次重启模糊，但 0.7 能到）。
 *   ≥1.0 的窗口内调用（1↔2.6↔5，str 恒 "wide"，组件本就不变）不记账、
 *   不补发 —— 继续保持 v4 消掉的「每次点按都重启模糊」。
 *   补发若撞上新一轮渐变窗口会被再拦 → 再记账 → 再排一次（自愈环）。
 *
 * 另：Ds 内拦截 Js 时跳过「值==当前值」的调用 —— 那是 Ms 动画器
 * start() 同步吐出的首帧（值=起点），不是直跳，拦它只是白记一条
 * st.target=起点 的脏账（v4 起就有，顺手修掉）。
 *
 * ============================================================
 * 第六层坑（v6，修「人像(171)切镜模糊 + 点按反应错乱 + 加档后卡 5x」）
 * ============================================================
 * 取证过程（10-10，一步步纠偏）：
 *
 * 19:46 曾判断人像走 L8→K7→w9；v6 实测推翻——L8/K7 全场 0 次调用，
 * 真正执行 switchCameraLens 的是 i9.e.w9(a0, boolean)
 * （内部 = Pe.b.R1() + Ph.b.e → StartControl.create(camId).setResetType(128)
 *   .setNeedBlurAnimation(z) → Camera.i8()，即 close→open = 那半秒模糊）。
 *
 * 第一版拦 w9 → 两个后果：
 *   ① 渐变不再被中途重启掐死 → cur 每次精确到位（5.0/2.6/1.0 全对），
 *      「只有第一次点能用、后面每次都不一样」就此修好；
 *   ② 但人像卡在 5x 换不了档、改档没反应。
 *
 * ② 的根因在 ZoomManager.y0 的分支结构（e.java:1166）：
 *       setZoomRatio(f)；                      // 只写组件值 F.D0，不推给相机
 *       if (L8(i, prev, f)) { ...; return false; }   // true = 「已切镜」分支，
 *                                                  //   它相信 w9/Camera.i8 已把
 *                                                  //   zoom 落下去 → 早退
 *       ... updatePreferenceInWorkThread({47,24,111,112});   // false 才走到这
 *       return true;
 *   拦掉 w9 只是跳过了 switchCameraLens，L8 照样 return true →
 *   那句真正把 zoom 推给相机的 updatePreferenceInWorkThread 永远不执行。
 *   日志能对上：ZoomManager 里 cur 已经 5.0→2.6→1.0，预览纹丝不动。
 *
 * v6 最终做法（第七刀）：把「不切镜头」做在 **L8 的返回值**上——
 *   渐变窗口内且 mode==171 → L8 直接 setResult(Boolean.FALSE)。
 *   → 走上面那条无切换的常规路径：zoom 照推 + 不触发 close→open，
 *     一并解决「模糊」和「改档没反应」。
 *   L8 在 i9.e 上有 8 个实现（p065j9.K/r/B/A/C/C1363i/z 全 extends e），
 *   Xposed 只 hook 声明它的那个方法对象，所以 8 个都得挂，少挂一个就会
 *   从那个实现漏过去。
 *   w9 那刀保留作兜底（L8 返回 false 后它本就不会被调，日志里应看不到
 *   「拦 w9」——若仍出现说明还有别的调用方）。
 *   167 等其它模式在窗口外、非 171 一律放行（专业已验证 OK，不许回归）。
 *
 * 附：L8/K7/y0 三个取证钩子保留（窗口内才记，量很小）。
 *
 * 配置（config.conf）：
 *   legend.proanim=0      关闭本修复（默认开）
 *   legend.proanim_modes=167        生效模式（默认 167；171 也已开）
 *   legend.proanim_ms=100           改写后的时长 ms（默认 100）
 * 日志：camport/proanim.log（每个模式记一次改写）。
 */
public final class ProZoomAnimFix implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "专业渐变: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    private static final String C_P0 = "H4.p0";

    private static ClassLoader sCl;
    private static boolean sInstalled;
    /** ⑨：正处于 Ph.b.e() 内且模式是人像(171) → 压掉模糊动画。 */
    private static volatile boolean sInPortraitSwitch;

    private static volatile boolean sCfgDone;
    private static volatile boolean sEnabled = true;
    /** 渐变**窗口**（含抑制换镜头 J9/w9）的模式，171 绝对不能进 —— 见 sAnimModes。 */
    private static volatile Set<Integer> sModes = parseModes("167");
    private static volatile long sMs = 100L;

    /**
     * v7：点按档位走「渐变动画」的模式。
     *
     * 必须跟 sModes 分开，这是 10-10 用 10 秒卡顿换来的教训：
     *   人像的 close→open 切镜（w9 → Camera.i8）**就是它最快的路径**，
     *   一旦进 sModes，窗口内 w9/J9 被拦 → 切镜永远不会发生 → 卡 10 秒。
     * 所以 171 只要动画、不要窗口抑制：Ds 内的直跳拦下来换成 Ns()，
     * 切镜照常放行。
     */
    private static volatile Set<Integer> sAnimModes = parseModes("167,171");
    /** 人像动画开关：0 = 退回原生直跳。改配置 + 重启相机即可，不用重编。 */
    private static volatile boolean sPortraitAnim = true;

    // ===== v7：人像「先渐变、动画结束再切一次镜」 =====
    //
    // 10-10 实测到的坑：拦下原生直跳改走 Ns 之后，**动画每一帧**都会各自
    // 走一遍 y0→L8→K7→w9，一次点按实测打了 3 次 close→open
    //   19:15:52.157 / .223 / .338  ← 180ms 内 3 次重启，比不加动画更糟
    // 而完全不切镜（10-10 上一版）又会卡 10 秒 —— 那条重启就是人像最快
    // 的路径，不能砍掉，只能**挪到动画之后，且只切一次**。
    private static volatile long sAnimUntil;       // 171 渐变窗口截止
    private static volatile long sLastAnimDur;     // Ns 实际算出的动画时长
    private static volatile boolean sW9Pending;    // 排队待补发的 w9
    private static volatile Object sW9Mgr;
    private static volatile Object sW9a0;
    private static volatile boolean sW9soft;
    private static volatile Runnable sW9Task;
    /** 正在 startAnim() 内 —— 用来接住 Kr.i.n 造出的 ValueAnimator 时长 */
    private static final ThreadLocal<Boolean> sInNs = new ThreadLocal<>();

    /**
     * v7 隔离实验：legend.portrait_seamless=1 时，把人像的 L8 拦成 false ——
     * 不再走 K7→w9→Camera.i8 的 close→open 重启，改走 y0 末尾的常规推 zoom
     * （由 HAL SAT 完成镜头切换，legend.lensswitch=1 已放行 ≥1.5 的边切边变焦）。
     *
     * 为什么值得单独验：10-10 上一次 `L8=false` 结论是「特别慢」，但那一版
     * **同时**挂着 12 帧渐变，每帧都跑一遍 y0 完整尾巴 —— 慢到底是 L8=false
     * 造成的还是 12 次串行推造成的，从没分开测过。现在 portrait_anim=0
     * （单次直跳，只有 1 次 y0），这个样本才是干净的。
     * 默认 0，改配置重启相机即回退。
     */
    private static volatile boolean sPortraitSeamless = false;

    /** 仅在 p0.Ms 执行栈内为“当前模式”，null = 不在 Ms 内 */
    private static final ThreadLocal<Integer> sInMs = new ThreadLocal<>();

    /** v4：渐变窗口截止时间（uptimeMillis）。窗口内抑制 J9 换镜头。 */
    private static volatile long sRampUntil;

    // ===== v6：人像(171)改成「窗口内只跳过、末帧一次性推」 =====
    //
    // 10-10 实测教训：把 L8 逐帧改成 false 之后，渐变每帧都会走一遍
    // y0 的完整推 zoom 分支
    //     updatePreferenceTrampoline({11,30,34,42,20,149})   ← 同步，主线程
    //     updatePreferenceInWorkThread({47,24,111,112})       ← 异步
    //     bVar.R1() / post 到主线程
    // 100ms 的渐变 ≈12 帧 = 12 次串行推 zoom，每次都有往返延迟，
    // 叠起来就是用户看到的「特别慢、特别卡」。
    //
    // 改法：窗口内的 y0 帧一律跳过（不推任何东西），到动画结束
    // sRampEnd+40ms 时自己补调一次 y0(target, 0) —— 全程只推 1 次
    // 终值。既没有 close→open 的半秒模糊，也不会因为 12 次串行
    // 推 zoom 而拖慢。
    private static volatile long sRampEnd;      // 动画结束时刻（不含 +150 缓冲）
    private static volatile float sTarget;      // 本次渐变的目标值
    private static volatile Object sMgr;        // ZoomManager 实例（j9.r）
    private static volatile boolean sFinalScheduled; // 末帧任务已排
    private static volatile boolean sFinalApply;     // 放行这唯一一次 y0
    private static volatile long sLastFrameLog;      // 帧日志节流（log 每次 flush FUSE）
    private static Runnable sFinalTask;              // 末帧一次性推 zoom

    // ===== v5：窗口内被拦、但涉及 <1.0（超广角）的最后一次 J9，窗口后补发 =====
    private static volatile boolean sPending;
    private static volatile float sPf;
    private static volatile float sPf2;
    private static volatile int sPi;
    private static volatile int sPi2;
    private static volatile Object sPa0;
    private static android.os.Handler sHandler;
    private static Runnable sReplayTask;

    /** 点按派发栈状态（mode∈proanim_modes 才置入），Ds 返回后必清 */
    private static final class DsState {
        final int mode;
        final float cur;
        boolean suppressed;   // Ds 内出现过直跳 Js（已拦）
        boolean msRan;        // Ds 原方法自己走了 Ms
        float target;         // 被拦的直跳目标
        int action;           // 被拦直跳的 action
        int hits;             // 本 Ds 内拦下几次 Js

        DsState(int mode, float cur) {
            this.mode = mode;
            this.cur = cur;
        }
    }

    private static final ThreadLocal<DsState> sInDs = new ThreadLocal<>();

    /**
     * 该模式是否要做「点按直跳 → 渐变」重定向（不等于要开抑制窗口）。
     * 两个门控是 AND 关系：proanim_anim 决定模式集，portrait_anim 是 171 的
     * 独立总闸（任一为假即关闭）。
     */
    private static boolean animOk(int mode) {
        if (!sAnimModes.contains(Integer.valueOf(mode))) {
            return false;
        }
        return mode != 171 || sPortraitAnim;
    }

    /** 只捞 app 侧的调用帧，用来定位「同一次点按第 2 个 Js 是谁打的」。 */
    private static String stackHint() {
        try {
            StackTraceElement[] s = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length && i < 40; i++) {
                String c = s[i].getClassName();
                if (c.startsWith("H4.") || c.startsWith("H4$")
                        || c.startsWith("com.android.camera")) {
                    if (sb.length() > 0) {
                        sb.append(" < ");
                    }
                    sb.append(c).append('.').append(s[i].getMethodName())
                            .append(':').append(s[i].getLineNumber());
                }
            }
            return sb.length() == 0 ? "?" : sb.toString();
        } catch (Throwable th) {
            return "?";
        }
    }

    /**
     * v7：把 Ds 内被拦下来的那次「直跳 Js」换成动画。
     *
     * 优先 Ns() —— 内部走 Kr.i.n()，时长按**非线性映射后**的倍率差算，
     * update 帧再经 Kr.i.f 曲线反解回倍率，这正是别的模式点按档位时看到的
     * 「非线性变焦动画」。人像(171) 在原生代码里被硬编码成 Js() 直跳：
     *     } else if (this.mCurrentMode == 171) { Js(f11, f1894P); }
     * 这一行就是「只有人像没有动画」的全部原因。
     *
     * 两种情况退回 Js（＝维持原生行为）：
     *   As()==false —— 非线性曲线 Kr.i.f 为 null，Ns 的 update 里
     *                  aVar==null 会直接不下发，会导致 zoom 卡死；
     *   zs(target)  —— 人像自己的「跨基准倍率」判定，原生本来就要直跳。
     */
    private static void startAnim(Object p0obj, DsState st) {
        try {
            boolean nonLinear = false;
            try {
                nonLinear = ((Boolean) XposedHelpers.callStaticMethod(
                        p0obj.getClass(), "As")).booleanValue();
            } catch (Throwable ignore) {
            }
            boolean crossBase = false;
            try {
                crossBase = ((Boolean) XposedHelpers.callMethod(p0obj, "zs",
                        Float.valueOf(st.target))).booleanValue();
            } catch (Throwable ignore) {
            }
            if (!nonLinear || crossBase) {
                // 退回原生：直接把被拦的那次 Js 原样发出去
                XposedHelpers.callMethod(p0obj, "Js",
                        Float.valueOf(st.target),
                        Integer.valueOf(st.action));
                log("→ 直跳保留 Js(" + st.target + ") mode=" + st.mode
                        + (nonLinear ? "（跨基准倍率 zs=true）"
                                : "（As()==false 无非线性曲线）"));
                return;
            }
            boolean fromClick = false;
            try {
                fromClick = ((Boolean) XposedHelpers.callMethod(p0obj, "xs"))
                        .booleanValue();
            } catch (Throwable ignore) {
            }
            XposedHelpers.callMethod(p0obj, "Os"); // 取消上一轮未跑完的 Ns
            sLastAnimDur = 0L;
            sInNs.set(Boolean.TRUE);
            try {
                XposedHelpers.callMethod(p0obj, "Ns",
                        Float.valueOf(st.target), Integer.valueOf(st.action),
                        Boolean.valueOf(fromClick));
            } finally {
                sInNs.remove();
            }
            // 动画期间必须把切镜 w9 挡住（否则每帧各开一次相机），
            // 动画结束再补一次 —— 窗口起止都要记死，绝不能永久拦。
            long dur = sLastAnimDur > 0L ? sLastAnimDur : 160L;
            long now = android.os.SystemClock.uptimeMillis();
            sAnimUntil = now + dur + 60L;
            log("→ 直跳已拦 Js(" + st.target + ")，改走 Ns 非线性渐变 mode="
                    + st.mode + " cur=" + st.cur + " dur=" + dur
                    + "ms，切镜延到 +" + (dur + 90L) + "ms 只切一次");
        } catch (Throwable th) {
            log("!! 补渐变失败 " + th);
            try {
                XposedHelpers.callMethod(p0obj, "Js",
                        Float.valueOf(st.target),
                        Integer.valueOf(st.action));
            } catch (Throwable ignore) {
            }
        }
    }

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
            log("legend.proanim=0 → 已关闭");
            return;
        }
        // ① Ms 打标/清标（线程本地，after 必然执行）
        try {
            XposedHelpers.findAndHookMethod(C_P0, sCl, "Ms",
                    Float.TYPE, Float.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = XposedHelpers.getIntField(
                                        p.thisObject, "mCurrentMode");
                                Integer im = Integer.valueOf(mode);
                                // 171 单独受 sPortraitAnim 控制（关掉即回原生直跳）
                                boolean anim = sAnimModes.contains(im)
                                        && (mode != 171 || sPortraitAnim);
                                if (anim) {
                                    sInMs.set(im);
                                    DsState st0 = sInDs.get();
                                    if (st0 != null) {
                                        st0.msRan = true;
                                    }
                                    log("Ms(" + p.args[0] + " → " + p.args[1]
                                            + ") mode=" + mode);
                                    // 渐变窗口（抑制换镜头）只给 sModes，
                                    // 171 不进 —— 否则切镜被拦 = 卡 10 秒
                                    if (sModes.contains(im)) {
                                        long now = android.os.SystemClock
                                                .uptimeMillis();
                                        sRampUntil = now + sMs + 150L;
                                        sRampEnd = now + sMs;
                                        sTarget = ((Float) p.args[1])
                                                .floatValue();
                                        sFinalScheduled = false;
                                        sFinalApply = false;
                                    }
                                }
                            } catch (Throwable ignore) {
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            sInMs.remove();
                        }
                    });
            log("hook " + C_P0 + ".Ms(float,float) ok modes=" + sModes
                    + " anim=" + sAnimModes + " ms=" + sMs);
        } catch (Throwable th) {
            log("!! hook Ms 失败 " + th);
        }
        // ② Ms 内把 setDuration(0) 改写成 sMs
        try {
            XposedHelpers.findAndHookMethod(ValueAnimator.class, "setDuration",
                    Long.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                Integer mode = sInMs.get();
                                if (mode == null) {
                                    return;
                                }
                                long d = ((Long) p.args[0]).longValue();
                                if (d == 0L) {
                                    p.args[0] = Long.valueOf(sMs);
                                    logOnce(mode.intValue(),
                                            "Ms duration 0 → " + sMs + "ms");
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook ValueAnimator.setDuration(long) ok");
        } catch (Throwable th) {
            log("!! hook setDuration 失败 " + th);
        }
        // ③ Ms 内构造 update 监听器 b(p0, float, boolean z8, boolean zN)
        //    时把 z8=false 改成 true → onAnimationUpdate 逐帧下发插值
        try {
            Class<?> clsP0 = XposedHelpers.findClass(C_P0, sCl);
            Class<?> clsB = XposedHelpers.findClass("H4.p0$b", sCl);
            XposedHelpers.findAndHookConstructor("H4.p0$b", sCl,
                    clsP0, Float.TYPE, Boolean.TYPE, Boolean.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                Integer mode = sInMs.get();
                                if (mode != null && p.args != null
                                        && p.args.length == 4
                                        && p.args[1] instanceof Float
                                        && p.args[2] instanceof Boolean
                                        && !((Boolean) p.args[2]).booleanValue()) {
                                    p.args[2] = Boolean.TRUE; // z8: 只应用最终值 → 逐帧插值
                                    logOnce(mode.intValue(),
                                            "监听器 z8 false→true（逐帧插值）");
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook " + C_P0 + "$b.<init> ok");
        } catch (Throwable th) {
            log("!! hook p0$b 构造 失败 " + th);
        }
        // ④ Ds（点按派发）打标：进栈记 mode/当前倍率，出栈按需补渐变
        try {
            XposedHelpers.findAndHookMethod(C_P0, sCl, "Ds",
                    Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = XposedHelpers.getIntField(
                                        p.thisObject, "mCurrentMode");
                                if (!animOk(mode)) {
                                    sInDs.remove();
                                    return;
                                }
                                float cur = ((Float) XposedHelpers.getObjectField(
                                        p.thisObject, "l")).floatValue();
                                sInDs.set(new DsState(mode, cur));
                                log("Ds(idx=" + p.args[0] + ", action="
                                        + p.args[1] + ") cur=" + cur
                                        + " mode=" + mode);
                            } catch (Throwable th) {
                                sInDs.remove();
                                log("!! Ds before 失败 " + th);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            DsState st = sInDs.get();
                            if (st == null) {
                                return;
                            }
                            try {
                                if (st.suppressed) {
                                    if (st.msRan) {
                                        log("→ 直跳已拦 Js(" + st.target
                                                + ")，原 Ms 接管渐变");
                                    } else if (Math.abs(st.cur - st.target)
                                            > 0.001f) {
                                        // v7：优先 Ns（非线性曲线渐变，其它
                                        // 模式走的就是它），不行再退 Ms
                                        startAnim(p.thisObject, st);
                                    } else {
                                        log("→ 直跳已拦 Js(" + st.target
                                                + ") 同值无需渐变");
                                    }
                                }
                            } catch (Throwable th) {
                                log("!! Ds 补渐变失败 " + th);
                            } finally {
                                sInDs.remove();
                            }
                        }
                    });
            log("hook " + C_P0 + ".Ds(int,int) ok");
        } catch (Throwable th) {
            log("!! hook Ds 失败 " + th);
        }
        // ⑤ 静态 Js：仅拦「mode∈proanim_modes 的 Ds 栈内直跳」；
        //    动画帧/捏合/其它模式的 Js 一律放行（sInDs 为 null）
        try {
            XposedHelpers.findAndHookMethod(C_P0, sCl, "Js",
                    Float.TYPE, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                DsState st = sInDs.get();
                                if (st == null) {
                                    // 渐变动画帧里的 Js（Ds 已出栈）——只在窗口内记，
                                    // 用来对账「渐变到底下发了哪些值」
                                    long nowNs = android.os.SystemClock
                                            .uptimeMillis();
                                    if (nowNs < sRampUntil) {
                                        // 10-10：log() 每次都 flush 到 /sdcard(FUSE)，
                                        // 而且在主线程。渐变 12 帧若每帧都写，
                                        // 一次点按就是十几次 FUSE flush → 直接拖成卡顿。
                                        // 改成 200ms 节流（一次渐变最多 1 行）。
                                        if (nowNs - sLastFrameLog >= 200L) {
                                            sLastFrameLog = nowNs;
                                            log("帧 Js(" + p.args[0]
                                                    + ", action=" + p.args[1]
                                                    + ") 余 "
                                                    + (sRampUntil - nowNs)
                                                    + "ms");
                                        }
                                    }
                                    return;
                                }
                                float tgt = ((Float) p.args[0]).floatValue();
                                if (Math.abs(tgt - st.cur) < 0.01f) {
                                    // Ms 动画器 start() 同步吐的首帧（值=起点），
                                    // 不是直跳，放行并记脏账
                                    return;
                                }
                                st.hits++;
                                p.setResult(null);
                                if (st.suppressed) {
                                    // 10-10 实测：人像一次点按会打 2 次 Js，
                                    // 第 1 次才是档位值（2.6/5.0/1.0），
                                    // 第 2 次是 cur+ε 的杂值。旧代码每次都
                                    // 覆盖 target → 拿 2.516 当目标去渐变，
                                    // 真正的 5.0 被吞掉 →「比之前还糟糕」。
                                    // 现在只认第一次，后面的一律丢弃。
                                    log("  · 同次点按第 " + st.hits + " 次直跳 Js("
                                            + tgt + ") 丢弃（目标已定 "
                                            + st.target + "）  < " + stackHint());
                                    return;
                                }
                                st.suppressed = true;
                                st.target = tgt;
                                st.action = ((Integer) p.args[1]).intValue();
                                log("Ds 内直跳拦截 Js(" + tgt + ", action="
                                        + st.action + ") mode=" + st.mode
                                        + " → 取为渐变目标");
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook " + C_P0 + ".Js(float,int) ok（Ds 内直跳→渐变）");
        } catch (Throwable th) {
            log("!! hook Js 失败 " + th);
        }
        // ⑤b v7：接住 Ns 造出来的 ValueAnimator 的**实际**时长。
        //      Kr.i.n 的时长是按非线性映射后的倍率差算的，事前算不出来；
        //      拿到它才能把「切镜延到动画之后」的窗口卡准。
        try {
            XposedHelpers.findAndHookMethod("Kr.i", sCl, "n",
                    Float.TYPE, Float.TYPE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                if (Boolean.TRUE.equals(sInNs.get())) {
                                    sLastAnimDur = ((ValueAnimator) p
                                            .getResult()).getDuration();
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook Kr.i.n(float,float) ok（取渐变实际时长）");
        } catch (Throwable th) {
            log("!! hook Kr.i.n 失败 " + th);
        }
        // ⑥ v4：渐变窗口内抑制换镜头 i9.e.J9（= switchLensInPro）
        try {
            XposedHelpers.findAndHookMethod("i9.e", sCl, "J9",
                    Float.TYPE, Float.TYPE, Integer.TYPE, Integer.TYPE,
                    "com.android.camera.module.a0", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                long now = android.os.SystemClock
                                        .uptimeMillis();
                                if (now < sRampUntil) {
                                    // v5：涉及 <1.0（要切超广角）的这次记账，
                                    // 窗口结束后补发，否则 0.7 永远到不了
                                    float fPrev = ((Float) p.args[0])
                                            .floatValue();
                                    float fCurr = ((Float) p.args[1])
                                            .floatValue();
                                    boolean ultra = fPrev < 1.0f
                                            || fCurr < 1.0f;
                                    if (ultra) {
                                        sPf = fPrev;
                                        sPf2 = fCurr;
                                        sPi = ((Integer) p.args[2]).intValue();
                                        sPi2 = ((Integer) p.args[3]).intValue();
                                        sPa0 = p.args[4];
                                        sPending = true;
                                        scheduleReplay();
                                    }
                                    p.setResult(Boolean.FALSE);
                                    log("抑制换镜头 J9(" + p.args[0] + "→"
                                            + p.args[1] + ") 渐变窗口内，余 "
                                            + (sRampUntil - now) + "ms"
                                            + (ultra ? "（<1.0 → 窗口后补发）"
                                                    : ""));
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook i9.e.J9(float,float,int,int,a0) ok（渐变窗口内不换镜头）");
        } catch (Throwable th) {
            log("!! hook J9 失败 " + th);
        }
        // ⑦ v6【整刀撤回 + 改向】：人像(171) 必须走**原生**切镜，
        //
        // 取证链（10-10，一路纠偏，结论和直觉相反）：
        //   a) 拦 i9.e.w9 → 人像卡在 5x 换不动。原因：w9 是在 L8 内部被调的，
        //      拦掉它 L8 照样 return true，而 y0 的结构是
        //          if (L8(...)) { ...; return false; }   // 早退，不再推 zoom
        //          ... updatePreferenceInWorkThread({47,24,111,112});
        //      真正把 zoom 推下去的那句永远走不到。
        //   b) 改拦 L8 返回值（false）→ 能换了，但「特别慢特别卡」。
        //   c) 进一步改成只调 setZoomRatio + d0()（applyZoomRatio）→
        //      **每换一次要等 10 秒**。
        //
        // 而 d0() 底下 g0/b0/c0 全都只是往 CaptureRequest 写参数，
        // 不建流不阻塞（配套的「y0 全程耗时」一次都没触发 = y0 <16ms）——
        // 所以那 10 秒不是我们这段代码的耗时，是把 w9→Camera.i8 这条
        // 重启路径掐掉之后，相机在等一个永远不会来的切换，自己超时去了。
        //
        // 结论：**人像的 close→open 重启就是它最快的路径**（原生 ~0.5s）。
        // 一刀切地绕开它只会更慢。所以人像整体退回原生，唯一保留的是
        // 把那层模糊动画关掉（见 ⑨）。
        // 167 那套渐变/抑制不受影响，专业已验证 OK。
        // ⑧ v6 诊断：渐变窗口内 ZoomManager 的逐帧入口 y0 / 切镜判定 K7 /
        //    真正执行 switchCameraLens 的 w9 —— 三条只要有一条命中，
        //    就说明人像的模糊确实来自 app 侧切镜。
        try {
            XposedHelpers.findAndHookMethod("i9.e", sCl, "y0",
                    Float.TYPE, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                long now = android.os.SystemClock
                                        .uptimeMillis();
                                Object mgr = p.thisObject;
                                sMgr = mgr;
                                // v7 隔离实验：默认 sPortraitSeamless=false
                                // 时这行不执行，人像完全走原生（当前已验证
                                // 不卡的那一版）。置 1 才拦 L8。
                                if (sPortraitSeamless) {
                                    ensureL8(mgr);
                                }
                                ensureEntry(mgr);
                                int mode = XposedHelpers.getIntField(mgr, "c");
                                if (now >= sRampUntil) {
                                    return;
                                }
                                log("窗口内 y0(zoom=" + p.args[0] + ", action="
                                        + p.args[1] + ") mode=" + mode
                                        + " 余 " + (sRampUntil - now) + "ms");
                            } catch (Throwable th) {
                                log("!! y0 失败 " + th);
                            }
                        }
                    });
            log("hook i9.e.y0(float,int) ok（窗口内逐帧取证）");
        } catch (Throwable th) {
            log("!! hook y0 失败 " + th);
        }
        try {
            Class<?> clsA0 = XposedHelpers.findClass(
                    "com.android.camera.module.a0", sCl);
            XposedHelpers.findAndHookMethod("i9.e", sCl, "K7",
                    Float.TYPE, Float.TYPE, clsA0, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                log("K7 切镜判定(" + p.args[0] + "→" + p.args[1]
                                        + ")");
                            } catch (Throwable ignore) {
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                log("K7 → " + p.getResult());
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook i9.e.K7(float,float,a0) ok（切镜判定取证）");
        } catch (Throwable th) {
            log("!! hook K7 失败 " + th);
        }
        //    v6 实测（10-10 12:47）：L8/K7 一次都没被调到（各 0 次），
        //    真正执行 switchCameraLens 的 w9 却在渐变窗口内被调了 26 次
        //    —— 每次渐变都被中途的 close→open 掐死：
        //      Ms(2.89→5.0) → y0 到 4.25 → w9 → 重启 → 动画停在 4.25，
        //      下一次点按 cur 已经不是上一次的目标值 → 「后面每次点都不一样」。
        //    所以真正的拦截点是 w9 本身（所有切镜路径的唯一汇聚点）。
        //    171 档位恒 ≥1.0、不存在超广角，HAL 侧 SAT 自己能完成切换
        //    （v4 洞察：app 的 close/open 是多余的破坏）→ 直接拦掉。
        try {
            Class<?> clsA0b = XposedHelpers.findClass(
                    "com.android.camera.module.a0", sCl);
            XposedHelpers.findAndHookMethod("i9.e", sCl, "w9", clsA0b,
                    Boolean.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mi = -1;
                                try {
                                    mi = ((Integer) XposedHelpers.callMethod(
                                            p.args[0], "getModuleIndex"))
                                            .intValue();
                                } catch (Throwable ignore) {
                                }
                                long now = android.os.SystemClock
                                        .uptimeMillis();
                                // v7：动画期间挡下切镜（实测一次点按打了 3 次
                                // close→open，全在 180ms 内），动画结束补一次。
                                // 窗口终点 sAnimUntil 是算死的，绝不会永久拦
                                // （10-10 上一版永久拦 → 卡 10 秒）。
                                if (mi == 171 && now < sAnimUntil) {
                                    sW9Mgr = p.thisObject;
                                    sW9a0 = p.args[0];
                                    sW9soft = ((Boolean) p.args[1])
                                            .booleanValue();
                                    sW9Pending = true;
                                    p.setResult(null);
                                    scheduleW9Replay();
                                    log("拦 w9(渐变中) mode=171 余 "
                                            + (sAnimUntil - now)
                                            + "ms，动画结束只补切一次");
                                } else {
                                    log("w9 放行 mode=" + mi + " soft="
                                            + p.args[1]
                                            + (now < sRampUntil ? " 窗口内"
                                                    : " 窗口外"));
                                }
                            } catch (Throwable th) {
                                log("!! w9 内部失败 " + th);
                            }
                        }
                    });
            log("hook i9.e.w9(a0,boolean) ok（渐变窗口内不切镜头）");
        } catch (Throwable th) {
            log("!! hook w9 失败 " + th);
        }
        // ⑨ v6：人像退回原生切镜后，唯一保留的一刀 —— 关掉模糊动画。
        //
        // Ph.b.e(a0, boolean, int)：
        //     StartControl.create(camId).setResetType(128)
        //         .setViewConfigType(i).setNeedBlurAnimation(z2)
        //     moduleCallback.i8(...)   // ← Camera.i8 = close→open 重启
        // z2 默认 true，就是那层「半秒模糊」。重启本身要保留（它是人像
        // 最快的切换路径，见 ⑦ 的取证），所以只把动画这一层压成 false。
        // 只在 mode==171 时生效，167 等其它模式完全不动。
        try {
            Class<?> clsA0p = XposedHelpers.findClass(
                    "com.android.camera.module.a0", sCl);
            Class<?> clsSC = XposedHelpers.findClass(
                    "com.android.camera.module.loader.base.StartControl", sCl);
            XposedHelpers.findAndHookMethod("Ph.b", sCl, "e",
                    clsA0p, Boolean.TYPE, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam p) throws Throwable {
                            try {
                                sInPortraitSwitch = Integer.valueOf(171)
                                        .equals(XposedHelpers.callMethod(
                                                p.args[0], "getModuleIndex"));
                            } catch (Throwable ignore) {
                                sInPortraitSwitch = false;
                            }
                        }

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam p) throws Throwable {
                            sInPortraitSwitch = false;
                        }
                    });
            log("hook Ph.b.e(a0,boolean,int) ok（人像切镜不带模糊动画）");
            XposedHelpers.findAndHookMethod(clsSC, "setNeedBlurAnimation",
                    Boolean.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam p) throws Throwable {
                            try {
                                if (sInPortraitSwitch
                                        && Boolean.TRUE.equals(p.args[0])) {
                                    p.args[0] = Boolean.FALSE;
                                    logOnce(171,
                                            "人像切镜 setNeedBlurAnimation true→false（重启保留，只去掉模糊）");
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook StartControl.setNeedBlurAnimation(boolean) ok");
        } catch (Throwable th) {
            log("!! hook 模糊动画 失败 " + th);
        }
    }

    /**
     * v5：把窗口内被拦、但涉及 <1.0 的最后一次 J9 排到窗口结束后补发。
     * 补发时窗口已过 → 本钩子放行 → 原方法执行 setComponentValue("ultra")
     * 切超广角（此时 app 侧 zoom 已是 0.7，重启落地即 0.7）。
     * 若补发撞上新一轮渐变窗口 → 再被拦 → 再记账 → 再排一次（自愈）。
     */
    /**
     * v7：渐变结束后补发被挡下的那一次人像切镜（w9 = Camera.i8 close→open）。
     * 与 scheduleReplay() 同款兜底：写死延迟、removeCallbacks 防叠、
     * 到点必发 —— 「永久拦住切镜」就是 10-10 卡 10 秒的根因。
     */
    private static void scheduleW9Replay() {
        try {
            if (sHandler == null) {
                sHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            if (sW9Task == null) {
                sW9Task = new Runnable() {
                    @Override
                    public void run() {
                        if (!sW9Pending || sW9a0 == null || sW9Mgr == null) {
                            return;
                        }
                        sW9Pending = false;
                        try {
                            log("渐变结束 → 补发 w9 切镜（close→open）");
                            XposedHelpers.callMethod(sW9Mgr, "w9", sW9a0,
                                    Boolean.valueOf(sW9soft));
                        } catch (Throwable th) {
                            log("!! 补发 w9 失败 " + th);
                        }
                    }
                };
            }
            sHandler.removeCallbacks(sW9Task);
            long delay = sAnimUntil + 30L
                    - android.os.SystemClock.uptimeMillis();
            if (delay < 30L) {
                delay = 30L;
            }
            sHandler.postDelayed(sW9Task, delay);
        } catch (Throwable th) {
            log("!! scheduleW9Replay 失败 " + th);
        }
    }

    private static void scheduleReplay() {
        try {
            if (sHandler == null) {
                sHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            if (sReplayTask == null) {
                sReplayTask = new Runnable() {
                    @Override
                    public void run() {
                        if (!sPending) {
                            return;
                        }
                        sPending = false;
                        try {
                            log("渐变窗口结束 → 补发 J9(" + sPf + "→" + sPf2
                                    + ") 切超广角");
                            XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("i9.e", sCl),
                                    "J9",
                                    Float.valueOf(sPf), Float.valueOf(sPf2),
                                    Integer.valueOf(sPi),
                                    Integer.valueOf(sPi2), sPa0);
                        } catch (Throwable th) {
                            log("!! 补发 J9 失败 " + th);
                        }
                    }
                };
            }
            sHandler.removeCallbacks(sReplayTask);
            long delay = sRampUntil + 60L - android.os.SystemClock
                    .uptimeMillis();
            if (delay < 40L) {
                delay = 40L;
            }
            sHandler.postDelayed(sReplayTask, delay);
        } catch (Throwable ignore) {
        }
    }

    /**
     * v6：渐变窗口内 y0 全部被跳过 → 一次 zoom 都没推。
     * 这里在首帧后 30ms 自己补调一次 y0(target, 0)，全程只推 1 次终值。
     *
     * 首帧 + 30ms 远早于 sRampUntil(=start+ms+150)，所以 L8 仍会被
     * 拦成 false → 走无切换的常规推 zoom 路径，不触发 close→open。
     */
    private static void scheduleFinalApply() {
        try {
            if (sHandler == null) {
                sHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
            }
            if (sFinalTask == null) {
                sFinalTask = new Runnable() {
                    @Override
                    public void run() {
                        // 注意：这里**不能**复位 sFinalScheduled。
                        // 之前复位了 → 动画每来一帧就再排一个任务 →
                        // 30ms 一轮反复推（实测 1.0→2.6 推了 3 次）。
                        // 只在 Ms 起算时复位，保证一次渐变只推 1 次。
                        Object mgr = sMgr;
                        if (mgr == null) {
                            log("!! 末帧推 zoom 跳过：无 ZoomManager 实例");
                            return;
                        }
                        sFinalApply = true;
                        try {
                            // 顺类继承链找 y0(float,int)（不假设 r.y0 是 public）
                            java.lang.reflect.Method m = null;
                            for (Class<?> k = mgr.getClass(); k != null; k = k
                                    .getSuperclass()) {
                                try {
                                    m = k.getDeclaredMethod("y0", Float.TYPE,
                                            Integer.TYPE);
                                    break;
                                } catch (Throwable ignore) {
                                }
                            }
                            if (m == null) {
                                throw new NoSuchMethodException(
                                        "y0(float,int) in " + mgr.getClass());
                            }
                            m.setAccessible(true);
                            m.invoke(mgr, Float.valueOf(sTarget),
                                    Integer.valueOf(0));
                        } catch (Throwable th) {
                            sFinalApply = false;
                            log("!! 末帧推 zoom 失败 " + th);
                            return;
                        }
                        log("末帧一次性推 zoom → " + sTarget
                                + "（窗口内逐帧已跳过，只推这 1 次）");
                    }
                };
            }
            sHandler.removeCallbacks(sFinalTask);
            // 动画帧已经全部被跳过了，没有理由再等动画跑完 ——
            // 固定 30ms（让 Ds/Ms 的调用栈先出栈）就推，点按即生效。
            long delay = 30L;
            sHandler.postDelayed(sFinalTask, delay);
        } catch (Throwable ignore) {
        }
    }

    private static final Set<String> sLogged = java.util.Collections
            .synchronizedSet(new HashSet<String>());

    private static void logOnce(int mode, String msg) {
        if (sLogged.add(mode + "|" + msg)) {
            log(msg + "  (mode=" + mode + ")");
        }
    }

    // 运行时发现并挂上 L8 的真实实现类（jadx 包名 p059i9/p065j9 ≠ 真包名
    // i9/j9，写死类名会 ClassNotFound）。
    private static final Set<Class<?>> sL8Done = java.util.Collections
            .synchronizedSet(new HashSet<Class<?>>());

    private static void ensureL8(Object zoomMgr) {
        try {
            Class<?> c = zoomMgr.getClass();
            Class<?> decl = null;
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    k.getDeclaredMethod("L8", Integer.TYPE, Float.TYPE,
                            Float.TYPE);
                    decl = k;
                    break;
                } catch (Throwable ignore) {
                }
            }
            if (decl == null || !sL8Done.add(decl)) {
                return;
            }
            final Class<?> declF = decl;
            log("ensureL8: 实例类 " + c.getName()
                    + " → 声明 L8 的是 " + decl.getName());
            // 运行时那个 XposedHelpers 是 LSPosed 的混淆版，没有
            // hookMethod(Member, ...) —— 10-10 实测 NoSuchMethodError。
            // 改用已被验证可用的字符串版 findAndHookMethod(String, cl, ...)。
            XposedHelpers.findAndHookMethod(declF.getName(), sCl, "L8",
                    Integer.TYPE, Float.TYPE, Float.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam p) throws Throwable {
                            try {
                                int mode = XposedHelpers.getIntField(
                                        p.thisObject, "c");
                                if (mode != 171 || !sPortraitSeamless) {
                                    return; // 167 等一律不动；开关关掉也立刻失效
                                }
                                // 人像只有 1.0 / 2.6 / 5.0 三档，恒 ≥1.0，
                                // 全程不切镜头。强制 false → y0 走无切换的
                                // 常规路径（末尾 applyZoomRatio），不再
                                // K7→w9→Camera.i8 的 close→open（那半秒模糊）。
                                // 之前用 now < sRampUntil 卡窗口，人像一旦
                                // 不走渐变窗口就永远不生效，所以这里不看窗口。
                                p.setResult(Boolean.FALSE);
                                logOnce(mode, "拦 L8 → false（" + declF.getName()
                                        + "）人像不切镜头，走常规推 zoom");
                            } catch (Throwable th) {
                                log("!! L8 内部失败 " + th);
                            }
                        }
                    });
            log("hook " + declF.getName()
                    + ".L8(int,float,float) ok（171 窗口内返回 false）");
        } catch (Throwable th) {
            log("!! ensureL8 失败 " + th);
        }
    }

    // y0 的“入口”取证：r.y0/z.y0 有一大段前置，i9.e.y0 是它们末尾才
    // 调的 super —— 只在 i9.e.y0 上计时会漏掉前置，所以顺继承链把
    // 真正的入口也挂上，量一次完整的 y0 耗时。
    private static final Set<Class<?>> sEntryDone = java.util.Collections
            .synchronizedSet(new HashSet<Class<?>>());
    private static volatile long sEntryT0;

    private static void ensureEntry(Object zoomMgr) {
        try {
            Class<?> c = zoomMgr.getClass();
            Class<?> decl = null;
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    k.getDeclaredMethod("y0", Float.TYPE, Integer.TYPE);
                    decl = k;
                    break;
                } catch (Throwable ignore) {
                }
            }
            if (decl == null || !sEntryDone.add(decl)) {
                return;
            }
            final Class<?> declF = decl;
            XposedHelpers.findAndHookMethod(declF.getName(), sCl, "y0",
                    Float.TYPE, Integer.TYPE, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam p) throws Throwable {
                            try {
                                if (XposedHelpers.getIntField(p.thisObject,
                                        "c") == 171) {
                                    sEntryT0 = android.os.SystemClock
                                            .uptimeMillis();
                                }
                            } catch (Throwable ignore) {
                            }
                        }

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam p) throws Throwable {
                            try {
                                long t0 = sEntryT0;
                                sEntryT0 = 0L;
                                if (t0 == 0L
                                        || XposedHelpers.getIntField(
                                                p.thisObject, "c") != 171) {
                                    return;
                                }
                                long d = android.os.SystemClock.uptimeMillis()
                                        - t0;
                                if (d >= 16L) {
                                    log("y0 全程耗时 " + d
                                            + "ms（含 r.y0/z.y0 前置）");
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook " + declF.getName() + ".y0(float,int) ok（耗时取证）");
        } catch (Throwable th) {
            log("!! ensureEntry 失败 " + th);
        }
    }

    private static Set<Integer> parseModes(String csv) {
        Set<Integer> s = new HashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                try {
                    s.add(Integer.valueOf(Integer.parseInt(part.trim())));
                } catch (Throwable ignore) {
                }
            }
        }
        return s;
    }

    /** 读 config.conf 的 legend.proanim*；只读一次 */
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
            log("未找到 config.conf，用默认 legend.proanim=1 modes=167 ms=100");
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
                    if ("legend.proanim".equals(k)) {
                        sEnabled = !"0".equals(v.trim());
                    } else if ("legend.proanim_modes".equals(k)) {
                        sModes = parseModes(v);
                    } else if ("legend.proanim_ms".equals(k)) {
                        sMs = Long.parseLong(v.trim());
                    } else if ("legend.proanim_anim".equals(k)) {
                        sAnimModes = parseModes(v);
                    } else if ("legend.portrait_anim".equals(k)) {
                        sPortraitAnim = !"0".equals(v.trim());
                    } else if ("legend.portrait_seamless".equals(k)) {
                        sPortraitSeamless = "1".equals(v.trim());
                    }
                } catch (Throwable ignore) {
                }
            }
            log("cfg=" + chosen + " enabled=" + sEnabled + " modes="
                    + sModes + " ms=" + sMs + " anim=" + sAnimModes
                    + " portrait_anim=" + sPortraitAnim
                    + " portrait_seamless=" + sPortraitSeamless);
        } catch (Throwable th) {
            log("ERROR " + th);
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
                        "proanim.log");
                File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(String.format(Locale.US, "%s I/专业渐变: %s%n",
                        new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                                Locale.US).format(new java.util.Date()), msg));
                sWriter.flush();
            }
        } catch (Throwable th) {
            sWriter = null;
        }
    }
}
