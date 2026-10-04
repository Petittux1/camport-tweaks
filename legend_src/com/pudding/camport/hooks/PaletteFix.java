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

public final class PaletteFix implements IXposedHookLoadPackage {

    private static final String TAG = "CamPort";
    private static final String HEAD = "调色盘fix: ";

    private static final String C_STAGE = "s7.b";
    private static final String C_S = "Sh.s";
    private static final String C_N1A = "N1.a";
    private static final String C_TAG = "com.xiaomi.camera.mivi.filter.MIVIRenderTag";
    private static final String STYLE = "StyleAdjust";

    private static final int PICTURE_STYLE = 512;
    private static final int FILTER = 1;

    private static int sForce = 1;
    private static int sInject = 1;
    private static int sDup = 1;
    private static int sProbe = 1;
    private static int sWithFilter = 1;
    private static boolean sCfgDone;

    private static volatile Object sTag;
    private static volatile int sTagType;

    private static final Object sLogLock = new Object();
    private static FileWriter sWriter;
    private static long sFlushAt;
    private static long sWinStart;
    private static int sWinCount;
    private static int sPending;
    private static int sTotal;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        log("ENTER pkg=" + lpparam.packageName);
        if (!"com.android.camera".equals(lpparam.packageName)) {
            return;
        }
        ensureCfg();
        final ClassLoader cl = lpparam.classLoader;

        try {
            Class<?> clsTag = XposedHelpers.findClass(C_TAG, cl);
            XposedHelpers.findAndHookMethod(clsTag, "initParams", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (sProbe == 0) {
                        return;
                    }
                    try {
                        cacheTag(p.thisObject);
                    } catch (Throwable t) {
                        log("!! cacheTag " + t);
                    }
                }
            });
            log("hook MIVIRenderTag.initParams ok");
        } catch (Throwable t) {
            log("!! hook MIVIRenderTag.initParams " + t);
        }

        try {
            final Class<?> clsS = XposedHelpers.findClass(C_S, cl);
            final Class<?> clsStage = XposedHelpers.findClass(C_STAGE, cl);

            XposedHelpers.findAndHookMethod(clsStage, "b", clsS, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (sProbe == 0) {
                        return;
                    }
                    try {
                        probeParser(p);
                    } catch (Throwable t) {
                        log("!! probeParser " + t);
                    }
                }
            });
            log("hook s7.b.b parser ok");

            XposedHelpers.findAndHookMethod(clsStage, "a", clsS, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sProbe == 0 && sInject == 0 && sForce == 0) {
                        return;
                    }
                    try {
                        onStageRun(p);
                    } catch (Throwable t) {
                        log("!! onStageRun " + t);
                    }
                }
            });
            log("hook s7.b.a execute ok");
        } catch (Throwable t) {
            log("!! hook s7.b " + t);
        }

        try {
            Class<?> clsN1 = XposedHelpers.findClass(C_N1A, cl);
            XposedHelpers.findAndHookMethod(clsN1, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (sProbe == 0 && sDup == 0) {
                        return;
                    }
                    try {
                        onRenderRun(p);
                    } catch (Throwable t) {
                        log("!! onRenderRun " + t);
                    }
                }
            });
            log("hook N1.a.run ok");
        } catch (Throwable t) {
            log("!! hook N1.a.run " + t);
        }

        log("installed force=" + sForce + " inject=" + sInject + " dup=" + sDup
                + " probe=" + sProbe + " withfilter=" + sWithFilter);
    }

    private static void cacheTag(Object tag) {
        ArrayList<?> candy = (ArrayList<?>) XposedHelpers.getObjectField(tag, "mCandyParams");
        int type = ((Integer) XposedHelpers.callMethod(tag, "getType")).intValue();
        if (candy == null || candy.isEmpty()) {
            return;
        }
        if (!hasStyle(candy)) {
            return;
        }
        sTag = tag;
        sTagType = type;
        log("cache tag type=" + type + " candy=" + candy.size() + " lut="
                + sz((ArrayList<?>) XposedHelpers.getObjectField(tag, "mLutBitmaps"))
                + " script=" + brief(candy.get(0)));
    }

    private static void probeParser(XC_MethodHook.MethodHookParam p) {
        Object sVar = p.args[0];
        Object cfg = XposedHelpers.getObjectField(p.thisObject, "b");
        StringBuilder sb = new StringBuilder("parser ret=");
        sb.append(p.getResult());
        try {
            Object aux = XposedHelpers.getObjectField(sVar, "b");
            sb.append(" parallelType=").append(XposedHelpers.getIntField(aux, "f"));
            sb.append(" moduleIndex=").append(XposedHelpers.getIntField(aux, "g"));
        } catch (Throwable t) {
            sb.append(" auxErr=").append(t);
        }
        try {
            Object eff = XposedHelpers.getObjectField(sVar, "d");
            sb.append(" candy=").append(sz((ArrayList<?>) XposedHelpers.getObjectField(eff, "j")));
            sb.append(" lut=").append(sz((ArrayList<?>) XposedHelpers.getObjectField(eff, "h")));
        } catch (Throwable t) {
            sb.append(" effErr=").append(t);
        }
        sb.append(" cfg=").append(cfg == null ? "null" : str(XposedHelpers.getObjectField(cfg, "a")));
        try {
            Object c11 = XposedHelpers.getObjectField(XposedHelpers.getObjectField(sVar, "k"), "l");
            sb.append(" c11=").append(c11 == null ? "null" : c11.getClass().getName());
        } catch (Throwable t) {
            sb.append(" c11Err=").append(t);
        }
        log(sb.toString());
    }

    private static void onStageRun(XC_MethodHook.MethodHookParam p) {
        Object sVar = p.args[0];
        Object cfg = XposedHelpers.getObjectField(p.thisObject, "b");
        if (cfg == null) {
            log("a() cfg==null, nothing to force");
            return;
        }
        Object eff = XposedHelpers.getObjectField(sVar, "d");
        ArrayList<?> candy = (ArrayList<?>) XposedHelpers.getObjectField(eff, "j");
        ArrayList<?> lut = (ArrayList<?>) XposedHelpers.getObjectField(eff, "h");

        StringBuilder sb = new StringBuilder("a() enter cfg.a=");
        sb.append(str(XposedHelpers.getObjectField(cfg, "a")));
        sb.append(" candy=").append(sz(candy));
        sb.append(" lut=").append(sz(lut));

        if (sInject == 1 && (candy == null || candy.isEmpty())) {
            boolean injected = injectTag(eff, sb);
            if (injected) {
                candy = (ArrayList<?>) XposedHelpers.getObjectField(eff, "j");
                lut = (ArrayList<?>) XposedHelpers.getObjectField(eff, "h");
            }
        } else if (candy != null && !candy.isEmpty()) {
            sb.append(" script=").append(brief(candy.get(0)));
        }

        Object curObj = XposedHelpers.getObjectField(cfg, "a");
        boolean cur = curObj instanceof Boolean && ((Boolean) curObj).booleanValue();
        if (sForce == 1 && !cur && candy != null && !candy.isEmpty() && hasStyle(candy)) {
            try {
                XposedHelpers.setObjectField(cfg, "a", Boolean.TRUE);
                sb.append(" FORCE true");
            } catch (Throwable t) {
                sb.append(" FORCE fail=").append(t);
            }
        } else if (sForce == 1 && !cur) {
            sb.append(" force-skip style=").append(hasStyle(candy));
        }
        log(sb.toString());
    }

    private static boolean injectTag(Object eff, StringBuilder sb) {
        Object tag = sTag;
        if (tag == null) {
            sb.append(" inject=noTag");
            return false;
        }
        int type = sTagType;
        if ((type & PICTURE_STYLE) == 0) {
            sb.append(" inject=noStyle type=").append(type);
            return false;
        }
        if ((type & FILTER) != 0 && sWithFilter == 0) {
            sb.append(" inject=skipFilter type=").append(type);
            return false;
        }
        ArrayList<?> tc = (ArrayList<?>) XposedHelpers.getObjectField(tag, "mCandyParams");
        ArrayList<?> tl = (ArrayList<?>) XposedHelpers.getObjectField(tag, "mLutBitmaps");
        if (tc == null || tc.isEmpty()) {
            sb.append(" inject=emptyTagCandy");
            return false;
        }
        XposedHelpers.setObjectField(eff, "j", new ArrayList<Object>(tc));
        ArrayList<?> lut = (ArrayList<?>) XposedHelpers.getObjectField(eff, "h");
        if ((lut == null || lut.isEmpty()) && tl != null && !tl.isEmpty()) {
            XposedHelpers.setObjectField(eff, "h", new ArrayList<Object>(tl));
        }
        sb.append(" inject=").append(tc.size()).append(" type=").append(type);
        return true;
    }

    private static void onRenderRun(XC_MethodHook.MethodHookParam p) {
        int kind = XposedHelpers.getIntField(p.thisObject, "a");
        if (kind != 2) {
            return;
        }
        Object dObj = XposedHelpers.getObjectField(p.thisObject, "c");
        ArrayList candy = (ArrayList) XposedHelpers.getObjectField(dObj, "o");
        ArrayList<?> lut = (ArrayList<?>) XposedHelpers.getObjectField(dObj, "m");
        StringBuilder sb = new StringBuilder("render candy=");
        sb.append(sz(candy));
        sb.append(" lut=").append(sz(lut));
        try {
            Object bp = XposedHelpers.getObjectField(dObj, "a");
            if (bp != null) {
                sb.append(" name=").append(str(XposedHelpers.getObjectField(bp, "a")));
                sb.append(" cv=").append(XposedHelpers.getIntField(bp, "b"));
                sb.append(" filter=").append(XposedHelpers.getIntField(bp, "c"));
                sb.append(" tone=").append(XposedHelpers.getIntField(bp, "e"));
                sb.append(" vib=").append(XposedHelpers.getIntField(bp, "g"));
                sb.append(" port=").append(XposedHelpers.getIntField(bp, "i"));
                sb.append(" hdr=").append(XposedHelpers.getIntField(bp, "p"));
                sb.append(" tune=").append(XposedHelpers.getIntField(bp, "k"));
                sb.append(" temp=").append(XposedHelpers.getIntField(bp, "m"));
                sb.append(" sharp=").append(XposedHelpers.getIntField(bp, "o"));
            }
        } catch (Throwable t) {
            sb.append(" bpErr=").append(t);
        }
        if (candy != null && !candy.isEmpty()) {
            sb.append(" script=").append(brief(candy.get(candy.size() - 1)));
        }
        if (sDup == 1 && candy != null && candy.size() == 1 && hasStyle(candy)) {
            candy.add(candy.get(0));
            sb.append(" DUP -> ").append(candy.size());
        }
        log(sb.toString());
    }

    private static boolean hasStyle(ArrayList<?> candy) {
        if (candy == null || candy.isEmpty()) {
            return false;
        }
        for (int i = 0; i < candy.size(); i++) {
            Object o = candy.get(i);
            if (o instanceof String && ((String) o).indexOf(STYLE) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int sz(ArrayList<?> l) {
        return l == null ? -1 : l.size();
    }

    private static String str(Object o) {
        return o == null ? "null" : o.toString();
    }

    private static String brief(Object o) {
        if (o == null) {
            return "null";
        }
        String s = o.toString();
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }

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
                    if ("palette.force".equals(k)) {
                        sForce = Integer.parseInt(v);
                    } else if ("palette.inject".equals(k)) {
                        sInject = Integer.parseInt(v);
                    } else if ("palette.dup".equals(k)) {
                        sDup = Integer.parseInt(v);
                    } else if ("palette.probe".equals(k)) {
                        sProbe = Integer.parseInt(v);
                    } else if ("palette.withfilter".equals(k)) {
                        sWithFilter = Integer.parseInt(v);
                    }
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private static void log(String msg) {
        try {
            Log.i(TAG, HEAD + msg);
        } catch (Throwable ignore) {
        }
        try {
            synchronized (sLogLock) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (sWriter == null) {
                    File f = new File(
                            "/sdcard/Android/data/com.android.camera/files/camport",
                            "camport_fix.log");
                    File parent = f.getParentFile();
                    if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                        sWriter = new FileWriter(f, true);
                        sFlushAt = now;
                    }
                }
                if (now - sWinStart >= 1000) {
                    sWinStart = now;
                    sWinCount = 0;
                }
                if (sWinCount >= 300) {
                    return;
                }
                sWinCount++;
                if (sWriter == null) {
                    return;
                }
                sWriter.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                        java.util.Locale.US).format(new java.util.Date())
                        + " I/调色盘fix: " + msg + "\n");
                sPending++;
                sTotal++;
                if (sTotal <= 20 || sPending >= 50 || now - sFlushAt >= 500) {
                    sWriter.flush();
                    sFlushAt = now;
                    sPending = 0;
                }
            }
        } catch (Throwable ignore) {
        }
    }
}
