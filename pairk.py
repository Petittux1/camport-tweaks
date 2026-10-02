#!/usr/bin/env python3
"""单步（非链式）NCC 尺度测量：直接给定 (i,j) 全范围搜 k。
用法: pairk.py <name.gray> <w> <h> i:j i:j ...
命令期望: 自由104758 k(0,15)=1.1407  k(0,45)=1.481  k(0,49)=?
          主角104827 k(0,40)=0.9998  k(0,45)=0.9344
"""
import sys, os
W=int(sys.argv[2]); H=int(sys.argv[3]); N=W*H
name=os.path.basename(sys.argv[4]) if False else None
path=sys.argv[1]; W=int(sys.argv[2]); H=int(sys.argv[3]); N=W*H
pairs=[tuple(int(x) for x in a.split(':')) for a in sys.argv[4:]]
d=open(path,'rb').read(); n=len(d)//N
fr=[d[i*N:(i+1)*N] for i in range(n)]
print("== %s (%d帧)" % (os.path.basename(path), n))

def ncc(ref,tgt,k):
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
    if m<50: return 2.0
    mr=sr/m; mt=st/m
    vr=sr2/m-mr*mr; vt=st2/m-mt*mt
    if vr<=1e-9 or vt<=1e-9: return 2.0
    return 1.0-(srt/m-mr*mt)/(vr*vt)**0.5

def search(ref,tgt):
    k=0.60; bk=0.60; be=2.0
    while k<=1.90:
        e=ncc(ref,tgt,k)
        if e<be: be=e; bk=k
        k+=0.02
    lo=max(0.55,bk-0.02); hi=bk+0.02; k=lo
    while k<=hi+1e-9:
        e=ncc(ref,tgt,k)
        if e<be: be=e; bk=k
        k+=0.002
    return bk,be

for i,j in pairs:
    if i>=n or j>=n:
        print("   f%02d->f%02d  越界(n=%d)"%(i,j,n)); continue
    k,e=search(fr[i],fr[j])
    print("   f%02d->f%02d  k=%.4f   1-NCC=%.4f%s" % (i,j,k,e,"  <--拟合差,不可信" if e>0.30 else ""))
