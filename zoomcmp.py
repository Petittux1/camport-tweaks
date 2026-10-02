#!/usr/bin/env python3
"""把日志里的 'apply zoom ratio to device' 时间线，与成片逐帧 zoom 对齐。
用法: zoomcmp.py <log> <shotname> <snap_wall> <snap_mono_us> <first_pts_us> <dur_us> <nframes>"""
import sys, re

LOG = sys.argv[1]
SHOT = sys.argv[2]
SNAP_WALL = sys.argv[3]          # HH:MM:SS.mmm
SNAP_MONO = int(sys.argv[4])     # µs
FIRST = int(sys.argv[5])         # µs
DUR = int(sys.argv[6])           # µs
NF = int(sys.argv[7])


def to_sec(t):
    h, m, s = t.split(':')
    return int(h) * 3600 + int(m) * 60 + float(s)


snap_s = to_sec(SNAP_WALL)

# 抽 zoom 指令
pat = re.compile(r'^\d\d-\d\d (\d\d:\d\d:\d\d\.\d{3}).*apply zoom ratio to device = ([\d.]+)')
cmds = []
for line in open(LOG, 'rb').read().decode('utf-8', 'replace').split('\n'):
    m = pat.search(line)
    if m:
        cmds.append((to_sec(m.group(1)), float(m.group(2))))
print("== %s  收到 %d 条 zoom 指令" % (SHOT, len(cmds)))
if not cmds:
    raise SystemExit

dt = DUR / max(NF - 1, 1)
print("   帧间隔=%.1f ms  snap_wall=%s  首帧早于快门 %.3f s" %
      (dt / 1000.0, SNAP_WALL, (SNAP_MONO - FIRST) / 1e6))

# 逐帧期望 zoom（线性插值指令）
def zoom_at(t):
    if t <= cmds[0][0]:
        return cmds[0][1]
    if t >= cmds[-1][0]:
        return cmds[-1][1]
    for i in range(1, len(cmds)):
        t0, v0 = cmds[i - 1]; t1, v1 = cmds[i]
        if t0 <= t <= t1:
            if t1 == t0:
                return v1
            return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    return cmds[-1][1]


print("\n   帧  视频t    墙钟     指令zoom   相对帧0")
base = None
for i in range(0, NF, 5):
    pts = FIRST + i * dt
    wall = snap_s + (pts - SNAP_MONO) / 1e6
    z = zoom_at(wall)
    if base is None:
        base = z
    hh = int(wall // 3600) % 24; mm = int(wall // 60) % 60; ss = wall - int(wall // 3600) * 3600 - mm * 60
    print("   f%02d  %5.3fs  %02d:%02d:%06.3f  %8.4f   ×%.4f" %
          (i, i * dt / 1e6, hh, mm, ss, z, z / base))

# 指令的运动形状：每 5 帧一窗的 Δzoom
print("\n   指令侧每窗 Δzoom (与成片测得的 k 直接可比):")
prev = None
for i in range(0, NF - 5, 5):
    z0 = zoom_at(snap_s + (FIRST + i * dt - SNAP_MONO) / 1e6)
    z1 = zoom_at(snap_s + (FIRST + (i + 5) * dt - SNAP_MONO) / 1e6)
    if prev is None:
        prev = z1 / z0
    print("     f%02d->f%02d  z %.4f -> %.4f   比值=%.4f" % (i, i + 5, z0, z1, z1 / z0))

# 指令节奏：相邻指令的时间间隔
gaps = [cmds[i + 1][0] - cmds[i][0] for i in range(len(cmds) - 1)]
gaps.sort()
print("\n   指令到达间隔: min=%.0fms  中位=%.0fms  max=%.0fms  条数=%d" %
      (gaps[0] * 1000, gaps[len(gaps) // 2] * 1000, gaps[-1] * 1000, len(gaps)))
