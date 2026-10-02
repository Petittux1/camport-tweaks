#!/usr/bin/env python3
"""打印 NCC-vs-k 曲线：看有没有清晰的峰，峰值在哪 = 真实缩放系数。
用法: kscan.py <gray> <w> <h> <i> <j> <lo> <hi>"""
import sys, os
path=sys.argv[1]; W=int(sys.argv[2]); H=int(sys.argv[3])
i=int(sys.argv[4]); j=int(sys.argv[5]); lo=float(sys.argv[6]); hi=float(sys.argv[7])
N=W*H
d=open(path,'rb').read(); n=len(d)//N
fr=[d[k*N:(k+1)*N] for k in range(n)]
ref, tgt = fr[i], fr[j]

def ncc(k):
    cx=(W-1)/2.0; cy=(H-1)/2.0
    if k>=1.0: xr=int(W/k/2); yr=int(H/k/2)
    else:      xr=int(W*k/2); yr=int(H*k/2)
    xr=max(xr,2); yr=max(yr,2)
    x0=int(cx)-xr; x1=int(cx)+xr; y0=int(cy)-yr; y1=int(cy)+yr
    sr=st=sr2=st2=srt=0.0; m=0
    for y in range(y0,y1+1):
        iy=int(cy+k*(y-cy)+0.5)
        if iy<0 or iy>=H: continue
        br=y*W; bt=iy*W
        for x in range(x0,x1+1):
            ix=int(cx+k*(x-cx)+0.5)
            if ix<0 or ix>=W: continue
            a=ref[br+x]; b=tgt[bt+ix]
            sr+=a; st+=b; sr2+=a*a; st2+=b*b; srt+=a*b; m+=1
    mr=sr/m; mt=st/m
    vr=sr2/m-mr*mr; vt=st2/m-mt*mt
    if vr<=1e-9 or vt<=1e-9: return 2.0
    return 1.0-(srt/m-mr*mt)/(vr*vt)**0.5

print("== %s  f%02d -> f%02d   (纵轴=1-NCC 越低越好)" % (os.path.basename(path), i, j))
res=[]
k=lo
while k<=hi+1e-9:
    e=ncc(k); res.append((k,e)); k+=0.01
e0=min(e for _,e in res)
for k,e in res:
    mark = "  <== 峰" if e<=e0+1e-9 else ""
    bar = "#"*int(max(0,(e-e0))*60)
    print("   k=%.3f  %.4f  %s%s" % (k, e, bar, mark))
