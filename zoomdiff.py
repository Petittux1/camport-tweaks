#!/usr/bin/env python3
"""对两张实况 PPM 做尺度搜索：找让 ref 与 tgt 最匹配的缩放系数 k。
k>1 表示 tgt 比 ref 更「拉近」（即发生了 zoom in）。
无 numpy/PIL，纯 python。"""
import sys, os, glob

def read_ppm(path):
    data = open(path, 'rb').read()
    i = 0; fields = []
    while len(fields) < 4:
        while i < len(data) and data[i:i+1].isspace(): i += 1
        if data[i:i+1] == b'#':
            while i < len(data) and data[i:i+1] != b'\n': i += 1
            continue
        j = i
        while j < len(data) and not data[j:j+1].isspace(): j += 1
        fields.append(data[i:j]); i = j
    i += 1
    w = int(fields[1]); h = int(fields[2])
    return w, h, data[i:i+w*h*3]

def gray(w, h, px, ow, oh):
    out = [0.0]*(ow*oh)
    bsx = max(1, w//ow); bsy = max(1, h//oh)
    inv = 1.0/(bsx*bsy)
    for oy in range(oh):
        y0 = oy*bsy
        for ox in range(ow):
            x0 = ox*bsx
            s = 0.0
            for yy in range(y0, min(y0+bsy, h)):
                base = yy*w*3
                for xx in range(x0, min(x0+bsx, w)):
                    o = base + xx*3
                    s += 299*px[o] + 587*px[o+1] + 114*px[o+2]
            out[oy*ow+ox] = s*inv/1000.0
    return out

def score(ref, tgt, w, h, k):
    cx = (w-1)/2.0; cy = (h-1)/2.0
    err = 0.0; n = 0
    for y in range(h):
        for x in range(w):
            qx = cx + k*(x-cx); qy = cy + k*(y-cy)
            ix = int(qx + 0.5); iy = int(qy + 0.5)
            if ix < 0 or iy < 0 or ix >= w or iy >= h: continue
            d = ref[y*w+x] - tgt[iy*w+ix]
            err += d*d; n += 1
    return err/max(n, 1)

def best_k(ref, tgt, w, h):
    best = (1e18, 1.0)
    res = []
    k = 0.90
    while k <= 1.80:
        s = score(ref, tgt, w, h, k)
        res.append((k, s))
        if s < best[0]: best = (s, k)
        k += 0.02
    return best[1], best[0], res

def main(d):
    files = sorted(glob.glob(os.path.join(d, 'enc_*.ppm')),
                   key=lambda p: (int(p.split('_s')[1].split('_')[0]),
                                  int(p.split('_')[-1].split('.')[0])))
    imgs = {}
    OW, OH = 160, 90
    for f in files:
        w, h, px = read_ppm(f)
        imgs[os.path.basename(f)] = gray(w, h, px, OW, OH)

    names = list(imgs.keys())
    print("文件:", len(names))

    def cmp(a, b):
        if a not in imgs or b not in imgs:
            print("  缺", a, b); return
        k, s, res = best_k(imgs[a], imgs[b], OW, OH)
        # 顺便给 k=1.0 的分数做基准
        s1 = [r[1] for r in res if abs(r[0]-1.0) < 0.011]
        s1 = s1[0] if s1 else -1
        gain = (1 - s/s1)*100 if s1 > 0 else 0
        print("  %-16s vs %-16s  best_k=%.2f  err=%.1f  (k=1 err=%.1f, 好 %.1f%%)"
              % (a, b, k, s, s1, gain))

    print("\n== 拍摄#1 (s1+s2 同一次) ==")
    cmp('enc_s1_0.ppm', 'enc_s1_24.ppm')
    cmp('enc_s1_0.ppm', 'enc_s2_8.ppm')
    cmp('enc_s1_0.ppm', 'enc_s2_16.ppm')
    cmp('enc_s2_0.ppm', 'enc_s2_16.ppm')

    print("\n== 拍摄#3 ==")
    cmp('enc_s3_0.ppm', 'enc_s3_8.ppm')
    cmp('enc_s3_0.ppm', 'enc_s3_16.ppm')
    cmp('enc_s3_0.ppm', 'enc_s3_24.ppm')
    cmp('enc_s3_0.ppm', 'enc_s3_32.ppm')
    cmp('enc_s3_0.ppm', 'enc_s3_40.ppm')
    cmp('enc_s3_0.ppm', 'enc_s3_44.ppm')
    cmp('enc_s3_24.ppm', 'enc_s3_44.ppm')
    cmp('enc_s3_32.ppm', 'enc_s3_44.ppm')

    print("\n== 跨拍摄（不同次，仅参考） ==")
    cmp('enc_s1_0.ppm', 'enc_s3_0.ppm')

if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '.')
