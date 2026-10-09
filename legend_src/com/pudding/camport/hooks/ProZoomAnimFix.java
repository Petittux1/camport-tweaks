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
 * 配置（config.conf）：
 *   legend.proanim=0      关闭本修复（默认开）
 *   legend.proanim_modes=167        生效模式（默认 167；180 也可加）
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

    private static volatile boolean sCfgDone;
    private static volatile boolean sEnabled = true;
    private static volatile Set<Integer> sModes = parseModes("167");
    private static volatile long sMs = 100L;

    /** 仅在 p0.Ms 执行栈内为“当前模式”，null = 不在 Ms 内 */
    private static final ThreadLocal<Integer> sInMs = new ThreadLocal<>();

    /** v4：渐变窗口截止时间（uptimeMillis）。窗口内抑制 J9 换镜头。 */
    private static volatile long sRampUntil;

    /** 点按派发栈状态（mode∈proanim_modes 才置入），Ds 返回后必清 */
    private static final class DsState {
        final int mode;
        final float cur;
        boolean suppressed;   // Ds 内出现过直跳 Js（已拦）
        boolean msRan;        // Ds 原方法自己走了 Ms
        float target;         // 被拦的直跳目标
        int action;           // 被拦直跳的 action

        DsState(int mode, float cur) {
            this.mode = mode;
            this.cur = cur;
        }
    }

    private static final ThreadLocal<DsState> sInDs = new ThreadLocal<>();

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
                                if (sModes.contains(Integer.valueOf(mode))) {
                                    sInMs.set(Integer.valueOf(mode));
                                    sRampUntil = android.os.SystemClock
                                            .uptimeMillis() + sMs + 150L;
                                    DsState st = sInDs.get();
                                    if (st != null) {
                                        st.msRan = true;
                                    }
                                    log("Ms(" + p.args[0] + " → "
                                            + p.args[1] + ") mode=" + mode);
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
            log("hook " + C_P0 + ".Ms(float,float) ok modes="
                    + sModes + " ms=" + sMs);
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
                                if (!sModes.contains(Integer.valueOf(mode))) {
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
                                        XposedHelpers.callMethod(p.thisObject,
                                                "Ms",
                                                Float.valueOf(st.cur),
                                                Float.valueOf(st.target));
                                        log("→ 直跳已拦 Js(" + st.target
                                                + ")，补 Ms(" + st.cur + "→"
                                                + st.target + ") 渐变");
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
                                    return;
                                }
                                float tgt = ((Float) p.args[0]).floatValue();
                                st.suppressed = true;
                                st.target = tgt;
                                st.action = ((Integer) p.args[1]).intValue();
                                p.setResult(null);
                                log("Ds 内直跳拦截 Js(" + tgt + ", action="
                                        + st.action + ") mode=" + st.mode);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook " + C_P0 + ".Js(float,int) ok（Ds 内直跳→渐变）");
        } catch (Throwable th) {
            log("!! hook Js 失败 " + th);
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
                                    p.setResult(Boolean.FALSE);
                                    log("抑制换镜头 J9(" + p.args[0] + "→"
                                            + p.args[1] + ") 渐变窗口内，余 "
                                            + (sRampUntil - now) + "ms");
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("hook i9.e.J9(float,float,int,int,a0) ok（渐变窗口内不换镜头）");
        } catch (Throwable th) {
            log("!! hook J9 失败 " + th);
        }
    }

    private static final Set<String> sLogged = java.util.Collections
            .synchronizedSet(new HashSet<String>());

    private static void logOnce(int mode, String msg) {
        if (sLogged.add(mode + "|" + msg)) {
            log(msg + "  (mode=" + mode + ")");
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
                    }
                } catch (Throwable ignore) {
                }
            }
            log("cfg=" + chosen + " enabled=" + sEnabled + " modes="
                    + sModes + " ms=" + sMs);
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
