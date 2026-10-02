#!/usr/bin/env python3
"""从小米 MVIMG (JPEG+MP4) 中提取内嵌 MP4 并读出时长。"""
import struct, sys, os

def boxes(buf, start, end):
    p = start
    while p + 8 <= end:
        size = struct.unpack('>I', buf[p:p+4])[0]
        typ = buf[p+4:p+8]
        hdr = 8
        if size == 1:
            size = struct.unpack('>Q', buf[p+8:p+16])[0]
            hdr = 16
        elif size == 0:
            size = end - p
        if size < hdr:
            break
        yield typ, p + hdr, p + size
        p += size

def find_moov(buf, s, e, depth=0):
    if depth > 6:
        return None
    for typ, s2, e2 in boxes(buf, s, e):
        if typ == b'moov':
            return (s2, e2)
        if typ in (b'moov',):
            r = find_moov(buf, s2, e2, depth+1)
            if r:
                return r
    # 有些封装 moov 在顶层
    return None

def mvhd(buf, s, e):
    for typ, s2, e2 in boxes(buf, s, e):
        if typ != b'mvhd':
            continue
        ver = buf[s2]
        p = s2 + 4
        if ver == 1:
            p += 8 + 8
            ts = struct.unpack('>I', buf[p:p+4])[0]; p += 4
            dur = struct.unpack('>Q', buf[p:p+8])[0]
        else:
            p += 4 + 4
            ts = struct.unpack('>I', buf[p:p+4])[0]; p += 4
            dur = struct.unpack('>I', buf[p:p+4])[0]
        return ts, dur
    return None

for path in sys.argv[1:]:
    data = open(path, 'rb').read()
    # 找所有 ftyp
    offs = []
    i = 0
    while True:
        i = data.find(b'ftyp', i)
        if i < 0:
            break
        offs.append(i - 4)
        i += 4
    print(f"\n== {os.path.basename(path)}  size={len(data)}  ftyp@ {offs}")
    for off in offs:
        if off < 0:
            continue
        try:
            sz = struct.unpack('>I', data[off:off+4])[0]
            if sz < 16:
                continue
            # ftyp 之后还有 moov/mdat，顶层 box 一直排到文件尾
            end = len(data)
            mv = find_moov(data, off, end)
            if not mv:
                print(f"   off={off} size={sz}  -> moov 未找到")
                continue
            r = mvhd(data, *mv)
            if r:
                ts, dur = r
                print(f"   off={off} size={sz}  moov=[{mv[0]},{mv[1]}]  timescale={ts} duration={dur}  => {dur/ts:.3f} s")
            else:
                print(f"   off={off} size={sz}  mvhd 未找到")
        except Exception as ex:
            print(f"   off={off} 解析失败: {ex}")
