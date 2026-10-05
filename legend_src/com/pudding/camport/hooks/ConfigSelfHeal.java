package com.pudding.camport.hooks;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置自愈。
 *
 * 背景：重装相机包会清掉外置 config.conf，此时 PortConfig.dumpTemplate()
 * 只写回内嵌的残缺模板 —— 缺 46 项（含 ai.smartcomp，导致智能构图进不去），
 * 而且不含 legend.lensswitch / legend.telefix，两个开关回到默认「开」，
 * 已修好的「换焦模糊半秒 + 2.6 显示 4.5」会直接复发。
 *
 * 做法：相机进程加载时，只把 config.conf 里缺的键增量补齐（含两个开关），
 * 已有键一律保留原值 —— 只增不改，绝不覆盖你手动调过的参数
 * （例如 legend.lensswitch=1 这类实验性开关必须能由用户自己控制）。
 */
public final class ConfigSelfHeal implements IXposedHookLoadPackage {

    private static final String TAG = "CamPort";
    private static final String PKG = "com.android.camera";

    private static final String[] PATHS = {
            "/data/data/com.android.camera/files/camport/config.conf",
            "/sdcard/Android/data/com.android.camera/files/camport/config.conf",
            "/storage/emulated/0/Android/data/com.android.camera/files/camport/config.conf",
            "/data/local/tmp/camport/config.conf"
    };

    private static boolean sDone;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !PKG.equals(lpparam.packageName)) {
            return;
        }
        if (sDone) {
            return;
        }
        sDone = true;
        try {
            ensure();
        } catch (Throwable t) {
            Log.i(TAG, "配置自愈失败: " + t);
        }
    }

    private static void ensure() {
        String existing = null;
        for (int i = 0; i < PATHS.length; i++) {
            File f = new File(PATHS[i]);
            if (f.isFile() && f.canRead()) {
                existing = PATHS[i];
                break;
            }
        }

        if (existing == null) {
            String w = writeFullTemplate();
            Log.i(TAG, "配置自愈: 未找到 config.conf，已写入完整模板 -> " + w);
            return;
        }

        Map<String, String> have = new LinkedHashMap<String, String>();
        List<String> raw = new ArrayList<String>();
        readLines(existing, raw);
        for (int i = 0; i < raw.size(); i++) {
            String kv = keyValue(raw.get(i));
            if (kv != null) {
                int eq = kv.indexOf('=');
                have.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }

        // 1) 内置模板里有、文件里没有的键 -> 补齐
        List<String> missing = new ArrayList<String>();
        Map<String, String> tpl = templateKeys();
        for (Map.Entry<String, String> e : tpl.entrySet()) {
            if (!have.containsKey(e.getKey())) {
                missing.add(e.getKey() + "=" + e.getValue());
            }
        }

        // 2) 开关（legend.lensswitch / legend.telefix）也在模板里，
        //    缺失时会随上面的 missing 一起补上；已存在则完全尊重文件当前值。

        if (missing.isEmpty()) {
            Log.i(TAG, "配置自愈: " + existing + " 完整，无需修改");
            return;
        }

        // 重写：保留所有原有行，末尾追加缺失键
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.size(); i++) {
            sb.append(raw.get(i)).append('\n');
        }
        sb.append("# --- 自愈补齐（重装相机包后缺失的键）---\n");
        for (int i = 0; i < missing.size(); i++) {
            sb.append(missing.get(i)).append('\n');
        }

        if (writeFile(existing, sb.toString())) {
            Log.i(TAG, "配置自愈: " + existing + " 补齐 " + missing.size() + " 项");
        } else {
            Log.i(TAG, "配置自愈: 写入失败 " + existing);
        }
    }

    /** 从 ConfigDefaults.FULL 解析出 key -> value（跳过注释与空行） */
    private static Map<String, String> templateKeys() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String[] lines = ConfigDefaults.FULL.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String kv = keyValue(lines[i]);
            if (kv == null) {
                continue;
            }
            int eq = kv.indexOf('=');
            String k = kv.substring(0, eq).trim();
            if (k.length() > 0) {
                out.put(k, kv.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /** 非注释、含 = 的行返回原样，否则 null */
    private static String keyValue(String line) {
        if (line == null) {
            return null;
        }
        String t = line.trim();
        if (t.length() == 0 || t.startsWith("#")) {
            return null;
        }
        int eq = t.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        return t;
    }

    private static void readLines(String path, List<String> out) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String line;
            while ((line = br.readLine()) != null) {
                out.add(line);
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored2) {
                }
            }
        }
    }

    private static boolean writeFile(String path, String content) {
        FileWriter fw = null;
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            fw = new FileWriter(f, false);
            fw.write(content);
            fw.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (fw != null) {
                try {
                    fw.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String writeFullTemplate() {
        for (int i = 0; i < PATHS.length; i++) {
            File f = new File(PATHS[i]);
            if (f.exists()) {
                continue;
            }
            if (writeFile(PATHS[i], ConfigDefaults.FULL)) {
                return PATHS[i];
            }
        }
        return null;
    }
}
