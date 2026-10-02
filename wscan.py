#!/usr/bin/env python3
"""逐个 5 帧窗独立做 NCC k 扫描（不链式），输出每窗峰值。
用法: wscan.py <gray> <w> <h> <lo> <hi>"""
import sys, os
path=sys.argv[1]; W=int(sys.argv[2]); H=int(sys.argv[3])
LO=float(sys.argv[4]); HI=float(sys.argv[5])
N=W*H
d=open(path,'rb').read(); n=len(d)//N
fr=[d[i*N:(i+1)*N] for i in range(n)]
cx,cy=(W-1)/2.0,(H-1)/2.0

def ncc(a,b,k):
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
            p=a[br+x]; q=b[bt+ix]
            sr+=p; st+=q; sr2+=p*p; st2+=q*q; srt+=p*q; m+=1
    if m<50: return 2.0
    mr=sr/m; mt=st/m; vr=sr2/m-mr*mr; vt=st2/m-mt*mt
    if vr<=1e-9 or vt<=1e-9: return 2.0
    return 1.0-(srt/m-mr*mt)/(vr*vt)**0.5

print("== %s" % os.path.basename(path))
print("   窗      k峰     1-NCC    判定     曲线")
i=0
while i+5 < n:
    a,b=fr[i],fr[i+5]
    res=[]
    k=LO
    while k<=HI+1e-9:
        res.append((k,ncc(a,b,k))); k+=0.01
    e0=min(e for _,e in res)
    # 峰锐度: 次优与最优的差
    es=sorted(e for _,e in res)
    sharp=es[1]-es[0]
    bk,be=min(res,key=lambda t:t[1])
    judge = "无缩放" if abs(bk-1.00)<0.006 and sharp>0.005 else ("可疑" if be>0.35 else "有缩放")
    bar=""
    for k,e in res:
        bar += " .:-=+*#%@"[min(9,int((e-e0)/max(sharp*6,0.008)*9.99))] if e>e0 else "*"
    print("   f%02d->f%02d  %.3f  %.4f  %-6s  %s" % (i,i+5,bk,be,judge,bar))
    i+=5
