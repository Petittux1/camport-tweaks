#!/usr/bin/env python3
"""归一化互相关尺度搜索：对亮度/对比度变化免疫，得到真实的几何缩放系数。
k>1 => tgt 比 ref 拉近 (zoom in)。
用法: ncczoom.py <name.gray> <w> <h> [win]"""
import sys, os

W = int(sys.argv[2]); H = int(sys.argv[3])
WIN = int(sys.argv[4]) if len(sys.argv) > 4 else 5
N = W * H


def load(p):
    d = open(p, 'rb').read()
    n = len(d) // N
    return [d[i * N:(i + 1) * N] for i in range(n)]


def ncc(ref, tgt, k):
    """返回 1-NCC (越小越好)。只用 tgt 在中心 k 区域内且 ref 全覆盖的点。"""
    cx = (W - 1) / 2.0; cy = (H - 1) / 2.0
    # 采样 ref 的范围，使 warp 后落在 tgt 内
    if k >= 1.0:
        xr = int(W / k / 2); yr = int(H / k / 2)     # ref 中心区域
    else:
        xr = int(W * k / 2); yr = int(H * k / 2)
    xr = max(xr, 2); yr = max(yr, 2)
    x0 = int(cx) - xr; x1 = int(cx) + xr
    y0 = int(cy) - yr; y1 = int(cy) + yr
    sr = st = sr2 = st2 = srt = 0.0
    m = 0
    for y in range(y0, y1 + 1):
        iy = int(cy + k * (y - cy) + 0.5)
        if iy < 0 or iy >= H:
            continue
        br = y * W; bt = iy * W
        for x in range(x0, x1 + 1):
            ix = int(cx + k * (x - cx) + 0.5)
            if ix < 0 or ix >= W:
                continue
            a = ref[br + x]; b = tgt[bt + ix]
            sr += a; st += b; sr2 += a * a; st2 += b * b; srt += a * b
            m += 1
    if m < 50:
        return 2.0
    mr = sr / m; mt = st / m
    vr = sr2 / m - mr * mr; vt = st2 / m - mt * mt
    if vr <= 1e-9 or vt <= 1e-9:
        return 2.0
    cov = srt / m - mr * mt
    return 1.0 - cov / (vr * vt) ** 0.5


def best_k(ref, tgt, lo, hi):
    k = lo; bk = lo; be = 2.0
    while k <= hi + 1e-9:
        e = ncc(ref, tgt, k)
        if e < be:
            be = e; bk = k
        k += 0.01
    lo2 = max(0.5, bk - 0.01); hi2 = bk + 0.01
    k = lo2
    while k <= hi2 + 1e-9:
        e = ncc(ref, tgt, k)
        if e < be:
            be = e; bk = k
        k += 0.002
    return bk, be


name = os.path.basename(sys.argv[1])
fr = load(sys.argv[1])
n = len(fr)
print("== %s  %d 帧  (NCC 尺度, 窗=%d)" % (name, n, WIN))

# 基线：相邻帧 NCC 拟合误差，用来判断"是否真能用缩放解释"
print("   帧对      k       1-NCC   (接近0=纯缩放能解释; 大=有别的变化)")
i = 0
acc = 1.0
prev = 1.0
ser = []
while i + WIN < n:
    lo = max(0.75, prev - 0.15); hi = min(1.55, prev + 0.15)
    k, e = best_k(fr[i], fr[i + WIN], lo, hi)
    acc *= k
    ser.append((i, i + WIN, k, acc, e))
    prev = k
    i += WIN
for a, b, k, c, e in ser:
    print("   f%02d->f%02d  k=%.4f  累计=%.4f   1-NCC=%.4f %s" %
          (a, b, k, c, e, "  <-- 拟合差" if e > 0.25 else ""))
ks = [x[2] for x in ser]
print("   k: min=%.4f max=%.4f  相邻极差=%.4f" % (min(ks), max(ks), max(ks) - min(ks)))
# 单帧速率
rates = [x[2] for x in ser]
print("   每窗 k 序列:", " ".join("%.3f" % k for k in rates))
