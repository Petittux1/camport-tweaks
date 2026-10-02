#!/usr/bin/env python3
"""从小米 MVIMG (JPEG+MP4) 中提取内嵌 MP4 到独立文件。
用法: mp4x.py <in.jpg> <out.mp4>   (或多个 in.jpg，输出到同名 .mp4)"""
import struct, sys, os


def boxes(buf, start, end):
    p = start
    while p + 8 <= end:
        size = struct.unpack('>I', buf[p:p + 4])[0]
        typ = buf[p + 4:p + 8]
        hdr = 8
        if size == 1:
            size = struct.unpack('>Q', buf[p + 8:p + 16])[0]
            hdr = 16
        elif size == 0:
            size = end - p
        if size < hdr:
            break
        yield typ, p + hdr, p + size
        p += size


def find_ftyp(buf):
    i = 0
    while True:
        i = buf.find(b'ftyp', i)
        if i < 0:
            return -1
        off = i - 4
        if off >= 0:
            sz = struct.unpack('>I', buf[off:off + 4])[0]
            if 16 <= sz <= 1024:
                return off
        i += 4


def extract(path, out):
    data = open(path, 'rb').read()
    off = find_ftyp(data)
    if off < 0:
        print("  %s: ftyp 未找到" % os.path.basename(path))
        return False
    blob = data[off:]
    # 确认里面有 moov
    if b'moov' not in blob[:64] and b'moov' not in blob:
        print("  %s: moov 未找到" % os.path.basename(path))
        return False
    open(out, 'wb').write(blob)
    print("  %-32s off=%d -> %s (%d bytes)" % (os.path.basename(path), off,
                                               os.path.basename(out), len(blob)))
    return True


if __name__ == '__main__':
    for p in sys.argv[1:]:
        o = os.path.splitext(p)[0] + '.mp4'
        extract(p, o)
