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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 焦段档位「新增」（zoom.add.<模式号>=v1,v2,...）。
 *
 * ============================================================
 * 背景：原模块的焦段引擎只有两种能力
 * ============================================================
 *   zoom_remap=3.2:2.6,6.4:5.0   —— 只能改「值」
 *   zoom.drop.<mode>=…            —— 只能「减」按钮
 *   （PortConfig 模板原话：「重映射只能改值，改不掉档位个数；只有剔除能让按钮少一个」）
 * 缺的正是「加」：专业模式(167) 档位源 data.i.T(167) = [0.7,1.0,3.2]，
 * 经 zoom_remap 后显示 [0.7,1.0,2.6] —— 按钮只有 3 个，没有 5x。
 * 拍照(163) 源表 [0.7,1.0,2.0,3.2,6.4] → remap → [0.7,1.0,2.0,2.6,5.0] 是 5 个。
 * 本 hook 补上「加」：按模式把缺的档位值追加到档位表末尾。
 *
 * ============================================================
 * hook 点（三处，与原引擎 remap 的落点同构，contains 去重保证不重复加）
 * ============================================================
 * ① data.i.T(int,boolean) 的返回          —— 数据源层：
 *    i.J/V（算索引）、FragmentZoomToggle、getSupportedBackZoomOuterValues 都从这走，
 *    保证「点按索引计算」和「按钮个数」用同一张表。
 * ② ZoomRatioToggleView.u(float[],Z,Z,Z)  —— 视图层入口（原引擎 remap 也在 u 落一层）：
 *    入参 before 追加 + 返回 after 追加；视图若从别的路拿到表，这里兜底。
 * ③ data.i.V(int,boolean) 的返回          —— 点按取档层（v6 加，修人像点 5 变 2.6）：
 *    点按链路是 ZoomRatioToggleView.m(mode,idx) → i.I(mode,idx) → i.V(mode,z9)，
 *    完全不经过 ① 的 T。V 对 mode==171 走专属分支（jadx 反编译失败区，
 *    返回 e.X0() 的 sparse[171] 或 k0.v()），也不调 T —— 所以 171 拿不到 5.0，
 *    i.I 越界 clamp 到 length-1 → 点 5 落回 2.6（idx0→1.0 / idx1→2.6 / idx2→2.6）。
 *    非 171（专业 167）落 L84 → T(mode,r0) → 经 ① 已含 5.0，本层幂等不重复加。
 *    若 ③ 装不上则退回 hook i.I（同样幂等，且在 V 已生效时是空操作）。
 * 两层之间无论 remap 先后（3.2:2.6 改值、6.4:5.0 改值都不会碰到新增值），
 * 最终都是 [0.7, 1.0, 2.6, 5.0]。
 *
 * ============================================================
 * 配置（与 zoom.drop.<mode> 同风格，写在同一个 config.conf）
 * ============================================================
 *   zoom.add.167=5.0        专业模式追加 5x（默认值，不写这行也生效）
 *   zoom.add.167=           空值 = 关闭该模式的新增
 *   zoom.add.171=1.0,2.6    给别的模式加档位（可多行、可多模式）
 * 观察闸门：observe_only=true 时本 hook 不改行为（与原引擎一致）。
 *
 * 日志：camport/zoomadd.log（每个模式只记一次「已新增」，不刷屏）。
 */
public final class ZoomAddFix implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "焦段新增: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    private static final String C_DATA_I = "com.android.camera.data.data.i";
    private static final String C_ZOOM_VIEW =
            "com.android.camera.ui.zoom.ZoomRatioToggleView";

    private static ClassLoader sCl;
    private static boolean sInstalled;

    private static volatile boolean sCfgDone;
    /** 模式 → 要保证存在的档位值（默认：专业模式要有 5.0） */
    private static volatile Map<Integer, float[]> sAdd = defaults();
    private static volatile boolean sObserveOnly;

    private static final Set<String> sLogged =
            java.util.Collections.synchronizedSet(new HashSet<String>());

    /**
     * 模式 → 视图上真正用的档位表长度（取自 ② u() 的返回，u 全工程只有
     * ZoomRatioToggleView.M 一处调用，fArrU 随后就是 this.R / 传给 v 的那张表）。
     *
     * ============================================================
     * 为什么必须有它（v6 实测崩溃，10-10 13:00）
     * ============================================================
     * java.lang.ArrayIndexOutOfBoundsException: length=3; index=4
     *   at ZoomRatioToggleView.n → q → v → M → H4.p0.provideAnimateElement
     *
     * n() 的实现：
     *   int iJ = i.J(a, z, p, mode);      // ← 下标按 data.i.V(mode,z) 那张表算
     *   if (fArr.length == 1) iJ = 0;
     *   if (a) iJ = (fArr.length - 1) - iJ;
     *   return Arrays.binarySearch(R, fArr[iJ]);   // ← fArr 却是 u() 的显示表
     *
     * 两张表长度天生不同：人像 V(171,false)=[1.0,2.0,3.2,4.3](+我们加的5.0=5)，
     * 而显示表 u()=[1.0,2.6](+5.0=3)。原生人像 zoom 恒 ≤2.6 → i.J 最多返回 1，
     * < 3 所以从来不炸；我们把 5.0 加进去、p 到 5.0 之后 i.J 返回 4 → fArr[4] 炸。
     *
     * 修法：i.J 的返回只在「会越界」时夹到显示表长度-1。有效下标本来
     * 就 < 显示表长度，所以这个夹子对 167 等一切正常路径零影响。
     */
    private static final Map<Integer, Integer> sGearLen =
            java.util.Collections.synchronizedMap(new HashMap<Integer, Integer>());

    private static Map<Integer, float[]> defaults() {
        Map<Integer, float[]> m = new HashMap<>();
        m.put(Integer.valueOf(167), new float[]{5.0f});
        return m;
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
        if (sAdd.isEmpty()) {
            log("无新增档位（zoom.add.* 全为空）");
            return;
        }
        if (sObserveOnly) {
            log("observe_only=true → 只观察，不新增");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(C_DATA_I, sCl, "T",
                    Integer.TYPE, Boolean.TYPE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = ((Integer) p.args[0]).intValue();
                                float[] need = need(mode);
                                if (need == null) {
                                    return;
                                }
                                float[] res = (float[]) p.getResult();
                                float[] neu = appendMissing(res, need);
                                if (neu != null) {
                                    p.setResult(neu);
                                    logOnce(mode, "data.i.T 追加后 = "
                                            + fmt(neu));
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook " + C_DATA_I + ".T(int,boolean) ok");
        } catch (Throwable th) {
            log("!! hook T 失败 " + th);
        }
        boolean vOk = false;
        try {
            XposedHelpers.findAndHookMethod(C_DATA_I, sCl, "V",
                    Integer.TYPE, Boolean.TYPE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = ((Integer) p.args[0]).intValue();
                                float[] need = need(mode);
                                if (need == null) {
                                    return;
                                }
                                float[] res = (float[]) p.getResult();
                                float[] neu = appendMissing(res, need);
                                if (neu != null) {
                                    p.setResult(neu);
                                    logOnce(mode, "data.i.V 追加后 = "
                                            + fmt(neu));
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            vOk = true;
            log("hook " + C_DATA_I + ".V(int,boolean) ok");
        } catch (Throwable th) {
            log("!! hook V 失败 " + th);
        }
        if (!vOk) {
            // ③ 装不上就退回到 i.I（点按索引→倍率的唯一入口），同样幂等
            try {
                XposedHelpers.findAndHookMethod(C_DATA_I, sCl, "I",
                        Integer.TYPE, Integer.TYPE, Boolean.TYPE, Boolean.TYPE,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p)
                                    throws Throwable {
                                try {
                                    int mode = ((Integer) p.args[0]).intValue();
                                    int idx = ((Integer) p.args[1]).intValue();
                                    boolean rev = ((Boolean) p.args[2])
                                            .booleanValue();
                                    boolean z9 = ((Boolean) p.args[3])
                                            .booleanValue();
                                    float[] need = need(mode);
                                    if (need == null) {
                                        return;
                                    }
                                    Class<?> cI = XposedHelpers.findClass(
                                            C_DATA_I, sCl);
                                    float[] arr = (float[]) XposedHelpers
                                            .callStaticMethod(cI, "V",
                                                    Integer.valueOf(mode),
                                                    Boolean.valueOf(z9));
                                    float[] ext = appendMissing(arr, need);
                                    if (ext == null) {
                                        return;
                                    }
                                    int n = ext.length;
                                    int i = idx;
                                    if (i < 0) {
                                        i = 0;
                                    } else if (i >= n) {
                                        i = n - 1;
                                    }
                                    p.setResult(Float.valueOf(rev
                                            ? ext[n - 1 - i] : ext[i]));
                                } catch (Throwable th) {
                                    err(th);
                                }
                            }
                        });
                log("hook " + C_DATA_I + ".I(int,int,Z,Z)（V 的兜底）ok");
            } catch (Throwable th) {
                log("!! hook I 失败 " + th);
            }
        }
        try {
            XposedHelpers.findAndHookMethod(C_ZOOM_VIEW, sCl, "u",
                    float[].class, Boolean.TYPE, Boolean.TYPE, Boolean.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                float[] need = needOfView(p.thisObject);
                                if (need == null) {
                                    return;
                                }
                                float[] neu = appendMissing(
                                        (float[]) p.args[0], need);
                                if (neu != null) {
                                    p.args[0] = neu;
                                    logOnce(modeOf(p.thisObject),
                                            "ZoomRatioToggleView.u 入参追加后 = "
                                                    + fmt(neu));
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = modeOf(p.thisObject);
                                float[] need = need(mode);
                                if (need != null) {
                                    float[] neu = appendMissing(
                                            (float[]) p.getResult(), need);
                                    if (neu != null) {
                                        p.setResult(neu);
                                    }
                                }
                                // 记下这张真正被视图使用的档位表长度，
                                // 供 ⑥ i.J 的越界夹取用（见 sGearLen 注释）。
                                float[] cur = (float[]) p.getResult();
                                if (cur != null) {
                                    sGearLen.put(Integer.valueOf(mode),
                                            Integer.valueOf(cur.length));
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook " + C_ZOOM_VIEW + ".u(float[],Z,Z,Z) ok");
        } catch (Throwable th) {
            log("!! hook u 失败 " + th);
        }
        // ④ v6：点按索引→倍率的**真正入口**是 ZoomRatioToggleView.m()
        //    （Ds 里 fM = f1903j.m(mode, idx, ...)），它内部会按光学偏移
        //    把 idx 减掉 e0.q 再交给 i.I —— 所以光补 i.V 不够，
        //    减完偏移落在补进来的 5.0 之前（点 5 仍解析成 2.6）。
        //    这里直接以**视图上真正显示的表 R** 为准做兜底：
        //    只有当 R[idx] 是我们新增的档位值、且和原结果不同时才改写。
        try {
            XposedHelpers.findAndHookMethod(C_ZOOM_VIEW, sCl, "m",
                    Integer.TYPE, Integer.TYPE, Boolean.TYPE, Boolean.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = ((Integer) p.args[0]).intValue();
                                float[] need = need(mode);
                                if (need == null) {
                                    return;
                                }
                                int idx = ((Integer) p.args[1]).intValue();
                                boolean rev = ((Boolean) p.args[2])
                                        .booleanValue();
                                float[] disp = null;
                                try {
                                    disp = (float[]) XposedHelpers
                                            .getObjectField(p.thisObject, "R");
                                } catch (Throwable ignore) {
                                }
                                float[] got = null;
                                if (p.getResult() instanceof Float) {
                                    got = new float[]{((Float) p.getResult())
                                            .floatValue()};
                                }
                                String sR = disp == null ? "?" : fmt(disp);
                                String sGot = got == null ? "?" : fmt(got);
                                if (disp == null || disp.length == 0 || idx < 0
                                        || idx >= disp.length) {
                                    log("视图.m(mode=" + mode + ", idx=" + idx
                                            + ", rev=" + p.args[2] + ") → "
                                            + sGot + " R=" + sR
                                            + "（idx 不在显示表内，不改）");
                                    return;
                                }
                                float exp = rev
                                        ? disp[disp.length - 1 - idx]
                                        : disp[idx];
                                boolean hit = false;
                                for (int i = 0; i < need.length; i++) {
                                    if (Math.abs(need[i] - exp) < 0.001f) {
                                        hit = true;
                                        break;
                                    }
                                }
                                float nativeV = ((Float) p.getResult())
                                        .floatValue();
                                if (hit && Math.abs(nativeV - exp) > 0.001f) {
                                    p.setResult(Float.valueOf(exp));
                                    log("视图.m(mode=" + mode + ", idx=" + idx
                                            + ", rev=" + p.args[2] + ") "
                                            + nativeV + " → " + exp
                                            + "  R=" + sR);
                                } else {
                                    log("视图.m(mode=" + mode + ", idx=" + idx
                                            + ", rev=" + p.args[2] + ") → "
                                            + sGot + " R=" + sR
                                            + (hit ? "（一致不改）"
                                                    : "（非新增档，不改）"));
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook " + C_ZOOM_VIEW + ".m(int,int,Z,Z) ok（点按以显示表为准）");
        } catch (Throwable th) {
            log("!! hook m 失败 " + th);
        }
        // ⑤ 取证：i.I(mode, idx) 用了哪张表、越界 clamp 到多少
        try {
            XposedHelpers.findAndHookMethod(C_DATA_I, sCl, "I",
                    Integer.TYPE, Integer.TYPE, Boolean.TYPE, Boolean.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = ((Integer) p.args[0]).intValue();
                                if (need(mode) == null) {
                                    return;
                                }
                                int idx = ((Integer) p.args[1]).intValue();
                                boolean z9 = ((Boolean) p.args[3])
                                        .booleanValue();
                                Class<?> cI = XposedHelpers.findClass(
                                        C_DATA_I, sCl);
                                float[] arr = (float[]) XposedHelpers
                                        .callStaticMethod(cI, "V",
                                                Integer.valueOf(mode),
                                                Boolean.valueOf(z9));
                                log("i.I(mode=" + mode + ", idx=" + idx
                                        + ", rev=" + p.args[2] + ") V="
                                        + fmt(arr) + " → " + p.getResult());
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook " + C_DATA_I + ".I(int,int,Z,Z) ok（取证）");
        } catch (Throwable th) {
            log("!! hook I 失败 " + th);
        }
        // ⑥ v6：修人像进模式/点按就闪退的 ArrayIndexOutOfBounds
        //    （length=3; index=4，见 sGearLen 注释里的完整堆栈）。
        //    i.J() 返回的是「按 V 表算出来的下标」，但 n()/M()/v() 拿它去索引
        //    u() 产出的显示表；两张表长度不同，人像加了 5.0 之后 p 一到 5.0
        //    下标就到 4 → fArr[4] 越界。这里只在**真的会越界**时夹一下，
        //    有效下标本来就不越界 → 对专业(167)等一切正常路径零影响。
        try {
            XposedHelpers.findAndHookMethod(C_DATA_I, sCl, "J",
                    Boolean.TYPE, Boolean.TYPE, Float.TYPE, Integer.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                int mode = ((Integer) p.args[3]).intValue();
                                if (need(mode) == null) {
                                    return; // 只管我们新增过档位的模式
                                }
                                Integer cap = sGearLen.get(
                                        Integer.valueOf(mode));
                                if (cap == null || cap.intValue() <= 0) {
                                    return; // 还没量到显示表长度 → 维持原状
                                }
                                Object res = p.getResult();
                                if (!(res instanceof Integer)) {
                                    return;
                                }
                                int idx = ((Integer) res).intValue();
                                int hi = cap.intValue() - 1;
                                if (idx > hi || idx < 0) {
                                    int fix = idx > hi ? hi : 0;
                                    p.setResult(Integer.valueOf(fix));
                                    log("i.J(mode=" + mode + ", p="
                                            + p.args[2] + ", 显示表长度="
                                            + cap + ") " + idx + " → 夹到 "
                                            + fix + "（越界修正）");
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook " + C_DATA_I + ".J(Z,Z,F,I) ok（越界夹取，防闪退）");
        } catch (Throwable th) {
            log("!! hook J 失败 " + th);
        }
    }

    private static float[] need(int mode) {
        return sAdd.get(Integer.valueOf(mode));
    }

    private static int modeOf(Object view) {
        return XposedHelpers.getIntField(view, "q");
    }

    private static float[] needOfView(Object view) {
        return need(modeOf(view));
    }

    /**
     * arr 里缺哪些就补哪些，返回新数组；不缺返回 null（= 无变化）。
     * 只往末尾加（档位表是升序的，新增值也按升序放末尾；若有更小值也照加，
     * 下游 i.J / binarySearch 都不假设顺序，View 会自己排）。
     */
    private static float[] appendMissing(float[] arr, float[] need) {
        if (arr == null || need == null || need.length == 0) {
            return null;
        }
        int missing = 0;
        for (int i = 0; i < need.length; i++) {
            if (!contains(arr, need[i])) {
                missing++;
            }
        }
        if (missing == 0) {
            return null;
        }
        float[] out = new float[arr.length + missing];
        System.arraycopy(arr, 0, out, 0, arr.length);
        int w = arr.length;
        for (int i = 0; i < need.length; i++) {
            if (!contains(arr, need[i])) {
                out[w++] = need[i];
            }
        }
        return out;
    }

    private static boolean contains(float[] arr, float v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == v) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(float[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    private static void logOnce(int mode, String msg) {
        String key = mode + "|" + msg;
        if (sLogged.add(key)) {
            log(msg + "  (mode=" + mode + ")");
        }
    }

    /** 读 config.conf 的 zoom.add.<mode> 与 observe_only；只读一次 */
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
            log("未找到 config.conf，用默认 zoom.add.167=5.0");
            return;
        }
        Map<Integer, float[]> add = defaults();
        boolean observe = false;
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
                    if ("observe_only".equals(k)) {
                        observe = "true".equalsIgnoreCase(v);
                    } else if (k.startsWith("zoom.add.")) {
                        int mode = Integer.parseInt(k.substring(9).trim());
                        if (v.length() == 0) {
                            add.remove(Integer.valueOf(mode));   // 空值=关闭
                        } else {
                            String[] parts = v.split(",");
                            float[] vals = new float[parts.length];
                            int n = 0;
                            for (int i = 0; i < parts.length; i++) {
                                vals[n++] = Float.parseFloat(parts[i].trim());
                            }
                            float[] trimmed = new float[n];
                            System.arraycopy(vals, 0, trimmed, 0, n);
                            add.put(Integer.valueOf(mode), trimmed);
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
            sObserveOnly = observe;
            sAdd = add;
            log("cfg=" + chosen + " observe_only=" + observe + " add=" + summarize());
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

    private static String summarize() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Integer, float[]> e : sAdd.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()).append('=').append(fmt(e.getValue()));
        }
        return sb.append('}').toString();
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
                        "zoomadd.log");
                File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(String.format(Locale.US, "%s I/焦段新增: %s%n",
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
