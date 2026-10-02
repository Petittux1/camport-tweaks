# camport-tweaks

小米 17 Pro 上跑 **18 Pro Max 相机 APK**（`6.8.001770.0`）的 LSPosed 模块扩展，
让三个在本机原本不生效的功能落地：

| 功能 | 状态 |
|---|---|
| **传奇一瞬**（M3 / M9 / M10R） | ✅ 预览 + 成片都通 |
| **实况运镜**（mode 231 自由 / 主角 / 红毯） | ✅ 能用，⚠️ 成片观感不理想（见下） |
| **AI帮拍**（mode 168 / `0xa8`） | ✅ 不再崩溃（`fd=0` 自 10-02 18:29 起归零），⚠️ 成因未定位 |
| **智能构图** | ⚠️ 开关显示开但**实际不生效**，且点不掉（`ai.smartcomp` 只是隔离开关） |
| **调色盘** | ⬜ 未做 |

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
    SmartCompFix.java     智能构图：ai.smartcomp 隔离开关（+ 可选 submit 钩子）
    HdrFix.java           AI帮拍：自动HDR互斥阻断 ai.hdrfix
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

# ---- 智能构图（隔离用，见「还没解决」）----
ai.smartcomp=1          0=完全不 hook 智能构图   1=启用下面的钩子
ai.smartcomp.submit=0   0=只打日志   1=真的调 A3.g.w() 提交构图

# ---- AI帮拍 / 自动HDR 互斥 ----
ai.hdrfix=1             1=进入 AI帮拍 时阻断「自动HDR」互斥抢模式
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

### 5. 还没解决

- **智能构图不生效**：开关显示开、`ai.smartcomp=1` 也在打日志，但实际画面没有构图裁剪。
  想做「真裁剪」已经走不通 —— `com.xiaomi.camera.autoCrop.*` 的 vendor tag 在 HAL 里**全缺**。
  目前卡在「构图引擎没喂数据」：`CompositionList` 拿不到，
  而 `SmartCompositionSimpleASD` 的 verbose 日志在 logcat 里看不到。
- **智能构图开关点不掉**：点了解除不了，一直显示开（小问题）。
- `dex/classes6.dex`（2.25MB，含 `updateCompositionUI`）还没反编译。
- `Logical CameraId = 15 is invalid` / `Out of bound camera 15`
- 快门后 ~1.63s 的**成片时长硬上限**，两轮都没突破（`maxImage=8→60` 也无效）
- 成片**前 0.5s 仍是静止画面**（这段内容本来就拍在快门之前，改动画时长救不了；
  要解决得把成片的时间窗整体后移，即动 ring 的 head / PTS 偏移）
- 红毯运镜「拍不出来」：`_231_4 takes 3122 ms` 无成图、该窗口 2_5 零帧，
  日志有 `isBlockSnap: master live is in zoom after reset`
- 调色盘、传奇真算法（`MadridLegendary`）移植

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

## 致谢 / 归属

- **[XiaomiCamPort]** —— 原 LSPosed 模块，本仓库的所有 hook 都是追加在它之上。
  原作者的代码、`module.apk`、签名密钥**均不在本仓库内**。
  正在联系原开发者探讨 fork / 上游合并。
- 相机 APK 与 HAL 相关代码属小米专有，本仓库**不含**任何反编译产物。

[XiaomiCamPort]: https://github.com/search?q=XiaomiCamPort
