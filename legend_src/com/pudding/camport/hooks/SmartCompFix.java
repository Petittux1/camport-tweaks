package com.pudding.camport.hooks;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
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
 * ============================================================
 * 三、相机里其实有**两代**实现，靠 V3/U3 二选一（这才是分叉点）
 * ============================================================
 * CaptureModule.appendInterceptor：
 *     if (!V3) { if (U3) add(v2 拦截器 s6.s0); return; }
 *     add(v1 拦截器 s6.r0 = SmartCompositionSimpleASD);
 * CaptureModule.updateSmartComposition：
 *     if (!U3) { if (V3) { v1 分支 K0().q(16) / K0().p(286331137) } return; }
 *     v2 分支
 *
 *   v1 = ASD 代：数据源 xiaomi.ai.misd.SemanticScene，解析进 i5.g 的 CompositionList
 *   v2 = autoCrop 代：数据源 com.xiaomi.camera.autoCrop.autoCropData
 *
 * 本机（小米17 Pro）两个数据源 tag **都不存在**，但 v2 是「跑起来但 100% 拿不到数据」，
 * v1 是「一次都没被走到」。10-02 全天实测：
 *      updateSmartComposition v2  99 次，首条 13:52:11.516 = ai.smartcomp=3 生效那刻（之前 0 次）
 *      updateSmartComposition v1   0 次
 *      SmartCompositionData composition data: Exception!   696/696 (100%)
 *      Not Supported SmartComposition State                257/257 (100%)
 *      updateSmartCompositionFromASD / updateCurrentSmartCompositonIndex  0 次
 *      updateCompositionUI isValidData 0 次（false 696 次）
 *   → **抬 U3（level 1..4）等于主动把相机推进 v2 这条死路。**
 *
 * 系统侧证据（带对照实验，证明搜索方法可靠）：
 *      chi override 注册 31 个 com.xiaomi.* 节，没有 autoCrop
 *      autoCrop.autoCropData / autoCropState / autoCropVersion / autoCropSupportSize  字面量 0 命中
 *      supportedfeatures.asd.aiComposition                                         字面量 0 命中
 *      xiaomi.ai.misd.SemanticScene                  0 命中
 *      xiaomi.ai.misd.NonSemanticScene（同节、确实存在）  7 命中  ← 对照
 *
 * ============================================================
 * config: ai.smartcomp = 0|1|2|3|4|5|6|7|8
 * ============================================================
 *   0 = 关（默认，不干预）
 *   1 = 抬能力门    hook l9.h.U3  → true     ★ 这条会把相机推上 v2，见上
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
 *   5 = ★只抬 V3★   hook l9.h.V3 → true，**U3/t0/S0/R 一个都不碰**
 *                  → !U3 为真、V3 为真 → appendInterceptor 选 **v1**、
 *                    updateSmartComposition 走 **v1 分支**（两边一致，不会错配）
 *                  → 顺带挂 v1 通路的诊断探针：
 *                      F.d0 / r0.initAndGetPriorCondition / r0.getInTimeCondition
 *                      / r0.acceptResult（+ 计数）
 *                  → 注意 F.d0 里硬编码 i9 == 163，**v1 只在拍照模式能进**，
 *                    AI帮拍(168) 必须另开 F.d0 的门，本档不碰它。
 *                  → 本档**不**挡 submitAiComposition（v1 要靠它下发）。
 *   6 = ★5 + 补齐 v1 通路★   v1 卡在 getInTimeCondition，本档补这一块：
 *                  ① hook com.android.camera.data.data.F.w(int)（F.w 就是它）
 *                     163/168 且原值 false → 改判 true。
 *                     理由：F.w(i) = "ai".equals(w2.a.getComponentValue(i))，
 *                     而 w2.a.getKey() 返回 null → 取内存 map 的 null 键，
 *                     从没写过就恒是缺省 "off" → F.w 恒 false →
 *                     i5.g.M() 恒 false → r0.getInTimeCondition 恒 false →
 *                     r0.acceptResult 永远不跑（v1 有门无数据）。
 *                  ② ★绝不动 w2.a 的存值★ —— 10-03 10:57 踩过并已回退：
 *                     直接把 w2.a.getComponentValue 改成 "ai" 会让
 *                     w2.a.m()=getComponentDataItemBitmap 走进 AI 分支去遍历
 *                     f18198a，而该 AI 图案列表未加载（null）→ NPE →
 *                     FragmentSmartComposition.initView 崩 → 相机每开必闪退。
 *                     F.w 是纯读侧布尔判断，改它不触发任何 UI 取图。
 *                  ③ 探针：F.w 原值 / w2.a 构图项原值(只读) / i5.g.M()，
 *                     配合档 5 的 r0 三探针，出问题时能一眼分辨
 *                     是「构图项没选」还是「L0.nn() 面板开着」还是「数据没回来」。
 *   7 = ★6 + 抬齐 v1 剩下 3 道门 + 合成数据后门★
 *                  v1 通路 5 道门，档5/6 只解决了 2 道，这档补齐：
 *                  ① hook l9.g.S0 → 对名字含 SemanticScene 的返回 true。
 *                     ★ 这是最关键的一道，档5/6 漏了 ★
 *                     prepare() 里 z9 = name.startsWith("android") || M4(caps,name)，
 *                     而 M4(g,s) = g.S0(s)。本机没注册 xiaomi.ai.misd.SemanticScene
 *                     → z9=false → supportPrior=false → prepare() 返回 false
 *                     → 拦截器根本不注册 → acceptResult 永远不会跑
 *                     （档6 的 accIn 探针一条都没出，根子就在这）。
 *                  ② hook F.d0(mode, caps) → 163/168 直接 true。
 *                     原式 = !g.N() && mode==163 && caps!=null && V3(caps)；
 *                     抬了 V3 之后 g.N() 仍可能挡路，这里按模式兜死。
 *                  ③ hook i5.g.M() → 原 false 就改 true（nn() 兜底）。
 *                     M() = F.w && !L0.nn()，是 getInTimeCondition 的后半段。
 *                  ④ 合成总闸 r0.f17135a（dex 真名 a）→ 在
 *                     interceptor.base.i.onCaptureResultNext 的 before 里对
 *                     s6.r0 直接 setBooleanField("a", true)。
 *                     ★ 不走 Kr.g.c ★ 原计划按调用者放行，但实测栈上只有
 *                     Xposed 混淆帧（A.fIUydx...XC_MethodHook）和相机自己的
 *                     AOP 责任链（s.intercept / l.proceed），拿不到业务类。
 *                     ★ 也**绝不**全局 setprop ★ 那会连带打开 fragment 的
 *                     debug TextView → findViewById 后直接 setVisibility →
 *                     layout 没那控件就 NPE 闪退（10-03 已踩过）。
 *                     fragment 那处仅在 ai.smartcomp.dbgfrag=1 时放行。
 *                  ⑤ hook Kr.g.a("debug_composition_index") → 返回
 *                     ai.smartcomp.dbgindex。r0.acceptResult 拿不到真实 ASD 数据
 *                     （HAL 不出这个 tag，永远拿不到）时会用它合成
 *                     r.a{a=11, b=index}：b>0 进 CompositionList → i5.g.v()
 *                     → 写 w2.a.b + 推 UI；b<=0 进 DetectTipList → 提示条。
 *
 *                  ⑥ 构图框可见化（本档前 5 道门都过了仍看不到框的原因）：
 *                     显示链路 fragment/smartComposition/v1/a.is() 要连过三关，
 *                       ① F.d0(mode,caps)      → 档7 ② 已抬
 *                       ② F.Q(mode) = w2.a.isSwitchOn = !"off".equals(构图项)
 *                          构图项默认 off → 恒 false → 直接 setVisibility(8)
 *                       ③ Xo() = w2.a.m(mode) 的位图非空
 *                          m() 第一行 off 就 return null
 *                     解法：
 *                       · hook C_BASE.getComponentValue after，档7 且
 *                         ai.smartcomp.compose=1 时把 off 改判成 "ai"（读侧改判，
 *                         不写存值，退出不留垃圾）→ ②③同时放行。
 *                       · hook w2.a.m(int)：列表空先调 i5.g.Pn() 懒加载
 *                         （d.a() 解析 assets/smart_composition/v1.0.0/
 *                         smart_composition_config.json，本机确认存在，含 1..60.webp，
 *                         我们合成的 index=1 必能命中 id="1"），加载完仍 null 就
 *                         setResult(null) 拦下 NPE。
 *                       · w2.a 的 n()/o()/getComponentDataItem()/p() 本就有 null
 *                         守卫，只有 m() 没有 —— 坑就在这一处。
 *                  ⑦ 探针 w2.a.r(list,list)：图案列表被填充的唯一入口（调用方只有
 *                     i5.g.Pn()）。它出现 = 官方时机已自动加载；不出现则⑥的懒加载
 *                     是主路径。
 *
 *                  ★ 边界要说清楚 ★
 *                  这台机器的 xiaomi.ai.misd.SemanticScene / autoCrop.* 全都没有，
 *                  所以「按真实场景智能选构图」做不到 —— 档7 是**合成固定数据**，
 *                  用来打通并验证整条 v1 UI 链路（开关 → 拦截器 → CompositionList
 *                  → 构图框）。索引本身不随场景变。
 *   8 = ★v2 引导环档（用户描述的原机形态）★
 *                  档5..7 走的是 v1 = PIP 样张参考图小窗（fragment id 3813），
 *                  那是「样张模板」不是用户要的「取景辅助环」。
 *                  用户要的原机形态是 v2 = 预览上的构图引导环 + 自动连续变焦
 *                  （fragment id 3815 = FragmentSmartCompositon +
 *                   SmartCompositionGuideView 的渐变取景环）。
 *                  两代在 C1329d.h() 里是 **else if 互斥** 的：
 *                      if (F.d0(168, caps) && H.R()) n(21, 3813);   // v1 PIP
 *                      else if (x.z0())                n(20, 3815);   // v2 引导环
 *                  原生本机：F.d0(168) 恒 false（式里硬编码 i9==163）、
 *                  x.z0()=H.f17374a 恒 false（U3 第一道门就死）→ **两代都不注册**。
 *                  所以原机没有框；档7 靠强抬 F.d0 把 3813 硬塞出来 —— 那正是
 *                  用户说的「其实不应该有这个框」。
 *                  档8 走**完全相反**的路：一个都不强抬，改成把 U3 抬开，
 *                  让 H.f17374a 走原生 reInit 通路变 true：
 *                    · F.d0(168) 保持原生 false → 3813 **不注册**（PIP 框消失）
 *                    · x.z0()=true → 3815 **注册**（引导环进 fragment 列表）
 *                    · !V3 且 U3 → appendInterceptor 选 **v2 拦截器 s6.s0**
 *                      （V3 原生 false，本档刻意**不**抬它，否则会退回 v1）
 *                    · U3 → updateSmartComposition 走 **v2 分支**并下发会话参数
 *                  门（1..4 档的四道 + 比例门）：
 *                    ① l9.h.U3 → true      能力门 autoCropVersion==2
 *                    ② l9.h.t0 → ∪ 常见比例 H.m() 里的 ratio 门
 *                    ③ l9.g.S0 → autoCropEnable 放行（HAL 不认，无害）
 *                    ④ u2.H.R 之后强制 f17374a=true（绕过 intent/facing）
 *                  ★ 本档不挡 A3.g.w()（submitAiComposition），与档5..7 同；
 *                    也不抬 V3、不动 F.d0、不动 w2.a 构图项、不碰 debug_composition_*。
 *
 *                  ★ 数据从哪来 ★
 *                  v2 的 tag 是 com.xiaomi.camera.autoCrop.autoCropData = float[6]，
 *                  本机 HAL **没注册这个 tag**（VendorTagDescriptor lookupTag 直接
 *                  does not exist，全盘搜不到 autoCrop）→
 *                  s6.s0.tagValueAutomaticParsed 每帧把它清成 null →
 *                  f17140c 恒 null → consumeResultOnMainThreadIfDataChanged 每帧打
 *                  "composition data: Exception!"（10-02 实测 696/696 = 100%）。
 *                  端侧模型也不存在：系统 AI 服务里 autoCrop 任务注册是 false，
 *                  /vendor /system /system_ext /product 全盘找 auto_crop 模型文件为 0。
 *                  ⇒ **原机那套「场景识别 → 推荐取景位」在这台机器上补不回来**。
 *
 *                  但画面里可用的**真实**信号有两个，够把框和变焦做活：
 *                    · android.statistics.faceRectangles / faceScores（标准 tag，
 *                      本机 FaceColor 算法在跑 → 人脸检测一定开着）
 *                    · android.control.afRegions（触摸对焦区）
 *                  所以本档的做法：挂 s6.s0.parseComplexValueManually(CaptureResult)
 *                  的 after，每帧
 *                    ① 用 SCALER_CROP_REGION 把人脸/对焦区从传感器坐标换算到 preview
 *                       像素坐标（和官方 Matrix 那套坐标系一致）
 *                    ② 外扩 ring.pad 倍 → EMA 平滑（ring.smooth）→ 写 float[6]
 *                       [0]=x [1]=y [2]=w [3]=h（w/h 是**偏移量**不是右下角）
 *                    ③ 按「主体应占画面宽度 ring.fill」反算目标倍率，写 float[4]
 *                    ④ 按 ring.zoomms 节流、每步 ≤0.03 倍地调
 *                       R6.C0.b().W4(ratio, 22) —— 官方 startZoomRatioAnimator
 *                       用的就是这条 onDualZoomValueChanged 通路
 *                  人动 → 框跟着动；人远 → 自动拉近；用户手动动变焦 → 冷却 1.5s 不抢。
 *                  取不到主体（人离开/没脸/没对焦点）时沿用上一帧 800ms，之后回落到
 *                  ring.x/y/w/h 的固定兜底矩形。
 *
 * config: ai.smartcomp.ring = 0|1      （只有 ai.smartcomp = 8 时才读）
 * ============================================================
 *   1 = 默认。合成 float[6] 喂给 v2 引导环。
 *   0 = 不合成（只抬四道门），用来对照「光抬门不喂数据」是什么样。
 *
 * config: ai.smartcomp.ring.x/.y/.w/.h  （只有 ai.smartcomp = 8 时才读）
 * ============================================================
 *   合成的取景区域，占 previewSize 的比例（0..1），默认 x=0.30 y=0.32
 *   w=0.40 h=0.36。经 consumeResult 的 Matrix 映射到 displayRect 上画环。
 *   w/h 是**偏移量**不是右下角坐标（源码 new RectF(x, y, x+w, y+h)）。
 *
 *   ★ 兜底矩形必须**居中**：FragmentSmartCompositon 的 mTargetAreaRect 恒等于
 *     displayRect 正中心（J.a() 里 center ± dimen/2），而 mFocusAreaRect 就是我们
 *     合成的这个框。两者不重合 → 引导环画箭头 + 方向提示。实测
 *     preview 中心 (0.5,0.5) 才会映射到 displayRect 中心，所以
 *         x = 0.5 - w/2 , y = 0.5 - h/2
 *     旧默认 x=0.40 y=0.30 → 中心在 preview(0.60,0.48) → 映射到屏幕中心下方约
 *     162px → 没主体时**永远提示「向下移动」**（用户反馈的问题）。
 *
 * config: ai.smartcomp.ring.zoom = <float>   （只有 ai.smartcomp = 8 时才读）
 * ============================================================
 *   float[4] = 自动变焦的目标倍率，默认 1.0（不动变焦）。
 *   只在 tipsType==2 时被写进 y.a（真正的连续变焦），其余情况作为
 *   targetZoomRatio 传给引导环做显示。想看自动变焦效果就调大。
 *
 * config: ai.smartcomp.ring.tip = <int>      （只有 ai.smartcomp = 8 时才读）
 * ============================================================
 *   float[5] = tipsType（**不是** CompositionDataType 的枚举序号，默认 0）。
 *     0/1 = TRACKING/MOTION_INVALID —— 正常跟随，环显示（**默认，先试这个**）
 *     2   = BEST_COMPOSITION        —— 直接判「已是最优构图」，且只有它会把
 *                                      ring.zoom 真正喂进连续变焦
 *     3   = NOT_DETECTION_DATA      —— 判「未识别到主体」，走提示条不出环
 *   ⚠ 两套编号天生错位，别拿枚举序号来对：h5/z.java 的枚举是
 *     0=TRACKING 1=INVALID 2=NOT_DETECTION 3=BEST_COMPOSITION 4=BEST_COMP_MOTION
 *     5=MOTION_INVALID，而 dk() 的分派是 `i9==2 → 枚举 BEST_COMPOSITION(3)`、
 *     `i9==3 → 枚举 NOT_DETECTION(2)`；连续变焦那道门在 s6/s0.java:104-108
 *     读的也是 tipsType：`if ((int) tipsType == 2) yVar.a = targetZoomRatio`。
 *     所以**配置里写 2 = 最优构图 / 能自动变焦**，3 = 未识别。
 *   分类逻辑在 h5/z.java dk()（jadx 显示为 FragmentSmartCompositon）里。
 *
 * config: ai.smartcomp.ring.src = 0|1|2|3     （只有 ai.smartcomp = 8 时才读）
 * ============================================================
 *   0 = 默认。主体优先级：人脸 → 触摸对焦区 →（800ms 内沿用上一帧）→ 固定兜底矩形。
 *   1 = 只认人脸（拍人时最准；没脸就直接落回固定矩形）。
 *   2 = 只认触摸对焦区（点哪儿框去哪儿；没点过就落回固定矩形）。
 *   3 = 固定矩形（= ring.x/y/w/h，等于关掉场景驱动，回到最初那版）。
 *
 * config: ai.smartcomp.ring.pad = <float>     （默认 1.30，1.0..3.0）
 * ============================================================
 *   主体框外扩倍数。1.30 = 框比人脸/对焦区大 30%，环套在人外面而不是贴着脸。
 *
 * config: ai.smartcomp.ring.fill = <float>    （默认 0.45，0.10..0.95）
 * ============================================================
 *   目标构图：主体应占**画面宽度**的比例。自动变焦按
 *       目标倍率 = 当前倍率 × fill ÷ 主体当前占宽比
 *   反算，再被 zmax 和「一次最多往目标靠 0.5x..2x」双重夹住。
 *   想拍特写就调大（0.6），想拍全景就调小（0.3）。
 *
 * config: ai.smartcomp.ring.autozoom = 0|1    （默认 1）
 * ============================================================
 *   1 = 算目标倍率并连续推进变焦（真正的自动放大/缩小）。
 *   0 = 只出框不动变焦（ring.zoom 在这种情况下作为固定 targetZoomRatio 用）。
 *       变焦推进本身**只在拿到主体时**发生；人走开不会被强行拉回 1x。
 *
 * config: ai.smartcomp.ring.zmax = <float>    （默认 6.0，1.0..10.0）
 *   目标倍率上限，防止 fill 算出个离谱的倍率把镜头拉到底。
 *
 * config: ai.smartcomp.ring.smooth = <float>  （默认 0.30，0.02..1.0）
 *   取景框的 EMA 平滑系数。越小越稳（0.05 几乎不动但跟手慢），
 *   1 = 完全不平滑（每帧跳，会闪）。人脸检测本身有抖，别关。
 *
 * config: ai.smartcomp.ring.zoomms = <int>    （默认 60，30..500）
 *   变焦指令最小间隔（ms）。官方 startZoomRatioAnimator 是按动画帧发的，
 *   60ms 已经比它温和。
 *
 * config: ai.smartcomp.ring.exp = 0|1|2      （默认 2）
 * ============================================================
 *   ★ 正负号开关，搞错了会一路拉到 zmax ★
 *   目标倍率 = 当前倍率 × fill ÷ 主体**屏幕上**的占宽比，
 *   而 屏幕占宽比 = 报告占宽比 × curZoom^exp。
 *   exp 怎么定的：
 *     · 本机 SCALER_CROP_REGION 恒为全幅 [0,0,4096,3072] 不随变焦缩；
 *     · 实测 STATISTICS_FACES 的宽 ∝ 1/curZoom
 *       （1229×1.0 ≈ 923×1.38 ≈ 768×1.6 ≈ 400×3.2 ≈ 1250）；
 *     · 而屏幕占比必须 ∝ curZoom（视场收窄），两者一乘正好是 cur²。
 *   10-03 23:25 / 23:43 同一套画面 A/B 实测（同一个人、同一个 rect 起点）：
 *     exp=0 → want ∝ cur² 正反馈：cur 1.0→1.6→3.2，want 1.5→3.2→6.0，撞 zmax；
 *     exp=2 → want 收敛在 ~1.5 不动，镜头停在那儿。
 *     2 = 乘 cur²（本机 pandora，**默认**）
 *     1 = 只乘一次 cur（bounds 已经按窗口缩放回全幅的机器）
 *     0 = 不补（crop region 本身会随变焦缩的机器）
 *
 * config: ai.smartcomp.ring.probe = 0|1     （默认 0）
 * ============================================================
 *   1 = 进相机后第一帧，把 CaptureResult 里所有非空字段 dump 一整段进
 *       camport_fix.log（每个进程只打一次）。摸清本机还有哪些可用信号
 *       （场景标签 / 深度 / 运动速度 / AE 区 / 人脸数 / ...），
 *       再决定 ringSubject 要不要补别的信号。看完记得改回 0。
 *
 * config: ai.smartcomp.ring.iou = 0.0~0.9   （默认 0.35）
 * ============================================================
 *   时域门：人脸检测是逐帧独立跑的，单帧乱跳会直接把 EMA 带歪（框在那儿抽）。
 *   新检测框和「上一帧框按这一帧应有的变焦缩放挪过来」的 IoU 低于它就先不信，
 *   连续 5 帧都不信才承认主体真的变了（换人 / 走动）。
 *   调大 = 更稳但跟得慢；调小 = 跟得快但会抖。0 = 关门。
 *
 * config: ai.smartcomp.ring.wband = 0.0~0.6   （默认 0.10）
 * ============================================================
 *   **目标倍率的迟滞带**。raw want（当前倍率 × fill/frac）逐帧会跳
 *   （人脸框宽一变，frac 就变），没带子时 EMA 会一路跟着走 → 镜头
 *   1.0→1.2→1.4 来回抽（10-04 用户反馈「变焦老在跳，不稳定」）。
 *   只有 |raw - 当前目标| > wband 时才允许移动目标，否则原地不动。
 *   0 = 关（退回纯 EMA）；越大越稳但响应越钝。
 *
 * config: ai.smartcomp.ring.dead = 0.0~0.3    （默认 0.06）
 * ============================================================
 *   **执行器死区**：|want - curZoom| 没超过 dead 就一条 W4 都不发。
 *   原来死区 0.012 比单步 0.03 还小 → 永远在 ±0.03 地微调，
 *   观感就是「一直在放大、一直在微抖」。0 = 关（退回 0.012）。
 *
 * config: ai.smartcomp.ring.lock = 0|1      （默认 1）
 * ============================================================
 *   **主体锁定**。人脸检测每帧独立跑，多张脸时原来每帧按「分数最高」挑，
 *   分数一翻转 bestIdx 就从画面这头跳到那头 → 中值/EMA/IoU 门再好也挡不住
 *   「整框换地方」（10-04 14:50 实测 subj 每秒在 [1502,1085] 和 [1585,2285]
 *   两块之间来回，wantZoom 跟着 1.52 ↔ 1.13 来回拽）。
 *   锁定后：有上一帧主体时改挑「和它重合/离它最近的那张」，人没动就永远不换脸；
 *   只有它 800ms 没再出现才放开重找。0 = 关（回到每帧挑最高分）。
 *
 * config: ai.smartcomp.ring.walpha = 0.02~1.0（默认 0.08）
 * ============================================================
 *   **目标倍率的 EMA 系数**。原来硬编码 0.30 → 约 3 帧就贴上 raw，等于没平滑，
 *   raw 一抖镜头就抽。0.08 ≈ 0.4s 时间常数，画面上是「缓慢移过去」。
 *
 * config: ai.smartcomp.ring.wrate = 0.0~5.0 （默认 0.5）
 * ============================================================
 *   **目标倍率的最大变化速率**，log 空间 / 秒（0.5 ≈ 每秒最多 ×1.65 或 ÷1.65）。
 *   迟滞带只挡得住小幅抖动，挡不住 raw 一路**单向大跳**（实测 want 1.52→1.13）。
 *   有速率上限后，再大的跳变也得花够时间走完 → 观感是「缓慢回位」而不是「跳一下」。
 *   0 = 关。
 *
 * config: ai.smartcomp.dbgindex = <int>   （只有 ai.smartcomp = 7 时才读）
 * ============================================================
 *   合成的构图索引，默认 1。b>0 走 CompositionList（构图框），
 *   0/-1/-2/-3 走 DetectTipList（对应 2132021983/2132021984/2132021982/… 提示条）。
 *   想看提示条就改成 0 或负数。
 *
 * config: ai.smartcomp.dbgfrag = 0|1       （只有 ai.smartcomp = 7 时才读）
 * ============================================================
 *   0 = 不开 fragment/smartComposition/v1/a 的 debug TextView（默认，安全）。
 *   1 = 开。会把 CompositionList / DetectTipList 打成屏幕上的红字，方便肉眼验证；
 *       但它的 initView 是 findViewById 后直接 setVisibility，layout 里若没有
 *       那两个 TextView 就 NPE → FragmentSmartComposition.initView 崩 → 每开必闪退。
 *       先 0 验证链路，确认不崩再试 1。
 *
 * config: ai.smartcomp.compose = 0|1      （只有 ai.smartcomp = 7 时才读）
 * ============================================================
 *   1 = 默认。把构图项 w2.a 的读值 off 改判成 "ai"，让 F.Q(isSwitchOn) 与
 *       Xo()(m() 位图) 两关放行 —— 没有它，前面 7 道门全过也看不到构图框。
 *       只改读侧不写存值，退出相机不会留下没人写过的 "ai"。
 *   0 = 关掉改判，回到「数据在跑但画面上无框」的状态（排查用）。
 *
 * config: ai.smartcomp.cycle = 0|1        （只有 ai.smartcomp = 7 时才读）
 * ============================================================
 *   0 = 默认。只有用户点构图框上的刷新按钮才换图（在 AI 模板 id 1..48 里轮转）。
 *   1 = 每次 r0.acceptResult 合成数据都自动往后推一格，画面会自己变。
 *       由于本机拿不到真实 ASD（全是 {a=0,b=0}），这个自动轮转是"假反应"，
 *       仅在想看效果时开。
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
 *        .V3(l9.g) -> boolean     com.xiaomi.camera.supportedfeatures.asd.aiComposition 门
 *                                 ★ 本机 0 命中 → 恒 false → 这是 v1/v2 的分叉点
 *        .t0(l9.g) -> List        SupportSmartCompositionSize 门（唯一调用方
 *                                 u2 之外只有 r6.W.ka()，注解 key="isSupportSmartCompositon"）
 *   jadx p081l9.C1412g  ==  dex Ll9/g;    （renamed from: l9.g）
 *        .S0(String) -> boolean   "这个 vendor tag 在 HAL 自报表里吗"
 *   jadx p168u2.H       ==  dex Lu2/H;    （未改名）
 *        .R(com.android.camera.data.data.C)  ComponentGlobalSmartComposition reInit
 *        字段 f17374a  dex 真名 = a（renamed from: a, collision with root package name）
 *   jadx p154s6.r0      ==  dex Ls6/r0;   ★ jadx 给整包加了 p154 前缀，dex 里搜不到！
 *        SmartCompositionSimpleASD（v1 拦截器），声明 tag = xiaomi.ai.misd.SemanticScene
 *        .initAndGetPriorCondition() -> boolean   = F.d0(moduleIndex, caps)
 *        .getInTimeCondition()      -> boolean   = i5.g.M()
 *        .acceptResult()            void         ← 数据真回来了才会进
 *   jadx p154s6.s0      ==  dex Ls6/s0;   v2 拦截器，声明 tag = autoCrop.autoCropData
 *   jadx p073i5.g       ==  dex Li5/g;    SmartCompositionManager（CompositionList 的家）
 *   jadx com.android.camera.data.data.F  ==  dex 同名（未混淆）
 *        .d0(int, l9.g) -> boolean  = !N() && i9==163 && caps!=null && l9.h.V3(caps)
 *                                    ★ i9==163 硬编码：v1 只进得了拍照模式
 *   com.android.camera.features.mode.capture.CaptureModule  ==  dex 同名
 *        .updateSmartComposition() void  ← if(!U3){if(V3) v1分支; return;} else v2分支
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
    // 注：dex 真签名 R(Ljava/lang/Object;)。jadx 把它收窄成了 data.data.C ——
    //     过去用 P_DATAC = "com.android.camera.data.data.C" 去挂必然 NoSuchMethodError。

    /**
     * dex Lcom/android/camera/data/data/c; —— jadx 叫 AbstractC0716c，组件基类。
     * getComponentValue(int) 在这里声明（子类大多覆写，w2.a 没覆写）。
     * 已用 dexfind 核对：supprotedItemsSize / resetComponentValue / getComponentValue 都指向它。
     */
    private static final String C_BASE = "com.android.camera.data.data.c";
    /** dex Lw2/a; —— jadx p186w2.a，构图项选择器（值 = off | ai | 创意图案 id） */
    private static final String C_SEL = "w2.a";
    /** dex Li5/g; —— jadx p073i5.g，SmartCompositionManager（M() 就是 getInTimeCondition 的前半段） */
    private static final String C_MGR = "i5.g";
    /** 构图项选中 AI 的字面值（F.w 的判据） */
    private static final String V_AI = "ai";
    /** v1 拦截器只在拍照(163)注册；开关组件 163/168 都支持 */
    private static final int MODE_PHOTO = 163;
    private static final int MODE_AI_HELP = 168;

    private static final String TAG_ENABLE = "com.xiaomi.camera.autoCrop.autoCropEnable";

    /**
     * dex LA3/g; —— AiFeatureSubmitHelper（submitAiComposition / submitAiPose / submitAiTuning）。
     * 注意不是 La3/g;：dex 里 A3 和 a3 两个包并存，jadx 因为输出文件系统不分大小写，
     * 把 a3 改名成了 p004a3（DualVideoRecorderProtocol）而保留了 A3。
     * 已用 dex method_ids 直读核对：LA3/g; 有 w/x/y，La3/g; 只有 Ak/registerProtocol。
     */
    private static final String C_SUBMIT = "A3.g";
    /** v2 拦截器（jadx p154s6.s0，dex 真名 s6.s0）—— 档8 的合成数据挂在这上面 */
    private static final String C_S0_V2 = "s6.s0";
    private static final String C_AI_FRAG = "com.android.camera.features.mode.ai.FragmentAi";
    /** s6.s0 的 v2 数据源 float[6]（dex 真名 c；jadx 改叫 f17140c） */
    private static final String F_V2_DATA = "c";
    /** s6.s0 持有的 h5.J manager（dex 真名 a；jadx 改叫 f17139a） */
    private static final String F_V2_MGR = "a";
    /** h5.J.previewSize（dex 真名 e；jadx 改叫 f12761e） */
    private static final String F_J_SIZE = "e";
    private static final String F_J_SIZE_JADX = "f12761e";
    /** R6.C0 = ZoomProtocol 接口；C0.b() 取实现，W4(float,int)=onDualZoomValueChanged */
    private static final String C_ZOOM = "R6.C0";

    /** t0 缺省只给 ["4x3"]，这里补齐常见比例，让 contains() 能过 */
    private static final String[] RATIOS = {
            "4x3", "3x2", "16x9", "9x16", "1x1", "full", "18x9", "20x9", "21x9"};

    private static ClassLoader sCl;
    private static boolean sInstalled;
    private static boolean sSubmitLogDone;

    // r0.acceptResult 内部探针的上下文：
    //   sInAcc     —— 只在 r0.acceptResult 方法体执行期间为 true，用来把
    //                 ja.r.b 的众多调用方区分开，只打印「r0 真正用的那份」
    //   sLastData  —— 上次解析出来的 {a,b} 串，只在**变化**时才打，避免刷屏
    //   sLastOut   —— 上次出口的 CompositionList / DetectTipList，同理
    private static volatile boolean sInAcc;
    private static String sLastData;
    private static String sLastOut;
    // 拦截器对比探针：i.onCaptureResultNext 的签名串，只在变化时打
    private static String sLastItr;

    // ---- 用户在相机里那个「智能构图」开关（u2.H / pref_smart_composition_key_<mode>）----
    //   -1 = 还没读到（此时按「开」处理，保持原有行为）
    //    0 = 用户关了 → 所有门一律不抬、不合成、不驱动变焦 = 零干预
    //    1 = 用户开了 → 照常抬门 + 合成
    // 没有这个开关的话，U3/f17374a 被我们无条件强抬 → appendInterceptor 永远选中
    // v2、3815 永远注册、float[6] 每帧照喂 → 用户在设置里点了关也停不下来。
    private static volatile int sUserOn = -1;
    private static volatile String sUserOnWhy;

    // ---- config ----
    private static volatile boolean sCfgDone;
    private static volatile int sLevel;      // 0..8
    // ---- ai.smartcomp = 8（v2 引导环）合成参数 ----
    // 合成的 float[6] 里 [0..3] 是取景矩形（previewSize 的比例），[4] 目标倍率，
    // [5] CompositionDataType。见文件头「config: ai.smartcomp.ring.*」。
    private static volatile int sRing = 1;          // 0=不合成  1=合成（默认）
    private static volatile float sRingX = 0.30f;
    private static volatile float sRingY = 0.32f;
    private static volatile float sRingW = 0.40f;
    private static volatile float sRingH = 0.36f;
    private static volatile float sRingZoom = 1.0f;
    private static volatile int sRingTip = 0;
    // ---- 档8：场景驱动（从 CaptureResult 的人脸/对焦区算，不再用固定矩形）----
    // ring.src  0=人脸→对焦区→沿用上一帧（默认） 1=只用人脸 2=只用对焦区 3=固定矩形(旧行为)
    // ★ 默认 1 = 只认人脸 ★
    //   0 的兜底是「自动对焦区」，而相机对空墙也永远有中心 AF 区
    //   （实测正好是画面中心 2048,1536，面积仅 8% 不触发 >55% 过滤），
    //   结果就是「没主体也永远有主体、引导一直在动」。
    //   改成只认脸后，画面里没人 = sub=null = 完全静止（对齐 18 Pro Max）。
    private static volatile int sRingSrc = 1;
    // ring.idle 0=没识别到主体就**什么都不喂**（对齐 18 Pro Max：引导框随识别出现/消失）
    //           1=老行为，没主体也硬塞一个固定兜底框（导致「一直在动」「每两秒都在识别」）
    //           注：ring.src=3（只用固定矩形）时不受此开关影响，必须照常合成
    private static volatile int sRingIdle = 0;
    // ring.pad  主体框外扩倍数（1.30 = 框比人脸大 30%）
    private static volatile float sRingPad = 1.30f;
    // ring.fill 目标主体占画面宽度比，自动变焦按它反算目标倍率
    private static volatile float sRingFill = 0.45f;
    // ring.autozoom 1=算出目标倍率并连续推进变焦 0=只出框不动变焦
    private static volatile int sRingAutoZoom = 1;
    // ring.zmax 目标倍率上限
    private static volatile float sRingZMax = 6.0f;
    // ring.smooth 框的 EMA 平滑系数（0.02 最稳 / 1 不平滑）
    private static volatile float sRingSmooth = 0.30f;
    // ring.zoomms 变焦指令最小间隔（ms）
    private static volatile long sRingZoomMs = 60;
    // ring.exp  屏幕真实占宽比 = 报告占宽比 × curZoom^exp
    //   2=默认。本机 SCALER_CROP_REGION 恒为全幅不随变焦缩，实测
    //      STATISTICS_FACES 的宽 ∝ 1/curZoom（1229×1.0 ≈ 923×1.38 ≈ 768×1.6 ≈ 400×3.2
    //      ≈ 1250），而屏幕占比必须 ∝ curZoom（视场收窄）⇒ 补 cur²。
    //      同一套画面 A/B 实测：不补（=0）want 随 cur² 正反馈拉到 zmax（1.0→3.2，
    //      want 1.5→6.0）；补上后 want 收敛在 ~1.5 不动。
    //   1=只补一次（bounds 已经是「按窗口缩放回全幅」的机器用这个）
    //   0=不补（crop region 本身会随变焦缩的机器用这个）
    private static volatile int sRingExp = 2;
    // ring.probe 1=把 CaptureResult 里所有非空字段 dump 一次到日志（找可用的识别信号用）
    private static volatile int sRingProbe;
    // ring.iou 时域门：新检测框和「上一帧框按预期变焦缩放后」的 IoU 低于它就先不信，
    //           连续 5 帧都这样才认定主体真的变了。防人脸检测逐帧乱跳把框带歪。
    private static volatile float sRingIou = 0.35f;
    // ring.med 原始检测框的中值滤波窗口（帧）。EMA 只能「慢一点地跟着跳」，
    //   单帧冒出一个 2 倍宽的框照样会把它拽走；中值窗能把孤立尖峰整个剔掉
    //   （人脸框宽 10-03 23:45 实测 434→865→1399 单帧跳）。1=关。
    private static volatile int sRingMed = 5;
    /** 中值滤波的环形缓冲：最近 sRingMed 帧的原始框 x/y/w/h */
    private static float[][] sMedBuf;
    private static int sMedI;
    private static int sMedC;
    private static boolean sProbed;
    /** 心跳首帧的 arg0 类型只打一次（确认 l9.p0.a 到底传没传 CaptureResult） */
    private static boolean sHbArgSeen;
    /** 上一帧的**原始**检测框（未 EMA、未 clamp），用来做时域门 */
    private static float[] sPrevRaw;
    private static float sPrevRawCur;
    private static int sGateMiss;
    /** 平滑后的框（preview px：x/y/w/h），无主体时沿用它 */
    private static float[] sRingRect;
    /** 最近一次拿到主体的时刻（ms, elapsedRealtime） */
    private static long sRingSeen;
    /** 我们最后一次下发的目标倍率 —— 用来识别「用户手动动过变焦」 */
    private static float sRingZoomCmd;
    private static long sRingZoomAt;
    /** 用户手动变焦后的冷却期，这期间不抢 */
    private static long sRingZoomPause;
    /** 目标倍率的 EMA（raw want → 平滑 → 才交给执行器） */
    private static float sRingWant;
    // ring.wband 目标倍率的**迟滞带**：|raw - sRingWant| 没超过它就完全不动目标。
    //   没有带子时 raw 逐帧在 1.15 / 1.72 / 1.34 之间抖（人脸框宽逐帧变），
    //   EMA 会一路跟着走 → 镜头就在 1.0↔1.2↔1.4 来回抽（用户 10-04 反馈）。0=关。
    private static volatile float sRingWantBand = 0.10f;
    // ring.dead 执行器死区：|want - cur| 小于它就不下发（≈ 2 个 step）。
    //   原来是 0.012，比单步 0.03 还小 → 永远在 ±0.03 地微调，看着就是「一直在放大」。
    private static volatile float sRingDead = 0.06f;
    // ring.walpha 目标倍率 EMA 系数（0.02 最慢 / 1 立即跟随）。
    //   原来硬编码 0.30 → 约 3 帧就贴上 raw，等于没平滑；raw 一抖镜头就抽。
    //   0.08 ≈ 0.4s 时间常数，画面是「缓慢移过去」。
    private static volatile float sRingWantAlpha = 0.08f;
    // ring.wrate 目标倍率的**最大变化速率**，log 空间 / 秒（0.5 ≈ 每秒最多 ×1.65）。
    //   迟滞带只能挡住小幅抖动，挡不住 raw 从 1.5 一路掉到 1.1 这种**单向大跳**；
    //   有了速率上限，再大的跳变也得花够时间走完 → 观感是「缓慢回位」而不是「跳一下」。
    //   0 = 关。
    private static volatile float sRingWantRate = 0.5f;
    /** 上一次推进目标倍率的时刻（ms），按真实 dt 限速 */
    private static long sRingWantAt;
    // ring.lock 1=主体锁定（默认）。人脸检测每帧独立跑，多张脸时按「分数最高」逐帧挑，
    //   分数一翻转整框就跳到画面另一头（10-04 14:50 实测 subj 每秒在上下两块之间来回）。
    //   锁定后优先挑和上一帧重合/最近的那张脸，只有它连续 N 帧消失才换人。0=每帧挑最高分。
    private static volatile int sRingLock = 1;
    /** 主体锁定：上一帧被选中的脸在**传感器坐标**下的框（用来做连续性） */
    private static float[] sLockRect;
    private static long sLockAt;
    private static volatile int sSubmit;     // 0=挡 submitAiComposition  1=放行
    // ai.smartcomp.dbgindex = <int>：档7 合成数据用的构图索引。
    //   r0.acceptResult 里合成 r.a{a=11, b=index}：
    //     b > 0  → CompositionList → i5.g.v() → w2.a.b = index（构图框）
    //     b <= 0 → DetectTipList   → i5.g.q() → 提示条（-3/-2/-1/0 各对应一条文案）
    //   默认 1（最小的正数，走 CompositionList 分支）。
    private static volatile int sDbgIndex = 1;
    // ai.smartcomp.dbgfrag = 0|1：要不要连 fragment/smartComposition/v1/a 的
    //   debug_composition_enable 也打开。那一处只是把 CompositionList 打成屏幕上的
    //   调试红字，但它的 initView 里 findViewById 后直接 setVisibility，
    //   layout 若没有那两个 TextView 就 NPE 闪退 —— 默认 0（不开）。
    private static volatile int sDbgFrag;
    // ai.smartcomp.compose = 0|1（档7 用，默认开）：把构图项从 off 改判成 "ai"。
    //
    // ★ 这是「合成数据进去了但画面上没有构图框」的唯一卡点 ★
    //   显示链路（fragment/smartComposition/v1/a.is()）要连过三关：
    //     ① F.d0(mode, caps)          —— 档7 已抬
    //     ② F.Q(mode) = w2.a.isSwitchOn = !"off".equals(getComponentValue(mode))
    //        → 构图项=off 时**恒 false**，直接 setVisibility(8) 收掉整个视图
    //     ③ Xo() = w2.a.m(mode) 返回的构图位图非空
    //        → m() 第一行就是 if ("off".equals(...)) return null
    //   而构图项默认就是 "off"（w2.a.getKey() 返回 null → 取内存 map 的 null 键，
    //   从没写过 → 缺省 off），所以②③一起把构图框按死。
    //
    // 10-03 踩过的坑（档6 因此明令禁止改 w2.a 存值）：
    //   一旦构图项读成 "ai"，m() 就走 AI 分支遍历 f18198a（AI 图案列表），
    //   该列表未加载为 null → NPE → FragmentSmartComposition.initView 崩 → 每开必闪退。
    //   本档的解法不是绕开，而是**把根因补上**：
    //     (a) m() 挂 before：列表空就先反射调 i5.g.Pn() 从 assets 的
    //         smart_composition/v1.0.0/smart_composition_config.json 懒加载，
    //         加载完仍为 null 就直接 setResult(null) 返回 —— 双保险，绝不 NPE；
    //     (b) n()/o()/getComponentDataItem()/p() 本来就有 null 守卫，不用动。
    private static volatile int sCompose = 1;
    // ai.smartcomp.cycle = 0|1（默认 0）：每次 r0.acceptResult 合成数据时
    //   自动把索引往后推一格（1..48 轮转）。0 = 只有用户点构图框上的刷新按钮才换图。
    private static volatile int sCycle;
    // AI 模板条数：assets/smart_composition/v1.0.0/smart_composition_config.json
    // 的 smartCompositionList 正好是 id 1..48（creativeCompositionList 是 49..63），
    // 所以轮转范围就是 1..48，都能在 f18198a 里命中。
    private static final int PAT_MIN = 1;
    private static final int PAT_MAX = 48;
    // i5.g 单例（SmartCompositionManager），从已 hook 的 i5.g.M() 里顺手缓存。
    // ⑥ 图案懒加载要调它的 Pn()，Pn() 内部 = 列表空就 d.a() 解析 assets 的
    // smart_composition/v1.0.0/smart_composition_config.json 再 w2.a.r(b, c) 填进去。
    private static volatile Object sMgr;
    // ---- 档7 ⑧：构图框的显示总闸（只在 is() 的调用期间为 true）----
    //   起因（10-03 21:2x 用户实测）：框出来了，但
    //     · 普通拍照模式也出 —— 用户认为只该在 AI帮拍(168) 出
    //     · 智能构图开关关掉也照样出 —— 因为 is() 压根不读那个开关，
    //       它只看 F.d0(能力) / F.Q(构图项) / Xo()(位图)，而这三道我们全给抬了
    //   做法：进 is() 先算 sHideFrame，F.d0 的 hook 里发现它就返回 false →
    //   is() 走它自己那条 if (!F.d0) { setVisibility(8); return; }，不碰任何字段反射。
    private static volatile boolean sInIs;
    private static volatile boolean sHideFrame;
    // 记住是哪个线程在跑 is()：F.d0 会在别的线程被别处调用（r0.initAndGetPriorCondition
    // 走相机工作线程），只在同一条线程上认这个隐藏标志，避免误伤拦截器那条路。
    private static volatile long sIsThread = -1;
    private static volatile long sGateLogMs;
    // fragment 实例，缓存下来供「开关切换后立刻刷新」用
    private static volatile Object sFragment;
    // u2.H（智能构图开关组件）DI 实例缓存
    private static volatile Object sSmartComp;
    // w2.a（构图项选择器）DI 实例缓存，⑩ 换图时直接写它的 b（当前模板索引）
    private static volatile Object sSel;
    // 存值缓存：[0]=mode163, [1]=mode168。0=还没读到 / 1=ON / 2=OFF。
    // 由基类 getComponentValue 探针在 self 是 u2.H 时记，DI 拿不到实例时兜底用。
    private static final int[] sSwRec = new int[2];
    // ai.aiscene = 0|1：0=只观测 i.i() 原值；1=强制 data.data.i.i()=true
    //   （= 相机自带「AI 场景」开关 → K0().l(true) → xiaomi.ai.asd.enabled=true）
    private static volatile int sAiScene;
    // diag.watchdog = 0|1：主线程卡顿看门狗（默认开）。
    //   采样主线程栈，连续 diag.watchdog.ms（默认 1500ms）帧不变就打全栈，
    //   用来定位「预览卡住几秒」到底卡在哪个方法。
    private static volatile int sWd = 1;
    private static volatile int sWdMs = 1500;
    /** 每帧心跳：最后一个采集结果到达的时刻（elapsedRealtime ms） */
    private static volatile long sLastFrame;
    /** 0 = 没在卡；>0 = 帧停住的起始时刻 */
    private static volatile long sStallStart;
    /** 帧停太久（相机关闭/退后台）后静音，直到帧恢复 */
    private static volatile boolean sStallMuted;
    /** 本轮停帧起于后台（前台判据：后台停帧只记一行，不 dump、不报持续） */
    private static volatile boolean sStallBg;

    // ---- 停帧自愈（diag.stallheal）----
    // 现象：拍完预览面冻住 10~30s，HAL 不再回帧（主线程其实是空闲的，
    //   纯粹等 onCaptureCompleted）。相机自己的恢复要等它 10 秒看门狗 +
    //   abortCaptures（buffer 卡住时实测 4251ms）→ 总共 ~15 秒。
    //
    // ★ 10-03 19:37 的教训：一上来就 flush 是错的 ★
    //   MIVI 还在算的时候 abortCaptures 要等它吐 buffer，实测 4042/4048/
    //   4256/4257ms；正常连拍一轮 2.7s、停帧起点就是成片出来的那一刻，
    //   于是「按一次」反而把 2~4 秒的正常停顿拖成 6~10 秒 —— 越拍越卡。
    //   那次 6247ms 触发硬档 → C1() 用了 4253ms → 一共停了 10539ms。
    //
    // 所以改成三级阶梯，**先做零成本的，再做有成本的**：
    //   ① 重发预览  l9.y0.p0() = resumePreview = session.setRepeatingRequest
    //                不 flush、不打断在拍的照片、不等任何东西，~0ms。
    //                停帧 ≥ diag.stallheal.ms（1500）就开始按，每 1500ms 一次。
    //   ② flush      l9.y0.C1() = abortCaptures，**只在 MIVI 空的时候**按，
    //                停帧 ≥ diag.stallheal.flush（4000）。
    //                MIVI 空说明成片都出完了，flush 此时是快的（实测 41ms）；
    //                MIVI 还在算就绝不按（按了只会更慢）。
    //   ③ 强制 flush 停帧 ≥ diag.stallheal.hard（12000）就不看 MIVI 了，
    //                再烂也比相机自己 57 秒不恢复强。
    // diag.stallheal        = 0|1，0=关（只报警不干预），默认 1
    // diag.stallheal.ms     = ① 的阈值，默认 1500，钳 800~2000
    // diag.stallheal.flush  = ② 的阈值，默认 4000，钳 ≥ ①+1000
    // diag.stallheal.hard   = ③ 的阈值，默认 12000，钳 ≤ 20000
    private static volatile int sHeal = 1;
    private static volatile int sHealMs = 1500;
    private static volatile int sFlushMs = 4000;
    private static volatile int sHardMs = 12000;
    /** MIVICaptureManager.hasParallelTaskData() —— 还有没有照片在算 */
    private static volatile java.lang.reflect.Method sBusyProbe;
    /** 心跳帧计数（只增）：用来算「停帧前预览是不是真的健康」 */
    private static volatile int sFrameN;
    /** 最近一次预览还在 ≥20fps 出帧的时刻 */
    private static volatile long sHealthyAt;
    private static int sLastFn;
    /** l9.y0 = MiCamera2 实例（预览帧回调每帧指认，调 C1() 要用） */
    private static volatile Object sCam2;
    /** 预览「应该在跑」的标志：p0()=resumePreview 置 1，n1()=stopPreview 清 0 */
    private static volatile boolean sPreviewArmed;
    /** 前台标志：离开相机（onPause）就不再自愈 */
    private static volatile boolean sForeground = true;
    /** 上次自愈时刻 / 本轮已自愈次数（冷却 + 限次） */
    private static volatile long sHealLast;
    private static volatile int sHealN;
    /** 相机自己走过 abortCaptures 的时刻（分清是它自救还是我们按的） */
    private static volatile long sAppAbortAt;
    /** 我们正在自己的线程里调 C1() —— 让 before 钩子别把它算成相机自救 */
    private static volatile boolean sOurCall;

    private static int sBudget = 8;

    /** 诊断探针用：每个 key 按 step 周期打一条，避免被刷屏淹没 */
    private static final java.util.HashMap<String, Integer> sSeen =
            new java.util.HashMap<String, Integer>();

    /** step 是「第 N 次调用打一条」（0 = 只打第一次），不是毫秒 */
    private static void logN(String key, String msg, int step) {
        int c;
        synchronized (sSeen) {
            Integer n = sSeen.get(key);
            c = (n == null ? 0 : n.intValue()) + 1;
            sSeen.put(key, Integer.valueOf(c));
        }
        if (c == 1 || (step > 0 && c % step == 0)) {
            log(msg + "  #" + c);
        }
    }

    /** 热路径探针：ms 内不重复算字符串/栈，直接跳过 */
    private static boolean tooSoon(long lastMs, int ms) {
        return ms > 0
                && (android.os.SystemClock.elapsedRealtime() - lastMs) < ms;
    }

    /** 只允许在**冷路径**（每 hook 只算一次）调用：抓栈很贵 */
    private static String firstCaller() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 1; i < st.length; i++) {
            String cn = st[i].getClassName();
            if (cn.startsWith("com.pudding")) {
                continue;
            }
            return "[" + cn + "." + st[i].getMethodName() + "] ";
        }
        return "[?] ";
    }

    /**
     * 读 w2.a 的 AI 图案列表（dex 字段名 f18198a，jadx 没重命名，但保险起见
     * 读不到就按「本类里唯一一个非静态 ArrayList 字段」找 —— 踩过字段被改名的坑）。
     */
    private static Object readPatternList(Object sel) {
        if (sel == null) {
            return null;
        }
        try {
            return XposedHelpers.getObjectField(sel, "f18198a");
        } catch (Throwable ignore) {
            // 落到下面按类型找
        }
        try {
            for (java.lang.reflect.Field f : sel.getClass().getDeclaredFields()) {
                if (f.getType() == java.util.ArrayList.class
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f.get(sel);
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * 图案懒加载（w2.a.m() 列表空时调，最多 6 次）。三级：
     *   ① 官方：i5.g.Pn() —— if (d 空) { d.a(); w2.a.r(d.b, d.c); }
     *   ② DI：  g2.a.j().x(i5.g.class) 拿不到实例就跳过（记日志，不再静默）
     *   ③ 兜底：自己 new i5.d() 调 a() 解析 JSON，再直接 w2.a.r(b, c)
     *      —— 不依赖任何实例/时机，d 是普通类，a() 只读 assets，
     *      解析的是 assets/smart_composition/v1.0.0/smart_composition_config.json
     *      （本机 APK 里确认存在，配 1..60.webp，我们合成的 index=1 必命中 id="1"）。
     */
    private static void ensurePatternLoaded(Object sel, int attempt) {
        try {
            // ① 官方路径：先拿到 i5.g 实例
            Object mgr = sMgr;
            if (mgr == null) {
                try {
                    Object container = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("g2.a", sCl), "j");
                    mgr = XposedHelpers.callMethod(container, "x",
                            XposedHelpers.findClass(C_MGR, sCl));
                    if (mgr != null) {
                        sMgr = mgr;
                        log("懒加载#" + attempt + " DI 拿到 i5.g 实例");
                    }
                } catch (Throwable t2) {
                    log("懒加载#" + attempt + " DI 取 i5.g 失败: " + t2);
                }
            }
            if (mgr != null) {
                try {
                    XposedHelpers.callMethod(mgr, "Pn");
                    log("懒加载#" + attempt + " 调了 i5.g.Pn()");
                } catch (Throwable t) {
                    log("懒加载#" + attempt + " i5.g.Pn() 调用失败: " + t);
                }
            } else {
                log("懒加载#" + attempt + " i5.g 实例还没拿到（DI 返回 null），"
                        + "走③兜底自行解析 JSON");
            }

            Object list = readPatternList(sel);
            if (list != null && !((java.util.Collection) list).isEmpty()) {
                log("★ 图案懒加载成功（①/②路径）：" + ((java.util.Collection) list).size() + " 条");
                return;
            }

            // ③ 兜底：自己 new i5.d().a() → w2.a.r(b, c)
            //    只试 3 次，避免每次 m() 都重读 assets
            if (attempt > 3) {
                log("懒加载#" + attempt + " 兜底已试满 3 次，不再重试");
                return;
            }
            try {
                Object d = XposedHelpers.findClass("i5.d", sCl)
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(d, "a");
                Object b = readListField(d, "b");
                Object c = readListField(d, "c", "f13213c");
                if (b == null || c == null) {
                    log("懒加载#" + attempt + " 兜底：i5.d 字段没读到 b=" + b + " c=" + c);
                    return;
                }
                XposedHelpers.callMethod(sel, "r", b, c);
                Object after = readPatternList(sel);
                log("★ 图案懒加载成功（③兜底自行解析 JSON）：AI图案="
                        + ((java.util.Collection) b).size() + " 条, 创意项="
                        + ((java.util.Collection) c).size() + " 条 → w2.a 里="
                        + (after == null ? "null"
                                : ((java.util.Collection) after).size()) + " 条");
            } catch (Throwable t3) {
                log("懒加载#" + attempt + " 兜底解析失败: " + t3);
            }
        } catch (Throwable th) {
            err(th);
        }
    }

    /**
     * 读「智能构图」主开关的**用户存值**。
     * u2.H.isSwitchOn(i) 原式 = f17374a && "ON".equals(getComponentValue(i))，
     * f17374a 被 U3 门写死成 false，所以档6 已经在它的**出口**按存值改判
     * （只在存值为 ON 时才改成 true，存值 OFF 就照原样 false）。
     * 因此这里拿到的结果 == 用户在面板上按下的那个开关，可直接用作显示总闸。
     */
    private static boolean isSmartSwitchOn(int mode) {
        try {
            Object comp = sSmartComp;
            if (comp == null) {
                // ★ u2.H 在容器 g() 里，不是 j() ★
                //   CaptureModule:590 / FragmentAi:3578 都是 g2.a.g().x(u2.H.class)。
                //   第一版写成 j() 拿到 null，且我只在抛异常时才打日志 →
                //   静默 return false → 总闸永远算出「开关=OFF」→ 框永远不显示。
                for (String mth : new String[]{"g", "j", "a", "c"}) {
                    try {
                        Object container = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("g2.a", sCl), mth);
                        Object o = XposedHelpers.callMethod(container, "x",
                                XposedHelpers.findClass(C_COMP, sCl));
                        if (o != null) {
                            comp = o;
                            sSmartComp = o;
                            log("拿到智能构图开关组件 u2.H（容器 g2.a." + mth + "()）");
                            break;
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
            if (comp == null) {
                // 兜底：用基类 getComponentValue 探针记下来的存值
                int rec = sSwRec[mode == MODE_AI_HELP ? 1 : 0];
                if (rec != 0) {
                    logN("swRec", "u2.H DI 拿不到实例，用缓存存值 " + (rec == 1 ? "ON" : "OFF")
                            + "（mode=" + mode + "）", 60);
                    return rec == 1;
                }
                logN("swMiss", "!! 智能构图开关组件 u2.H 拿不到（DI 全空且无缓存）"
                        + "，按 OFF 处理 → 构图框隐藏", 1);
                return false;
            }
            return Boolean.TRUE.equals(XposedHelpers.callMethod(comp, "isSwitchOn",
                    Integer.valueOf(mode)));
        } catch (Throwable th) {
            err(th);
            return false;
        }
    }

    /** 下一个 AI 模板索引（1..48 轮转） */
    private static int nextPatIndex() {
        int i = sDbgIndex + 1;
        if (i > PAT_MAX || i < PAT_MIN) {
            i = PAT_MIN;
        }
        return i;
    }

    /**
     * 立刻把合成索引落到 w2.a.b 并刷新构图框。
     * w2.a.b 是 dex 真名（jadx 没改），m() 用
     * f8820r.equals(Integer.toString(w2.a.b)) 去 48 条 AI 模板里找位图。
     * 正常这条路是 i5.g.v() ← r0.acceptResult ← 异步分析 才写进来的；
     * 我们点刷新要立刻见效，就直接写 + 补调 is()（is() 里的 Xo() 会 setImageBitmap）。
     */
    private static void applyIndexNow() {
        try {
            Object sel = sSel;
            if (sel == null) {
                try {
                    Object container = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("g2.a", sCl), "j");
                    sel = XposedHelpers.callMethod(container, "x",
                            XposedHelpers.findClass(C_SEL, sCl));
                    if (sel != null) {
                        sSel = sel;
                    }
                } catch (Throwable ignore) {
                }
            }
            if (sel != null) {
                // 桩里没有 setIntField，用 setObjectField 传 Integer ——
                // 反射 Field.set 对 int 字段会自动拆箱，没问题
                XposedHelpers.setObjectField(sel, "b", Integer.valueOf(sDbgIndex));
            } else {
                log("!! 拿不到 w2.a，写不了 b");
            }
            Object frag = sFragment;
            if (frag != null) {
                XposedHelpers.callMethod(frag, "is");
            }
            Object bm = (sel == null) ? null
                    : XposedHelpers.callMethod(sel, "m", Integer.valueOf(MODE_AI_HELP));
            log("构图框已切到 index=" + sDbgIndex + "，位图="
                    + (bm == null ? "null（这张取不到，下次刷新会跳过它）" : "OK"));
        } catch (Throwable th) {
            err(th);
        }
    }

    /** 按名字读 ArrayList 字段（jadx 可能改名，逐个名字试，再按类型兜底） */
    private static Object readListField(Object obj, String... names) {
        if (obj == null) {
            return null;
        }
        for (String n : names) {
            try {
                return XposedHelpers.getObjectField(obj, n);
            } catch (Throwable ignore) {
            }
        }
        try {
            for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                if (f.getType() == java.util.ArrayList.class
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f.get(obj);
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * 属性后门专用：找出「是谁在读 debug_composition_*」。
     *
     * 栈上从上到下依次是：本方法 → 我们的 beforeHookedMethod → Xposed 框架帧
     * → 被 hook 的 Kr.g.a / Kr.g.c 本身 → **真正的调用者**。
     * 所以除了 com.pudding 还要跳过 xposed / reflect / Kr.g 才能拿到业务类。
     * 只在 key 命中 debug_composition_* 时调用（冷路径，属性读取很少）。
     */
    private static String dbgCaller() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (int i = 1; i < st.length; i++) {
                String cn = st[i].getClassName();
                String mn = st[i].getMethodName();
                if (cn == null
                        || cn.startsWith("com.pudding")
                        || cn.startsWith("de.robv.android.xposed")
                        || cn.contains("Xposed")
                        || cn.contains("XC_MethodHook")
                        || cn.startsWith("java.lang.reflect")
                        || cn.startsWith("Kr.g")
                        // 相机自己的 AOP 责任链帧（类名被混淆成 s/l 这种单字母，
                        // 只能按方法名认）
                        || "intercept".equals(mn)
                        || "proceed".equals(mn)) {
                    continue;
                }
                return cn;
            }
        } catch (Throwable ignore) {
        }
        return "?";
    }

    /** 诊断用：把前几帧压成一行短串（类名只留最后一段），定位属性后门的调用者 */
    private static String dbgStack() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (int i = 1; i < st.length && shown < 15; i++) {
                String cn = st[i].getClassName();
                if (cn == null || cn.startsWith("com.pudding")) {
                    continue;
                }
                int dot = cn.lastIndexOf('.');
                String shortName = dot >= 0 ? cn.substring(dot + 1) : cn;
                if (sb.length() > 0) {
                    sb.append(" <- ");
                }
                sb.append(shortName).append(".").append(st[i].getMethodName());
                shown++;
            }
            return sb.toString();
        } catch (Throwable ignore) {
            return "?";
        }
    }

    /** 单个 tagHolder 的读取状态：tag 名 / 是否受支持 / 持有值 / 本帧 CaptureResult 实际值 */
    private static void appendTag(StringBuilder sb, Object holder, Object res) {
        try {
            if (holder == null) {
                sb.append(" [holder=null]");
                return;
            }
            Object key = XposedHelpers.getObjectField(holder, "b");
            Object val = XposedHelpers.getObjectField(holder, "a");
            Object sup = XposedHelpers.getObjectField(holder, "c");
            String name = "?";
            Object rv = "<n/a>";
            if (key instanceof android.hardware.camera2.CaptureResult.Key) {
                android.hardware.camera2.CaptureResult.Key k =
                        (android.hardware.camera2.CaptureResult.Key) key;
                name = k.getName();
                if (res instanceof android.hardware.camera2.CaptureResult) {
                    try {
                        rv = ((android.hardware.camera2.CaptureResult) res).get(k);
                    } catch (Throwable t) {
                        rv = "err";
                    }
                }
            }
            sb.append(" [").append(name)
                    .append(" 受支持=").append(sup)
                    .append(" holder=").append(describe(val))
                    .append(" 本帧=").append(describe(rv)).append("]");
        } catch (Throwable th) {
            sb.append(" [inspect err:").append(th).append("]");
        }
    }

    private static String describe(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof byte[]) {
            return "len=" + ((byte[]) o).length;
        }
        return String.valueOf(o);
    }

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
        installFrameHeartbeat();
        installFgFlag();
        installStallHeal();
        startWatchdog();
        log("installed ai.smartcomp=" + sLevel
                + " submit=" + sSubmit
                + " dbgindex=" + sDbgIndex
                + " dbgfrag=" + sDbgFrag
                + " watchdog=" + sWd
                + " stallheal=" + sHeal + "/" + sHealMs
                + "/" + sFlushMs + "/" + sHardMs + "ms"
                + (sLevel <= 0 ? "  (关，不干预)" : ""));
        if (sLevel <= 0) {
            return;
        }

        // ============================================================
        // ★ 开关权威：相机设置里的「智能构图」 = u2.H / pref_smart_composition_key_<mode>
        //   在动任何门之前先把用户开关采到手（sUserOn），之后：
        //     · U3 / t0 / S0 强抬      → 开关关了就不抬（不换流、不下发会话参数）
        //     · A3.g.w() 拦截          → 开关关了就放行（别把「效果推荐」一起带死）
        //     · consumeResult 合成     → 开关关了就不喂假数据
        //     · ringTick（取景框+变焦）→ 开关关了就不动镜头
        //   ★ u2.H.R() 那道门刻意**不**吃开关：f17374a 必须恒 true，否则
        //     下次再把开关打开时 isSwitchOn 出不去、开关再也打不上来。
        //   采不到（-1）时按「开」处理 = 保持旧行为，不会更坏。
        // ============================================================
        // ★ 只从 R() / isSwitchOn 两处采开关（两处都带着「当前 mode」）。
        //   不去挂父类 AbstractC0716c.getComponentValue：那会连别的设置项
        //   （滤镜/水印/...）的存值也读进来，把开关判错。
        //   也不许在安装期直调相机单例（g2.a.a()）：会赶在 Application 之前
        //   把 <clinit> 触发失败 → 「Rejecting re-init on previously-failed
        //   class g2.a$a」→ 相机黑屏进不去（10-04 14:08 实测）。
        try {
            XposedHelpers.findAndHookMethod(C_COMP, sCl, "isSwitchOn", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                int mode = ((Integer) p.args[0]).intValue();
                                if (mode != MODE_PHOTO && mode != MODE_AI_HELP) {
                                    return;
                                }
                                boolean on = Boolean.TRUE.equals(p.getResult());
                                if (!on) {
                                    // false 有两种可能：存值就是 OFF，或被 f17374a 挡住。
                                    // 存值才是用户的意思，读出来区分。
                                    on = "ON".equals(String.valueOf(
                                            XposedHelpers.callMethod(p.thisObject,
                                                    "getComponentValue", p.args[0])));
                                }
                                setUserOn(on, "isSwitchOn(" + mode + ")");
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("hook u2.H.isSwitchOn ok（采开关）");
        } catch (Throwable th) {
            log("!! hook u2.H.isSwitchOn 失败 " + th);
        }

        // reInit 一结束就采开关。★ 不能挂 before：R(C) 的参数字段在 dex 里
        //   是别的名字（jadx 叫 f8774a，dex 里没有），取不到 mode。
        //   after 时 this.mCurrentMode 已经是本轮 mode 了（真实字段名，dex 里在）。
        try {
            XposedHelpers.findAndHookMethod(C_COMP, sCl, "R", Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                int mode = XposedHelpers.getIntField(p.thisObject, "mCurrentMode");
                                Object v = XposedHelpers.callMethod(p.thisObject,
                                        "getComponentValue", Integer.valueOf(mode));
                                setUserOn("ON".equals(String.valueOf(v)),
                                        "R() 后 mode=" + mode + " 存值=" + v);
                            } catch (Throwable th) {
                                logN("swsamp", "采开关失败(R) " + th, 600);
                            }
                        }
                    });
            log("hook u2.H.R 后采开关 ok");
        } catch (Throwable th) {
            log("!! hook u2.H.R 后采开关 失败 " + th);
        }
        // ★ 严禁在这里直接去调相机自己的单例（g2.a.a() 之类）直读开关：
        //   这会赶在 Application 之前把它的 <clinit> 触发掉，一旦失败
        //   「Rejecting re-init on previously-failed class g2.a$a」会让整个
        //   相机黑屏进不去（10-04 14:08 实测）。开关只从 R()/isSwitchOn 采。

        // ============================================================
        // ★ AI帮拍「智能构图」开关点不动 / 效果识别一直转圈 —— 根因在这 ★
        //   FragmentAi.onClick 第一行：
        //       if (!f8958y0) { "onClick ignore while analyzing"; return; }
        //   f8958y0 = 「分析已结束」，只有 onAiEffectResult / onAiPoseResult
        //   回来时调 ft(..., true) 才会置上。本机 AI 结果一直不回来 → 它永远
        //   是 false → 智能构图 / 效果推荐 / 姿势引导 三个按钮全被吞
        //   （10-04 14:16、14:34 实测连点几十次全是 ignore，
        //     pref_smart_composition_key_168 永远改不动）。
        //   ① ft(str,false) 一律跳过 —— 不许它把自己按回「分析中」
        //   ② UI 起来后补一次 ft(str,true) —— 把开机就卡在 false 的状态拉起来
        //   ft() 本体只有一行 log + 一个字段赋值，跳过/多调一次都安全。
        // ============================================================
        boolean[] aiFtOk = {false};
        try {
            XposedHelpers.findAndHookMethod(C_AI_FRAG, sCl, "ft",
                    String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                if (!Boolean.TRUE.equals(p.args[1])) {
                                    p.setResult(null);   // void：跳过原方法体
                                    logN("aiunstick", "FragmentAi.ft(" + p.args[0]
                                            + ", false) 已跳过 → 不再卡 onClick", 120);
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            aiFtOk[0] = true;
            log("hook FragmentAi.ft ok（分析标志不会被按回 false）");
        } catch (Throwable th) {
            log("!! hook FragmentAi.ft 失败 " + th);
        }
        if (aiFtOk[0]) {
            // getFragmentId() 是 FragmentAi 自己 final 实现的（返回 3821），
            // UI 建起来必被调到 —— 比 onResume 更保险（onResume 不一定被覆写）。
            for (String m : new String[]{"getFragmentId", "onResume"}) {
                try {
                    final String which = m;
                    XposedHelpers.findAndHookMethod(C_AI_FRAG, sCl, m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                XposedHelpers.callMethod(p.thisObject, "ft",
                                        "camport", Boolean.TRUE);
                                logN("aiunstick",
                                        "FragmentAi." + which + "() → 置分析结束，onClick 恢复",
                                        60);
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
                    log("hook FragmentAi." + m + " ok（兜底置分析结束）");
                } catch (Throwable th) {
                    log("!! hook FragmentAi." + m + " 失败 " + th);
                }
            }
        }

        // ============================================================
        // ★ ai.smartcomp = 5 / 6 / 7：只抬 V3，逼相机走 v1（ASD 代 + PIP 样张框）
        //    U3 / t0 / S0 一个都不碰 —— 因为抬 U3 会把相机推上 v2 死路。
        //    见文件头「三、相机里其实有两代实现」。
        //    6 = 5 + 补齐 v1 通路剩下两块：u2.H 支持门 + 构图项 = "ai"
        //    ★ 档8 明确**不**走这里：档8 要的是 v2 引导环，靠的是下面 1..4 四道门
        //      （抬 U3 而**不是**抬 V3），所以 5..7 的 if 必须带上 <= 7，
        //      否则 sLevel=8 会掉进这个 v1 分支 return 掉，后面 1..4 全不生效。
        // ============================================================
        if (sLevel >= 5 && sLevel <= 7) {
            // ⑥ 分叉门：com.xiaomi.camera.supportedfeatures.asd.aiComposition
            try {
                XposedHelpers.findAndHookMethod(C_H, sCl, "V3", C_G, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try {
                            if (!gateOpen()) {
                                return;   // 用户关了智能构图 → 不抬 asd.aiComposition
                            }
                            Object orig = p.getResult();
                            p.setResult(Boolean.TRUE);
                            logN("V3", "V3 " + orig + " -> true  (抬 asd.aiComposition → 走 v1)", 0);
                        } catch (Throwable th) {
                            err(th);
                        }
                    }
                });
                log("hook l9.h.V3 ok (只抬 V3，U3 保持 false → v1)");
            } catch (Throwable th) {
                log("!! hook l9.h.V3 失败 " + th);
            }

            // ---- v1 通路诊断探针（只为看清楚卡在哪，不改任何结果）----
            // (a) F.d0 == SmartCompositionSimpleASD.initAndGetPriorCondition 的门
            //     = !N() && i9==163 && caps!=null && V3(caps)
            try {
                XposedHelpers.findAndHookMethod(
                        "com.android.camera.data.data.F", sCl, "d0",
                        int.class, C_G, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                try {
                                    Object[] a = p.args;
                                    logN("Fd0", "F.d0(mode=" + a[0] + ", caps=" + (a[1] != null)
                                            + ") = " + p.getResult(), 0);
                                } catch (Throwable th) {
                                    err(th);
                                }
                            }
                        });
                log("probe com.android.camera.data.data.F.d0 ok");
            } catch (Throwable th) {
                log("!! probe F.d0 失败 " + th);
            }

            // (b) r0 = SmartCompositionSimpleASD 的两道运行条件
            try {
                XposedHelpers.findAndHookMethod(
                        "s6.r0", sCl, "initAndGetPriorCondition", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                try {
                                    logN("r0init", "r0.initAndGetPriorCondition = "
                                            + p.getResult(), 0);
                                } catch (Throwable th) {
                                    err(th);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod(
                        "s6.r0", sCl, "getInTimeCondition", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                try {
                                    logN("r0in", "r0.getInTimeCondition = "
                                            + p.getResult(), 0);
                                } catch (Throwable th) {
                                    err(th);
                                }
                            }
                        });
                // (c) 数据真的回来了才会进 acceptResult —— 这是 v1 通不通的判据
                XposedHelpers.findAndHookMethod(
                        "s6.r0", sCl, "acceptResult", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                try {
                                    logN("r0acc", "★ r0.acceptResult 进入", 50);
                                } catch (Throwable th) {
                                    err(th);
                                }
                            }
                        });
                log("probe s6.r0 ok (initAndGetPriorCondition / getInTimeCondition / acceptResult)");
            } catch (Throwable th) {
                log("!! probe s6.r0 失败 " + th);
            }

            // (d) v1 分支的入口：updateSmartComposition() 里 if (!U3) { if (V3) ... }
            try {
                XposedHelpers.findAndHookMethod(
                        "com.android.camera.features.mode.capture.CaptureModule", sCl,
                        "updateSmartComposition", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                try {
                                    logN("usc", "CaptureModule.updateSmartComposition() 进入", 0);
                                } catch (Throwable th) {
                                    err(th);
                                }
                            }
                        });
                log("probe CaptureModule.updateSmartComposition ok");
            } catch (Throwable th) {
                log("!! probe updateSmartComposition 失败 " + th);
            }

            // ============================================================
            // ★ ai.smartcomp = 6：v1 通路剩下两块
            //
            // v1 真正的运行闸门只有两道，U3 抬门那套（t0/S0/下发）一概不需要：
            //
            //  ① u2.H.R() 的第一道门是 U3 —— 我们故意不抬 U3，于是
            //     f17374a = false → isSwitchOn(mode) 恒 false（哪怕
            //     pref_smart_composition_key_168 已经是 ON）。
            //     解法：R() 跑完直接把字段改回 true，**不碰 U3**。
            //
            //  ② SmartCompositionManager.M() = F.w(模块号) && !L0.nn()
            //     而 F.w(i) = "ai".equals(w2.a.getComponentValue(i))。
            //     w2.a.getKey() 返回 null → 取的是内存 map 的 null 键 →
            //     从没被写过就恒等于缺省值 "off" → M() 恒 false →
            //     getInTimeCondition 恒 false → acceptResult 永远不跑。
            //     解法：把 w2.a 的构图项从 "off" 补成 "ai"（只在 163/168，
            //     且只在没选过时；用户手动选了创意图案则尊重原值）。
            // ============================================================
            if (sLevel >= 6) {
                // (e) ★真正的闸门★ F.w(mode) = "ai".equals(w2.a.getComponentValue(mode))
                //     w2.a.getKey() 返回 null → 取的是内存 map 的 null 键 → 从没写过
                //     就恒等于缺省 "off" → i5.g.M() 恒 false → getInTimeCondition 恒 false
                //     → r0.acceptResult 永远不跑。
                //
                //     ★ 只在 F.w 这一层改判，**绝不改 w2.a 的存值** ★
                //     10-03 10:57 实测踩过：直接把 w2.a.getComponentValue 改成 "ai"，
                //     会让 w2.a.m()（getComponentDataItemBitmap）走进 AI 分支去遍历
                //     f18198a，而那个 AI 图案列表根本没加载（null）→
                //     NullPointerException → 相机每开必闪退。
                //     F.w 是纯读侧的布尔判断，改它不会触发任何 UI 取图。
                try {
                    XposedHelpers.findAndHookMethod("com.android.camera.data.data.F", sCl,
                            "w", int.class, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                    try {
                                        int mode = ((Integer) p.args[0]).intValue();
                                        boolean orig = Boolean.TRUE.equals(p.getResult());
                                        if (!orig && (mode == MODE_PHOTO || mode == MODE_AI_HELP)) {
                                            p.setResult(Boolean.TRUE);
                                            logN("FwFix", "F.w(" + mode + ") false -> true（补判 AI 构图，不动 w2.a 存值）", 0);
                                        } else {
                                            logN("Fw", "F.w(" + mode + ") = " + orig, 120);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook F.w ok (163/168 补判 true)");
                } catch (Throwable th) {
                    log("!! hook F.w 失败 " + th);
                }

                // (e2) ★「点了 UI 不亮」就卡在这★
                //      FragmentAi.onFeatureSmartComposition → x.t0(mode)
                //        → u2.H.isSwitchOn(mode)
                //          = f17374a && "ON".equals(getComponentValue(mode))
                //      f17374a 是被 R() 里那道 U3 门写死成 false 的（我们刻意不抬 U3），
                //      所以点击写进 prefs 的 ON 永远读不出来 → 图标每次刷新都被打回 OFF。
                //
                //      ★ 只在 isSwitchOn 的**出口**按存值改判，绝不碰 f17374a 字段 ★
                //      理由：m() = isReconfigStreamCompositionInit() 也读同一个字段，
                //      直接抬字段会把换流判断一起带翻（可能触发不支持的重配流）。
                //      这里只让「开关读回用户存的值」，其余逻辑一字不动。
                try {
                    XposedHelpers.findAndHookMethod(C_COMP, sCl, "isSwitchOn", int.class,
                            new XC_MethodHook() {
                                // 反射 Method 缓存：这条是热路径，每帧都会来
                                final java.lang.reflect.Method[] gm = {null};

                                @Override
                                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                    try {
                                        if (Boolean.TRUE.equals(p.getResult())) {
                                            return;   // 已经 true，说明字段本来就是 true
                                        }
                                        int mode = ((Integer) p.args[0]).intValue();
                                        if (mode != MODE_PHOTO && mode != MODE_AI_HELP) {
                                            return;
                                        }
                                        Object self = p.thisObject;
                                        if (gm[0] == null) {
                                            gm[0] = self.getClass()
                                                    .getMethod("getComponentValue", int.class);
                                            gm[0].setAccessible(true);
                                        }
                                        Object v = gm[0].invoke(self, Integer.valueOf(mode));
                                        if ("ON".equals(v)) {
                                            p.setResult(Boolean.TRUE);
                                            logN("swFix", "u2.H.isSwitchOn(" + mode
                                                    + ") false -> true（f17374a 挡住存值 ON，"
                                                    + "只改判出口，未抬字段）", 20);
                                        } else {
                                            logN("sw", "u2.H.isSwitchOn(" + mode
                                                    + ") = false, 存值=" + v, 600);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook u2.H.isSwitchOn ok (f17374a 门只在出口绕过)");
                } catch (Throwable th) {
                    log("!! hook u2.H.isSwitchOn 失败 " + th);
                }

                // (f) 构图项读取：档7 且 ai.smartcomp.compose=1 时把 off 改判成 ai。
                //     这是显示链路的②③两关（isSwitchOn / m() 位图）的唯一卡点，
                //     见字段 sCompose 的完整注释。仍是**读侧**改判：
                //     不写 w2.a 的存值，用户在面板上看到的选项和实际存值都还是 off，
                //     这样退出时不会把一个没人写过的 "ai" 留在 prefs 里。
                //     配合 m() 那侧的懒加载 + null 守卫，改判后不会 NPE。
                try {
                    XposedHelpers.findAndHookMethod(C_BASE, sCl, "getComponentValue", int.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                    try {
                                        Object self = p.thisObject;
                                        if (self == null) {
                                            return;
                                        }
                                        String cn = self.getClass().getName();
                                        if (C_COMP.equals(cn)) {
                                            // 顺手把「智能构图」主开关的存值记下来，
                                            // 给⑧的显示总闸兜底（DI 拿不到实例时用）
                                            int m = ((Integer) p.args[0]).intValue();
                                            if (m == MODE_PHOTO || m == MODE_AI_HELP) {
                                                sSwRec[m == MODE_AI_HELP ? 1 : 0] =
                                                        "ON".equals(p.getResult()) ? 1 : 2;
                                            }
                                            return;
                                        }
                                        if (!C_SEL.equals(cn)) {
                                            return;   // 只看构图项这一个组件
                                        }
                                        int mode = ((Integer) p.args[0]).intValue();
                                        if (mode != MODE_PHOTO && mode != MODE_AI_HELP) {
                                            return;
                                        }
                                        Object orig = p.getResult();
                                        if (sLevel >= 7 && sCompose == 1
                                                && "off".equals(orig)) {
                                            p.setResult(V_AI);
                                            logN("selAi", "w2.a.getComponentValue(" + mode
                                                    + ") off -> ai（放行显示链路的 "
                                                    + "isSwitchOn / m() 两关）", 60);
                                        } else {
                                            logN("sel", "w2.a.getComponentValue(" + mode + ") = "
                                                    + orig, 120);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe " + C_BASE + ".getComponentValue ok ("
                            + (sLevel >= 7 && sCompose == 1
                                    ? "档7 改判 off->ai，构图框可见"
                                    : "只读") + ")");
                } catch (Throwable th) {
                    log("!! probe 构图项失败 " + th);
                }

                // (g) 探针：i5.g.M() 是 getInTimeCondition 的完整判据
                //     若 F.w 已 true 而 M() 仍 false → 卡在 L0.nn()（构图面板开着）
                try {
                    XposedHelpers.findAndHookMethod(C_MGR, sCl, "M", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                logN("mgrM", "i5.g.M() = " + p.getResult(), 60);
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
                    log("probe i5.g.M ok");
                } catch (Throwable th) {
                    log("!! probe i5.g.M 失败 " + th);
                }

                // (h) 探针：r0.acceptResult 解析的原始字节
                //     ja.r.b(byte[]) 就是把 xiaomi.ai.misd.SemanticScene 的 byte[] 拆成
                //     {a, b} 数组。它返回空 = acceptResult 直接 return，UI 永远拿不到构图索引。
                //     这条日志用来分辨「HAL 没出数据」还是「解析失败」。
                try {
                    XposedHelpers.findAndHookMethod("ja.r", sCl, "b", byte[].class,
                            new XC_MethodHook() {
                                // 调用方只认一次就够（每帧抓栈会把 capture-result 线程拖死）
                                final String[] who = {null};
                                final long[] t = {0L};

                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        if (sInAcc) {
                                            logN("asdB", "ja.r.b 由 r0.acceptResult 调用",
                                                    60);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        long now =
                                                android.os.SystemClock.elapsedRealtime();
                                        if (now - t[0] < 200) {
                                            return;
                                        }
                                        Object r = p.getResult();
                                        // ★ 只在「解析结果变化」时才打印 —— 不区分调用方。
                                        //   这样既能看 r0 要的 SemanticScene（恒 null），
                                        //   也能看 X / ja.r.a 那份 NonSemanticScene 的真实 {a,b}。
                                        StringBuilder sb = new StringBuilder();
                                        if (r instanceof Object[]) {
                                            Object[] arr = (Object[]) r;
                                            int shown = 0;
                                            for (int i = 0;
                                                 i < arr.length && shown < 6; i++) {
                                                Object e = arr[i];
                                                if (e == null) {
                                                    sb.append(" null");
                                                    continue;
                                                }
                                                sb.append(" {a=")
                                                  .append(XposedHelpers.getIntField(e, "a"))
                                                  .append(",b=")
                                                  .append(XposedHelpers.getIntField(e, "b"))
                                                  .append("}");
                                                shown++;
                                            }
                                            sb.insert(0, arr.length + "条:");
                                        } else {
                                            sb.append("null");
                                        }
                                        String s = sb.toString();
                                        t[0] = now;
                                        if (s.equals(sLastData)) {
                                            return;
                                        }
                                        sLastData = s;
                                        if (who[0] == null) {
                                            who[0] = sInAcc
                                                    ? "[r0.acceptResult] "
                                                    : firstCaller();
                                        }
                                        log("★ ja.r.b " + who[0] + s);
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe ja.r.b ok (SemanticScene 原始字节)");
                } catch (Throwable th) {
                    log("!! probe ja.r.b 失败 " + th);
                }

                // (i) ★决定性探针★ r0.acceptResult 的进/出口
                //     进入：r0.a 就是 debug_composition_enable 的缓存值（CaptureModule:617）
                //     出口：c = CompositionList、d = DetectTipList
                //           只有「a==11 且 b!=1111111」才会进 c；
                //           c 恒空 → 后面的 p073i5.i.a() UI 推送拿不到东西 → 图标不亮
                try {
                    XposedHelpers.findAndHookMethod("s6.r0", sCl, "acceptResult",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        sInAcc = true;
                                        Object dbg = XposedHelpers.getObjectField(
                                                p.thisObject, "a");
                                        logN("accIn",
                                                "r0.acceptResult 进入：debug_composition_enable="
                                                        + dbg, 200);
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        sInAcc = false;
                                        Object cl = XposedHelpers.getObjectField(
                                                p.thisObject, "c");
                                        Object dl = XposedHelpers.getObjectField(
                                                p.thisObject, "d");
                                        String s = "r0 出口 CompositionList=" + cl
                                                + "  DetectTipList=" + dl;
                                        if (!s.equals(sLastOut)) {
                                            sLastOut = s;
                                            log("★ " + s);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe s6.r0.acceptResult 进/出口 ok");
                } catch (Throwable th) {
                    log("!! probe acceptResult 进出口 失败 " + th);
                }

                // (j) 探针：Kr.g 属性读取器 —— debug_composition_enable / _index
                //     的真实取值（决定后门是否可用、索引是多少）
                try {
                    XposedHelpers.findAndHookMethod("Kr.g", sCl, "a", String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        String k = (String) p.args[0];
                                        if (k != null && k.startsWith("debug_composition")) {
                                            logN("propA", "Kr.g.a(" + k + ") = "
                                                    + p.getResult(), 0);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    XposedHelpers.findAndHookMethod("Kr.g", sCl, "c",
                            String.class, boolean.class, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        String k = (String) p.args[0];
                                        if (k != null && k.startsWith("debug_composition")) {
                                            logN("propC", "Kr.g.c(" + k + ", "
                                                    + p.args[1] + ") = " + p.getResult(), 0);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe Kr.g 属性读取器 ok (debug_composition_*)");
                } catch (Throwable th) {
                    log("!! probe Kr.g 失败 " + th);
                }

                // (k) ★对比探针★ 同一帧上，r0 和 X 对 xiaomi.ai.misd.SemanticScene
                //     的读取状态。X（declareTags 的第 0 个就是 v0）能拿到 16 字节，
                //     而 r0 的 tagHolder 是 null → 差异一定在 r0 这一侧：
                //       supportPrior / tag 受支持位 / tagHolder，而不是 HAL 没出数据。
                //     受支持 = b.c（f10839c），holder = b.a（f10838a），b = Key
                try {
                    XposedHelpers.findAndHookMethod(
                            "com.android.camera.module.interceptor.base.i", sCl,
                            "onCaptureResultNext",
                            android.hardware.camera2.CaptureResult.class,
                            new XC_MethodHook() {
                                final long[] t = {0L};

                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        Object self = p.thisObject;
                                        String cn = self.getClass().getName();
                                        if (!"s6.r0".equals(cn) && !"s6.X".equals(cn)) {
                                            return;
                                        }
                                        // 每帧都跑（30~60fps × 2 拦截器），先按时间挡掉，
                                        // 否则反射 + res.get() 会拖慢 capture-result 线程
                                        if (tooSoon(t[0], 300)) {
                                            return;
                                        }
                                        t[0] = android.os.SystemClock.elapsedRealtime();
                                        android.hardware.camera2.CaptureResult res =
                                                (android.hardware.camera2.CaptureResult) p.args[0];
                                        StringBuilder sb = new StringBuilder(cn);
                                        sb.append(" 处理=").append(p.getResult());
                                        sb.append(" supportPrior=").append(
                                                XposedHelpers.getObjectField(self, "supportPrior"));
                                        sb.append(" supportInTime=").append(
                                                XposedHelpers.getObjectField(self, "supportInTime"));
                                        if ("s6.r0".equals(cn)) {
                                            appendTag(sb, XposedHelpers.getObjectField(
                                                    self, "tagHolder"), res);
                                        } else {
                                            java.util.List tags = (java.util.List)
                                                    XposedHelpers.getObjectField(self, "tagList");
                                            int n = (tags == null) ? 0 : Math.min(3, tags.size());
                                            for (int i = 0; i < n; i++) {
                                                appendTag(sb, tags.get(i), res);
                                            }
                                        }
                                        String s = sb.toString();
                                        if (!s.equals(sLastItr)) {
                                            sLastItr = s;
                                            log("★ " + s);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe i.onCaptureResultNext ok (r0 vs X 同帧对比)");
                } catch (Throwable th) {
                    log("!! probe onCaptureResultNext 失败 " + th);
                }

                // (l) AI 场景开关 A/B：data.data.i.i(mode) = Camera2Module.getAiSceneEnabled()
                //     → K0().l(z) → xiaomi.ai.asd.enabled（本机 HAL 唯一存在的 ASD 使能 tag）
                //     ai.aiscene=0 只观测原值；=1 强制 true，
                //     用 (k) 探针的 SemanticScene「本帧=」判断是否被点亮。
                //     ★ 只改这一个出口，不动 pref、不动 w2.a ★
                try {
                    XposedHelpers.findAndHookMethod("com.android.camera.data.data.i", sCl,
                            "i", int.class, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        int mode = ((Integer) p.args[0]).intValue();
                                        boolean orig = Boolean.TRUE.equals(p.getResult());
                                        if (sAiScene == 1 && !orig) {
                                            p.setResult(Boolean.TRUE);
                                            logN("aiScene",
                                                    "i.i(" + mode + ") false -> true"
                                                            + "（ai.aiscene=1 强制 AI 场景）", 0);
                                        } else {
                                            logN("aiScene", "i.i(" + mode + ") = " + orig, 60);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe data.data.i.i ok (ai.aiscene=" + sAiScene + ")");
                } catch (Throwable th) {
                    log("!! probe data.data.i.i 失败 " + th);
                }

                // ---- 点击链路探针（只读，不改任何返回值）----
                // (m) 写值：只看 u2.H 这一个组件 → 揭示用户点击时的 mode 与写入值
                //     setComponentValue 是从基类继承的，dex 真名 com.android.camera.data.data.c
                try {
                    XposedHelpers.findAndHookMethod("com.android.camera.data.data.c", sCl,
                            "setComponentValue", int.class, String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        Object self = p.thisObject;
                                        String cn = self.getClass().getName();
                                        if (!"u2.H".equals(cn)) {
                                            return;
                                        }
                                        logN("scWrite", "u2.H.setComponentValue(mode="
                                                + p.args[0] + ", val=" + p.args[1] + ")", 0);
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe u2.H.setComponentValue ok (写值观测)");
                } catch (Throwable th) {
                    log("!! probe setComponentValue 失败 " + th);
                }

                // (n) 「这个模式支不支持智能构图」的门 = u2.H.isSupportMode（直接返回 f17374a）
                try {
                    XposedHelpers.findAndHookMethod("u2.H", sCl, "isSupportMode", int.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        logN("scSup", "u2.H.isSupportMode(" + p.args[0]
                                                + ") = " + p.getResult(), 0);
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe u2.H.isSupportMode ok");
                } catch (Throwable th) {
                    log("!! probe isSupportMode 失败 " + th);
                }

                // (o) 图标刷新口：FragmentAi.zt(boolean)
                //     init 时走 x.t0(mode)，点击时走 tag 取反 —— 打调用方即可分辨
                try {
                    XposedHelpers.findAndHookMethod(
                            "com.android.camera.features.mode.ai.FragmentAi", sCl,
                            "zt", boolean.class, new XC_MethodHook() {
                                final boolean[] first = {true};

                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        if (!first[0]) {
                                            return;
                                        }
                                        first[0] = false;
                                        StringBuilder sb = new StringBuilder();
                                        sb.append("FragmentAi.zt(").append(p.args[0]).append(")");
                                        StackTraceElement[] st =
                                                new Throwable().getStackTrace();
                                        int shown = 0;
                                        for (int i = 1;
                                             i < st.length && shown < 2; i++) {
                                            String cn = st[i].getClassName();
                                            if (cn.startsWith("com.pudding")) {
                                                continue;
                                            }
                                            sb.append(" <- ").append(cn).append(".")
                                              .append(st[i].getMethodName());
                                            shown++;
                                        }
                                        logN("scIcon", sb.toString(), 0);
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe FragmentAi.zt ok (图标刷新口)");
                } catch (Throwable th) {
                    log("!! probe FragmentAi.zt 失败 " + th);
                }
            }

            // ============================================================
            // ★ ai.smartcomp = 7：档6 + 合成数据后门 ★
            //
            // v1 通路一共 5 道门，档5/6 只抬了 2 道（V3 分叉门 + F.w 判据），
            // 剩下 3 道让 r0 根本不注册 / 不跑：
            //
            //  ① F.d0(mode, caps) = !g.N() && mode==163 && caps!=null && V3(caps)
            //     → r0.initAndGetPriorCondition()，进不去就 prepare() 直接 false
            //  ② prepare() 的 tag 支持位：
            //         z9 = name.startsWith("android") || l9.h.M4(caps, name)
            //         M4(g, s) = g.S0(s)
            //     r0 的 tag = xiaomi.ai.misd.SemanticScene —— 既不以 android 开头，
            //     本机 HAL 又没注册（全盘 0 命中）→ z9=false → supportPrior=false
            //     → prepare() 返回 false → **拦截器压根不注册 → acceptResult 永不跑**
            //     ★ 这是档5/6 漏掉的最关键一道门，也是「探针一条都没出」的原因 ★
            //  ③ getInTimeCondition() = i5.g.M() = F.w(mode) && !L0.nn()
            //     → 档6 抬了 F.w；nn() 若挡路这里兜底
            //  ④ r0.f17135a = Kr.g.c("debug_composition_enable", false)
            //     → 合成数据的总闸
            //  ⑤ r.b(getTagValue(null)) 拿不到 SemanticScene 数据（HAL 不出）
            //     → 靠 Kr.g.a("debug_composition_index") 合成 r.a{a=11, b=index}
            //
            // ④⑤ 是相机自带的 debug 后门（代码里现成的），只是属性从来没被设过。
            // ★ 刻意**不**全局 setprop：★ 那会同时打开 fragment 的 debug TextView，
            //   它 initView 里 findViewById 后直接 setVisibility，layout 没那两个
            //   控件就 NPE 闪退（10-03 已踩过一次）。这里按调用者逐个放行。
            // ============================================================
            if (sLevel >= 7) {

                // ---- ② tag 支持门：S0(name) 对 SemanticScene 放行 ----
                try {
                    XposedHelpers.findAndHookMethod(C_G, sCl, "S0", String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        String n = (String) p.args[0];
                                        if (n != null && n.contains("SemanticScene")) {
                                            p.setResult(Boolean.TRUE);
                                            logN("S0v1", "l9.g.S0(" + n
                                                    + ") -> true（抬 v1 tag 支持门，"
                                                    + "prepare 才会注册拦截器）", 0);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook l9.g.S0 ok (SemanticScene 支持门)");
                } catch (Throwable th) {
                    log("!! hook l9.g.S0 失败 " + th);
                }

                // ---- ① F.d0：r0.initAndGetPriorCondition 的门（按模式直判）----
                try {
                    XposedHelpers.findAndHookMethod(
                            "com.android.camera.data.data.F", sCl, "d0",
                            int.class, C_G, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        int mode = ((Integer) p.args[0]).intValue();
                                        // ⑧ 的显示总闸优先：is() 正在跑且判定该隐藏 → 一律 false，
                                        // 让 is() 走它原生的 setVisibility(8) 分支。
                                        if (sHideFrame
                                                && sIsThread == Thread.currentThread().getId()) {
                                            p.setResult(Boolean.FALSE);
                                            logN("Fd0hide", "F.d0(" + mode
                                                    + ") 被显示总闸压成 false → 收掉构图框", 30);
                                            return;
                                        }
                                        if (mode == MODE_PHOTO || mode == MODE_AI_HELP) {
                                            if (!Boolean.TRUE.equals(p.getResult())) {
                                                p.setResult(Boolean.TRUE);
                                                logN("Fd0fix", "F.d0(" + mode
                                                        + ") false -> true（进 v1 拦截器）", 0);
                                            }
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook F.d0 ok (163/168 进 v1)");
                } catch (Throwable th) {
                    log("!! hook F.d0 失败 " + th);
                }

                // ---- ③ i5.g.M()：getInTimeCondition 的后半段（nn() 兜底）----
                try {
                    XposedHelpers.findAndHookMethod(C_MGR, sCl, "M", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            try {
                                // 顺手缓存 i5.g 实例：⑥ 的图案懒加载要调它的 Pn()
                                if (sMgr == null && p.thisObject != null) {
                                    sMgr = p.thisObject;
                                    log("缓存 i5.g 实例（供图案懒加载 Pn() 用）");
                                }
                                if (!Boolean.TRUE.equals(p.getResult())) {
                                    p.setResult(Boolean.TRUE);
                                    logN("mgrMfix", "i5.g.M() false -> true"
                                            + "（F.w 已抬，nn() 挡路时兜底）", 0);
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
                    log("hook i5.g.M ok (兜底 getInTimeCondition)");
                } catch (Throwable th) {
                    log("!! hook i5.g.M 失败 " + th);
                }

                // ---- ④ 合成数据总闸：直接写 r0.f17135a（dex 真名 a）----
                // 最初试的是 hook Kr.g.c("debug_composition_enable") 按调用者放行，
                // 但实测栈上全是 Xposed 混淆帧（A.fIUydx...XC_MethodHook）和相机自己的
                // AOP 责任链（s.intercept / l.proceed），拿不到业务类，判断不稳。
                //
                // 而 CaptureModule 装配完 r0 之后就没人再改这个字段了，
                // 所以更稳的做法：在拦截器每帧入口 onCaptureResultNext 的 before 里
                // 直接把字段写成 true —— 方法体里 z8 = this.f17135a 在 acceptResult
                // 一开始就读，那时已经是 true，合成分支必走。
                //
                // ★ 刻意**不**用全局 setprop 打开 debug_composition_enable ★
                //   那会连带打开 fragment/smartComposition/v1/a 的 debug TextView，
                //   它 initView 里 findViewById 后直接 setVisibility，layout 没那两个
                //   控件就 NPE → FragmentSmartComposition.initView 崩（10-03 已踩过）。
                try {
                    XposedHelpers.findAndHookMethod(
                            "com.android.camera.module.interceptor.base.i", sCl,
                            "onCaptureResultNext",
                            android.hardware.camera2.CaptureResult.class,
                            new XC_MethodHook() {
                                // Field 缓存：这条是每帧热路径，别反复 getDeclaredField
                                final java.lang.reflect.Field[] fA = {null};

                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        Object self = p.thisObject;
                                        if (self == null
                                                || !"s6.r0".equals(self.getClass().getName())) {
                                            return;
                                        }
                                        if (fA[0] == null) {
                                            java.lang.reflect.Field f =
                                                    self.getClass().getDeclaredField("a");
                                            f.setAccessible(true);
                                            fA[0] = f;
                                        }
                                        if (!fA[0].getBoolean(self)) {
                                            fA[0].setBoolean(self, true);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook r0 总闸 ok (onCaptureResultNext 置 f17135a=true)");
                } catch (Throwable th) {
                    log("!! hook r0 总闸失败 " + th);
                }

                // ---- 诊断：Kr.g.c(debug_composition_enable) 的真实调用者 ----
                //   只打日志不改值。三个调用点：CaptureModule 装配 r0 /
                //   p073i5.g$a 静态块 / fragment.a 字段初始化。
                try {
                    XposedHelpers.findAndHookMethod("Kr.g", sCl, "c",
                            String.class, boolean.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        String k = (String) p.args[0];
                                        if (!"debug_composition_enable".equals(k)) {
                                            return;
                                        }
                                        String caller = dbgCaller();
                                        logN("dbgGate", "Kr.g.c(debug_composition_enable, "
                                                + p.args[1] + ") caller=" + caller
                                                + " 栈=" + dbgStack(), 60);
                                        // ai.smartcomp.dbgfrag=1 时才顺带放开 fragment 那处
                                        // 的 debug TextView（默认 0，见字段注释里的 NPE 风险）
                                        if (sDbgFrag == 1
                                                && caller.contains("smartComposition")
                                                && !Boolean.TRUE.equals(p.getResult())) {
                                            p.setResult(Boolean.TRUE);
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe Kr.g.c ok (debug_composition_enable 调用者)");
                } catch (Throwable th) {
                    log("!! probe Kr.g.c 失败 " + th);
                }

                // ---- ⑤ 属性后门：debug_composition_index（合成数据）----
                try {
                    XposedHelpers.findAndHookMethod("Kr.g", sCl, "a", String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        String k = (String) p.args[0];
                                        if (!"debug_composition_index".equals(k)) {
                                            return;
                                        }
                                        if (sCycle == 1) {
                                            // ai.smartcomp.cycle=1：每次合成都往后推一格
                                            sDbgIndex = nextPatIndex();
                                        }
                                        p.setResult(String.valueOf(sDbgIndex));
                                        logN("dbgIdx", "Kr.g.a(debug_composition_index) <- "
                                                + sDbgIndex + "  合成 r.a{a=11, b="
                                                + sDbgIndex + "}", 600);
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook Kr.g.a ok (debug_composition_index=" + sDbgIndex + ")");
                } catch (Throwable th) {
                    log("!! hook Kr.g.a 失败 " + th);
                }

                // ---- ⑥ 构图位图安全网：w2.a.m() 图案懒加载 + null 守卫 ----
                // m() 是 w2.a 里**唯一**没有 null 守卫的方法（n()/o()/
                // getComponentDataItem()/p() 都有），也是②③两关的交汇点：
                //   off  -> 直接 return null（Xo()=false，构图框不显示）
                //   ai   -> 遍历 f18198a 找 id == String.valueOf(w2.a.b) 的图案位图
                //           f18198a 为 null 时 → NPE → FragmentSmartComposition 崩
                // 所以这里：列表空就懒加载，加载完仍 null 就拦下返回 null。
                //
                // ★ 10-03 实测踩坑：第一版只试了一次（tried 标志），而第一次 m()
                //   发生在 21:06:36.770，i5.g 实例是 21:06:37.034 才被 ③ 缓存到的
                //   —— 早了 260ms，DI 当时也还没注册 → mgr==null → 静默 return，
                //   连日志都没有，后面 6 次因为 tried=1 全部跳过。
                //   改成：m() 每次进来只要列表还空就重试（限 6 次），并把每一步都打出来。
                try {
                    XposedHelpers.findAndHookMethod(C_SEL, sCl, "m", int.class,
                            new XC_MethodHook() {
                                final int[] attempts = {0};

                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        Object list = readPatternList(p.thisObject);
                                        boolean empty = list == null
                                                || ((java.util.Collection) list).isEmpty();
                                        if (empty && attempts[0] < 6) {
                                            attempts[0]++;
                                            ensurePatternLoaded(p.thisObject, attempts[0]);
                                            list = readPatternList(p.thisObject);
                                            empty = list == null
                                                    || ((java.util.Collection) list).isEmpty();
                                        }
                                        if (list == null) {
                                            p.setResult(null);
                                            log("w2.a.m(): 图案列表仍为 null → 返回 null"
                                                    + "（拦下 NPE，构图框本次不显示）");
                                        } else if (empty) {
                                            log("w2.a.m(): 图案列表空（0 条）→ m() 自己会"
                                                    + " return null，构图框本次不显示");
                                        }
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        logN("selBitmap", "w2.a.m(" + p.args[0] + ") -> "
                                                + (p.getResult() == null
                                                        ? "null（不显示）" : "位图（构图框显示）"),
                                                60);
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook w2.a.m ok (图案懒加载 + null 守卫，compose=" + sCompose + ")");
                } catch (Throwable th) {
                    log("!! hook w2.a.m 失败 " + th);
                }

                // ---- ⑦ 探针：w2.a.r() —— 图案列表真正被填充的唯一入口 ----
                //   调用方只有 i5.g.Pn()。它被调过 = 图案已加载；
                //   一次都没出现 = Pn() 的事件时机没走到，⑥ 的懒加载就是主路径。
                try {
                    XposedHelpers.findAndHookMethod(C_SEL, sCl, "r",
                            java.util.ArrayList.class, java.util.ArrayList.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        java.util.ArrayList a1 =
                                                (java.util.ArrayList) p.args[0];
                                        java.util.ArrayList a2 =
                                                (java.util.ArrayList) p.args[1];
                                        log("★ w2.a.r() 图案加载：AI图案=" + (a1 == null
                                                ? "null" : a1.size()) + " 条, 创意项="
                                                + (a2 == null ? "null" : a2.size()) + " 条"
                                                + "  caller=" + dbgCaller());
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("probe w2.a.r ok (图案加载观测)");
                } catch (Throwable th) {
                    log("!! probe w2.a.r 失败 " + th);
                }

                // ---- ⑧ 构图框显示总闸：只在 AI帮拍(168) 且用户开关是 ON ----
                //   10-03 21:2x 用户实测：框出来了，但普通拍照也出、开关关掉也出。
                //   原因：fragment/smartComposition/v1/a.is() 根本不读「智能构图」
                //   主开关，它只看 F.d0 / F.Q / Xo() 三道，而这三道我们全给抬了。
                //
                //   做法：进 is() 先算好 sHideFrame，再由 F.d0 的 hook 返回 false，
                //   于是 is() 自己走它原生的 if (!F.d0) { setVisibility(8); return; }
                //   把框收掉 —— 不反射任何 View 字段，不改原逻辑一行。
                try {
                    XposedHelpers.findAndHookMethod(
                            "com.android.camera.fragment.smartComposition.v1.a", sCl,
                            "is", new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    sInIs = true;
                                    sIsThread = Thread.currentThread().getId();
                                    sFragment = p.thisObject;
                                    try {
                                        if (sCompose != 1) {
                                            sHideFrame = false;
                                            return;
                                        }
                                        int mode = ((Integer) XposedHelpers.getObjectField(
                                                p.thisObject, "mCurrentMode")).intValue();
                                        boolean sw = isSmartSwitchOn(MODE_AI_HELP);
                                        // 只在 AI帮拍(168) 显示；普通拍照(163) 一律收掉
                                        sHideFrame = (mode != MODE_AI_HELP) || !sw;
                                        long nowMs = android.os.SystemClock
                                                .elapsedRealtime();
                                        if (nowMs - sGateLogMs > 400) {
                                            sGateLogMs = nowMs;
                                            log("构图框总闸: is() mode=" + mode + " 开关="
                                                    + (sw ? "ON" : "OFF") + " → "
                                                    + (sHideFrame ? "隐藏" : "显示"));
                                        }
                                    } catch (Throwable th) {
                                        sHideFrame = false;
                                        err(th);
                                    }
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    sInIs = false;
                                    sIsThread = -1;
                                }
                            });
                    log("hook FragmentSmartComposition.is ok (显示总闸：仅 AI帮拍 + 开关 ON)");
                } catch (Throwable th) {
                    log("!! hook is() 失败 " + th);
                }

                // ---- ⑨ 开关切换后立刻刷新构图框 ----
                //   is() 只在 provideAnimateElement / ci() 里被调，用户在面板上按开关
                //   不一定触发它 → 会出现「关了开关框还在」。这里在写值后主动补一次。
                try {
                    XposedHelpers.findAndHookMethod("com.android.camera.data.data.c", sCl,
                            "setComponentValue", int.class, String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        Object self = p.thisObject;
                                        if (self == null
                                                || !C_COMP.equals(self.getClass().getName())) {
                                            return;   // 只关心智能构图开关这一个组件
                                        }
                                        int mode = ((Integer) p.args[0]).intValue();
                                        if (mode != MODE_PHOTO && mode != MODE_AI_HELP) {
                                            return;
                                        }
                                        Object frag = sFragment;
                                        if (frag == null) {
                                            return;
                                        }
                                        log("开关写入 u2.H.setComponentValue(" + mode + ", "
                                                + p.args[1] + ") → 主动刷 is()");
                                        XposedHelpers.callMethod(frag, "is");
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook c.setComponentValue ok (u2.H 开关切换即时刷新构图框)");
                } catch (Throwable th) {
                    log("!! hook setComponentValue 失败 " + th);
                }

                // ---- ⑩ 构图框上的「刷新」按钮：点了要真的换图 ----
                //   10-03 21:4x 用户实测：点击是有响应的（logcat 里
                //   MCAM_FragmentSmartComposition: onClickRefresh / onClickClose 都打了），
                //   但画面永远是同一张 —— 因为整条数据是我们合成的固定 index=1，
                //   刷新触发的重分析最后还是读回 debug_composition_index=1。
                //
                //   做法：把 sDbgIndex 变成**活的轮转状态**
                //     before：先把索引推到下一格 → 官方刷新链路读到的就是新索引
                //     after ：直接把 w2.a.b 写成新索引并补调一次 is()，立刻换图，
                //              不用等异步分析回来
                //   于是刷新按钮 = 在 id 1..48 的 AI 模板里循环换图。
                try {
                    XposedHelpers.findAndHookMethod(
                            "com.android.camera.fragment.smartComposition.v1.a", sCl,
                            "hs", new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        sDbgIndex = nextPatIndex();
                                        log("★ 构图框刷新：索引 → " + sDbgIndex);
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam p)
                                        throws Throwable {
                                    try {
                                        applyIndexNow();
                                    } catch (Throwable th) {
                                        err(th);
                                    }
                                }
                            });
                    log("hook FragmentSmartComposition.hs ok (刷新按钮换图)");
                } catch (Throwable th) {
                    log("!! hook hs() 失败 " + th);
                }
            }

            log("ai.smartcomp=" + sLevel + " 生效：只抬 V3 走 v1"
                    + (sLevel >= 6 ? "，补判 F.w(163/168)=true + isSwitchOn 出口改判" : "")
                    + (sLevel >= 7 ? "，抬 S0/F.d0/M 三门 + debug_composition_* 合成后门(index="
                            + sDbgIndex + ") + 构图框可见化(compose=" + sCompose
                            + "：构图项 off->ai、m() 图案懒加载+null 守卫)" : "")
                    + "，submitAiComposition 不拦（v1 需要它）");
            return;
        }

        // ① 能力门：autoCropVersion == 2
        if (sLevel >= 1) {
            try {
                XposedHelpers.findAndHookMethod(C_H, sCl, "U3", C_G, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try {
                            if (!gateOpen()) {
                                return;   // 用户关了 → 不抬 autoCropVersion，相机走原生
                            }
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
                                if (!gateOpen()) {
                                    return;   // 开关关了 → 别拦 submitAiComposition，放行原生
                                }
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
                            if (!gateOpen()) {
                                return;   // 用户关了 → 比例门不动
                            }
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
                                    if (!gateOpen()) {
                                        return;   // 用户关了 → 不下发 autoCropEnable
                                    }
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
                // dex 真签名是 R(Ljava/lang/Object;) -> V（jadx 把参数收窄成了
                // com.android.camera.data.data.C，拿它去查 getDeclaredMethod 必然
                // NoSuchMethodError —— 这道 hook 在档位 4 上从来没生效过）。
                XposedHelpers.findAndHookMethod(C_COMP, sCl, "R", Object.class, new XC_MethodHook() {
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

        // ============================================================
        // ★ ai.smartcomp = 8：v2 引导环（3815）★
        //  上面 1..4 四道门抬完之后，u2.H 的 reInit 走原生通路把 f17374a 置 true：
        //     · x.z0() = H.f17374a = true  → 注册 3815 引导环（else-if 分支）
        //     · F.d0(168) 保持原生 false（式里硬编码 i9==163）
        //                                  → 3813 PIP 样张框**不注册**（用户要的回退）
        //     · !V3（原生 false，本档刻意不抬）且 U3 → appendInterceptor 选 s6.s0
        //     · U3 → updateSmartComposition 走 v2 分支并下发会话参数
        //  但 v2 的数据源 com.xiaomi.camera.autoCrop.autoCropData 本机 HAL 没注册，
        //  s6.s0.tagValueAutomaticParsed 永远不跑 → f17140c 恒 null →
        //  每帧 "composition data: Exception!"，引导环拿不到 Rect 就永远不 show。
        //  这里在 consumeResultOnMainThreadIfDataChanged 的 before 里按 previewSize
        //  合成一份 float[6]，让状态机跑起来。
        // ============================================================
        if (sLevel >= 8) {
            log("ai.smartcomp=8 生效：v2 引导环 3815"
                    + "（门1..4=U3/t0/S0/R；不抬 V3、不强抬 F.d0 → PIP 框不注册）"
                    + (sRing == 1 ? "，合成 autoCropData" : "，但 ring=0 不合成（对照组）"));

            // ★ 档8 **不再**强制挡 submitAiComposition（10-04 14:16 实测教训）★
            //   旧做法：挡掉 A3.g.w() 想避免「AI 分析不回来 → onClick ignore
            //   while analyzing → 点了没反应」。结果正好相反：
            //     submitAiTuning 发出 → 紧接着 w() 被我们拦掉 →
            //     onAiEffectResult 永远不回来 → setAnalyzeFinish 一直 false →
            //     FragmentAi.onClick 全被 "ignore while analyzing" 吞掉 →
            //     **智能构图开关点不动、效果识别一直转圈卡住**。
            //   拦不拦只由 ai.smartcomp.submit（上面 ⑤，带 gateOpen）决定。

            if (sRing != 1) {
                return;
            }
            try {
                XposedHelpers.findAndHookMethod(C_S0_V2, sCl,
                        "consumeResultOnMainThreadIfDataChanged",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                try {
                                    if (!gateOpen()) {
                                        // 用户关了智能构图 → 不喂假数据；把之前合成的那份
                                        // 清掉，免得残留一帧把引导环又顶出来
                                        Object stale = XposedHelpers.getObjectField(
                                                p.thisObject, F_V2_DATA);
                                        if (stale instanceof float[]) {
                                            XposedHelpers.setObjectField(
                                                    p.thisObject, F_V2_DATA, null);
                                            logN("ringoff",
                                                    "开关=关 → 清掉合成的 autoCropData，交回原生",
                                                    300);
                                        }
                                        return;
                                    }
                                    Object cur = XposedHelpers.getObjectField(p.thisObject, F_V2_DATA);
                                    boolean hasData = cur instanceof float[] && ((float[]) cur).length == 6;
                                    // ★ 主体新鲜度 ★
                                    //   ringTick 在主体消失后仍会**沿用上一帧最长 800ms**（防人脸闪一下就丢），
                                    //   这段时间 cur 依然有效，但真实主体已经没了。
                                    //   所以判「有没有主体」只能看 sRingSeen，绝不能只看 cur。
                                    boolean fresh = hasData
                                            && (android.os.SystemClock.elapsedRealtime() - sRingSeen) < 800L;
                                    // ★ 没识别到主体 → 彻底静默，原生一次都不许跑 ★
                                    //   只「不喂数据」是没用的：原生照样会拿空 RectF 去刷 UI，
                                    //   状态机就走 compositionShow → Completed → Ignore → Idle 的循环
                                    //   （约 1~2 秒一圈），表现正是「构图完成」提示条和白色方块
                                    //   反复出现又消失。只要这里直接拦掉原生，UI 不再被刷新，就停了。
                                    //   ring.idle=1 恢复旧行为；ring.src=3（固定矩形）必须照常合成。
                                    if (sRingIdle != 1 && sRingSrc != 3 && !fresh) {
                                        p.setResult(null);   // 原生不执行 → UI 不刷新 → 状态机停住
                                        logN("ringidle",
                                                "无主体 → 静默（拦掉原生，UI 不再刷新）", 30);
                                        return;
                                    }
                                    if (hasData) {
                                        float[] d = (float[]) cur;
                                        // 证明场景数据真的被相机自己的 consumeResult 吃掉了
                                        logN("v2feed", "consumeResult 吃到场景数据 rect=("
                                                + d[0] + "," + d[1] + "," + d[2] + "," + d[3]
                                                + ") zoom=" + d[4] + " tip=" + (int) d[5], 150);
                                        return;   // ringTick 刚写好的，别覆盖
                                    }
                                    Object mgr = XposedHelpers.getObjectField(p.thisObject, F_V2_MGR);
                                    if (mgr == null) {
                                        logN("ringnosize", "v2 合成跳过：manager 还没建", 0);
                                        return;
                                    }
                                    Object size = null;
                                    try {
                                        size = XposedHelpers.getObjectField(mgr, F_J_SIZE);
                                    } catch (Throwable ignore) {
                                    }
                                    if (!(size instanceof android.util.Size)) {
                                        try {
                                            size = XposedHelpers.getObjectField(mgr, F_J_SIZE_JADX);
                                        } catch (Throwable ignore) {
                                        }
                                    }
                                    if (!(size instanceof android.util.Size)) {
                                        logN("ringnosize",
                                                "v2 合成跳过：previewSize 还没喂进来 (size=" + size + ")",
                                                600);
                                        return;
                                    }
                                    android.util.Size ps = (android.util.Size) size;
                                    float pw = ps.getWidth();
                                    float ph = ps.getHeight();
                                    if (pw <= 0f || ph <= 0f) {
                                        return;
                                    }
                                    // 源码：new RectF(x, y, x + w, y + h) —— w/h 是**偏移量**
                                    float[] synth = new float[]{
                                            sRingX * pw, sRingY * ph,
                                            sRingW * pw, sRingH * ph,
                                            sRingZoom, sRingTip};
                                    XposedHelpers.setObjectField(p.thisObject, F_V2_DATA, synth);
                                    logN("ringdata",
                                            "v2 合成 autoCropData preview=" + ps
                                                    + " rect=(" + synth[0] + "," + synth[1]
                                                    + "," + synth[2] + "," + synth[3] + ")"
                                                    + " zoom=" + sRingZoom + " tip=" + sRingTip,
                                            600);
                                } catch (Throwable th) {
                                    err(th);
                                }
                            }
                        });
                log("hook s6.s0.consumeResultOnMainThreadIfDataChanged ok（合成 v2 数据）");
            } catch (Throwable th) {
                log("!! hook s6.s0.consumeResult 失败 " + th);
            }

            // ★ 场景驱动：每帧从 CaptureResult 的人脸/对焦区算出取景框 ★
            //   挂 parseComplexValueManually(CaptureResult) 的 after —— 它是
            //   onCaptureResultNext 里唯一带 CaptureResult 的一环，且跑在
            //   tagValueAutomaticParsed()（把 float[6] 清成 null）之后、
            //   acceptResult() 之前，写进去正好被 consumeResult 读到。
            //   同时在这里推进连续变焦（R6.C0.b().W4）。
            try {
                XposedHelpers.findAndHookMethod(C_S0_V2, sCl,
                        "parseComplexValueManually",
                        android.hardware.camera2.CaptureResult.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                                android.hardware.camera2.CaptureResult res =
                                        (p.args != null && p.args.length > 0
                                                && p.args[0] instanceof android.hardware.camera2.CaptureResult)
                                                ? (android.hardware.camera2.CaptureResult) p.args[0]
                                                : null;
                                ringTick(p.thisObject, res);
                            }
                        });
                log("hook s6.s0.parseComplexValueManually ok（场景驱动取景框 + 连续变焦，src="
                        + sRingSrc + " pad=" + sRingPad + " fill=" + sRingFill
                        + " autozoom=" + sRingAutoZoom + " smooth=" + sRingSmooth
                        + " zoomms=" + sRingZoomMs + " zmax=" + sRingZMax
                        + " exp=" + sRingExp + ")");
            } catch (Throwable th) {
                log("!! hook s6.s0.parseComplexValueManually 失败 " + th);
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

    // ===================== 档8：场景驱动取景框 + 连续变焦 =====================

    /** 从 h5.J 上读 previewSize（dex 真名 e / jadx 名 f12761e） */
    private static android.util.Size readPreviewSize(Object mgr) {
        if (mgr == null) {
            return null;
        }
        Object v = null;
        try {
            v = XposedHelpers.getObjectField(mgr, F_J_SIZE);
        } catch (Throwable ignore) {
        }
        if (!(v instanceof android.util.Size)) {
            try {
                v = XposedHelpers.getObjectField(mgr, F_J_SIZE_JADX);
            } catch (Throwable ignore) {
            }
        }
        return (v instanceof android.util.Size) ? (android.util.Size) v : null;
    }

    /**
     * 从 CaptureResult 里挑主体框（**传感器坐标**，与 SCALER_CROP_REGION 同一坐标系）。
     * 返回 null = 这一帧没有可用主体。
     */
    private static android.graphics.Rect ringSubject(android.hardware.camera2.CaptureResult res,
                                                     android.graphics.Rect crop,
                                                     android.graphics.Rect lock) {
        if (res == null || crop == null || sRingSrc == 3) {
            return null;
        }
        if (sRingSrc != 2) {
            android.graphics.Rect f = ringFace(res, lock);
            if (f != null) {
                return f;
            }
            if (sRingSrc == 1) {
                return null;
            }
        }
        return ringAf(res, crop);
    }

    /** 人脸：并集（得分 ≥50 的都算一组；一个都不到就用得分最高的那个） */
    private static android.graphics.Rect ringFace(android.hardware.camera2.CaptureResult res,
                                                  android.graphics.Rect lock) {
        android.hardware.camera2.params.Face[] faces;
        try {
            faces = res.get(android.hardware.camera2.CaptureResult.STATISTICS_FACES);
        } catch (Throwable ignore) {
            return null;
        }
        if (faces == null || faces.length == 0) {
            return null;
        }
        int bestIdx = -1;
        int bestScore = -1;
        for (int i = 0; i < faces.length; i++) {
            android.graphics.Rect r = faces[i] == null ? null : faces[i].getBounds();
            if (r == null || r.width() <= 0 || r.height() <= 0) {
                continue;
            }
            int sc = faces[i].getScore();
            if (sc > bestScore) {
                bestScore = sc;
                bestIdx = i;
            }
        }
        if (bestIdx < 0 || bestScore < 20) {
            return null;
        }
        // ★ 主体锁定 ★（ring.lock=1）
        //   多张脸时上面这段「挑分数最高的」每帧是**独立**算的：分数一翻转，bestIdx
        //   就从画面这头跳到那头 → 外层的中值/EMA/IoU 门再好也挡不住「整框换地方」
        //   （10-04 14:50 实测 subj 每秒在 [1502,1085] 和 [1585,2285] 两块之间来回，
        //    wantZoom 跟着 1.52 ↔ 1.13 来回拽）。
        //   有上一帧主体时改成「挑和它重合/离它最近的那张」，人没动就永远不换脸。
        if (sRingLock == 1 && lock != null && lock.width() > 0 && lock.height() > 0) {
            float best = -1e9f;
            int pick = -1;
            float rad = Math.max(lock.width(), lock.height());
            for (int i = 0; i < faces.length; i++) {
                android.graphics.Rect r = faces[i] == null ? null : faces[i].getBounds();
                if (r == null || r.width() <= 0 || r.height() <= 0) {
                    continue;
                }
                if (faces[i].getScore() < 20) {
                    continue;
                }
                float iou = ringIou(lock.left, lock.top, lock.width(), lock.height(),
                        r.left, r.top, r.width(), r.height());
                float dx = r.centerX() - lock.centerX();
                float dy = r.centerY() - lock.centerY();
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                // 既不重合又离得远的不参与，免得三张脸里反而挑了个最远的
                if (iou <= 0f && dist > rad * 1.5f) {
                    continue;
                }
                float s = iou * 2f - dist / (rad + 1f);
                if (s > best) {
                    best = s;
                    pick = i;
                }
            }
            if (pick >= 0) {
                if (pick != bestIdx) {
                    logN("ringlock", "主体锁定：本帧最高分是 #" + bestIdx
                            + "，仍认上一帧那张 #" + pick, 60);
                }
                bestIdx = pick;
            }
        }
        // 分数太低的脸基本是误检，别让它把框带偏（按**被选中**那张算，不是全组最高）
        int th = Math.max(30, (int) (faces[bestIdx].getScore() * 0.5f));
        // ★ 只把**挨在一起**的脸并成一组（同一个人 / 同一群人）。
        //   原来是「所有达标的脸全 union」：背景里另有一张脸时框会被拉成一大片，
        //   变焦又按这个大框反算目标倍率 → 一会儿拉近一会儿拉远，看着就乱
        //   （10-04 用户反馈「有一点点乱构图」）。间距超过「半张脸」的才算另一组。
        android.graphics.Rect u = new android.graphics.Rect(faces[bestIdx].getBounds());
        boolean grew = true;
        int guard = 0;
        while (grew && guard++ < 16) {
            grew = false;
            for (int i = 0; i < faces.length; i++) {
                if (i == bestIdx) {
                    continue;
                }
                android.graphics.Rect r = faces[i] == null ? null : faces[i].getBounds();
                if (r == null || r.width() <= 0 || r.height() <= 0) {
                    continue;
                }
                if (i != bestIdx && faces[i].getScore() < th) {
                    continue;
                }
                if (u.intersects(r.left, r.top, r.right, r.bottom)) {
                    if (!u.contains(r)) {
                        u.union(r);
                        grew = true;
                    }
                    continue;
                }
                int gapX = Math.max(0, Math.max(u.left - r.right, r.left - u.right));
                int gapY = Math.max(0, Math.max(u.top - r.bottom, r.top - u.bottom));
                int gap = gapX > gapY ? gapX : gapY;
                if (gap <= Math.max(u.width(), u.height()) / 2) {
                    u.union(r);
                    grew = true;
                }
            }
        }
        return u;
    }

    /**
     * 中值滤波：把最近 sRingMed 帧的原始框推进环形缓冲，返回逐分量中值。
     *   · 孤立尖峰（单帧 2 倍宽）直接被中值吃掉，EMA 不会再被它拽走
     *   · 主体真动了时，中值只滞后 (med-1)/2 帧 —— 5 帧 @33fps ≈ 60ms，看不出
     *   · 只喂给「时域门」和「EMA」用；sRingRect 的 clamp 仍在外层做
     */
    private static float[] ringMedPush(float x, float y, float w, float h) {
        if (sRingMed <= 1 || sMedBuf == null || sMedBuf.length != sRingMed) {
            sMedBuf = new float[sRingMed][];
            for (int i = 0; i < sRingMed; i++) {
                sMedBuf[i] = new float[4];
            }
            sMedI = 0;
            sMedC = 0;
        }
        float[] b = sMedBuf[sMedI];
        b[0] = x;
        b[1] = y;
        b[2] = w;
        b[3] = h;
        sMedI = (sMedI + 1) % sRingMed;
        if (sMedC < sRingMed) {
            sMedC++;
        }
        if (sMedC < 3) {
            return new float[]{x, y, w, h};
        }
        float[] out = new float[4];
        float[] vals = new float[sMedC];
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i < sMedC; i++) {
                vals[i] = sMedBuf[i][c];
            }
            for (int i = 1; i < sMedC; i++) {
                float v = vals[i];
                int j = i - 1;
                while (j >= 0 && vals[j] > v) {
                    vals[j + 1] = vals[j];
                    j--;
                }
                vals[j + 1] = v;
            }
            out[c] = vals[sMedC / 2];
        }
        return out;
    }

    /** 主体丢失/重来时清历史，别拿上一个人的框去中和这一个人的 */
    private static void ringMedReset() {
        sMedI = 0;
        sMedC = 0;
    }

    /** 矩形交并比（时域门用） */
    private static float ringIou(float ax, float ay, float aw, float ah,
                                 float bx, float by, float bw, float bh) {
        float ix = Math.max(0f, Math.min(ax + aw, bx + bw) - Math.max(ax, bx));
        float iy = Math.max(0f, Math.min(ay + ah, by + bh) - Math.max(ay, by));
        float inter = ix * iy;
        float uni = aw * ah + bw * bh - inter;
        return uni <= 0f ? 0f : inter / uni;
    }

    /**
     * ai.smartcomp.ring.probe = 1 → 把这一帧 CaptureResult 里所有非空字段 dump 一次
     * （每个进程只打一次，打进 camport_fix.log）。用来摸清本机到底还有哪些可用的
     * 识别信号（场景标签 / 深度 / 运动速度 / AE 区 / 人脸数 / ...），
     * 再决定 ringSubject 要不要补别的信号。
     */
    private static void ringProbe(android.hardware.camera2.CaptureResult res) {
        if (sRingProbe != 1 || sProbed || res == null) {
            return;
        }
        sProbed = true;
        try {
            log("probe 开始 dump（心跳/ringTick 触发）");
            int n = 0;
            int skipped = 0;
            // 分批发：logcat 单条 4076 字节上限、log() 每秒 300 条上限，
            // 整坨一次发会被静默丢掉（10-04 11:55 实测：全无输出）
            StringBuilder chunk = new StringBuilder();
            chunk.append("probe CaptureResult:");
            for (android.hardware.camera2.CaptureResult.Key<?> k : res.getKeys()) {
                String kn = k.getName();
                // 噪声通道：标定类/色彩矩阵/畸变，和「识别主体」无关，
                // 不滤掉的话它们会把上限顶满，把真正要找的 com.* / face 挤出去
                if (isProbeNoise(kn)) {
                    skipped++;
                    continue;
                }
                Object v;
                try {
                    v = res.get(k);
                } catch (Throwable ignore) {
                    continue;
                }
                if (v == null) {
                    continue;
                }
                String vs = probeVal(kn, v);
                if (vs.length() > 300) {
                    vs = vs.substring(0, 300) + "...";
                }
                String line = '\n' + "  " + kn + " = " + vs;
                if (chunk.length() + line.length() > 2200) {
                    log(chunk.toString());
                    chunk.setLength(0);
                    chunk.append("+");
                }
                chunk.append(line);
                n++;
                if (n >= 900) {
                    chunk.append("\n  !! 达到 900 上限，后续 key 未列出");
                    break;
                }
            }
            String tail = "\n  (" + n + " non-null keys, "
                    + skipped + " noise keys skipped)";
            log(chunk.toString());
            log(tail);
        } catch (Throwable th) {
            log("probe 失败 " + th);
        }
    }

    /**
     * 定标 / 统计 / ISP 配置类 key，对「找主体」没用。
     * 注意 key 全名是 `android.colorCorrection.xxx`，用 startsWith("colorCorrection.")
     * 一条都匹配不上（10-04 实测：只滤掉 5 个，400 上限全被噪声占满）——
     * 这里改成对整名做 contains，同时保留人脸/身体/运动/场景/变焦相关通道。
     */
    private static boolean isProbeNoise(String kn) {
        // 必须留下的（contains 一票否决）
        if (kn.contains("face") || kn.contains("Face") || kn.contains("body")
                || kn.contains("Body") || kn.contains("motion") || kn.contains("Motion")
                || kn.contains("scene") || kn.contains("Scene") || kn.contains("asd")
                || kn.contains("misd") || kn.contains("segment") || kn.contains("gme")
                || kn.contains("objectTracking") || kn.contains("zoom") || kn.contains("Zoom")
                || kn.contains("crop") || kn.contains("Crop") || kn.contains("afDistance")
                || kn.contains("Regions") || kn.contains("autoframing")) {
            return false;
        }
        return kn.contains("colorCorrection")
                || kn.contains("tonemap") || kn.contains("Tonemap")
                || kn.contains("lensShading") || kn.contains("noiseReduction")
                || kn.contains("hotPixel") || kn.contains("blackLevel")
                || kn.contains("intrinsicCalibration") || kn.contains("poseRotation")
                || kn.contains("poseTranslation") || kn.contains("Distortion")
                || kn.contains("neutralColorPoint") || kn.contains("greenSplit")
                || kn.contains("testPattern") || kn.contains("Calibration")
                || kn.startsWith("android.sensor.") || kn.startsWith("android.lens.")
                || kn.startsWith("android.jpeg.") || kn.startsWith("android.flash.")
                || kn.startsWith("android.shading.") || kn.startsWith("android.edge")
                || kn.contains("jpegquantizationtables") || kn.contains("sessionParameters")
                || kn.contains("properties_") || kn.contains("frameControl")
                || kn.contains("statsControl") || kn.contains("histogram")
                || kn.contains("TuningMode") || kn.contains("qtimer")
                || kn.contains("manualExposure") || kn.contains("strobeControl")
                || kn.contains("sensorbps") || kn.contains("stats.internal")
                || kn.contains("qtistatic.stats") || kn.contains("icaconfigs")
                || kn.contains("ISP") || kn.contains("CSTInfo")
                || kn.contains("DynamicMargin") || kn.contains("exposure_metering")
                || kn.contains("iso_exp_priority") || kn.contains("StreamFormatInfo")
                || kn.contains("rawcbinfo") || kn.contains("multiFrameData")
                || kn.contains("AFAlgo") || kn.contains("debugDumpConfig")
                || kn.contains("RefCropSize") || kn.contains("OEMJPEG")
                || kn.contains("AWBFrameControl") || kn.contains("AEC")
                || kn.contains("PeerInfo") || kn.contains("Sync =")
                || kn.contains("is_flash_snapshot") || kn.contains("is_lls_needed")
                || kn.contains("DCGMode") || kn.contains("ExposureCount");
    }

    /** 把数组类的 value 排版成人能读的样子（Face[] 打出来永远是个 @hash） */
    private static String probeVal(String kn, Object v) {
        if (v instanceof android.hardware.camera2.params.Face[]) {
            android.hardware.camera2.params.Face[] fs =
                    (android.hardware.camera2.params.Face[]) v;
            if (fs.length == 0) {
                return "Face[0]";
            }
            StringBuilder sb = new StringBuilder("Face[").append(fs.length).append(']');
            int lim = Math.min(fs.length, 8);
            for (int i = 0; i < lim; i++) {
                android.hardware.camera2.params.Face f = fs[i];
                if (f == null) {
                    sb.append(" null");
                    continue;
                }
                sb.append(' ').append(f.getBounds().toShortString())
                        .append('@').append(f.getScore());
            }
            if (fs.length > lim) {
                sb.append(" ...");
            }
            return sb.toString();
        }
        if (v instanceof android.hardware.camera2.params.MeteringRectangle[]) {
            android.hardware.camera2.params.MeteringRectangle[] ms =
                    (android.hardware.camera2.params.MeteringRectangle[]) v;
            StringBuilder sb = new StringBuilder("Metering[").append(ms.length).append(']');
            int lim = Math.min(ms.length, 6);
            for (int i = 0; i < lim; i++) {
                sb.append(' ').append(ms[i] == null ? "null"
                        : ms[i].getRect().toShortString());
            }
            return sb.toString();
        }
        if (v instanceof byte[]) {
            return "byte[" + ((byte[]) v).length + "]";
        }
        if (v instanceof int[]) {
            return intArrStr((int[]) v);
        }
        if (v instanceof float[]) {
            return floatArrStr((float[]) v);
        }
        if (v instanceof double[]) {
            double[] d = (double[]) v;
            StringBuilder sb = new StringBuilder();
            int lim = Math.min(d.length, 8);
            for (int i = 0; i < lim; i++) {
                sb.append(' ').append(d[i]);
            }
            return "double[" + d.length + "]" + sb;
        }
        String s = String.valueOf(v);
        if (s.startsWith("[L") && s.indexOf('@') > 0) {
            // 未知对象数组：至少给出长度，别只给一个 @hash
            try {
                int len = java.lang.reflect.Array.getLength(v);
                return s.substring(0, s.indexOf('@')) + "@" + len;
            } catch (Throwable ignore) {
            }
        }
        return s;
    }

    private static String intArrStr(int[] a) {
        StringBuilder sb = new StringBuilder("int[").append(a.length).append(']');
        int lim = Math.min(a.length, 8);
        for (int i = 0; i < lim; i++) {
            sb.append(' ').append(a[i]);
        }
        return sb.toString();
    }

    private static String floatArrStr(float[] a) {
        StringBuilder sb = new StringBuilder("float[").append(a.length).append(']');
        int lim = Math.min(a.length, 8);
        for (int i = 0; i < lim; i++) {
            sb.append(' ').append(a[i]);
        }
        return sb.toString();
    }

    /** 对焦区：只取「明显小于整幅」的那个（默认 AF 区往往是全画面，没意义） */
    private static android.graphics.Rect ringAf(android.hardware.camera2.CaptureResult res,
                                                android.graphics.Rect crop) {
        android.hardware.camera2.params.MeteringRectangle[] af;
        try {
            af = res.get(android.hardware.camera2.CaptureResult.CONTROL_AF_REGIONS);
        } catch (Throwable ignore) {
            return null;
        }
        if (af == null || af.length == 0) {
            return null;
        }
        long cropArea = (long) crop.width() * (long) crop.height();
        if (cropArea <= 0) {
            return null;
        }
        android.graphics.Rect pick = null;
        long pickArea = Long.MAX_VALUE;
        for (int i = 0; i < af.length; i++) {
            android.graphics.Rect r = af[i] == null ? null : af[i].getRect();
            if (r == null || r.width() <= 0 || r.height() <= 0) {
                continue;
            }
            long a = (long) r.width() * (long) r.height();
            if (a * 100L / cropArea > 55) {
                continue;   // 覆盖 >55% 画面 = 未对焦到具体主体
            }
            if (a < pickArea) {
                pickArea = a;
                pick = r;
            }
        }
        return pick;
    }

    /**
     * 每帧一算：主体框 → 外扩 → EMA 平滑 → 写进 s6.s0 的 float[6]；
     * 顺带按「主体应占画面比例」反算目标倍率并**连续推进**变焦。
     * 挂在 s6.s0.parseComplexValueManually(CaptureResult) 的 after 上
     * —— 它在 onCaptureResultNext 里紧跟着 tagValueAutomaticParsed 之后跑，
     * 而 tagValueAutomaticParsed 一定把 float[6] 清成 null（本机没这个 tag）。
     */
    private static void ringTick(Object self, android.hardware.camera2.CaptureResult res) {
        if (sRingProbe == 1) {
            ringProbe(res);
        }
        if (!gateOpen()) {
            // 用户关了智能构图 → 不算框、不驱动变焦；把变焦状态清干净，
            // 否则重开时会拿着上一轮的 cmd / want 直接接着跳。
            sRingWant = 0f;
            sRingZoomCmd = 0f;
            sRingZoomPause = 0L;
            sRingWantAt = 0L;
            return;
        }
        if (sRing != 1 || sRingSrc == 3) {
            return;
        }
        Object mgr = XposedHelpers.getObjectField(self, F_V2_MGR);
        android.util.Size ps = readPreviewSize(mgr);
        if (ps == null) {
            logN("ringnosize", "ringTick 跳过：previewSize 还没喂进来", 600);
            return;
        }
        float pw = ps.getWidth();
        float ph = ps.getHeight();
        if (pw <= 0f || ph <= 0f) {
            return;
        }

        android.graphics.Rect crop = null;
        float curZoom = 1f;
        if (res != null) {
            try {
                crop = res.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION);
            } catch (Throwable ignore) {
            }
            try {
                Float fz = res.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO);
                if (fz != null && fz.floatValue() > 0f) {
                    curZoom = fz.floatValue();
                }
            } catch (Throwable ignore) {
            }
        }
        if (crop == null || crop.width() <= 0 || crop.height() <= 0) {
            logN("ringcrop", "ringTick 跳过：SCALER_CROP_REGION 拿不到", 600);
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        // ★ 主体锁定：把上一帧**被选中**的脸（传感器坐标）递给选脸逻辑。
        //   传感器坐标以 SCALER_CROP_REGION 为基准、不随变焦变，所以跨帧直接可比。
        //   超过 800ms 没再确认过就放开锁，免得人真走了还死咬着旧位置。
        android.graphics.Rect lock = null;
        if (sRingLock == 1 && sLockRect != null && now - sLockAt <= 800L) {
            lock = new android.graphics.Rect(Math.round(sLockRect[0]), Math.round(sLockRect[1]),
                    Math.round(sLockRect[0] + sLockRect[2]), Math.round(sLockRect[1] + sLockRect[3]));
        }
        android.graphics.Rect sub = ringSubject(res, crop, lock);

        // ★ 剔除不可信主体 ★
        //   检测偶尔会吐出贴边碎块（实测 raw=(-8.6,112,74,69)，宽只有 crop 的 1.8%，
        //   还有 [0,1481][162,2183] 这种被画面左边切掉的）。拿这种框算 frac 会把
        //   目标倍率直接拽走，画面就是「框一跳变焦就抽」。
        //   宽度阈值按 1/curZoom 缩（人脸报告宽 ∝ 1/curZoom），高倍率不误杀。
        if (sub != null) {
            float cw = crop.width();
            float minW = cw * 0.05f / (curZoom > 1f ? curZoom : 1f);
            boolean clipped = sub.left - crop.left <= 1 || sub.top - crop.top <= 1
                    || crop.right - sub.right <= 1 || crop.bottom - sub.bottom <= 1;
            if (sub.width() < minW || (clipped && sub.width() < cw * 0.15f)) {
                logN("ringbadsubj", "ringTick 丢弃不可信主体 " + sub.toShortString()
                        + "（minW=" + (int) minW + (clipped ? "，贴边" : "") + "）", 60);
                sub = null;
            }
        }

        // ---- 变焦目标 ----
        float wantZoom = curZoom;
        float subW = 0f;
        if (sub != null) {
            float sx = pw / (float) crop.width();
            float sy = ph / (float) crop.height();
            float x0 = (sub.left - crop.left) * sx;
            float y0 = (sub.top - crop.top) * sy;
            float w0 = sub.width() * sx;
            float h0 = sub.height() * sy;
            if (w0 > 1f && h0 > 1f) {
                // 外扩
                float ex = w0 * (sRingPad - 1f) * 0.5f;
                float ey = h0 * (sRingPad - 1f) * 0.5f;
                float x = x0 - ex;
                float y = y0 - ey;
                float w = w0 + ex * 2f;
                float h = h0 + ey * 2f;

                // ★ 中值滤波 ★（在时域门之前）：先把单帧尖峰剔掉，门和 EMA 拿到的
                // 就是「过去 N 帧的代表框」，再怎么抖也不会拽歪平滑结果。
                float[] md = ringMedPush(x, y, w, h);
                x = md[0];
                y = md[1];
                w = md[2];
                h = md[3];

                // ★ 时域门 ★
                // 人脸检测是逐帧独立跑的，单帧乱跳会直接把 EMA 带歪（画面上就是框在那儿抽）。
                // 先把上一帧的框按「这一帧应有的变焦缩放」挪到本帧坐标系，再和新框比 IoU；
                // 低于 ring.iou 的先不信，连续 5 帧都不信才承认主体真的变了。
                boolean accept = true;
                if (sPrevRaw != null && sPrevRawCur > 0f && curZoom > 0f) {
                    float k = sPrevRawCur / curZoom;
                    float pcx = sPrevRaw[0] + sPrevRaw[2] * 0.5f;
                    float pcy = sPrevRaw[1] + sPrevRaw[3] * 0.5f;
                    float ecx = pw * 0.5f + (pcx - pw * 0.5f) * k;
                    float ecy = ph * 0.5f + (pcy - ph * 0.5f) * k;
                    float ew = sPrevRaw[2] * k;
                    float eh = sPrevRaw[3] * k;
                    float iou = ringIou(ecx - ew * 0.5f, ecy - eh * 0.5f, ew, eh, x, y, w, h);
                    if (iou < sRingIou) {
                        sGateMiss++;
                        // 「框宽突然变成 <1/2 或 >2 倍」基本只有一种来源：
                        // 人脸丢了、退到对焦区（或反过来）。这两种框的尺寸天生差好几倍，
                        // 放行它 = 目标倍率立刻被拽走 → 拉近拉回地抽。
                        // 多挡 15 帧（≈0.45s）给脸检一个恢复的机会；真换了主体也只晚 0.45s。
                        boolean sizeFlip = sPrevRaw[2] > 1f
                                && (w > sPrevRaw[2] * 2f || w < sPrevRaw[2] * 0.5f);
                        int need = sizeFlip ? 15 : 5;
                        if (sGateMiss < need) {
                            accept = false;
                            logN("ringgate", "时域门挡下一次乱跳 iou=" + iou
                                    + (sizeFlip ? "（尺寸翻转，多挡）" : "")
                                    + " raw=(" + x + "," + y + "," + w + "," + h + ")", 20);
                        } else {
                            sGateMiss = 0;
                        }
                    } else {
                        sGateMiss = 0;
                    }
                }
                if (!accept) {
                    // 保持上一帧的框，但主体还在（sRingSeen 不动，5 帧内一定放行）
                    sRingSeen = now;
                    subW = w0;
                } else {
                    sPrevRaw = new float[]{x, y, w, h};
                    sPrevRawCur = curZoom;
                    sRingSeen = now;
                    subW = w0;
                    // 锁定跟着走：只有被时域门接受的那帧才换锁，
                    // 被挡下的乱跳不会把锁拽走（否则锁本身就成了新的抖动源）
                    sLockRect = new float[]{sub.left, sub.top, sub.width(), sub.height()};
                    sLockAt = now;
                    // EMA 平滑（框跟得上，但不抖）
                    float a = sRingSmooth;
                    if (sRingRect == null) {
                        sRingRect = new float[]{x, y, w, h};
                    } else {
                        sRingRect[0] += (x - sRingRect[0]) * a;
                        sRingRect[1] += (y - sRingRect[1]) * a;
                        sRingRect[2] += (w - sRingRect[2]) * a;
                        sRingRect[3] += (h - sRingRect[3]) * a;
                    }
                    // 框必须待在画面里：贴边 / 出屏是「乱构图」观感的直接来源之一
                    if (sRingRect[2] > pw * 0.92f) {
                        sRingRect[2] = pw * 0.92f;
                    }
                    if (sRingRect[3] > ph * 0.92f) {
                        sRingRect[3] = ph * 0.92f;
                    }
                    if (sRingRect[0] < 0f) {
                        sRingRect[0] = 0f;
                    }
                    if (sRingRect[0] + sRingRect[2] > pw) {
                        sRingRect[0] = pw - sRingRect[2];
                    }
                    if (sRingRect[1] < 0f) {
                        sRingRect[1] = 0f;
                    }
                    if (sRingRect[1] + sRingRect[3] > ph) {
                        sRingRect[1] = ph - sRingRect[3];
                    }
                // 主体占画面比例 → 目标倍率
                if (sRingAutoZoom == 1 && sRingRect[2] / pw > 0.02f) {
                    // ★ 这里是整套算法的正负号所在 ★
                    // w/pw 是「人脸报告空间」里的占宽比。本机实测 SCALER_CROP_REGION
                    // 恒为全幅 [0,0,4096,3072] 不随变焦缩，而 STATISTICS_FACES 的宽
                    // ∝ 1/curZoom（1229×1.0 ≈ 923×1.38 ≈ 768×1.6 ≈ 400×3.2 ≈ 1250）。
                    // 真正显示在屏幕上的占宽比 = 报告占宽比 × cur²（crop 不动 → 可见窗口
                    // 是 cropW/cur，主体物理尺寸是 报告宽×cur）：
                    //     frac_screen = (报告宽 × cur) / (cropW / cur) / cropW × cropW ...
                    //               = 报告占宽比 × cur²
                    // 不补 cur² 的话 frac ∝ 1/cur，want = cur×fill/frac ∝ cur²，
                    // 会一路正反馈拉到 zmax（10-03 23:25 实测：cur 1.0→3.2，want 1.5→6.0）。
                    //
                    // 用**平滑后**的 sRingRect[2] 而不是原始 w0：人脸检测框宽逐帧能
                    // 跳 434→865→1399（10-03 23:45 实测），拿原始值算 want 会让目标倍率
                    // 在 cur×0.5 和 cur×2 两个钳位之间来回打（实测 cur 1.0↔2.5 锯齿）。
                    float frac = sRingRect[2] / pw;
                    if (sRingExp == 1) {
                        frac *= curZoom;
                    } else if (sRingExp >= 2) {
                        frac *= curZoom * curZoom;
                    }
                    if (frac > 0.98f) {
                        frac = 0.98f;
                    }
                    float raw = curZoom * (sRingFill / frac);
                    if (raw < 0.5f) {
                        raw = 0.5f;
                    }
                    if (raw > sRingZMax) {
                        raw = sRingZMax;
                    }
                    // 目标本身也做一次 EMA —— 变焦是执行器，目标抖一下镜头就会来回抽。
                    // ★ 迟滞带：raw 离当前目标不到 ring.wband 就**完全不动**目标。
                    //   没这道门时 raw 逐帧在 1.15 / 1.72 / 1.34 之间乱跳（人脸框宽一变
                    //   frac 就变），EMA 一路跟着走 → 镜头 1.0↔1.2↔1.4 来回抽。
                    // ★ 速率上限：迟滞带只挡得住小幅抖，挡不住 raw 一路单向大跳
                    //   （实测 14:50:14→16 want 1.52→1.13）。先把目标夹在
                    //   「每秒 wrate 个 log 单位」内，再做 EMA → 大跳变被摊成缓动。
                    float dt = 0f;
                    if (sRingWantAt > 0L) {
                        dt = (now - sRingWantAt) / 1000f;
                        if (dt < 0f) {
                            dt = 0f;
                        }
                        if (dt > 0.5f) {
                            dt = 0.5f;
                        }
                    }
                    sRingWantAt = now;
                    if (sRingWant <= 0f) {
                        // 从**当前**倍率起步，不要直接跳到 raw。
                        // 主体丢失/开关重开后 sRingWant 会被清零，这时若直接赋 raw，
                        // 会被下面的 cur×2 钳位夹成一次瞬移（实测 want 直接 1.12→2.0）；
                        // 起点取 curZoom 就能顺着速率上限滑过去。
                        sRingWant = curZoom;
                    } else if (sRingWantBand <= 0f
                            || Math.abs(raw - sRingWant) > sRingWantBand) {
                        float target = raw;
                        if (sRingWantRate > 0f && dt > 0f) {
                            float l = (float) Math.log(target / sRingWant);
                            float cap = sRingWantRate * dt;
                            if (l > cap) {
                                target = sRingWant * (float) Math.exp(cap);
                            } else if (l < -cap) {
                                target = sRingWant * (float) Math.exp(-cap);
                            }
                        }
                        float wa = sRingWantAlpha;
                        if (wa <= 0f) {
                            wa = 0.08f;
                        }
                        if (wa > 1f) {
                            wa = 1f;
                        }
                        sRingWant += (target - sRingWant) * wa;
                    }
                    float t = sRingWant;
                    // 一次只允许往目标靠 0.5x..2x，避免镜头来回抽
                    if (t > curZoom * 2f) {
                        t = curZoom * 2f;
                    }
                    if (t < curZoom * 0.5f) {
                        t = curZoom * 0.5f;
                    }
                    wantZoom = t;
                }
            }
            }
        }

        // 从没拿到过主体 → 不写，交回静态兜底矩形
        if (sRingRect == null) {
            logN("ringnosubj", "ringTick 无主体（人脸/对焦区都没取到，src=" + sRingSrc
                    + "），用固定兜底矩形", 60);
            return;
        }
        // 主体丢了：800ms 内沿用上一帧（人走开会闪），超时就清掉，回落到静态兜底
        if (subW <= 0f && now - sRingSeen > 800) {
            sRingRect = null;
            sRingWant = 0f;
            ringMedReset();
            sPrevRaw = null;
            sLockRect = null;
            sLockAt = 0L;
            sPrevRawCur = 0f;
            sGateMiss = 0;
            try {
                XposedHelpers.setObjectField(self, F_V2_DATA, null);
            } catch (Throwable ignore) {
            }
            logN("ringlost", "ringTick 主体丢失 >800ms → 回落固定兜底矩形", 0);
            return;
        }

        float[] out = new float[]{
                sRingRect[0], sRingRect[1], sRingRect[2], sRingRect[3],
                wantZoom, (float) sRingTip};
        XposedHelpers.setObjectField(self, F_V2_DATA, out);

        logN("ringtick", "ringTick subj=" + (sub == null ? "none" : sub.toShortString())
                + " crop=" + crop.toShortString() + " -> rect=("
                + sRingRect[0] + "," + sRingRect[1] + "," + sRingRect[2] + "," + sRingRect[3]
                + ") pw=" + (int) pw
                + " rawW=" + (sub == null ? -1 : sub.width())
                + " fracPx=" + (int) (sRingRect[2] / pw * 1000f)
                + " curZoom=" + curZoom + " wantZoom=" + wantZoom, 30);

        if (sRingAutoZoom == 1 && subW > 0f) {
            ringZoomDrive(wantZoom, curZoom);
        }
    }

    /**
     * 连续变焦：按 sRingZoomMs 节流，每步最多 ±0.03 倍地把当前倍率推向 want。
     * 下发走 R6.C0.b().W4(ratio, 22) —— 和官方 startZoomRatioAnimator 用的是同一条
     * 「onDualZoomValueChanged」通路（22 = 构图变焦的 reason）。
     * 一旦发现实际倍率和我们最后下发的对不上（=用户手动动了），立刻同步并冷却 1.5s，不抢。
     */
    private static void ringZoomDrive(float want, float cur) {
        long now = android.os.SystemClock.elapsedRealtime();
        // 判断「是不是用户手动动了」不能只看偏差大小，得看方向：
        //   · 我们正把 cmd 往上推，而 cur 跑到 cmd **上面**去了 —— 那多半是我们自己
        //     W4 下发后相机的动画落地过头（W4 会触发它自己的 startZoomRatioAnimator），
        //     不是用户；反之往下同理。
        //   · 只有 cur 朝**相反方向**跑了，才算被外部改了。
        // 10-03 23:45 实测：只按 0.30 阈值判，1.243→1.679 被误判成用户手动，
        // 结果白冷却 1.5s，镜头停在半路。
        if (sRingZoomCmd > 0f) {
            float diff = cur - sRingZoomCmd;
            // 0.30→0.35 而不是 0.15：W4 会顺带触发它自己的 startZoomRatioAnimator，
            // 我们一边往下踩 cmd、cur 还在从上面往下追（10-04 12:12:15 实测
            // cmd=1.177 cur=1.346 want=1.160，diff=+0.17，方向看起来还相反），
            // 0.15 会把「自己动画在飞」误判成用户手动 → 白白冷却 1.5s，变焦一顿一顿。
            if (Math.abs(diff) > 0.35f) {
                boolean weGoUp = (want - sRingZoomCmd) >= 0f;
                boolean sameDir = weGoUp ? (diff > 0f) : (diff < 0f);
                if (!sameDir || Math.abs(diff) > 0.80f) {
                    logN("ringuser", "变焦被外部改动 " + sRingZoomCmd + " -> " + cur
                            + "（want=" + want + "）冷却 1.5s 不抢", 10);
                    sRingZoomPause = now + 1500;
                    sRingZoomCmd = cur;
                    return;
                }
            }
            if (Math.abs(diff) > 0.25f) {
                // 同向但差得多 = 我们的动画在跑，直接把基线跟上，避免一直被判「差太多」
                sRingZoomCmd = cur;
            }
        } else {
            sRingZoomCmd = cur;
            return;
        }
        if (now < sRingZoomPause || now - sRingZoomAt < sRingZoomMs) {
            return;
        }
        // 基线取「离目标更近的那个」：cur 通常已经跟上了上一次 cmd，
        // 但如果相机动画还在飞（cur 落在 cmd 后面），拿 cmd 当基线才不会把命令往回发。
        float base = Math.abs(want - cur) < Math.abs(want - sRingZoomCmd) ? cur : sRingZoomCmd;
        float d = want - base;
        // ★ 死区：已经在目标附近就别再发命令。原来的 0.012 比单步 0.03 还小 →
        //   每一帧都能凑出一点偏差、都发一条 ±0.03，观感就是「一直在放大/一直在抖」。
        float dead = sRingDead > 0f ? sRingDead : 0.012f;
        if (Math.abs(d) < dead) {
            return;
        }
        float step = d > 0f ? Math.min(d, 0.030f) : Math.max(d, -0.030f);
        float next = base + step;
        try {
            Class<?> c0 = XposedHelpers.findClass(C_ZOOM, sCl);
            Object proto = XposedHelpers.callStaticMethod(c0, "b");
            if (proto == null) {
                return;
            }
            XposedHelpers.callMethod(proto, "W4", Float.valueOf(next), Integer.valueOf(22));
            sRingZoomCmd = next;
            sRingZoomAt = now;
            logN("ringzoom", "连续变焦 cur=" + cur + " -> cmd=" + next
                    + " (want=" + want + ")", 60);
        } catch (Throwable th) {
            logN("ringzoomerr", "连续变焦失败 " + th, 0);
        }
    }

    /**
     * 记录用户在相机设置里那个「智能构图」开关的状态（只在变化时打日志）。
     *   -1 = 还没采到 → gateOpen() 视为「开」，保持原有行为
     *    0 = 用户关了 → 下面所有强抬/合成/变焦一律不做
     *    1 = 用户开了 → 照常
     */
    private static void setUserOn(boolean on, String why) {
        int n = on ? 1 : 0;
        if (sUserOn == n) {
            return;
        }
        sUserOn = n;
        sUserOnWhy = why;
        log("智能构图开关 = " + (on ? "开" : "关") + "  (" + why + ")"
                + (on ? "" : "  → U3/t0/S0 强抬 + A3.g.w 拦截 + 合成 + 变焦 全部停"));
    }

    /** 所有「强抬 / 合成」的统一闸门：开关关了就零干预 */
    private static boolean gateOpen() {
        return sUserOn != 0;
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

    /**
     * 每帧心跳 —— 直接回答「预览到底还出不出帧」。
     *
     * ★ 指标源已更换（2026-10-06 根因修复）★
     * 旧源 l9.p0.a（CaptureResultParser.getAsdNightResult）在本机型**不是每帧指标**：
     * 待机时根本不被调用，只在点按（AF→ASD 分析）后被调 1 次。看门狗拿它当帧流
     * → 每次点按必报假停帧 → 自愈链 ~4s 后按 ②flush/abortCaptures 把预览管线
     * 真的 flush 掉 → 用户看到 1.5~15s 真断流（RTPreview 断流与自愈#3 flush 精确
     * 吻合，见 exp4.log 15:56:10.722）。下一 tap 由 app 自身 resumePreview 救回，
     * 同时旧心跳被调刷新判定 → 表现为「点按恢复」。
     *
     * 新源 i.onCaptureResultNext：interceptor 基类，每个采集结果必走
     * （30~60fps × 多拦截器，见 (k) 探针实测注释）。断流如实反映，
     * 点按不再影响指标。
     */
    private static void installFrameHeartbeat() {
        // 新帧流指标：每帧采集结果 → interceptor 分发
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.camera.module.interceptor.base.i", sCl,
                    "onCaptureResultNext",
                    android.hardware.camera2.CaptureResult.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            try {
                                sFrameN++;
                                sLastFrame = android.os.SystemClock.elapsedRealtime();
                                logN("hb", "帧心跳 frame=" + sFrameN, 300);
                                if (sRingProbe == 1 && !sProbed) {
                                    Object r = p.args.length > 0 ? p.args[0] : null;
                                    if (!sHbArgSeen) {
                                        sHbArgSeen = true;
                                        log("帧心跳首帧 arg0=" + (r == null ? "null"
                                                : r.getClass().getName())
                                                + " args.len=" + p.args.length
                                                + " frame=" + sFrameN);
                                    }
                                    ringProbe(r instanceof android.hardware.camera2.CaptureResult
                                            ? (android.hardware.camera2.CaptureResult) r : null);
                                }
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("probe 帧心跳 i.onCaptureResultNext ok（每帧采集结果）");
        } catch (Throwable th) {
            log("!! probe 帧心跳 i.onCaptureResultNext 失败 " + th);
        }
        // 旧探针 l9.p0.a：本机型非每帧（tap 后偶发），降级为纯观察，不更新指标
        try {
            XposedHelpers.findAndHookMethod("l9.p0", sCl, "a",
                    android.hardware.camera2.CaptureResult.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p)
                                throws Throwable {
                            logN("asd", "ASD解析 l9.p0.a（非帧指标）", 300);
                        }
                    });
            log("probe ASD l9.p0.a ok（观察用，不作帧指标）");
        } catch (Throwable th) {
            log("!! probe ASD l9.p0.a 失败 " + th);
        }
    }

    /** 长栈按 2500 字符切段打印，避免 logcat 单条 4KB 上限截断 */
    private static void logMulti(StringBuilder sb) {
        String s = sb.toString();
        int i = 0;
        while (i < s.length()) {
            int j = Math.min(s.length(), i + 2500);
            if (j < s.length()) {
                int nl = s.lastIndexOf('\n', j);
                if (nl > i) {
                    j = nl;
                }
            }
            log(s.substring(i, j));
            i = j;
        }
    }

    private static boolean interestingThread(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.contains("render") || n.contains("gl") || n.contains("candy")
                || n.contains("cam") || n.contains("capture") || n.contains("jpeg")
                || n.contains("save") || n.contains("night") || n.contains("hdr")
                || n.contains("mimo") || n.contains("asan") || n.contains("worker");
    }

    /**
     * 卡住时的现场：主线程全栈 + 各线程一行摘要 + 关键线程较长栈。
     * 只在卡住事件里跑（每秒最多一次），平时零开销。
     */
    private static void dumpStuck(String why, long ms) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("★ ").append(why).append(' ').append(ms).append("ms");
            Thread main = null;
            try {
                android.os.Looper l = android.os.Looper.getMainLooper();
                main = (l == null) ? null : l.getThread();
            } catch (Throwable ignore) {
            }
            if (main != null) {
                StackTraceElement[] st = main.getStackTrace();
                sb.append("\n  [main/").append(main.getState()).append(']');
                for (int i = 0; i < st.length && i < 50; i++) {
                    sb.append("\n        at ").append(st[i]);
                }
            }
            logMulti(sb);
            java.util.Map<Thread, StackTraceElement[]> all =
                    Thread.getAllStackTraces();
            StringBuilder sum = new StringBuilder("★ 线程摘要：");
            StringBuilder detail = new StringBuilder();
            int shown = 0;
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                    : all.entrySet()) {
                Thread t = e.getKey();
                if (t == null || t == main) {
                    continue;
                }
                StackTraceElement[] st = e.getValue();
                if (st == null || st.length == 0) {
                    continue;
                }
                sum.append("\n    ").append(t.getName()).append('/')
                   .append(t.getState()).append("  顶帧=").append(st[0]);
                if (shown < 8 && interestingThread(t.getName())) {
                    detail.append("\n  [").append(t.getName()).append('/')
                          .append(t.getState()).append(']');
                    for (int i = 0; i < st.length && i < 15; i++) {
                        detail.append("\n        at ").append(st[i]);
                    }
                    shown++;
                }
            }
            logMulti(sum);
            if (detail.length() > 0) {
                logMulti(detail);
            }
        } catch (Throwable th) {
            err(th);
        }
    }

    /**
     * ★ 主线程卡顿看门狗 ★
     * 只读采样，不碰相机任何逻辑：每 250ms 取一次主线程栈，
     * 连续 diag.watchdog.ms（默认 1500ms）栈顶不变 = 真卡住了，
     * 就把主线程全栈打出来 —— 直接回答「预览卡住的那几秒卡在哪个方法」。
     * 另外看帧心跳：采集结果停了 = 预览真的不出帧。
     */
    private static void startWatchdog() {
        if (sWd <= 0 && sHeal <= 0) {
            log("diag.watchdog=0 且 diag.stallheal=0 → 看门狗/自愈都关");
            return;
        }
        final int ms = sWdMs < 500 ? 500 : sWdMs;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
                Thread mt = null;
                try {
                    android.os.Looper l = android.os.Looper.getMainLooper();
                    mt = (l == null) ? null : l.getThread();
                } catch (Throwable ignore) {
                }
                if (mt == null) {
                    log("wd: 拿不到主线程 → 跳过卡顿检测，只做停帧自愈（"
                            + sHealMs + "ms）");
                } else {
                    log("wd: 卡顿看门狗启动（阈值 " + ms + "ms，主线程="
                            + mt.getName() + "）"
                            + (sHeal > 0 ? "；停帧自愈 " + sHealMs + "ms" : ""));
                }
                String lastSig = null;
                long since = 0L;
                long lastHit = 0L;    // 帧停事件的限流
                long lastMain = 0L;   // 主线程卡事件的限流
                int hits = 0;
                while (true) {
                    try {
                        Thread.sleep(250);
                        long now = android.os.SystemClock.elapsedRealtime();

                        // ---- ① 帧心跳：采集结果停了 = 预览真的不出帧 ----
                        long lf = sLastFrame;
                        if (lf > 0) {
                            long age = now - lf;

                            // 健康采样：每 250ms 数一次心跳，≥20fps 就算
                            // 「停帧前预览是好的」—— 用来把退后台/切模式
                            // 造成的自然停帧挡在自愈门外。
                            int fn = sFrameN;
                            int dfn = fn - sLastFn;
                            sLastFn = fn;
                            // 有帧就算健康。原来要求 ≥20fps 才算，结果连拍
                            // 一卡帧率掉下去 sHealthyAt 就再也不更新，自愈被
                            // 「停帧前不健康」这条永久挡死（实测 19:21:59 那次
            // 1595ms 的卡根本没按）。
                            if (dfn >= 1) {
                                sHealthyAt = now;
                            }

                            if (age >= ms) {
                                if (sStallStart == 0) {
                                    sStallStart = lf;
                                    lastHit = now;
                                    if (!sForeground) {
                                        // 前台判据：后台停帧 = 退后台/进程被冻结的
                                        // 自然停帧（2026-10-08 实测 5 次报警里 3 次
                                        // 是这种），只记一行，不 dump、不进持续报警。
                                        sStallBg = true;
                                        log("★ 后台停帧忽略 " + age
                                                + "ms（前台判据）");
                                    } else {
                                        sStallBg = false;
                                        String bs = miviBusy() ? "MIVI忙" : "MIVI空";
                                        if (sWd > 0) {
                                            dumpStuck("★ 预览/采集停帧(" + bs + ")", age);
                                        } else {
                                            log("★ 预览/采集停帧 " + age + "ms " + bs);
                                        }
                                    }
                                } else if (sStallBg || !sForeground) {
                                    // 后台期间不报持续停帧；期间回到前台的话
                                    // fg 钩子会清 sStallStart 重新走上面的起报。
                                } else if (!sStallMuted
                                        && now - lastHit >= 1000) {
                                    if (age > 30000) {
                                        log("★ 帧停超过 30s → 静音"
                                                + "（相机多半已关闭/退后台）");
                                        sStallMuted = true;
                                    } else {
                                        lastHit = now;
                                        if (sWd > 0) {
                                            dumpStuck("★ 预览/采集持续停帧", age);
                                        } else {
                                            log("★ 预览/采集持续停帧 "
                                                    + age + "ms");
                                        }
                                    }
                                }
                            } else if (sStallStart != 0) {
                                if (sStallBg) {
                                    log("★ 后台停帧结束 共停了 "
                                            + (now - sStallStart) + "ms");
                                } else {
                                    log("★ 帧恢复：共停了 "
                                            + (now - sStallStart) + "ms"
                                            + (sHealN > 0
                                                    ? "（自愈 " + sHealN + " 次）"
                                                    : ""));
                                }
                                sStallStart = 0;
                                sStallMuted = false;
                                sStallBg = false;
                                sHealN = 0;   // 一轮结束，下次停帧还能再自愈
                            }

                            // ---- 停帧自愈：①重发预览 → ②flush → ③强制flush ----
                            tryStallHeal(now, age);
                        }

                        // ---- ② 主线程栈是否卡在同一个方法（diag.watchdog 才做）----
                        if (sWd <= 0 || mt == null) {
                            continue;
                        }
                        StackTraceElement[] st = mt.getStackTrace();
                        if (st == null || st.length == 0) {
                            continue;
                        }
                        // 主线程空闲 = 停在 MessageQueue.nativePollOnce 等消息，
                        // 栈当然永远不变 —— 这不是卡，直接重置计时。
                        if ("nativePollOnce".equals(st[0].getMethodName())
                                && st[0].getClassName()
                                        .startsWith("android.os.MessageQueue")) {
                            lastSig = null;
                            hits = 0;
                            continue;
                        }
                        String sig = sigOf(st);
                        if (!sig.equals(lastSig)) {
                            lastSig = sig;
                            since = now;
                            hits = 0;
                            continue;
                        }
                        if (sForeground && now - since >= ms
                                && now - lastMain >= 1000) {
                            lastMain = now;
                            hits++;
                            StringBuilder sb = new StringBuilder();
                            sb.append("★ 主线程卡 ").append(now - since)
                              .append("ms（栈未变）#").append(hits)
                              .append("  顶帧=").append(st[0]);
                            for (int i = 0; i < st.length && i < 50; i++) {
                                sb.append("\n        at ").append(st[i]);
                            }
                            logMulti(sb);
                        }
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable th) {
                        err(th);
                    }
                }
            }
        }, "camport-watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        try {
            t.start();
        } catch (Throwable th) {
            err(th);
        }
    }

    /** 栈签名：取前 6 帧，够区分「卡在同一个方法里」与「还在跑」 */
    private static String sigOf(StackTraceElement[] st) {
        StringBuilder sb = new StringBuilder();
        int n = st.length < 6 ? st.length : 6;
        for (int i = 0; i < n; i++) {
            sb.append(st[i].toString()).append('|');
        }
        return sb.toString();
    }

    /**
     * 停帧自愈的判定，由看门狗线程每 250ms 调一次（只判定，不动手）。
     *
     * 先过硬条件：diag.stallheal=1、帧确实停了、相机在前台、
     * 预览「应该在跑」（有 MiCamera2 实例、没被 stopPreview 撤防）、
     * 停帧前预览是活的（挡掉切模式/关相机的余波）。
     *
     * 然后按 age 走三级阶梯，**先零成本后有成本**：
     *
     *   ① age ≥ stallheal.ms（1500）且距上次动手 ≥1500ms
     *      → p0() = resumePreview = setRepeatingRequest。
     *        不 flush、不打断在拍的照片、不等 buffer，0ms 完事。
     *        连拍的正常停顿（实测 1548ms 起）也只是被多发一次
     *        重复请求，无副作用。
     *
     *   ② age ≥ stallheal.flush（4000）且 **MIVI 空**
     *      → C1() = abortCaptures。成片都出完了才 flush，此时是快的；
     *        MIVI 还在算绝不按（10-03 19:37 实测：忙时按下去
     *        C1() 要 4253ms，反而把卡顿拉到 10.5 秒）。
     *
     *   ③ age ≥ stallheal.hard（12000）→ 不看 MIVI，强制 flush。
     *      再慢也比相机自己 57 秒不恢复强。
     *
     * 一轮最多 6 次（① 可重按、② ③ 各一次），帧恢复后清零。
     */
    private static void tryStallHeal(long now, long age) {
        if (sHeal <= 0 || sStallStart == 0 || sStallMuted) {
            return;
        }
        if (!sForeground || !sPreviewArmed || sCam2 == null) {
            return;
        }
        if (age < sHealMs || age > 30000) {
            return;
        }
        // 停帧前最后那次健康采样已经太旧 = 预览本来就该停（切模式/关相机）
        if (sHealthyAt == 0 || now - sHealthyAt > sHardMs + 1500) {
            return;
        }
        if (sHealN >= 6) {
            return;
        }
        long cooldown = (age >= sFlushMs) ? 1000 : 1500;
        if (sHealN > 0 && now - sHealLast < cooldown) {
            return;
        }

        String how;
        if (age >= sHardMs) {
            how = "③强制flush";
        } else if (age >= sFlushMs) {
            if (miviBusy()) {
                return;   // MIVI 还在算 —— 绝不 flush，等它算完或等硬档
            }
            how = "②flush(MIVI空)";
        } else {
            how = "①重发预览";
        }
        sHealLast = now;
        sHealN++;
        doStallHeal(age, sHealN, how);
    }

    /**
     * 还有没有照片在 MIVI 里算。
     * MIVICaptureManager.hasParallelTaskData()：addAll（拍照）进表、
     * releaseData（成片出完）出表。拿不到探针就保守当「忙」，只走硬档。
     */
    private static boolean miviBusy() {
        java.lang.reflect.Method m = sBusyProbe;
        if (m == null) {
            try {
                Class<?> c = XposedHelpers.findClass(
                        "com.xiaomi.camera.mivi.MIVICaptureManager", sCl);
                m = c.getMethod("hasParallelTaskData");
                sBusyProbe = m;
            } catch (Throwable th) {
                return true;
            }
        }
        try {
            return !Boolean.FALSE.equals(m.invoke(null));
        } catch (Throwable th) {
            return true;
        }
    }

    /**
     * 真正动手，两种原语：
     *   ① MiCamera2.p0()  = resumePreview = session.setRepeatingRequest
     *                        零成本：不 flush、不打断在拍的照片、不等 buffer
     *   ② MiCamera2.C1()  = session.abortCaptures() + PostProc close
     *                        = 相机自己的恢复路径（ABORT_CAPTURES-START），
     *                        会等 flush，MIVI 空时 ~41ms、buffer 卡住时 4000+ms
     * 两者都放独立线程，不能占用看门狗的 250ms 节拍。
     */
    private static void doStallHeal(final long age, int nth, final String how) {
        final Object cam = sCam2;
        if (cam == null) {
            return;
        }
        final boolean flush = how.indexOf("flush") >= 0;
        long ago = sAppAbortAt > 0
                ? (android.os.SystemClock.elapsedRealtime() - sAppAbortAt) : -1;
        log("★ 停帧 " + age + "ms → 自愈 #" + nth + " [" + how + "]"
                + "：MiCamera2." + (flush ? "C1()=abortCaptures(flush HAL)"
                                        : "p0()=resumePreview(重发重复请求)")
                + "（相机上次自救在 " + ago + "ms 前）");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = android.os.SystemClock.elapsedRealtime();
                try {
                    if (flush) {
                        sOurCall = true;
                    }
                    XposedHelpers.callMethod(cam, flush ? "C1" : "p0");
                    log("★ 自愈 #" + nth + " [" + how + "] 返回，用时 "
                            + (android.os.SystemClock.elapsedRealtime() - t0)
                            + "ms");
                } catch (Throwable th) {
                    log("!! 自愈 [" + how + "] 抛错：" + th);
                    err(th);
                } finally {
                    sOurCall = false;
                }
            }
        }, "camport-stallheal");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 停帧自愈用到的钩子。目标类 l9.y0 = MiCamera2
     * （jadx 里叫 p081l9.C1448y0，但 logcat 栈里就是 l9.y0）：
     *
     *   l9.y0$k.onCaptureCompleted  会话回调，每帧都有；this$0 就是外层
     *                               l9.y0 —— 用它指认「正在预览的那台」
     *   p0()      = resumePreview（每次拍完「onRepeatingEnd」回预览）
     *   n1()      = stopPreview  （只在 surfaceDestroyed 走）
     *   C1()      = abortCaptures —— 相机自己的自救点，记时刻好分清谁按的
     *
     * 再加 Activity.onResume/onPause 当前台标志，退后台绝不 flush。
     */
    private static void installStallHeal() {
        if (sHeal <= 0) {
            log("diag.stallheal=0 → 停帧自愈关（只报警不干预）");
            return;
        }
        try {
            Class<?> c = XposedHelpers.findClass("l9.y0", sCl);
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    // 只记数。真正认「哪台是正在预览的相机」靠下面的预览帧回调：
                    // 一台相机开起来会同时 new 好几个 l9.y0（主摄/辅摄），
                    // 按 new 的顺序挑很可能挑错。
                    log("heal: new MiCamera2(l9.y0) @"
                            + System.identityHashCode(p.thisObject)
                            + "（等预览帧来指认）");
                }
            });
            log("probe heal: hook l9.y0 <init> ok（记相机实例）");
        } catch (Throwable th) {
            log("!! heal: hook l9.y0 构造失败 " + th);
        }
        // 预览帧回调：每帧都从 inner class 的 this$0 拿到外层 l9.y0，
        // 这样 sCam2 永远是「最后还在出预览帧的那台」，停帧时正好就是它。
        try {
            Class<?> y0c = XposedHelpers.findClass("l9.y0", sCl);
            Class<?> kc = XposedHelpers.findClass("l9.y0$k", sCl);
            // 混淆把 this$0 改名了，按类型挑外层引用
            java.lang.reflect.Field pick = null;
            for (java.lang.reflect.Field f : kc.getDeclaredFields()) {
                if (f.getType() == y0c) {
                    pick = f;
                    break;
                }
            }
            if (pick == null) {
                throw new NoSuchFieldException(
                        "l9.y0$k 里找不到类型为 l9.y0 的外层引用");
            }
            final java.lang.reflect.Field outerF = pick;
            outerF.setAccessible(true);
            XposedHelpers.findAndHookMethod("l9.y0$k", sCl,
                    "onCaptureCompleted",
                    android.hardware.camera2.CameraCaptureSession.class,
                    android.hardware.camera2.CaptureRequest.class,
                    android.hardware.camera2.TotalCaptureResult.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object o = outerF.get(p.thisObject);
                                if (o == null) {
                                    return;
                                }
                                if (sCam2 != o) {
                                    sCam2 = o;
                                    log("heal: 预览帧指认活动 MiCamera2 @"
                                            + System.identityHashCode(o));
                                }
                                sPreviewArmed = true;
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            log("probe heal: hook l9.y0$k.onCaptureCompleted ok"
                    + "（每帧指认活动相机）");
        } catch (Throwable th) {
            log("!! heal: hook l9.y0$k 失败 " + th);
        }
        try {
            XposedHelpers.findAndHookMethod("l9.y0", sCl, "p0",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (sCam2 != p.thisObject) {
                                sCam2 = p.thisObject;
                                log("heal: resumePreview 指认 MiCamera2 @"
                                        + System.identityHashCode(p.thisObject));
                            }
                            sPreviewArmed = true;
                        }
                    });
            XposedHelpers.findAndHookMethod("l9.y0", sCl, "n1",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            sPreviewArmed = false;
                            log("heal: stopPreview → 撤防（停帧不再自愈）");
                        }
                    });
            log("probe heal: hook l9.y0.p0/n1 ok（resume/stopPreview 布防）");
        } catch (Throwable th) {
            log("!! heal: hook l9.y0.p0/n1 失败 " + th);
        }
        try {
            XposedHelpers.findAndHookMethod("l9.y0", sCl, "C1",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (sOurCall) {
                                return;
                            }
                            sAppAbortAt = android.os.SystemClock.elapsedRealtime();
                            log("heal: 相机自己按了 abortCaptures"
                                    + "（停帧 " + (sStallStart > 0
                                            ? (android.os.SystemClock.elapsedRealtime()
                                                    - sStallStart) : -1)
                                    + "ms，等回帧）");
                        }
                    });
            log("probe heal: hook l9.y0.C1 ok（相机自救点）");
        } catch (Throwable th) {
            log("!! heal: hook l9.y0.C1 失败 " + th);
        }
        installFgFlag();
    }

    /** 前台标志钩子：看门狗前台判据 + 停帧自愈共用；stallheal=0 也必须装 */
    private static volatile boolean sFgInstalled;

    private static void installFgFlag() {
        if (sFgInstalled) {
            return;
        }
        sFgInstalled = true;
        try {
            XC_MethodHook fg = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object o = p.thisObject;
                    if (o == null) {
                        return;
                    }
                    String cn = o.getClass().getName();
                    if (cn == null || !cn.startsWith("com.android.camera")) {
                        return;
                    }
                    boolean on = "onResume".equals(p.method.getName());
                    if (sForeground != on) {
                        sForeground = on;
                        if (on) {
                            // 回前台：后台起的停帧重新起报（前台真停帧不漏）
                            if (sStallBg && sStallStart != 0) {
                                sStallStart = 0;
                            }
                            sStallBg = false;
                            sStallMuted = false;
                        }
                        log("heal: " + (on ? "回到前台" : "离开前台 → 停自愈")
                                + " (" + cn + ")");
                    }
                }
            };
            XposedHelpers.findAndHookMethod(android.app.Activity.class,
                    "onResume", fg);
            XposedHelpers.findAndHookMethod(android.app.Activity.class,
                    "onPause", fg);
            log("probe heal: hook Activity.onResume/onPause ok（前台标志）");
        } catch (Throwable th) {
            log("!! heal: hook 前台标志失败 " + th);
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
                    } else if ("ai.smartcomp.dbgindex".equals(k)) {
                        sDbgIndex = Integer.parseInt(v);
                    } else if ("ai.smartcomp.dbgfrag".equals(k)) {
                        sDbgFrag = Integer.parseInt(v);
                    } else if ("ai.smartcomp.compose".equals(k)) {
                        sCompose = Integer.parseInt(v);
                    } else if ("ai.smartcomp.cycle".equals(k)) {
                        sCycle = Integer.parseInt(v);
                    } else if ("ai.smartcomp.ring".equals(k)) {
                        sRing = Integer.parseInt(v);
                    } else if ("ai.smartcomp.ring.x".equals(k)) {
                        sRingX = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.y".equals(k)) {
                        sRingY = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.w".equals(k)) {
                        sRingW = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.h".equals(k)) {
                        sRingH = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.zoom".equals(k)) {
                        sRingZoom = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.tip".equals(k)) {
                        sRingTip = Integer.parseInt(v);
                    } else if ("ai.smartcomp.ring.src".equals(k)) {
                        sRingSrc = Integer.parseInt(v);
                    } else if ("ai.smartcomp.ring.idle".equals(k)) {
                        sRingIdle = Integer.parseInt(v) != 0 ? 1 : 0;
                    } else if ("ai.smartcomp.ring.pad".equals(k)) {
                        sRingPad = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.fill".equals(k)) {
                        sRingFill = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.autozoom".equals(k)) {
                        sRingAutoZoom = Integer.parseInt(v);
                    } else if ("ai.smartcomp.ring.zmax".equals(k)) {
                        sRingZMax = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.smooth".equals(k)) {
                        sRingSmooth = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.zoomms".equals(k)) {
                        sRingZoomMs = Long.parseLong(v);
                    } else if ("ai.smartcomp.ring.exp".equals(k)) {
                        sRingExp = Integer.parseInt(v);
                    } else if ("ai.smartcomp.ring.probe".equals(k)) {
                        sRingProbe = Integer.parseInt(v);
                    } else if ("ai.smartcomp.ring.iou".equals(k)) {
                        sRingIou = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.med".equals(k)) {
                        sRingMed = Integer.parseInt(v);
                    } else if ("ai.smartcomp.ring.wband".equals(k)) {
                        sRingWantBand = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.dead".equals(k)) {
                        sRingDead = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.walpha".equals(k)) {
                        sRingWantAlpha = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.wrate".equals(k)) {
                        sRingWantRate = Float.parseFloat(v);
                    } else if ("ai.smartcomp.ring.lock".equals(k)) {
                        sRingLock = Integer.parseInt(v);
                    } else if ("ai.aiscene".equals(k)) {
                        sAiScene = Integer.parseInt(v);
                    } else if ("diag.watchdog".equals(k)) {
                        sWd = Integer.parseInt(v);
                    } else if ("diag.watchdog.ms".equals(k)) {
                        sWdMs = Integer.parseInt(v);
                    } else if ("diag.stallheal".equals(k)) {
                        sHeal = Integer.parseInt(v);
                    } else if ("diag.stallheal.ms".equals(k)) {
                        sHealMs = Integer.parseInt(v);
                    } else if ("diag.stallheal.flush".equals(k)) {
                        sFlushMs = Integer.parseInt(v);
                    } else if ("diag.stallheal.hard".equals(k)) {
                        sHardMs = Integer.parseInt(v);
                    }
                } catch (Throwable ignore) {
                }
            }
            if (sLevel < 0) {
                sLevel = 0;
            }
            if (sLevel > 8) {
                // ★ 上限必须跟档位表同步！写成 4 会把 5 悄悄改写成 4，
                //   于是「只抬 V3 走 v1」根本没执行，跑的却是档位 4（v2 死路）——
                //   10-02 22:39 实测复现：cfg 打印 ai.smartcomp=4，设备卡住。
                //   同理写 5 会把 6 悄悄降成 5，v1 通路的两块补丁就不生效；
                //   写 7 会把 8 悄悄降成 7 —— 那是 PIP 样张框档，不是引导环档。
                sLevel = 8;
            }
            if (sSubmit < 0) {
                sSubmit = 0;
            }
            if (sSubmit > 1) {
                sSubmit = 1;
            }
            // 档8 的合成参数：x/y/w/h 限定在 0..1（previewSize 比例），
            // zoom 0.5..10（1.0 = 不动变焦），tip 0..3（CompositionDataType）。
            if (sRingX < 0f) {
                sRingX = 0f;
            }
            if (sRingX > 0.95f) {
                sRingX = 0.95f;
            }
            if (sRingY < 0f) {
                sRingY = 0f;
            }
            if (sRingY > 0.95f) {
                sRingY = 0.95f;
            }
            if (sRingW <= 0f) {
                sRingW = 0.40f;
            }
            if (sRingW > 1f) {
                sRingW = 1f;
            }
            if (sRingH <= 0f) {
                sRingH = 0.36f;
            }
            if (sRingH > 1f) {
                sRingH = 1f;
            }
            if (sRingZoom < 0.5f) {
                sRingZoom = 0.5f;
            }
            if (sRingZoom > 10f) {
                sRingZoom = 10f;
            }
            if (sRingTip < 0) {
                sRingTip = 0;
            }
            if (sRingTip > 3) {
                sRingTip = 3;
            }
            if (sRingSrc < 0) {
                sRingSrc = 0;
            }
            if (sRingSrc > 3) {
                sRingSrc = 3;
            }
            if (sRingPad < 1f) {
                sRingPad = 1f;
            }
            if (sRingPad > 3f) {
                sRingPad = 3f;
            }
            if (sRingFill < 0.10f) {
                sRingFill = 0.10f;
            }
            if (sRingFill > 0.95f) {
                sRingFill = 0.95f;
            }
            if (sRingAutoZoom < 0) {
                sRingAutoZoom = 0;
            }
            if (sRingAutoZoom > 1) {
                sRingAutoZoom = 1;
            }
            if (sRingZMax < 1f) {
                sRingZMax = 1f;
            }
            if (sRingZMax > 10f) {
                sRingZMax = 10f;
            }
            if (sRingSmooth < 0.02f) {
                sRingSmooth = 0.02f;
            }
            if (sRingSmooth > 1f) {
                sRingSmooth = 1f;
            }
            if (sRingZoomMs < 30) {
                sRingZoomMs = 30;
            }
            if (sRingZoomMs > 500) {
                sRingZoomMs = 500;
            }
            if (sRingExp < 0) {
                sRingExp = 0;
            }
            if (sRingExp > 2) {
                sRingExp = 2;
            }
            if (sRingProbe < 0) {
                sRingProbe = 0;
            }
            if (sRingProbe > 1) {
                sRingProbe = 1;
            }
            if (sRingIou < 0f) {
                sRingIou = 0f;
            }
            if (sRingIou > 0.9f) {
                sRingIou = 0.9f;
            }
            if (sRingMed < 1) {
                sRingMed = 1;
            }
            if (sRingMed > 9) {
                sRingMed = 9;
            }
            if (sRingWantBand < 0f) {
                sRingWantBand = 0f;
            }
            if (sRingWantBand > 0.6f) {
                sRingWantBand = 0.6f;
            }
            if (sRingDead < 0f) {
                sRingDead = 0f;
            }
            if (sRingDead > 0.3f) {
                sRingDead = 0.3f;
            }
            if (sRingWantAlpha < 0.02f) {
                sRingWantAlpha = 0.02f;
            }
            if (sRingWantAlpha > 1f) {
                sRingWantAlpha = 1f;
            }
            if (sRingWantRate < 0f) {
                sRingWantRate = 0f;
            }
            if (sRingWantRate > 5f) {
                sRingWantRate = 5f;
            }
            if (sRingLock < 0) {
                sRingLock = 0;
            }
            if (sRingLock > 1) {
                sRingLock = 1;
            }
            if (sDbgFrag < 0) {
                sDbgFrag = 0;
            }
            if (sDbgFrag > 1) {
                sDbgFrag = 1;
            }
            if (sAiScene < 0) {
                sAiScene = 0;
            }
            if (sAiScene > 1) {
                sAiScene = 1;
            }
            if (sHeal < 0) {
                sHeal = 0;
            }
            if (sHeal > 1) {
                sHeal = 1;
            }
            if (sHealMs < 800) {
                sHealMs = 800;
            }
            if (sHealMs > 2000) {
                // 上限钉死 2000：这是「卡死不超过 2 秒」的硬要求，
                // 手滑写成 5000 也不能放宽。
                sHealMs = 2000;
            }
            if (sFlushMs < sHealMs + 1000) {
                sFlushMs = sHealMs + 1000;
            }
            if (sFlushMs > 10000) {
                sFlushMs = 10000;
            }
            if (sHardMs < sFlushMs) {
                sHardMs = sFlushMs;
            }
            if (sHardMs > 20000) {
                sHardMs = 20000;
            }
            log("cfg=" + chosen + " ai.smartcomp=" + sLevel
                    + " ai.smartcomp.submit=" + sSubmit
                    + " ai.smartcomp.dbgindex=" + sDbgIndex
                    + " ai.smartcomp.dbgfrag=" + sDbgFrag
                    + (sLevel >= 8
                            ? " ai.smartcomp.ring=" + sRing
                                    + " ring.rect=" + sRingX + "/" + sRingY
                                    + "/" + sRingW + "/" + sRingH
                                    + " ring.zoom=" + sRingZoom
                                    + " ring.tip=" + sRingTip
                                    + " ring.src=" + sRingSrc
                                    + " ring.idle=" + sRingIdle
                                    + " ring.pad=" + sRingPad
                                    + " ring.fill=" + sRingFill
                                    + " ring.autozoom=" + sRingAutoZoom
                                    + " ring.zmax=" + sRingZMax
                                    + " ring.smooth=" + sRingSmooth
                                    + " ring.zoomms=" + sRingZoomMs
                                    + " ring.exp=" + sRingExp
                                    + " ring.probe=" + sRingProbe
                                    + " ring.iou=" + sRingIou
                                    + " ring.med=" + sRingMed
                                    + " ring.wband=" + sRingWantBand
                                    + " ring.dead=" + sRingDead
                                    + " ring.walpha=" + sRingWantAlpha
                                    + " ring.wrate=" + sRingWantRate
                                    + " ring.lock=" + sRingLock
                            : "")
                    + " ai.aiscene=" + sAiScene
                    + " diag.stallheal=" + sHeal
                    + " diag.stallheal.ms/flush/hard="
                    + sHealMs + "/" + sFlushMs + "/" + sHardMs);
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

    private static java.io.BufferedWriter sWriter;
    private static String sLastError;
    private static final Object sLogLock = new Object();
    private static long sWinStart;
    private static int sWinCount;
    private static int sPending;
    private static long sFlushAt;
    /** 累计日志条数 —— 开头那批安装日志必须立刻落盘（见 log() 里的 sTotal<=20） */
    private static int sTotal;

    /**
     * ★ 性能★：每条日志都 flush 会把调用线程（相机的 capture-result 线程）卡在
     * /sdcard 的 FUSE 写上，实测 ja.r.b 峰值 85 条/秒 → 预览/AI帮拍 会一顿一顿的。
     * 这里改成：缓冲写 + 每 50 条或每 500ms 才 flush + 每秒最多 300 条（超出静默丢）。
     */
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
                        sWriter = new java.io.BufferedWriter(new FileWriter(f, true));
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
                        + " I/构图fix: " + msg + "\n");
                sPending++;
                sTotal++;
                // ★ 开头 20 条（cfg/installed/所有 hook ok）必须无条件落盘：
                //   否则一旦后面相机没开预览、本类再没日志可打，
                //   "每 500ms 才 flush" 的条件永远不触发（它只在**新日志到来**时才判断），
                //   安装日志就全卡在缓冲区里 —— 10-03 23:34 实测：12 条 hook 日志一条都没写出来。
                if (sTotal <= 20 || sPending >= 50 || now - sFlushAt >= 500) {
                    sWriter.flush();
                    sFlushAt = now;
                    sPending = 0;
                }
            }
        } catch (Throwable th) {
            sWriter = null;
        }
    }

    /** 把缓冲里的日志落盘（异常路径用，避免崩了丢尾巴） */
    private static void flushLog() {
        try {
            synchronized (sLogLock) {
                if (sWriter != null) {
                    sWriter.flush();
                    sPending = 0;
                    sFlushAt = android.os.SystemClock.elapsedRealtime();
                }
            }
        } catch (Throwable ignore) {
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
