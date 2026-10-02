#!/usr/bin/env python3
"""对解码后的灰度 raw 序列做两项测量：
  1) 逐帧差分  -> 找重复帧(≈0) / 跳帧(尖峰) = 画面层面的「断断续续」
  2) 相对首帧的尺度 k -> 真实 zoom 轨迹
无 numpy，纯 python。
用法: rawzoom.py <name.gray> <w> <h>"""
import sys, os

W = int(sys.argv[2]); H = int(sys.argv[3])
N = W * H


def load(path):
    data = open(path, 'rb').read()
    n = len(data) // N
    return [data[i * N:(i + 1) * N] for i in range(n)]


def score(ref, tgt, k):
    """ref[y,x] vs tgt 在 k 缩放下的误差（以画面中心为原点）。"""
    cx = (W - 1) / 2.0; cy = (H - 1) / 2.0
    err = 0.0; n = 0
    for y in range(H):
        qy = cy + k * (y - cy)
        iy = int(qy + 0.5)
        if iy < 0 or iy >= H:
            continue
        base_r = y * W
        base_t = iy * W
        for x in range(W):
            qx = cx + k * (x - cx)
            ix = int(qx + 0.5)
            if ix < 0 or ix >= W:
                continue
            d = ref[base_r + x] - tgt[base_t + ix]
            err += d * d
            n += 1
    return err / max(n, 1)


def best_k(ref, tgt, lo, hi):
    """粗到细搜索。返回 (k, err)"""
    # coarse
    step = 0.02
    k = lo; bk = lo; be = None
    while k <= hi + 1e-9:
        e = score(ref, tgt, k)
        if be is None or e < be:
            be = e; bk = k
        k += step
    # fine
    lo2 = max(0.5, bk - step); hi2 = bk + step
    k = lo2
    while k <= hi2 + 1e-9:
        e = score(ref, tgt, k)
        if e < be:
            be = e; bk = k
        k += 0.002
    return bk, be


def main():
    fr = load(sys.argv[1])
    n = len(fr)
    name = os.path.basename(sys.argv[1])
    print("== %s  %d 帧  %dx%d" % (name, n, W, H))

    # ---------- 1) 逐帧差分 ----------
    diffs = []
    for i in range(1, n):
        a, b = fr[i - 1], fr[i]
        s = 0
        for j in range(N):
            d = a[j] - b[j]
            s += d if d >= 0 else -d
        diffs.append(s / N)
    mx = max(diffs) if diffs else 1
    print("  逐帧平均绝对差: min=%.3f  中位=%.3f  max=%.3f" % (
        min(diffs), sorted(diffs)[len(diffs) // 2], mx))
    # 点图
    bar = "  "
    for i, d in enumerate(diffs):
        v = d / (mx or 1)
        bar += " .:-=+*#%@"[min(9, int(v * 9.99))]
    print(bar)
    # 异常点
    med = sorted(diffs)[len(diffs) // 2]
    lo = [i for i, d in enumerate(diffs) if d < med * 0.35]
    hi = [i for i, d in enumerate(diffs) if d > med * 2.2]
    print("  异常小(疑似重复帧) idx:", lo if lo else "无")
    print("  异常大(疑似跳帧)   idx:", hi if hi else "无")
    if lo:
        print("     它们的差值:", " ".join("%.2f" % diffs[i] for i in lo))
    if hi:
        print("     它们的差值:", " ".join("%.2f" % diffs[i] for i in hi))

    # ---------- 2) 尺度轨迹 ----------
    print("  尺度轨迹 (相对首帧, 每 5 帧一个采样):")
    pts = [0] + list(range(5, n, 5))
    if pts[-1] != n - 1:
        pts.append(n - 1)
    ref = fr[0]
    prev = 1.0
    out = []
    for i in pts:
        k, e = best_k(ref, fr[i], max(0.6, prev - 0.35), prev + 0.55)
        out.append((i, k, e))
        prev = k
        print("     f%02d  k=%.4f  err=%.1f" % (i, k, e))
    # 平滑度：相邻采样的 Δk
    dks = [out[i + 1][1] - out[i][1] for i in range(len(out) - 1)]
    steps = [out[i + 1][0] - out[i][0] for i in range(len(out) - 1)]
    print("  Δk/采样步长:", " ".join("%+.4f/%d" % (d, s) for d, s in zip(dks, steps)))
    nz = [d / s for d, s in zip(dks, steps) if s]
    if nz:
        mn, mx2 = min(nz), max(nz)
        print("  单帧 zoom 变化率: min=%+.5f max=%+.5f  极差/均值=%.2f" % (
            mn, mx2, (mx2 - mn) / (sum(nz) / len(nz)) if sum(nz) else 0))


if __name__ == '__main__':
    main()
