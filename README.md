# camport-tweaks

小米 17 Pro 上跑 **18 Pro Max 相机 APK**（`6.8.001770.0`）的 LSPosed 模块扩展，
让三个在本机原本不生效的功能落地：

| 功能 | 状态 |
|---|---|
| **传奇一瞬**（M3 / M9 / M10R） | ✅ 预览 + 成片都通 |
| **调色盘** | ⬜ 未做 |
| **实况运镜**（mode 231 自由 / 主角 / 红毯） | ✅ 能用，⚠️ 成片观感不理想（见下） |

> **本仓库为 Private。** 所有 hook 都是追加在 **XiaomiCamPort** 这个第三方模块之上，
> 原模块**不是我的**。仓库里**不含**原模块的 `module.apk`、不含相机 APK、不含反编译产物。
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
    Mode231Fix.java       实况运镜：闪退修复 + 绿色花屏修复 + 帧探针
    Mode231Probe.java     实况运镜：开相机 id / 流配置探针
    LegendaryColor.java   传奇一瞬：M3/M9/M10R -> CubeLut 滤镜映射
build_legend.sh           javac -> d8 -> zip -> apksigner -> pm install -r
config.example.conf       全部可调键（拷到设备 config.conf 用）
*.py                      成片分析脚本（下面单独说）
```

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
4. 打包：重写 `assets/xposed_init`（五行，含 `MasterLiveTune`）
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

### 4. 还没解决

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
