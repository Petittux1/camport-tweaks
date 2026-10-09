# camport-tweaks

小米 17 Pro 上跑 **18 Pro Max 相机 APK**（`6.8.001770.0`）的 LSPosed 模块扩展，
让五个在本机原本不生效（或不完整）的功能落地：

| 功能 | 状态 |
|---|---|
| **传奇一瞬**（M3 / M9 / M10R） | ✅ 预览 + 成片都通 |
| **实况运镜**（mode 231 自由 / 主角 / 红毯） | ✅ 能用，⚠️ 成片观感不理想（见下） |
| **AI帮拍**（mode 168 / `0xa8`） | ✅ 亮场卡死 + sigabort 已修（10-09 根治：`ai.hdrfix=2` 放行进入 + 强制 `hdrType=5`，实测 0 崩溃；见 §4b） |
| **智能构图** | ✅ 预览引导环 + 连续自动变焦（10-04 合成 `autoCropData` 喂 v2 通路，用户确认可用） |
| **调色盘** | ✅ 拍照模式所见即所得（10-04 `PaletteFix`，用户确认可用） |

> **本仓库为 Private。** 所有 hook 都是追加在 **XiaomiCamPort** 这个第三方模块之上，
> 原模块**不是我的**。仓库里**不含**原模块的 `module.apk`、不含相机 APK、不含反编译产物
> （`module.apk` / 相机 APK 只放在 Release 资产里）。
> 正在联系原开发者，看能否走 fork / 上游合并的方式正式发布。

---

## 环境

- 机型 `pandora`（小米 17 Pro）/ HyperOS 4 / Android 17 / KernelSU
- 相机 `com.android.camera 6.8.001770.0`（18 Pro Max 的包，包名伪装 `build.DEVICE=madrid`）
- 开发全程在 **Termux** 里做，不连电脑
- 相机进程：`com.android.camera`，模块进程同名

---

## 目录

```
legend_src/com/pudding/camport/hooks/
    MasterLiveTune.java   实况运镜：运镜动画时长 + 快门后 AF 冻结
    Mode231Fix.java       实况运镜：闪退修复 + 绿色花屏修复 + 帧探针（live.probe 门控）
    Mode231Probe.java     实况运镜：开相机 id / 流配置探针
    LegendaryColor.java   传奇一瞬：M3/M9/M10R -> CubeLut 滤镜映射
    SmartCompFix.java     智能构图：ai.smartcomp 档位门（0..8，8=v2 引导环 + 合成 autoCropData）
    HdrFix.java           AI帮拍：放行HDR互斥+强制 hdrType=5 ai.hdrfix
    PaletteFix.java       调色盘：拍照模式成片所见即所得 palette.*
build_legend.sh           javac -> d8 -> zip -> apksigner -> pm install -r
config.example.conf       全部可调键（拷到设备 config.conf 用）
*.py                      成片分析脚本（下面单独说）
```

模块打进 APK 的一共 **7 行** `assets/xposed_init`：
原模块自带的 `MainHook` + 上面 6 个新类。

---

## 构建

```bash
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
bash build_legend.sh
```

脚本流程：

1. 编译 Xposed 桩（仅编译期用，不进 dex）
2. `javac --release 8` 编 `legend_src`
3. `d8` 把新 class **合并**进原模块的 `classes.dex`
4. 打包：重写 `assets/xposed_init`（**7 行** = `MainHook` + 6 个新类）
5. `apksigner` 签名（v1+v2+v3）并 `pm install -r`

**依赖（都在本机，不入库）：**

| 文件 | 来源 |
|---|---|
| `module.apk` | XiaomiCamPort 原模块（**第三方，不入库**） |
| `camera.apk` | 18 Pro Max 相机（**小米专有，不入库**） |
| `camport.keystore` | 签名密钥（**密钥，绝不入库**） |
| `~/work/sdk/android-35/android.jar` | Android SDK |
| `~/work/sdkbt/android-14/r8.jar` `lib/apksigner.jar` | Android build-tools |

改 **config.conf** 不用重装，`su -c am force-stop com.android.camera` 即生效。

---

## config.conf 关键开关

完整见 `config.example.conf`。本仓库新增的部分：

```properties
# ---- 绿色花屏 ----
live231size=auto        auto=基准×0.9（16:9 即 2304x1296）
                        off=回退到花屏状态
                        <W>x<H>=自定义 ★mode 231 不能乱填，见下方「已证伪」

# ---- 实况运镜成片观感 ----
legend.zoomdur=1.1      快门触发的运镜动画时长（原自由/红毯 3.0、主角 2.0），0=不改
legend.aflock=2         0=关  1=跳过值没变的重复 applyAfMode  2=窗口内强制 AF_MODE_AUTO 冻结
legend.afwin=2.0        AF 介入窗口（秒，从 startAutoZoom 起算）

# ---- 传奇一瞬 ----
legend.color=1
legend.filter.m3=77     M3 黑白传奇 -> 滤镜 id
legend.filter.m9=84     M9 CCD 传奇
legend.filter.m10r=73   M10R CMOS 传奇
legend.degree=100       滤镜强度 0-100

# ---- 帧探针（性能：默认只采 mode 231）----
live.probe=231          off=整条探针停掉   231=只采实况运镜（默认，=10-01 原始行为）
                        all=按尺寸白名单全采 ★AI帮拍/拍照/2亿 与 231 尺寸撞车，会误命中

# ---- 智能构图（v2 引导环，见 config.example.conf 的逐键说明）----
ai.smartcomp=8          0=不干预   8=★抬四道门 + 合成 autoCropData 喂 v2 引导环
                        1..7 是历史 A/B 档位，日常不用
ai.smartcomp.ring=1     1=合成 float[6] 喂引导环（0=对照组）
ai.smartcomp.ring.x=0.30   兜底矩形，★必须居中：x=0.5-w/2, y=0.5-h/2
ai.smartcomp.ring.y=0.32   否则没主体时引导环永远提示「向下移动手机」
ai.smartcomp.ring.src=0    0=人脸→触摸对焦→上一帧→兜底  3=只用兜底矩形
ai.smartcomp.ring.autozoom=1  1=连续自动变焦（按 ring.fill 反算目标倍率）
ai.smartcomp.submit=1    1=放行 submitAiComposition。★ 0 强拦会让 onAiEffectResult
                        永远不回来 → 分析标志卡 false → 三个 chip 全部点不动（别改 0）
# 稳定性三道门（治「变焦 1→1.2→1.4 来回跳」「引导框乱窜」）
ai.smartcomp.ring.lock=1    1=主体锁定：多脸时挑和上一帧重合/最近的那张，不逐帧挑最高分
ai.smartcomp.ring.wband=0.10 目标倍率迟滞带，|raw-目标| 没超过就不动目标
ai.smartcomp.ring.walpha=0.08 目标倍率 EMA（0.30 太快≈没平滑）
ai.smartcomp.ring.wrate=0.5   目标倍率速率上限 log/秒，把大跳变摊成缓动
ai.smartcomp.ring.dead=0.06   执行器死区，原 0.012 比单步还小 → 一直在微调

# App 内开关现在是**真开关**：关掉「智能构图」后 U3/t0/S0/A3.g.w/合成/变焦全停。
# 采样来自 u2.H.isSwitchOn / u2.H.R(后置) 的 pref_smart_composition_key_<mode>，
# 日志 tag=智能构图开关。⚠ 顺带修好「点不动」：FragmentAi 的 onClick 首行会因为
# 「分析中」把点击吞掉，而本机 onAiEffectResult 只有放行 submitAiComposition 才会回来。

# ---- 调色盘（拍照模式成片所见即所得）----
palette.force=1         palette.inject=1        palette.dup=1
palette.probe=1         palette.withfilter=1

# ---- AI帮拍 / 自动HDR 互斥 ----
ai.hdrfix=2             2=根治主档：放行进入 + 双强制 m2()&isSR() → hdrType=5
                         （10-09 实测放行且 0 崩溃；两挂点任一失败自动退 1）
                         1=兜底：进 AI帮拍 时阻断「自动HDR」互斥抢模式
                         （10-09 证伪为主档：阻断后照样 SIGABRT，只作兜底）
                         0=关

# ---- 预览断流三级自愈（SmartCompFix 停帧看门狗）----
diag.stallheal=1         ①≥1.5s 重发预览(零成本) → ②≥4s 且 MIVI空 才 flush
                         → ③≥12s 强制 flush。护栏：前台判据/停帧前健康采样/
                         冷却限次/30s 上限；假停帧根因已在 v0.15.0 真帧心跳根修
```

---

## 定案的根因结论

### 1. 闪退（已修）

`getActualOpenCameraId` 对 mode 231 算出 `cameraId = -1` → `openCamera` 连续失败三次
→ 相机自我保护退出（**不是 Java 崩溃**，logcat 无 FATAL）。

原因：本机镜头角色表里没有 `role 20`（18 Pro Max 的标准长焦），
只有 `role 23/24`（17.1° 超长焦），而 mode 231 的组件值 `n.h(231)="tele"` 走 `role 20`。
**只在算出负数时回退**，正常路径不碰。

### 2. 绿色花屏（已修）

`PostProcService` 实际按 **1728x1296 的 packed stride** 写 ImageReader，
应用却按 `1920x1440` 读 → 行错位 + 未覆盖区全 0 → `Y=125 U=0 V=0` = BT.601 **纯绿**。

修法：hook `m6.n.c()`（`getLivePhotoVideoSize`，2_5 流尺寸的唯一上游），
仅 `mode==231` 时改成 `基准 × 0.9`（复刻 mode 163 的规则），下游全部自动跟随。

> ⚠️ **`live231size=1728x1296` 已证伪**：CamX 在 `ImageBuffer::Import` 硬校验，
> 每次快门后 100~170ms 让 `vendor.qti.camera.provider-service_64` **SIGABRT**，
> 2_5 流一帧都产不出 → 完全没有实况视频。栈：
> ```
> #01 CamX::ImageBuffer::Import(ImageFormat const*, ExtBufferInfo*, ...)
> #02 CamX::CNodeImpl::SetupRequestOutputPorts(PerBatchedFrameInfo*)
> #03 CamX::CNodeImpl::AcquireResourcesForRequest(...)
> #04 CamX::Pipeline::ProcessRequest(...)
> ```
> **mode 231 的 2_5 流尺寸不能随便给，只有 `auto`(2304x1296) 验证可用。**

### 3. 实况运镜「zoom 断断续续」（成因已定案，改善但不理想）

两个独立问题叠加：

**(a) 动画装不进成片。** 用 `wscan.py` 对成片逐 5 帧窗做独立 NCC k 扫描：

```
f00->f05  k=1.000 (fit 0.090，全场最佳)   指令要求 1.036
f05->f10  k=0.980 (0.106)                 指令 1.054
f10->f15  k=1.010 (0.251)                 指令 1.051
f30->f35  k=1.050 (0.193)                 指令 1.043
f40->f45  k=1.050 (0.121)                 指令 1.039
```

**前 0.5 秒完全没 zoom，1.0 秒之后与指令严丝合缝。**
即「成片第 p 秒」显示的是「真实时间 p − 0.5 秒」拍到的内容
（f0 的内容落在快门之前，也印证了这点）。

成片长 1.63s → 只能覆盖动画的前 **1.13s**，而动画硬编码 **3.0s**（自由/红毯）
/ 2.0s（主角），**只推到 38% 就结束了**。

滞后下界可硬性推出 ≥0.32s（曝光不可能早于快门按下），
0.32~0.5s 的不确定度来自「传感器时钟 vs 日志墙钟」的未知偏移。
根因是快门后 `PostProcService` 积压回灌 → camera 请求队列堵住 → zoom 指令滞后到传感器。

→ 修法 `legend.zoomdur=1.1`（< 1.13s，且在整个不确定区间内都成立）。

**(b) 快门后 AF 被重扫。** 成片 +0.50s 那次「糊→清」硬跳
（清晰度 14.8→31.5、帧体积 19.6K→163.8K）对应：

```
+0ms   onShutterButtonClick
+15ms  still 请求: applyAfMode: focusMode=4 + applyAfTrigger: 0
+111ms startAutoZoom
+145ms 重复请求重建: applyAfMode: focusMode=4
+312ms afState 2 -> 0   INACTIVE（硬复位）
+429ms afState 0 -> 1   PASSIVE_SCAN  ← 镜头找焦 = 画面变糊
+594ms afState 1 -> 2   PASSIVE_LOCKED ← 成片里那次「啪」
```

对照拍摄**之前**：每轮扫描只有 67ms；快门后多 118ms INACTIVE、扫描拉到 165ms。

→ 修法 `legend.aflock=2`：窗口内把 `applyAfMode` 强制为 **1（AF_MODE_AUTO）**。
**不选 AF_MODE_OFF(0)** —— OFF 模式镜头跟 `LENS_FOCUS_DISTANCE` 走，
没有显式设置就是默认 `0.0` = 无穷远，8.6x 长焦超焦距可达几十米，主体必糊；
而 AUTO 模式没有触发就不动镜头也不扫描，既冻住 AF 又完全不需要回写焦距。

### 4. AI帮拍 `fd=0` 连环崩溃（**已止血，成因未定位**）

因果链是清楚的，日志 1:1 对齐：

```
进 AI帮拍(0xa8)
  → 0.5~17.7s（中位 4.1s，22/23 在 10s 内）
  → CSLMapBufferHW() Mapping CSL Buffer failed for fd = 0
  → 每 ~330ms 一次 PrepareForRecovery，连续 6 次
  → FATAL: Consecutive 6 recovery detected for logical cameraId: 7
  → SIGABRT（`Fatal signal 6 … tid (Preview_X), pid (camera.provider)`）
  → provider 重启 → 预览冻结 = 「卡 + 哒哒声」+ `CameraExitHint showErrorScreen`
```

**规模**：10-02 一天 93 次进 `0xa8`，其中 **40 次（44%）** 60s 内走到 `fd=0`，
**全部落在 13:11 ~ 18:29:08** 这个窗口。18:29:08 之后 **0 次**；
21:21 换回当前构建（6 新类 + 原 17 类全挂载）之后也 **0 次**。

**已逐条排除**（每条都是日志统计，不是读码推断）：

| 假设 | 证据 | 结论 |
|---|---|---|
| `SmartCompFix` | `ai.smartcomp=0`（关）期间仍崩 8 组 | ❌ |
| `HdrFix` | 13:11~16:57 的崩时它还没进构建；17:09 起 44 条阻断日志只覆盖 11/40 | ❌ |
| `LegendaryColor` | 140 条日志全落在 mode `0x100`，与 `0xa8` 无交集 | ❌ |
| `Mode231Fix` / `Mode231Probe` | `live231size` 全天 14 条全是 `mode=231`；cid 覆写 0 次触发；探针 15:01 后未在非 231 跑过 | ❌ |
| `MasterLiveTune` | AF 窗口只在 `startAutoZoom` 后 2 秒 | ❌ |
| config 取值 | 18:08~18:10 与 18:29:41 之后**同配置**，前者崩后者不崩 | ❌ |
| 流配置 | `1440x1080 / 640x480 / 4096x3072×7 / 8192x6144×2` 在「崩 6 / 不崩 6」里完全一致 | ❌ |
| 快门次数、会话内第几次进、provider 年龄 | 方向都反或无差异 | ❌ |
| `camera.feature.isSupportAiModule` | 全天 0 次被相机查询（`DeviceProbe` 挂了 5 个重载）是死的 | 不需回滚 |

**所以：结论只能是「止血」，不是「修好」。** 唯一还没被实验否掉的结构差异是
`build_legend.sh` 把原 `classes.dex` 连同新类一起重跑了一遍 d8（113824 → 163664 字节），
但 jadx diff 显示那 17 个类的源码**完全一致**，也可能无辜。
真凶要靠新的崩溃样本来二分（变体 A = 原 dex 字节不动 + 新类另开 `classes2.dex`；
变体 B = 只重跑原 17 类、不带新类）。

> `build.DEVICE=madrid`、`block.msg=11`、`pixel.fix`、`cvtype.natural`、`enable_mode`
> 都是**原模块预设**，不是本仓库的 delta。

### 4b. AI帮拍「用着用就突然卡死」——预览断流 wedge（10-08/09 定位）

**App 侧全程健康，是管线不出帧。** 两场现场的线程转储（`dumpStuck`）：

- 10-08 17:22:17 切进 168 → 1.5s 后 `停帧(MIVI忙)` 一路涨到 11.6s → 进程死亡重启；
  随后 provider 会话 `close()` 卡 `CameraBufferManager::Destroy →
  ConcurrentQueue::Dequeue()` **48 秒** → ANR（`/data/anr/anr_2026-10-08_17-23-19-968`：
  cameraserver → provider binder 阻塞链 + 多个 AI 推理线程在 `libcdsprpc ioctl`）。
- 10-09 00:25 `停帧(MIVI空)` 29.8s 后自愈（重复请求丢失型）。

两场的 `[main/RUNNABLE] nativePollOnce`、全部相机线程 WAITING/idle ——
**没有任何线程被卡 = HAL/管线 wedge，不是 Java 死锁**。

**两个对症杠杆（都是纯配置，force-stop 生效）：**

1. `diag.stallheal=1`：模块自带三级停帧自愈（①1.5s `resumePreview` 零成本重发
   → ②4s 且 MIVI 空才 `abortCaptures` → ③12s 强制 flush）。当年因假停帧误按关掉，
   假停帧根因已在 v0.15.0「真帧心跳」根修，10-09 重新打开。
   MIVI空 型 1.5s 即可救活；MIVI忙 型 ①×2 后等 ③@12s 强制 flush 兜底。
2. `ai.hdrfix=2`（**10-09 根治，取代此前的 =1 阻断档**）：
   - **=1 阻断档被实测证伪**：hdrfix=1 下 `Camera2Module$c.a(1)`（下发 hdrType 的
     唯一入口）一次都没被调用、HDR 进入已彻底堵死，12:17/12:22 **照样** 同栈
     `Fatal signal 6 ... camera.provider` —— 说明「挡住 HDR 进入」不是病因方向，
     HAL 在亮场照样楔死（帧先停 → `PrepareForRecovery` 连败 6 次 → abort）。
   - **=2 = 放行进入 + 双强制 hdrType=5**：同时挂 `isSuperResolutionHDR()` 与
     `Pe.b.m2()` 为 true（旧 =2 只强制前者，`(m2() && isSR())` 在 m2()=false 时
     短路 → type1 静默溜进去，10-08 卡死的漏洞），使公式必得 5。依据作者 10-02
     统计：AI帮拍 + hdrType=1 → 12/12 崩，hdrType=5 → 0 崩。
   - **实测（10-09 13:04 起）**：`choke: i9=1` 反复出现且每次都 `↳ 放行进入 +
     双强制 → hdrType=5`，新 APK 起 provider SIGABRT 归零，用户确认「不卡了」。
   - 安全兜底：两个挂点任一失败 → 自动退回 =1 阻断语义，绝不放 hdrType=1 出去。
   代价：AI帮拍 亮场走超分HDR（type5），HDR 仍然生效。

### 5. 智能构图：✅ 10-04 已修好（走 v2，端侧合成数据）

**做法**：`ai.smartcomp=8` 抬 `U3/t0/S0/R` 四道门让相机自己走原生 `reInit` 打开 v2 通路
（不强抬 `F.d0` → PIP 样张框不注册，用户要的回退），再在
`s6.s0.consumeResultOnMainThreadIfDataChanged` 前**合成 `com.xiaomi.camera.autoCrop.autoCropData = float[6]`**：
`[0..3]` = 取景框 x/y/w/h（w/h 是偏移量）、`[4]` = 目标倍率、`[5]` = CompositionDataType。
框来自**人脸 → 触摸对焦区 → 上一帧（800ms）→ 兜底矩形**，经 `SCALER_CROP_REGION` 换算到 preview
坐标；变焦按「主体应占画面宽度 `ring.fill`」反算，走 `R6.C0.W4()` 官方
`startZoomRatioAnimator` 通路连续推进（用户手动动变焦则冷却 1.5s 不抢）。

**踩过的坑**：兜底矩形必须**居中**（`x=0.5-w/2, y=0.5-h/2`）。`mTargetAreaRect` 恒等于
displayRect 正中心，`mFocusAreaRect` 是我们合成的框；两者不重合 → 引导环画箭头 + 方向提示，
旧默认 `x=0.40,y=0.30` 把兜底框放到屏幕中心下方 ~162px → 没主体时**永远提示「向下移动手机」**。

**10-06 定案：没主体时「一直在动 / 顶部『构图完成』+ 中间白方块反复出现消失」（v0.14.2）**

两层原因，都修了：

1. **状态机循环**：无主体时原生 `consumeResultOnMainThreadIfDataChanged` 照样执行，
   拿**空 RectF** 去刷 UI → `compositionShow → Completed → Ignore the data(×25) → Idle → 又 Show`，
   约 1~2 秒一圈，正是顶部提示条和白方块反复消失又显示。
   修法：无主体时**直接 `p.setResult(null)` 拦掉原生**，UI 一次都不刷。
   （已核对：该方法只「读数据 → 算坐标 → 刷 UI」，**不参与帧流转**，拦掉不会导致 MIVI 空帧。）
2. **根本没走到无主体分支**：`ring.src` 默认 0 的兜底是**自动对焦区**，而相机对空墙也永远有
   中心 AF 区（实测正好在画面正中心 `2048,1536`、面积仅 8%，**不触发 `ringAf` 自带的 >55% 过滤**）
   → 永远判「有主体」。改成 **`ring.src=1` 只认人脸** = 画面里没人就完全静止（对齐 18 Pro Max）。

> 踩坑：「有没有主体」只能看 `sRingSeen`（`SystemClock.elapsedRealtime` 时基），
> **不能看 `F_V2_DATA`** —— `ringTick` 在主体消失后仍会沿用上一帧最长 800ms，
> 那期间数据依然有效，只看数据会让静默被每帧复位（第一版就栽在这里，`fresh` 恒 true 静默形同虚设）。

顺带关掉 `ring.probe`（诊断用，在帧回调线程刷几百条 CaptureResult key，默认 `=0`）。

<details><summary>历史：10-02 的诊断（两代实现 + 8 道门）</summary>

**核心事实：相机里其实有两代智能构图，`CaptureModule.appendInterceptor` 靠 `V3`/`U3` 二选一。**

```java
if (!V3) { if (U3) add(v2 拦截器 s6.s0); return; }   // v2 = autoCrop 代
add(v1 拦截器 s6.r0 = SmartCompositionSimpleASD);      // v1 = ASD 代
// updateSmartComposition():  if (!U3) { if (V3) v1分支; return; } else v2分支
```

| | v1（ASD 代） | v2（autoCrop 代） |
|---|---|---|
| 数据源 tag | `xiaomi.ai.misd.SemanticScene` | `com.xiaomi.camera.autoCrop.autoCropData` |
| 进入条件 | `V3=true` | `V3=false && U3=true` |
| 本机 HAL 有没有该 tag | ❌ 0 命中 | ❌ 0 命中 |
| 10-02 实测 | **0 次**（从没走到过） | **99 次**，首条 `13:52:11.516` = `ai.smartcomp=3` 生效那刻，**之前 0 次** |

**于是最反直觉的一条：`ai.smartcomp ≥ 1` 抬 `U3`，等于主动把相机推进 v2 那条死路。**

**8 道门清单**（每条都带日志或系统侧证据）：

| 门 | tag | HAL | 日志证据 | 我们抬了吗 |
|---|---|---|---|---|
| ① `h.U3` 能力门 | `autoCrop.autoCropVersion` | ❌ | `SupportSmartCompositionVersion:null` ×1122 | ✅ level≥1 |
| ② `h.t0` 比例门 | `autoCrop.autoCropSupportSize` | ❌ | `SupportSmartCompositionSize:null` ×47 → 默认 4x3 | ✅ level≥2 |
| ③ `g.S0` 下发门 | `autoCrop.autoCropEnable` | ❌ | `isTagDefined:false` 818/883（65 次 true **全在 13:52–14:12** = 我们 level3 窗口） | ✅ level=3 |
| ④ `o0.N0()` 状态下发 | `autoCrop.autoCropState` | ❌ | `Not Supported SmartComposition State` **257/257** | ❌ |
| ⑤ **v2 数据源** | `autoCrop.autoCropData` | ❌ | `composition data: Exception!` **696/696** | ❌ |
| ⑥ **`h.V3` 分叉门** | `supportedfeatures.asd.aiComposition` | ❌ | — | ❌ ← **level=5 只抬这道** |
| ⑦ **v1 数据源** | `xiaomi.ai.misd.SemanticScene` | ❌ | — | — |
| ⑧ `F.d0` 模式门 | 硬编码 `i9 == 163` | — | — | ❌（AI帮拍=168 进不去） |

系统侧证据（**带对照实验，证明搜索方法可靠**）：
- chi override 注册 **31 个 `com.xiaomi.*` 节，没有 `autoCrop`**
- `autoCrop.*` 四个 tag、`asd.aiComposition`、`xiaomi.ai.misd.SemanticScene` 全系统字面量 **0 命中**
- 对照：同一节 `xiaomi.ai.misd` 下确实存在的 `NonSemanticScene` **7 处命中** ✅

构图引擎侧的空转（10-02 全天）：`updateSmartCompositionFromASD` **0 次**、
`updateCurrentSmartCompositonIndex` **0 次**、`updateCompositionUI isValidData` **0 次**（`false` 696 次）。
而 `updateSmartCompositionCropState` 在 **21:21–21:40（当前构建）出现 48 次** —— 说明
门① 是开着的（`f17374a=true`），开关和状态都在动，**只是数据永远是空的**。

**当时的动作**：`ai.smartcomp=5` = 只抬 `V3`、`U3/t0/S0/R` 一个不碰 → 强行走 v1，
并挂 v1 通路探针（`F.d0` / `r0.initAndGetPriorCondition` / `r0.getInTimeCondition` /
`r0.acceptResult`）。**必须在拍照模式(163)测**，AI帮拍(168) 被 `F.d0` 的 `i9==163` 挡住。

> 仍未被实验否掉的路：① v1 实测；② 内置 debug 后门 `debug_composition_enable` /
> `debug_composition_index`；③ 把 `C0.v0` 重定向到已存在的 `NonSemanticScene`（解析格式可能对不上）；
> ④ 完全绕开 HAL 做软件裁剪。
</details>

### 6. 还没解决

- `Logical CameraId = 15 is invalid` / `Out of bound camera 15`
- 快门后 ~1.63s 的**成片时长硬上限**，两轮都没突破（`maxImage=8→60` 也无效）
- 成片**前 0.5s 仍是静止画面**（这段内容本来就拍在快门之前，改动画时长救不了；
  要解决得把成片的时间窗整体后移，即动 ring 的 head / PTS 偏移）
- 红毯运镜「拍不出来」：`_231_4 takes 3122 ms` 无成图、该窗口 2_5 零帧，
  日志有 `isBlockSnap: master live is in zoom after reset`
- 传奇真算法（`MadridLegendary`）移植

---

## 成片分析脚本

输入是 MVIMG 里内嵌的 mp4，抽成 162x288 灰度帧序列后做几何比对
（测「两帧之间的缩放系数 k」，用 NCC 做相似度，抛物线插值细化峰值）。

| 脚本 | 用途 |
|---|---|
| `mp4x.py` | 从 MVIMG 提取内嵌 MP4 |
| `mp4dur.py` | 帧数 / 时长 / Δpts 均匀性 |
| `wscan.py` | **主力**：逐 5 帧窗**独立**（非链式）k 扫描，输出峰值 k、1-NCC、ASCII 曲线 |
| `kscan.py` | 打印单对帧的 NCC-vs-k 完整曲线 |
| `pairk.py` | 指定 `i:j` 对做全范围 k 搜索 |
| `ncczoom.py` `winzoom.py` `zoomcmp.py` `zoomdiff.py` `rawzoom.py` | 链式 / 滑窗 / 日志对齐等早期版本 |
| `framestats.py` | 逐帧清晰度 + 帧体积统计（看糊→清跳变） |

`wscan.py` 用法：

```bash
python3 wscan.py <gray文件> <宽> <高> <k下限> <k上限>
```

**方法本身的正确性已用合成数据验证**：把 f30 人为放大 1.14 倍，
算法精确找回 `k=1.14`，峰值极尖（1−NCC 0.081 vs 1.10 处 0.31 / 1.20 处 0.41）。

---

## 单位与时钟的坑（再踩一遍就不是人）

- `onImageAvailable2_5 ts`、`onCaptureStarted:timestamp` 是 **纳秒**
- `firstFramePTS` / snap / head / first / last 是 **微秒**
- `(ts差)/1e9` 才是秒，`/1e6` 是**毫秒**
- 传感器时钟比日志墙钟**快 0.2~0.45s**（末帧算出的延迟会是负数）
  —— 绝对值测不准，但**同设备同会话两组对比有效**

---

## 非线性换镜变焦（10-05，双模块发布）

同时用两个模块落地，**缺一不可**：

| 模块 | 载体 | 作用层 |
|---|---|---|
| **LSPosed 模块**（`legendfix.apk`） | LSPosed，作用域 `com.android.camera` | app 层：配置自愈、按倍率分段放行换镜、变焦路径探针 |
| **KSU 模块**（`camzoom`，源码 `ksu-module/`） | KernelSU 刷入 | HAL 层：bind 挂载 3 个 `/odm/etc/camera` 配置到 init ns |

### ⚠ 机型白名单（v1.1 新增）

模块里的 3 份配置是 **Xiaomi 17 Pro (pandora) 原厂文件改出来的**，
其中 `camxoverridesettings.txt` 是**整机 HAL 配置** —— 直接覆盖到别的机型会让
CHI 配流失败、**相机打不开**（小米17 已经踩过）。

`devices.txt` 每行一个 `ro.product.device`，**不在名单里模块直接跳过、一个字节都不改**：

```
pandora          # 当前唯一验证过的机型
# 要在别的机器上试：先备份 /odm/etc/camera/ 下同名文件，
# 把 `getprop ro.product.device` 追加一行，再重启验证
```

KSU 模块挂载（`post-fs-data.sh`，均 `mount --bind` 到 pid1 ns）：

```
files/satsettings.json         -> /odm/etc/camera/xiaomi/satsettings.json
files/miZA_params.json         -> /odm/etc/camera/miZA_params.json
files/camxoverridesettings.txt -> /odm/etc/camera/camxoverridesettings.txt   # 缺它会 CHI 配流失败闪退
```

### 结果
- `0.7 ↔ 1` 切换**模糊消失**；
- `2.6 ↔ 5` 视差跳动减轻，且**焦距差异消失**（前景不再窜位）；
- 配置被 app 覆盖后能**自愈**（`ConfigSelfHeal` 只补缺失键，不再强制改写用户改过的键）。

### 根因定案
1. **模糊**：`isCameraSwitchingDuringZoomingAllowed() false→true` 与 `telefix v6.f.q() -1→4`。二者独立，处置是 `legend.lensswitch=1` + `legend.telefix=0` + `legend.lensswitch_minzoom=1.5`（≥1.5 才放行，低倍不干预）。
2. **预览不顺、不像非线性**：18PM 的 `smoothZoomV2`（`defaultZoomInCurve` / `495ms` 等）**在本机固件里根本没有代码** —— 全系统 `*.so` 与相机 APK 全文检索零命中，**改配置永远开不出来**。18PM 的顺滑来自它 HAL 独有能力，不能靠抄配置获得。
3. **18PM 的配置不能整包照抄**：把 `bezierCurve=0.6:1.0:0.8:1.0` 搬过来反而造成 app 缓动与 HAL 缓动错配 → 出现「焦距差异」。**删掉该键、让 HAL 走默认曲线后差异消失**（键缺失 ≠ 设为线性，两者完全不同）。
4. **「想跑快但卡」是热降频**：CPU `scaling_max_freq` 被压到 1.1 GHz（上限 4.6 GHz）、核心 83°C。降温后不卡。抓日志时务必 `persist.vendor.sat.debug.log=0`，否则每帧打印同样会烤机。

### config.conf 开关（LSPosed）

```
legend.lensswitch=1             # 换镜放行总开关
legend.lensswitch_minzoom=1.5   # 只在倍率 ≥ 此值时放行（治低倍模糊）
legend.telefix=0                # 长焦修复保持关闭，与 lensswitch 叠加会模糊半秒
legend.zoompath=0               # 变焦路径探针（排障用，常开有开销）
legend.zoomdur_ms=0             # 强制 app 变焦动画时长(ms)，0=不干预
```

⚠ 实测：`zoomdur_ms` 只驱动 app 侧 ramp（100ms→1 帧跳完、800ms→776ms，可客观复现），
但**肉眼几乎无感**；2000ms 会让下面的小数字跟不上画面。**默认保持 0。**

---

## 致谢 / 归属

- **[XiaomiCamPort]** —— 原 LSPosed 模块，本仓库的所有 hook 都是追加在它之上。
  原作者的代码、`module.apk`、签名密钥**均不在本仓库内**。
  正在联系原开发者探讨 fork / 上游合并。
- 相机 APK 与 HAL 相关代码属小米专有，本仓库**不含**任何反编译产物。

[XiaomiCamPort]: https://github.com/search?q=XiaomiCamPort
