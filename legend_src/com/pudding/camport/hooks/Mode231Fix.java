package com.pudding.camport.hooks;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.FileWriter;

/**
 * 实况运镜（mode 231）闪退修复。
 *
 * 根因（logcat 的角色表 + dex 反编译双向确认）：
 *   getActualOpenCameraId 的实现 B2.c.c() 对 mode 231 算出 cameraId = -1，
 *   openCamera 拿到 cid=-1 连续 Failure 三次 -> camera auto recover fail -> Activity 自己退出。
 *   不是 Java 崩溃（logcat 无 FATAL），是相机自我保护。
 *
 * 为什么会是 -1：
 *   p181v6.e.q()（= getAuxCameraId()，镜头表 v6.f.R().q()）
 *       int i9 = this.f18025h.get(20, -1);        // 硬查 vendor 角色 id 20
 *   而 p181v6.e.K()（= getUltraTeleCameraId()）
 *       int i9 = this.f18025h.get(23, -1);        // 角色 id 23
 *
 *   本机实际注册的角色表（logcat MCAM_Camera2CompatAdapterRole）：
 *       role:   0 ( 74.1°) <->  2      主摄
 *       role:  21 ( 88.6°) <->  3      超广角
 *       role:  23 ( 17.1°) <->  4      ★本机唯一的长焦
 *       role:  24 ( 17.1°) <->  4      ★
 *       role:  60 ( 74.1°) <->  5 = [2,3,4]   SAT
 *       role: 108 ( 51.5°) <->  8
 *   表里没有 role 20 —— 18 Pro Max 有标准长焦（role 20），本机只有超长焦（role 23）。
 *
 *   而 mode 231 的镜头组件值 n.h(231) = "tele"，
 *   U3/r 走 case "tele": gVarM = f.R().M(f.R().q()) -> M(-1) -> null。
 *
 *   长焦硬件是真实存在的（camera 4，FOV 17.1°），只是注册的角色编号不同 ——
 *   所以这里把 role 20 缺失时回退到本机真实存在的长焦。
 *
 * 只在算出负数时介入，正常路径一个字节都不碰。
 */
public final class Mode231Fix implements IXposedHookLoadPackage {
    private static final String TAG = "CamPort";
    private static final String HEAD = "运镜fix: ";

    private static final String C_CAMERA_PKG = "com.android.camera";
    private static final String C_GETID = "B2.c";
    private static final String C_DATA_N = "com.android.camera.data.data.n";
    private static final String C_LENS = "v6.f";   // jadx 改名成 p181v6.f，真实 descriptor Lv6/f;

    /**
     * dex Lm6/n; -> jadx p106m6.n：
     *   public static Size c(Size, m6.n$a)   = getLivePhotoVideoSize
     *   n$a 的 int 字段 "d"（jadx 显示成 f14105d，"renamed from: d"）就是 mode。
     *
     * 这是 2_5 实况流尺寸的**唯一上游**：
     *   n.c() -> Camera2Module sizeC -> F.put(m6.n.b.R, sizeC)
     *          -> updateSizeResult() case 28 ("LivePhotoVideoSize") -> C1419j0.w
     *          -> startLivephoto: ImageReader.newInstance(.w) + PostProcServiceClient.configSurface(.w)
     *          -> Gm.s.b2() 只是把它 swap 后打日志
     */
    private static final String C_LIVE_SIZE = "m6.n";
    private static final String C_LIVE_ARG = "m6.n$a";

    /** vendor 特性：决定 MasterLive 支持哪些 scene / 用哪颗镜头 / 分辨率 */
    private static final String VENDOR_INFO = "com.xiaomi.camera.masterLivePhoto.info";

    private static ClassLoader sCl;
    private static android.content.Context sCtx;
    private static boolean sInstalled;
    private static boolean sDumped;
    private static String sLastError;
    private static FileWriter sWriter;

    /** 当前 B2.c.c() 正在算哪个 mode（用来在帧采样里标注来源） */
    private static volatile int sCurMode;
    private static int sIdx231;
    private static int sIdx163;
    private static boolean sSaved231;
    private static boolean sSaved163;
    private static boolean sSavedBad;
    private static int sDumpedBad;

    /** 修复后 2_5 实况流首帧落盘，用来数值复核（stride / 内容长度） */
    private static int sDumpedLive;
    private static boolean sSaved231Live;

    /**
     * 实况编码流（startLivephoto 新建、喂进 CircularVideoEncoderV2 的那一路 2304x1296）
     * 按「一次拍摄」分段落盘，用来看画面里到底有没有 zoom。
     *
     * 实测每次拍摄 42~45 帧：第 0 帧是快门后 1.5s 的历史回灌，中间快门处有 ~500ms 空洞，
     * 最后一帧固定落在 snap+1.664s（PostProcService 发 ts=-1 收工）。
     * 取 0/8/16/24/32/40 六个位置，正好横跨「动画前 → 动画中」。
     */
    private static final int[] ENC_DUMP_AT = {0, 8, 16, 24, 32, 40, 44};
    private static long sEncLastTs = Long.MIN_VALUE;
    private static long sEncFirst;
    private static long sEncLast;
    private static int sEncShot;
    private static int sEncIdx;
    private static int sEncCnt;
    private static int sEncDumped;

    /**
     * 绿色花屏修复开关：强制 mode 231 的 2_5 实况流尺寸。
     *
     * 实测：PostProcService 不管传进去的尺寸是 1920x1440 还是 1728x1296，
     * 都按 1728x1296 往 ImageReader 里写（Y 是 packed stride=1728 的 1296 行）。
     * 应用却按 1920x1440 读 -> 行错位 + 未覆盖区为 0 -> Y=125 U=0 V=0 = BT.601 纯绿。
     * mode 163 传的正好是 1728x1296，所以普通实况是好的。
     *
     * config.conf: live231size=1728x1296 | auto(基础尺寸x0.9) | off
     */
    private static volatile boolean sCfgDone;
    private static volatile boolean sSizeFix = true;
    private static volatile boolean sSizeAuto;
    private static volatile int sSizeW = 1728;
    private static volatile int sSizeH = 1296;

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
        log("fix installed proc=" + lpparam.processName);

        // 拿到 Context 才能取 CameraManager。
        // 注意：attach() 时刻 getApplicationContext() 还是 null（mApplication 在 attach 之后才赋），
        // 所以这里必须存 attach 传进来的原始 base context（ContextImpl，getSystemService 可用）。
        try {
            XposedHelpers.findAndHookMethod(android.app.Application.class, "attach",
                    android.content.Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (sCtx == null && param.args != null && param.args.length > 0
                                    && param.args[0] instanceof android.content.Context) {
                                sCtx = (android.content.Context) param.args[0];
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!sDumped) {
                                sDumped = true;
                                dumpMasterLiveInfo();
                            }
                        }
                    });
        } catch (Throwable th) {
            err(th);
        }

        try {
            XposedHelpers.findAndHookMethod(C_GETID, sCl, "c",
                    Integer.TYPE, Integer.TYPE, Boolean.TYPE, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int mode = ((Integer) param.args[1]).intValue();
                                sCurMode = mode;
                                int ret = ((Integer) param.getResult()).intValue();
                                if (ret >= 0) {
                                    return;
                                }
                                int fix = resolve(mode);
                                if (fix < 0) {
                                    log("!! mode=" + mode + " cid=" + ret
                                            + " 且回退也失败，保持 " + ret);
                                    return;
                                }
                                param.setResult(Integer.valueOf(fix));
                                log("mode=" + mode + " cid " + ret + " -> " + fix
                                        + " role=" + role(mode));
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
        } catch (Throwable th) {
            err(th);
        }

        installLiveSizeFix();
        installFrameProbe();
    }

    /**
     * 绿色花屏修复：把 mode 231 的 2_5 实况流尺寸改成服务端真正输出的尺寸。
     *
     * 依据（对坏帧原始平面做 stride 扫描）：
     *   Y  平面：stride=1728 时垂直平均差 2.891，stride=1920 时 44.075
     *            且字节 [0, 2239488) 全部有效、[2239488, 2240640) 全 0
     *            -> 2239488 = 1728 x 1296 整除，服务端写的就是 1728x1296
     *   UV 平面：同样 stride=1728 最佳（0.439 vs 1920 的 9.452）
     * 而 ImageReader / configSurface 收到的是 1920x1440 —— 尺寸错配。
     *
     * 只在 n$a.d == 231 时介入；163/171 的路径一个字节都不碰。
     */
    private void installLiveSizeFix() {
        try {
            XposedHelpers.findAndHookMethod(C_LIVE_SIZE, sCl, "c",
                    android.util.Size.class, XposedHelpers.findClass(C_LIVE_ARG, sCl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                int mode = XposedHelpers.getIntField(param.args[1], "d");
                                // 顺手同步当前 mode，给帧探针标注来源用
                                if (mode == 163 || mode == 171 || mode == 231) {
                                    sCurMode = mode;
                                }
                                if (mode != 231) {
                                    return;
                                }
                                ensureSizeCfg();
                                if (!sSizeFix) {
                                    return;
                                }
                                android.util.Size old = (android.util.Size) param.getResult();
                                if (old == null) {
                                    return;
                                }
                                int w;
                                int h;
                                if (sSizeAuto) {
                                    // 复刻 mode 163 的规则：基础尺寸 x livephotoRatio(0.9)
                                    w = even(old.getWidth() * 0.9);
                                    h = even(old.getHeight() * 0.9);
                                } else {
                                    w = sSizeW;
                                    h = sSizeH;
                                }
                                if (old.getWidth() == w && old.getHeight() == h) {
                                    return;
                                }
                                param.setResult(new android.util.Size(w, h));
                                log("live231size " + old.getWidth() + "x" + old.getHeight()
                                        + " -> " + w + "x" + h + " mode=" + mode
                                        + (sSizeAuto ? " (auto x0.9)" : ""));
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
            log("live231size hook installed");
        } catch (Throwable th) {
            err(th);
        }
    }

    private static int even(double v) {
        int i = (int) Math.round(v);
        return (i & 1) == 0 ? i : i + 1;
    }

    /** 读 config.conf 的 live231size；只读一次 */
    private static synchronized void ensureSizeCfg() {
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
            java.io.File f = new java.io.File(paths[i]);
            if (f.isFile() && f.canRead()) {
                chosen = paths[i];
                break;
            }
        }
        if (chosen == null) {
            return;
        }
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader(chosen));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.length() == 0 || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0 || !"live231size".equals(t.substring(0, eq).trim())) {
                    continue;
                }
                String v = t.substring(eq + 1).trim();
                if ("off".equalsIgnoreCase(v) || "0".equals(v) || "false".equalsIgnoreCase(v)) {
                    sSizeFix = false;
                } else if ("auto".equalsIgnoreCase(v)) {
                    sSizeAuto = true;
                } else {
                    int x = v.indexOf('x');
                    if (x <= 0) {
                        x = v.indexOf('X');
                    }
                    if (x > 0) {
                        int w = Integer.parseInt(v.substring(0, x).trim());
                        int h = Integer.parseInt(v.substring(x + 1).trim());
                        if (w > 0 && h > 0) {
                            sSizeW = w;
                            sSizeH = h;
                        }
                    }
                }
            }
            log("live231size config=" + chosen + " on=" + sSizeFix
                    + " auto=" + sSizeAuto + " " + sSizeW + "x" + sSizeH);
        } catch (Throwable th) {
            err("ensureSizeCfg", th);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable th) {
                }
            }
        }
    }

    private static void err(String where, Throwable th) {
        log("ERROR " + where + " " + th);
    }

    /**
     * 实况（2_5）帧内容采样 —— 直接判断「绿色花屏」是不是空帧造成的。
     *
     * NV12 全零缓冲（Y=0 U=0 V=0）按 BT.601 反算出来正好是 RGB(0,135,0) = 纯绿，
     * 所以只要 Y 的 mean/min/max 全是 0、U/V 的均值也掉到 0，
     * 就说明 HAL 往这条流里塞的是没写过数据的 buffer —— 会话参数没下发的后果。
     * 反过来如果 Y 有正常起伏、U/V 均值在 128 附近，帧是好的，问题在 GL/编码那段。
     */
    private void installFrameProbe() {
        try {
            XposedHelpers.findAndHookMethod(android.media.ImageReader.class, "acquireNextImage",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object r = param.getResult();
                                if (!(r instanceof android.media.Image)) {
                                    return;
                                }
                                android.media.Image img = (android.media.Image) r;
                                int w = img.getWidth(), h = img.getHeight();
                                // 编码器实况流到底是什么格式？format!=35 会被下面静默丢掉，
                                // 先记一行免得白排查。
                                if (w == 2304 && h == 1296 && img.getFormat() != 35) {
                                    log("enc231帧 format=" + img.getFormat() + " " + w + "x" + h);
                                }
                                if (img.getFormat() != 35) {     // YUV_420_888
                                    return;
                                }
                                // 修复前 2_5 是 1920x1440（错配），修复后是 1728x1296（服务端真实尺寸）
                                // live231size=auto 后编码器流是 2304x1296（2560x1440 x 0.9）——
                                // 这一路才是喂进 CircularVideoEncoderV2 的，之前探针完全没采到它。
                                // mode 163 的 2_5 也是 1728x1296，靠 sCurMode 区分
                                boolean is231 = (w == 1920 && h == 1440)
                                        || (w == 2304 && h == 1296)
                                        || (w == 1728 && h == 1296 && sCurMode == 231);
                                boolean is163 = (w == 2560 && h == 1440)
                                        || (w == 1728 && h == 1296 && !is231);
                                if (!is231 && !is163) {
                                    return;
                                }
                                probeFrame(img, w, h, is231);
                            } catch (Throwable th) {
                                err(th);
                            }
                        }
                    });
        } catch (Throwable th) {
            err(th);
        }
    }

    private static void probeFrame(android.media.Image img, int w, int h, boolean is231) {
        int idx = is231 ? sIdx231++ : sIdx163++;
        android.media.Image.Plane[] pl = img.getPlanes();
        if (pl == null || pl.length < 3) {
            return;
        }
        java.nio.ByteBuffer yb = pl[0].getBuffer().duplicate();
        java.nio.ByteBuffer ub = pl[1].getBuffer().duplicate();
        java.nio.ByteBuffer vb = pl[2].getBuffer().duplicate();
        int yStride = pl[0].getRowStride(), yPix = pl[0].getPixelStride();

        int yBase = yb.position(), yLen = yb.limit() - yBase;
        long sum = 0;
        int mn = 255, mx = 0, n = 0;
        if (yLen > 0) {
            int step = Math.max(1, yLen / 4096);
            for (int i = 0; i < yLen; i += step) {
                int v = yb.get(yBase + i) & 0xff;
                sum += v;
                if (v < mn) mn = v;
                if (v > mx) mx = v;
                n++;
            }
        }
        long uMean = planeMean(ub), vMean = planeMean(vb);

        // NV12 的 U/V 是交错存在同一个 buffer 里的（rowStride 1920 / pixelStride 2），
        // 必须偶数位=U、奇数位=V 分开统计，否则两个值永远相等、分不出谁坏了。
        long[] evOdd = evenOddMeans(ub);
        long[] evOdd2 = evenOddMeans(vb);
        String hex1 = hex(ub, 32), hex2 = hex(vb, 32);

        long yMean = n > 0 ? sum / n : -1;
        boolean bad = evOdd[0] < 100 || evOdd[1] < 100;

        if (is231) {
            // 每帧一行：ts 用来和 logcat 的 onImageAvailable2_5 逐帧对齐，确认读的是哪一路 reader
            log("f#" + idx + " Y=" + yMean + " U=" + evOdd[0] + " V=" + evOdd[1]
                    + " ts=" + img.getTimestamp()
                    + " mode=" + sCurMode
                    + " sz=" + w + "x" + h
                    + (bad ? "   <== 绿帧" : ""));
        }
        boolean detail = idx <= 4 || (idx % 30) == 0;
        if (detail) {
            log("2_5帧#" + idx + " " + (is231 ? "[231]" : "[163]") + " " + w + "x" + h
                    + " mode=" + sCurMode
                    + " Y[mean=" + yMean + " min=" + mn + " max=" + mx + "]"
                    + " | p1buf even=" + evOdd[0] + " odd=" + evOdd[1]
                    + " | p2buf even=" + evOdd2[0] + " odd=" + evOdd2[1]
                    + " | p1pos=" + ub.position() + "/" + ub.limit()
                    + " p2pos=" + vb.position() + "/" + vb.limit()
                    + " stride y" + yStride + "/" + yPix
                    + " u" + pl[1].getRowStride() + "/" + pl[1].getPixelStride()
                    + " v" + pl[2].getRowStride() + "/" + pl[2].getPixelStride());
            log("   p1hex=" + hex1 + "  p2hex=" + hex2);
        }

        int uPix0 = pl[1].getPixelStride(), vPix0 = pl[2].getPixelStride();
        int uStride0 = pl[1].getRowStride();
        int uB = ub.position(), vB = vb.position();
        if (uPix0 == 2 && vPix0 == 2 && uB == vB) {
            vB = uB + 1;
        }

        // 前 3 个坏帧：dump 原始平面 + 逐行色度 + 直方图。
        // 连续 3 帧对比可判断「Y 下半段」是每帧都在写的实时数据，还是上一帧残留（冻结）。
        if (is231 && bad && sDumpedBad < 3) {
            sDumpedBad++;
            analyzeBad(yb, ub, yb.position(), uB, yStride, yPix, uStride0, uPix0, w, h, idx);
        }

        // 修复后 2_5 实况流（1728x1296）首帧落盘，回 Termux 复核 stride / 内容长度
        if (is231 && w == 1728 && h == 1296 && sDumpedLive < 1) {
            sDumpedLive++;
            dumpRaw("live_y.bin", yb, yb.position());
            dumpRaw("live_uv.bin", ub, uB);
        }

        boolean doPpm = false;
        String ppmName = null;
        if (is231 && bad) {
            if (!sSavedBad) {
                sSavedBad = true;
                doPpm = true;
                ppmName = "frame_231_bad.ppm";
            }
        } else if (is231) {
            boolean live = (w == 1728 && h == 1296);
            if (live && !sSaved231Live && idx <= 400) {
                sSaved231Live = true;
                doPpm = true;
                ppmName = "frame_231_live.ppm";
            } else if (!live && !sSaved231 && idx <= 200) {
                sSaved231 = true;
                doPpm = true;
                ppmName = "frame_231.ppm";
            }
        } else {
            if (!sSaved163 && idx <= 200) {
                sSaved163 = true;
                doPpm = true;
                ppmName = "frame_163.ppm";
            }
        }
        if (doPpm) {
            savePpm(ppmName, yb, ub, vb, yStride, yPix,
                    uStride0, uPix0, pl[2].getRowStride(), vPix0, w, h, idx, uB, vB);
        }

        // —— 实况编码流（2304x1296）：按一次拍摄分段，落首/中/尾多张 PPM ——
        if (is231 && w == 2304 && h == 1296) {
            long ts = img.getTimestamp();
            if (ts < 0) {
                // PostProcService 的哨兵帧（-1=discardFreeBuffers / -3=释放 reader）
                log("enc哨兵 s" + sEncShot + " ts=" + ts + " 已收" + sEncCnt + " 帧"
                        + (sEncCnt > 0 ? " 跨度=" + ((sEncLast - sEncFirst) / 1000000L) + "ms" : ""));
            } else {
                encShotNew(ts);
                int inShot = sEncIdx++;
                boolean want = false;
                for (int k = 0; k < ENC_DUMP_AT.length; k++) {
                    if (ENC_DUMP_AT[k] == inShot) {
                        want = true;
                        break;
                    }
                }
                if (want && sEncShot <= 3) {
                    sEncDumped++;
                    savePpm("enc_s" + sEncShot + "_" + inShot + ".ppm",
                            yb, ub, vb, yStride, yPix,
                            uStride0, uPix0, pl[2].getRowStride(), vPix0,
                            w, h, idx, uB, vB);
                    log("enc落盘 s" + sEncShot + " #" + inShot
                            + " ts=" + ts
                            + " 距首帧=" + ((ts - sEncFirst) / 1000000L) + "ms");
                }
            }
        }
    }

    /**
     * 识别「新的一次拍摄」：ts 倒退，或者和上一帧差 > 400ms（快门处本来就有 ~500ms 空洞，
     * 拍完到下一次开拍则差几秒）都算新的一段。顺手把上一段的收尾统计打出来。
     */
    private static void encShotNew(long ts) {
        boolean neu = sEncLastTs == Long.MIN_VALUE
                || ts < sEncLastTs
                || (ts - sEncLastTs) > 400000000L;
        if (neu) {
            if (sEncShot > 0 && sEncCnt > 0) {
                log("拍摄#" + sEncShot + " 收尾: " + sEncCnt + " 帧"
                        + " ts=" + sEncFirst + ".." + sEncLast
                        + " 跨度=" + ((sEncLast - sEncFirst) / 1000000L) + "ms"
                        + " 落盘=" + sEncDumped);
            }
            sEncShot++;
            sEncIdx = 0;
            sEncCnt = 0;
            sEncDumped = 0;
            sEncFirst = ts;
        }
        sEncLastTs = ts;
        sEncLast = ts;
        sEncCnt++;
    }

    /**
     * 坏帧解剖：逐行色度 + 直方图 + 原始平面落盘。
     * 用来回答「污染是整帧均匀的，还是只有画面下半段坏」。
     */
    private static void analyzeBad(java.nio.ByteBuffer yb, java.nio.ByteBuffer ub,
                                   int yBase, int uBase, int yStride, int yPix,
                                   int uStride, int uPix, int w, int h, int idx) {
        try {
            // 直方图（对交错 buffer 的所有字节分 8 段）
            int base = ub.position(), len = ub.limit() - base;
            long[] hist = new long[8];
            for (int i = 0; i < len; i++) {
                hist[Math.min(7, (ub.get(base + i) & 0xff) >> 5)]++;
            }
            StringBuilder hs = new StringBuilder("坏帧#" + idx + " 色度直方图(0-31..224-255):");
            for (int i = 0; i < 8; i++) {
                hs.append(' ').append(hist[i] * 100 / Math.max(1, len)).append('%');
            }
            log(hs.toString());

            // 逐行色度（UV 平面每行 uStride 字节，取该行所有样本求均值）
            int rows = len / Math.max(1, uStride);
            StringBuilder rs = new StringBuilder("坏帧#" + idx + " 逐行色度(共" + rows
                    + " 行, 每 " + Math.max(1, rows / 18) + " 行取 1):");
            int step = Math.max(1, rows / 18);
            for (int r = 0; r < rows; r += step) {
                int off = base + r * uStride;
                long se = 0, so = 0;
                int ne = 0, no = 0;
                for (int c = 0; c < uStride; c++) {
                    if (c >= ub.limit() - off) {
                        break;
                    }
                    int v = ub.get(off + c) & 0xff;
                    if ((c & 1) == 0) {
                        se += v;
                        ne++;
                    } else {
                        so += v;
                        no++;
                    }
                }
                rs.append(" r").append(r).append('=')
                        .append(ne > 0 ? se / ne : -1).append('/')
                        .append(no > 0 ? so / no : -1);
            }
            log(rs.toString());

            // 逐行亮度（同样间隔）
            int ylen = yb.limit() - yBase;
            int yrows = ylen / Math.max(1, yStride);
            StringBuilder ys = new StringBuilder("坏帧#" + idx + " 逐行亮度:");
            int ystep = Math.max(1, yrows / 18);
            for (int r = 0; r < yrows; r += ystep) {
                int off = yBase + r * yStride;
                long s = 0;
                int c = 0;
                for (int x = 0; x < w && off + x * yPix < yb.limit(); x++) {
                    s += yb.get(off + x * yPix) & 0xff;
                    c++;
                }
                ys.append(' ').append(c > 0 ? s / c : -1);
            }
            log(ys.toString());

            // 原始平面落盘，回 Termux 里做精确重建
            dumpRaw("bad" + idx + "_y.bin", yb, yBase);
            dumpRaw("bad" + idx + "_uv.bin", ub, uBase);
        } catch (Throwable th) {
            err(th);
        }
    }

    private static void dumpRaw(String name, java.nio.ByteBuffer b, int base) {
        try {
            java.io.File f = new java.io.File(
                    "/sdcard/Android/data/com.android.camera/files/camport", name);
            int len = b.limit() - base;
            byte[] arr = new byte[len];
            java.nio.ByteBuffer d = b.duplicate();
            d.position(base);
            d.get(arr);
            java.io.OutputStream os = new java.io.FileOutputStream(f);
            os.write(arr);
            os.close();
            log("已 dump " + name + " " + len + " bytes");
        } catch (Throwable th) {
            err(th);
        }
    }

    private static long planeMean(java.nio.ByteBuffer b) {
        int base = b.position(), len = b.limit() - base;
        if (len <= 0) {
            return 128;
        }
        int step = Math.max(1, len / 4096);
        long sum = 0;
        int n = 0;
        for (int i = 0; i < len; i += step) {
            sum += b.get(base + i) & 0xff;
            n++;
        }
        return n > 0 ? sum / n : 128;
    }

    /** NV12 交错：偶数偏移=U，奇数偏移=V。返回 {evenMean, oddMean} */
    private static long[] evenOddMeans(java.nio.ByteBuffer b) {
        int base = b.position(), len = b.limit() - base;
        long se = 0, so = 0;
        int ne = 0, no = 0;
        for (int i = 0; i < len; i++) {
            int v = b.get(base + i) & 0xff;
            if ((i & 1) == 0) {
                se += v;
                ne++;
            } else {
                so += v;
                no++;
            }
        }
        return new long[]{ne > 0 ? se / ne : -1, no > 0 ? so / no : -1, ne, no};
    }

    private static String hex(java.nio.ByteBuffer b, int cnt) {
        int base = b.position(), len = Math.min(cnt, b.limit() - base);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int v = b.get(base + i) & 0xff;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
            sb.append(' ');
        }
        return sb.toString();
    }

    private static int at(java.nio.ByteBuffer b, int base, int stride, int pix, int row, int col,
                          int rowMax, int colMax) {
        if (row < 0) row = 0;
        if (col < 0) col = 0;
        if (row >= rowMax) row = rowMax - 1;
        if (col >= colMax) col = colMax - 1;
        int i = base + row * stride + col * pix;
        if (i < 0 || i >= b.limit()) {
            return -1;
        }
        return b.get(i) & 0xff;
    }

    /** 把一帧 YUV_420_888 缩到 640 宽，转成 RGB 写 PPM，好直接看画面 */
    private static void savePpm(String name, java.nio.ByteBuffer yb, java.nio.ByteBuffer ub,
                                java.nio.ByteBuffer vb, int yStride, int yPix,
                                int uStride, int uPix, int vStride, int vPix,
                                int w, int h, int idx, int uForce, int vForce) {
        try {
            int ow = 640;
            int oh = Math.max(1, h * ow / w);
            int sx = Math.max(1, w / ow), sy = Math.max(1, h / oh);
            int yBase = yb.position(), uBase = uForce, vBase = vForce;
            java.nio.ByteBuffer vSrc = (uPix == 2 && vPix == 2) ? ub : vb;
            java.io.File f = new java.io.File(
                    "/sdcard/Android/data/com.android.camera/files/camport", name);
            java.io.File p = f.getParentFile();
            if (p != null && !(p.isDirectory() || p.mkdirs())) {
                return;
            }
            java.io.OutputStream os = new java.io.FileOutputStream(f);
            os.write(("P6\n" + ow + " " + oh + "\n255\n").getBytes("US-ASCII"));
            byte[] row = new byte[ow * 3];
            for (int oy = 0; oy < oh; oy++) {
                int ry = oy * sy;
                for (int ox = 0; ox < ow; ox++) {
                    int rx = ox * sx;
                    int Y = at(yb, yBase, yStride, yPix, ry, rx, h, w);
                    int U = at(ub, uBase, uStride, uPix, ry >> 1, rx >> 1,
                            Math.max(1, h >> 1), Math.max(1, w >> 1));
                    int V = at(vSrc, vBase, vStride, vPix, ry >> 1, rx >> 1,
                            Math.max(1, h >> 1), Math.max(1, w >> 1));
                    if (Y < 0) Y = 0;
                    if (U < 0) U = 128;
                    if (V < 0) V = 128;
                    int r = Y + (int) (1.402f * (V - 128));
                    int g = Y - (int) (0.344136f * (U - 128)) - (int) (0.714136f * (V - 128));
                    int b = Y + (int) (1.772f * (U - 128));
                    row[ox * 3] = (byte) (r < 0 ? 0 : (r > 255 ? 255 : r));
                    row[ox * 3 + 1] = (byte) (g < 0 ? 0 : (g > 255 ? 255 : g));
                    row[ox * 3 + 2] = (byte) (b < 0 ? 0 : (b > 255 ? 255 : b));
                }
                os.write(row);
            }
            os.close();
            log("PPM 已存 " + name + " 640x" + oh + " (src #" + idx + " " + w + "x" + h + ")");
        } catch (Throwable th) {
            err(th);
        }
    }

    /**
     * 一次性自检：逐颗相机问 HAL 有没有 com.xiaomi.camera.masterLivePhoto.info。
     *
     * l9.h.k3() 就是靠它判断「这颗镜头支不支持实况运镜」——
     * 没有它 -> k3=false -> U3/r 里下发 MasterLive 会话参数的整块被跳过
     *        -> HAL 不知道这次是 MasterLive 拍摄 -> 专用流输出垃圾 -> 实况绿色花屏。
     * 这一步用来确定：本机到底是哪颗镜头带这个标签，从而知道 231 该开谁。
     */
    private static void dumpMasterLiveInfo() {
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) sCtx.getSystemService(
                            android.content.Context.CAMERA_SERVICE);
            if (cm == null) {
                log("dump: CameraManager null");
                return;
            }
            android.hardware.camera2.CameraCharacteristics.Key<int[]> key =
                    new android.hardware.camera2.CameraCharacteristics.Key<>(VENDOR_INFO,
                            int[].class);
            String[] ids = cm.getCameraIdList();
            StringBuilder sb = new StringBuilder("masterLive.info 支持情况:");
            for (String id : ids) {
                int[] v;
                try {
                    v = cm.getCameraCharacteristics(id).get(key);
                } catch (Throwable th) {
                    sb.append(" | cid=").append(id).append(" ERR ").append(th.getMessage());
                    continue;
                }
                Integer facing = null;
                try {
                    facing = cm.getCameraCharacteristics(id).get(
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                } catch (Throwable th) {
                }
                sb.append(" | cid=").append(id)
                        .append(" facing=").append(facing)
                        .append(" -> ").append(v == null ? "null" : java.util.Arrays.toString(v));
            }
            log(sb.toString());
        } catch (Throwable th) {
            err(th);
        }
    }

    /**
     * 按 231 的镜头组件值选镜头，与 U3/r 的 switch 保持一致；
     * 唯一差别是 "tele" 在 role 20 缺失时回退到本机真实长焦。
     */
    private static int resolve(int mode) {
        Object lens = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(C_LENS, sCl), "R");
        String r = role(mode);
        if ("wide".equals(r)) {
            return num(lens, "e", -1);          // 主摄 role 0
        }
        if ("ultra".equals(r)) {
            return num(lens, "j", -1);          // 超广角 role 21
        }
        if ("Standalone".equals(r)) {
            return num(lens, "K", -1);          // 超长焦 role 23
        }
        // "tele"（以及组件还没设置的 "0"）：先要 role 20，缺了就用本机真实长焦
        int q = num(lens, "q", -1);
        if (q >= 0) {
            return q;
        }
        int k = num(lens, "K", -1);
        if (k >= 0) {
            return k;
        }
        return num(lens, "e", -1);
    }

    private static String role(int mode) {
        try {
            Object r = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(C_DATA_N, sCl), "h", Integer.valueOf(mode));
            return r == null ? "?" : r.toString();
        } catch (Throwable th) {
            return "?";
        }
    }

    /** 反射调镜头表的 int 方法，失败返回 fallback */
    private static int num(Object lens, String method, int fallback) {
        try {
            Object v = XposedHelpers.callMethod(lens, method);
            return (v instanceof Integer) ? ((Integer) v).intValue() : fallback;
        } catch (Throwable th) {
            return fallback;
        }
    }

    private static void log(String msg) {
        try {
            Log.i(TAG, HEAD + msg);
        } catch (Throwable th) {
        }
        try {
            if (sWriter == null) {
                java.io.File f = new java.io.File(
                        "/sdcard/Android/data/com.android.camera/files/camport",
                        "camport_fix.log");
                java.io.File parent = f.getParentFile();
                if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                    sWriter = new FileWriter(f, true);
                }
            }
            if (sWriter != null) {
                sWriter.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                        java.util.Locale.US).format(new java.util.Date()) + " I/运镜fix: "
                        + msg + "\n");
                sWriter.flush();
            }
        } catch (Throwable th) {
            sWriter = null;
        }
    }

    /** 同一条错误只记一次，避免刷屏 */
    private static void err(Throwable th) {
        String msg = String.valueOf(th);
        if (msg.equals(sLastError)) {
            return;
        }
        sLastError = msg;
        log("ERROR " + msg);
    }
}
