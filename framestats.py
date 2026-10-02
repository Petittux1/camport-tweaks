#!/usr/bin/env python3
"""逐帧统计：亮度均值/标准差/清晰度(梯度能量)/边缘能量。
用来区分突变类型：AE(亮度) / AF(清晰度) / 纯位移或缩放(都不变)。
用法: framestats.py <name.gray> <w> <h>"""
import sys, os

W = int(sys.argv[2]); H = int(sys.argv[3])
N = W * H
data = open(sys.argv[1], 'rb').read()
n = len(data) // N

print("== %s  %d 帧" % (os.path.basename(sys.argv[1]), n))
rows = []
for i in range(n):
    f = data[i * N:(i + 1) * N]
    s = 0; s2 = 0
    for v in f:
        s += v; s2 += v * v
    mean = s / N
    var = max(0.0, s2 / N - mean * mean)
    # 水平+垂直梯度能量（清晰度）
    g = 0
    for y in range(H):
        b = y * W
        for x in range(W - 1):
            d = f[b + x + 1] - f[b + x]
            g += d if d > 0 else -d
    for y in range(H - 1):
        b = y * W
        for x in range(W):
            d = f[b + W + x] - f[b + x]
            g += d if d > 0 else -d
    rows.append((mean, var ** 0.5, g / N))

print("   帧   亮度均值   标准差   清晰度")
for i, (m, sd, sh) in enumerate(rows):
    bar = "#" * int(sh * 1.2)
    print("   f%02d   %7.3f   %6.3f   %7.3f  %s" % (i, m, sd, sh, bar))

# 变化率
print("\n   突变检测（相邻帧变化 > 3×中位 视为突变）:")
for name, idx in (("亮度", 0), ("标准差", 1), ("清晰度", 2)):
    d = [abs(rows[i + 1][idx] - rows[i][idx]) for i in range(n - 1)]
    sd = sorted(d)[len(d) // 2]
    big = [(i, round(d[i], 3)) for i in range(len(d)) if d[i] > max(sd * 3, 1e-6)]
    print("     %-5s 中位变化=%.4f  突变帧: %s" % (name, sd, big if big else "无"))
