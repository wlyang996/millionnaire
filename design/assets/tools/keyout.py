"""Pillow-only magenta key, edge unmix/despill, trim and 8px padding.
Usage: python keyout.py input.png output.png --max-size 512 512
"""
import argparse
from collections import deque
from PIL import Image


def keyout(source, max_size=None, padding=8):
    im = source.convert('RGB')
    w, h = im.size
    colors = list(im.get_flattened_data())
    background = bytearray(w*h)
    for i, (r,g,b) in enumerate(colors):
        if min(r,b)>160 and g<100 and min(r,b)-g>110:
            background[i] = 1
    # Only despill near keyed pixels, preserving violet hair and salmon hats.
    distance = bytearray([255])*(w*h)
    queue = deque()
    for i in range(w*h):
        if background[i]:
            distance[i]=0
            queue.append(i)
    while queue:
        i=queue.popleft()
        if distance[i]>=4:
            continue
        x,y=i%w,i//w
        for j in (i-1 if x else -1, i+1 if x<w-1 else -1,
                  i-w if y else -1, i+w if y<h-1 else -1):
            if j>=0 and distance[j]>distance[i]+1:
                distance[j]=distance[i]+1
                queue.append(j)
    out=[]
    for i,(r,g,b) in enumerate(colors):
        if background[i]:
            out.append((0,0,0,0))
            continue
        a=255
        if distance[i]<=4 and min(r,b)-g>20:
            x,y=i%w,i//w
            refs=[]
            for dy in range(-4,5):
                for dx in range(-4,5):
                    xx,yy=x+dx,y+dy
                    if 0<=xx<w and 0<=yy<h:
                        j=yy*w+xx
                        rr,gg,bb=colors[j]
                        if not background[j] and distance[j]>distance[i] and min(rr,bb)-gg<=20:
                            refs.append((dx*dx+dy*dy,j))
            if refs:
                _,j=min(refs)
                f=colors[j]
                key=(255,0,255)
                v=[f[k]-key[k] for k in range(3)]
                denom=sum(t*t for t in v)
                alpha=max(0,min(1,sum((colors[i][k]-key[k])*v[k] for k in range(3))/max(1,denom)))
                if alpha<0.04:
                    out.append((0,0,0,0)); continue
                rgb=[max(0,min(255,round((colors[i][k]-(1-alpha)*key[k])/alpha))) for k in range(3)]
                r,g,b=rgb
                a=round(alpha*255)
            # Conservative residual despill only at silhouette, never interior.
            spill=max(0,min(r,b)-g-18)
            if spill:
                r-=spill; b-=spill
        out.append((r,g,b,a))
    result=Image.new('RGBA',(w,h)); result.putdata(out)
    bbox=result.getbbox()
    if bbox is None:
        raise ValueError('No foreground remains')
    result=result.crop(bbox)
    if max_size:
        result.thumbnail((max_size[0]-2*padding,max_size[1]-2*padding),Image.Resampling.LANCZOS)
    padded=Image.new('RGBA',(result.width+2*padding,result.height+2*padding))
    padded.paste(result,(padding,padding))
    return padded


def finish_edges(im):
    """Remove key-color ringing introduced by Lanczos resampling."""
    im=im.convert('RGBA')
    data=list(im.get_flattened_data()); w,h=im.size
    cleaned=list(data)
    for y in range(h):
        for x in range(w):
            i=y*w+x; r,g,b,a=data[i]
            if not a:
                cleaned[i]=(0,0,0,0); continue
            edge=a<255 or any(data[yy*w+xx][3]<255
                             for yy in range(max(0,y-1),min(h,y+2))
                             for xx in range(max(0,x-1),min(w,x+2)))
            if edge:
                spill=max(0,min(r,b)-g-18)
                cleaned[i]=(r-spill,g,b-spill,a)
    im.putdata(cleaned)
    return im


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('input'); p.add_argument('output')
    p.add_argument('--max-size',nargs=2,type=int)
    p.add_argument('--padding',type=int,default=8)
    args=p.parse_args()
    finish_edges(keyout(Image.open(args.input),args.max_size,args.padding)).save(args.output)
