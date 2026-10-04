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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 拍照模式最右「5」按钮的重复点按循环（问题一）。
 *
 * ============================================================
 * 病因（已实测闭环）
 * ============================================================
 * 重复点按「当前已选中」的按钮不会走 p0.Ds，而是走另一条路：
 *   ZoomRatioToggleView.onClick
 *     -> x0.n(int)                     （dex Lv2/x0; -> jadx p177v2.x0）
 *     -> setComponentValue + ZoomRatioToggleView.M.Is(value, index)
 *
 * x0.n() 不是按「显示行」循环，而是按 c1() 建出来的**档位(gear)项表**循环：
 *   C0209.c1()[163] = { gear1:["1.0","28mm","35mm"], gear4:["6.4","300mm"] }
 *   -> items ~= [1.0, 1.2, 1.5, 6.4, 10.0]，gear = [1,1,1,4,4]
 *   -> f17892j[163]（jadx 名，dex 字段 j）= [-1,0,-1,-1,3]
 * x0.n() 逻辑（x0.java:666）：
 *   找到第一个 i 满足 cur >= items[i].r；
 *   若 i+1 越界 或 gear 变了 -> 回卷到「本档第一项」 items[j[gear(i)]].r
 *   否则 -> items[i+1].r
 *
 * 因为 camport 的 zoom_remap=6.4:5.0 只改了**显示行**（data.i.T / 按钮.m /
 * 适配器.* / setZoomArray），**没有改 c1() 建出来的项表**，于是
 * getComponentValue(163) = "5.0" 而项表里只有 6.4：
 *   5.0 < 6.4  -> 落在 gear1 末项 -> 换档 -> 回卷到 gear1 首项 = 1.0
 * 实测日志：
 *   MCAM_ZoomRatioToggleView: currentValue = 5.0 nextValue = 1.0
 *   然后 ZOOMING_BY_INPROCESS_TOGGLE_FOCAL_BUTTON 4.47/3.52/2.6/2.0/1.0
 * 这就是「点 5 回到 1×」。
 *
 * ============================================================
 * 6.4 -> 10.0 就是「长焦档再点一次翻倍」吗？
 * ============================================================
 * c1()[163] gear4 只有两项：
 *   "6.4"    -> 无 mm，走 else 分支，直接取数值 6.4（原机长焦原生倍率）
 *   "300mm"  -> 走 mm 分支：ratio = (300 / p(该镜头焦距mm)) * 该镜头原生倍率
 * p(id) = Math.round(h.r(R().M(id)))，即该镜头的等效焦距；
 * 原机 6.4x 长焦的等效焦距是 150mm 级 -> (300/150) * 6.4 = 12.8 = 2 * 6.4
 * 本机 role23 长焦原生 5.0x、等效焦距同为 150mm 级 -> (300/150) * 5.0 = 10.0
 * 即 **gear 第二项 = 本档第一项的 2 倍**，确实是「长焦按钮再点一次翻倍」。
 * 只是 5.0 匹配不到项表（项表里是 6.4），翻倍逻辑永远触发不了。
 *
 * ============================================================
 * 本 hook 做什么（最小侵入）
 * ============================================================
 * 只在 x0.n() 返回前，对「最右按钮所在的倍率区间」按配置的 stop 列表
 * 重新算下一个值；其余全部原样放行。
 *   - 不改显示行（0.7 / 1 / 2 / 2.6 / 5 不动）
 *   - 不碰双指缩放、变焦拨盘（走的是 p0.Ds / zoomring，不是 n()）
 *   - 只在 mode 命中（默认 163 = 拍照）且 cur >= legend.zoomcycle_min（默认 3.0，
 *     即只有「当前停在最右按钮区间」时）才介入；1x 档的 1.0->1.2->1.5 循环原样保留
 *
 * config.conf 新增键：
 *   legend.zoomcycle=1              0=关
 *   legend.zoomcycle_stops=5,10,20  循环序列（升序，点按依次取下一个，超出回卷到第一个）
 *   legend.zoomcycle_modes=163      逗号分隔
 *   legend.zoomcycle_min=3.0        低于此倍率不介入
 */
public final class ZoomCycleFix implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "变焦循环: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    /** dex Lv2/x0;（jadx 叫 p177v2.x0），已在 ZoomProbe.C_SWITCH_ZOOM 交叉验证 */
    private static final String C_SWITCH_ZOOM = "v2.x0";

    private static ClassLoader sCl;
    private static boolean sInstalled;

    private static volatile boolean sCfgDone;
    private static volatile boolean sEnabled = true;
    private static volatile float sMin = 3.0f;
    private static volatile float[] sStops = new float[]{5.0f, 10.0f, 20.0f};
    private static volatile LinkedHashSet<Integer> sModes = modes("163");

    private static volatile boolean sDumpedItems;

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
            XposedHelpers.findAndHookMethod(C_SWITCH_ZOOM, sCl, "n", Integer.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                apply(param);
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook " + C_SWITCH_ZOOM + ".n(int) ok  modes=" + sModes
                    + " stops=" + fmt(sStops) + " min=" + sMin);
        } catch (Throwable th) {
            log("!! hook 失败 " + th);
        }
    }

    private static void apply(XC_MethodHook.MethodHookParam param) {
        int mode = ((Integer) param.args[0]).intValue();
        if (!sModes.contains(Integer.valueOf(mode))) {
            return;
        }
        Object self = param.thisObject;
        String cur = (String) XposedHelpers.callMethod(self, "getComponentValue",
                Integer.valueOf(mode));
        if (cur == null) {
            return;
        }
        float f;
        try {
            f = Float.parseFloat(cur.trim());
        } catch (Throwable th) {
            return;
        }
        if (f < sMin) {
            return;   // 1x 档及其以下：完全不介入，保留原厂 1.0->1.2->1.5 循环
        }
        float next = nextStop(f);
        if (next < 0f) {
            return;
        }
        String nxt = String.valueOf(next);
        Object origObj = param.getResult();
        String orig = origObj == null ? "null" : String.valueOf(origObj);
        if (nxt.equals(cur) || nxt.equals(orig)) {
            return;
        }
        param.setResult(nxt);
        log("mode=" + mode + " cur=" + cur + " orig=" + orig + " -> " + nxt
                + "  items=" + dumpItems(self));
    }

    /** 升序 stop 列表里第一个 > f 的；全都 <= f 则回卷到第一个 */
    private static float nextStop(float f) {
        float[] st = sStops;
        if (st == null || st.length == 0) {
            return -1f;
        }
        for (int i = 0; i < st.length; i++) {
            if (st[i] > f + 1.0E-4f) {
                return st[i];
            }
        }
        return st[0];
    }

    /** 只为诊断：把 x0 的项表打出来，确认 6.4/10.0 这套「翻倍」结构 */
    private static String dumpItems(Object self) {
        if (sDumpedItems) {
            return "-";
        }
        try {
            List<?> items = (List<?>) XposedHelpers.callMethod(self, "getItems");
            if (items == null || items.isEmpty()) {
                return "<empty>";
            }
            sDumpedItems = true;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                Object it = items.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('{').append(XposedHelpers.getObjectField(it, "r"));
                sb.append("/g").append(XposedHelpers.getIntField(it, "c")).append('}');
            }
            return sb.append(']').toString();
        } catch (Throwable th) {
            return "<dump-err " + th + ">";
        }
    }

    private static String fmt(float[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    private static LinkedHashSet<Integer> modes(String csv) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        for (String t : csv.split(",")) {
            try {
                set.add(Integer.valueOf(t.trim()));
            } catch (Throwable ignore) {
            }
        }
        return set.isEmpty() ? modes("163") : set;
    }

    /** 读 config.conf 的 legend.zoomcycle*；只读一次 */
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
                    } else if ("legend.zoomcycle_stops".equals(k)) {
                        List<Float> l = new ArrayList<>();
                        for (String t2 : v.split(",")) {
                            l.add(Float.valueOf(Float.parseFloat(t2.trim())));
                        }
                        float[] a = new float[l.size()];
                        for (int i = 0; i < a.length; i++) {
                            a[i] = l.get(i).floatValue();
                        }
                        sStops = a;
                    }
                } catch (Throwable ignore) {
                }
            }
            log("cfg=" + chosen + " enabled=" + sEnabled + " modes=" + sModes
                    + " stops=" + fmt(sStops) + " min=" + sMin);
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
                sWriter.write(String.format(Locale.US, "%s I/变焦循环: %s%n",
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
