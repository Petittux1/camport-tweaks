#!/usr/bin/env python3
"""滑窗成对尺度：对 (i, i+w) 逐窗求 k，累乘得到 zoom 曲线。
同时打印逐帧差值全量序列。
用法: winzoom.py <name.gray> <w> <h> [win]"""
import sys, os

W = int(sys.argv[2]); H = int(sys.argv[3])
WIN = int(sys.argv[4]) if len(sys.argv) > 4 else 5
N = W * H


def load(path):
    data = open(path, 'rb').read()
    n = len(data) // N
    return [data[i * N:(i + 1) * N] for i in range(n)]


def score(ref, tgt, k):
    cx = (W - 1) / 2.0; cy = (H - 1) / 2.0
    err = 0.0; n = 0
    for y in range(H):
        iy = int(cy + k * (y - cy) + 0.5)
        if iy < 0 or iy >= H:
            continue
        br = y * W; bt = iy * W
        for x in range(W):
            ix = int(cx + k * (x - cx) + 0.5)
            if ix < 0 or ix >= W:
                continue
            d = ref[br + x] - tgt[bt + ix]
            err += d * d; n += 1
    return err / max(n, 1)


def best_k(ref, tgt, lo, hi):
    step = 0.01
    k = lo; bk = lo; be = None
    while k <= hi + 1e-9:
        e = score(ref, tgt, k)
        if be is None or e < be:
            be = e; bk = k
        k += step
    lo2 = max(0.5, bk - step); hi2 = bk + step
    k = lo2
    while k <= hi2 + 1e-9:
        e = score(ref, tgt, k)
        if e < be:
            be = e; bk = k
        k += 0.002
    return bk, be


name = os.path.basename(sys.argv[1])
fr = load(sys.argv[1])
n = len(fr)
print("== %s  %d 帧" % (name, n))

# ---- 逐帧差全量 ----
d = []
for i in range(1, n):
    a, b = fr[i - 1], fr[i]
    s = 0
    for j in range(N):
        v = a[j] - b[j]
        s += v if v >= 0 else -v
    d.append(s / N)
med = sorted(d)[len(d) // 2]
print("  逐帧差 (中位 %.2f):" % med)
for s in range(0, len(d), 25):
    print("   %3d-%3d: %s" % (s, min(s + 24, len(d) - 1),
          " ".join("%5.1f" % x for x in d[s:s + 25])))
slow = [(i, d[i]) for i in range(len(d)) if d[i] < med * 0.45]
print("  明显变慢的帧 (<45%%中位):", slow if slow else "无")
fast = [(i, d[i]) for i in range(len(d)) if d[i] > med * 2.0]
print("  明显变快的帧 (>200%%中位):", fast if fast else "无")

# ---- 滑窗成对尺度 ----
print("  滑窗尺度 (窗=%d 帧):" % WIN)
acc = 1.0
i = 0
prev_k = 1.0
ser = []
while i + WIN < n:
    lo = max(0.70, prev_k - 0.12); hi = min(1.60, prev_k + 0.12)
    k, e = best_k(fr[i], fr[i + WIN], lo, hi)
    acc *= k
    ser.append((i, i + WIN, k, acc, e))
    prev_k = k
    i += WIN
for a, b, k, c, e in ser:
    print("   f%02d->f%02d  k=%.4f  累计=%.4f  err=%7.1f" % (a, b, k, c, e))
ks = [x[2] for x in ser]
es = [x[4] for x in ser]
if len(ks) > 1:
    print("  k: min=%.4f max=%.4f  相邻极差=%.4f" % (min(ks), max(ks), max(ks) - min(ks)))
    print("  err: min=%.0f max=%.0f  (某窗 err 突增=尺度无法解释的内容突变)" % (min(es), max(es)))
