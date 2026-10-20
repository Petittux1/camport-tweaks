package com.pudding.legendjudge;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 传奇一瞬判定 hook（17Pro/pandora 适配版，兄弟"一瞬判定"APK 的同语义移植）。
 * 原独立模块 com.pudding.legendjudge 已并入 camport（mega8 编入 classes4.dex）。
 *
 * mediaeditor 里 rt.a.g() = (Build.DEVICE=="nezha" && ro.theme_customize=="LCC")：
 *   - method_is_legend_available = rt.a.g() && 国行  -> 相册一瞬标的点击门
 *   - CreateLegendV2TaskParams.deviceType = g()?1:0  -> 服务端积分判定输入
 *
 * hook 为 true：deviceType=1（LCC 定制设备身份）+ legend available。
 * 仅在 com.miui.mediaeditor 进程生效（包名守卫），camport scope 需包含该包。
 */
public final class JudgeHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.miui.mediaeditor".equals(lpparam.packageName)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                "rt.a", lpparam.classLoader, "g",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });
            XposedBridge.log("[LegendJudge] rt.a.g() hooked -> true ("
                + lpparam.processName + ")");
        } catch (Throwable t) {
            XposedBridge.log("[LegendJudge] hook failed: " + t);
        }
    }
}
