"""PIL only. Non-destructive sprite extraction, magenta dematte and audit.
All mutations are confined to assets-v3; README is updated separately by append.
"""
from pathlib import Path
from collections import Counter, deque
import argparse, hashlib, json, math
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT.parent
for sub in ('png', 'previews', 'comparisons', 'raw', 'references'):
    (ROOT/sub).mkdir(exist_ok=True)

def dump(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def key_magenta(im):
    im = im.convert('RGBA')
    out=[]
    for r,g,b,a in im.getdata():
        diff=min(r,b)-g
        if r>210 and b>200 and diff>185:
            out.append((0,0,0,0)); continue
        if r>175 and b>140 and diff>100:
            opacity=max(0., min(1., (255-diff)/155))
            if opacity<.035:
                out.append((0,0,0,0)); continue
            r=round(max(0,min(255,(r-255*(1-opacity))/opacity)))
            b=round(max(0,min(255,(b-255*(1-opacity))/opacity)))
            g=round(max(0,min(255,g/opacity)))
            a=round(a*opacity)
        out.append((r,g,b,a))
    im.putdata(out)
    # Remove remaining magenta spill only in the two-pixel silhouette band.
    alpha=im.getchannel('A'); inner=alpha.filter(ImageFilter.MinFilter(5))
    cleaned=[]; changed=0
    for (r,g,b,a),inside in zip(im.getdata(),inner.getdata()):
        if a and inside<245 and r>160 and b>150 and min(r,b)-g>70:
            r=min(r,g+35); b=min(b,g+35); changed+=1
        cleaned.append((r,g,b,a))
    im.putdata(cleaned)
    return im,changed

def audit(im):
    a=im.getchannel('A'); bb=a.getbbox()
    inner=a.filter(ImageFilter.MinFilter(5))
    fringe=sum(1 for (r,g,b,v),z in zip(im.getdata(),inner.getdata())
               if v>8 and z<245 and r>160 and b>150 and min(r,b)-g>70)
    allmag=sum(1 for r,g,b,v in im.getdata() if v>8 and r>200 and b>180 and min(r,b)-g>120)
    return dict(size=list(im.size),mode=im.mode,alpha_bbox=list(bb) if bb else None,
                magenta_edge_candidates=fringe,magenta_visible_candidates=allmag,
                transparent_pixels=sum(1 for v in a.getdata() if v==0),
                soft_alpha_pixels=sum(1 for v in a.getdata() if 0<v<255),
                border_8_clear=bool(bb and bb[0]>=8 and bb[1]>=8 and im.width-bb[2]>=8 and im.height-bb[3]>=8))

def save_asset(key, im, raw, origin='还原', note='', status='待确认', resize=None, extra=None):
    dest=ROOT/'png'/(key+'.png')
    reg=ROOT/'manifest.json';records=json.loads(reg.read_text(encoding='utf-8')) if reg.exists() else []
    previous=next((r for r in records if r['key']==key),None)
    if dest.exists() and (not previous or hashlib.sha256(dest.read_bytes()).hexdigest()!=previous['sha256']):
        raise RuntimeError('Refusing to overwrite external or modified asset '+str(dest))
    im=im.convert('RGBA');bb=im.getchannel('A').getbbox()
    if not bb: raise RuntimeError('Empty sprite '+key)
    im=im.crop(bb)
    native=list(im.size)
    if resize:
        im=im.resize(tuple(resize),Image.Resampling.LANCZOS)
    im=ImageOps.expand(im,border=8,fill=(0,0,0,0));im.save(dest)
    inv={i['key']:i for i in json.loads((ROOT/'inventory.json').read_text(encoding='utf-8'))}
    rec=inv.get(key,dict(key=key,category='derived',stage=1,display=[64,64],source=None,bbox=None,nine_slice=False,slice_margin=None)).copy()
    rec.update(file='png/'+key+'.png',raw=raw,origin=origin,quality_status=status,difference=note,native_sprite_size=native,qa=audit(im),sha256=hashlib.sha256(dest.read_bytes()).hexdigest())
    if extra: rec.update(extra)
    records=[r for r in records if r['key']!=key];records.append(rec);dump(reg,records)
    return rec

def components(im):
    w,h=im.size; mask=bytearray(1 if v>32 else 0 for v in im.getchannel('A').getdata()); found=[]
    for pos in range(w*h):
        if not mask[pos]:continue
        mask[pos]=0;q=deque([pos]);area=0;left=w;top=h;right=bottom=0
        while q:
            p=q.popleft();x=p%w;y=p//w;area+=1
            left=min(left,x);top=min(top,y);right=max(right,x+1);bottom=max(bottom,y+1)
            for n in ((p-1 if x else -1),(p+1 if x<w-1 else -1),(p-w if y else -1),(p+w if y<h-1 else -1)):
                if n>=0 and mask[n]:mask[n]=0;q.append(n)
        if area>12:found.append((area,(left,top,right,bottom)))
    return sorted(found,reverse=True)

def split_sheet(filename, keys, cols, rows, status='待确认', note='忠实还原候选；细节与参考存在差异，需人工确认。'):
    im,changed=key_magenta(Image.open(ROOT/'raw'/filename))
    cs=components(im); main=cs[:len(keys)]
    # Include detached parts (trade arrows, gear dots, glints) with nearest main.
    for area,b in cs[len(keys):]:
        cx=(b[0]+b[2])/2;cy=(b[1]+b[3])/2
        j=min(range(len(main)),key=lambda j:(cx-(main[j][1][0]+main[j][1][2])/2)**2+(cy-(main[j][1][1]+main[j][1][3])/2)**2)
        old=main[j][1];ox=(old[0]+old[2])/2;oy=(old[1]+old[3])/2
        if math.hypot(cx-ox,cy-oy)<max(old[2]-old[0],old[3]-old[1])*1.15:
            main[j]=(main[j][0]+area,(min(old[0],b[0]),min(old[1],b[1]),max(old[2],b[2]),max(old[3],b[3])))
    main.sort(key=lambda c:(c[1][1]+c[1][3])/2)
    ordered=[]
    for r in range(rows):ordered.extend(sorted(main[r*cols:(r+1)*cols],key=lambda c:(c[1][0]+c[1][2])/2))
    result=[]
    for n,key in enumerate(keys):
        # Connected components, not equal-sized grid cuts, determine each sprite.
        b=ordered[n][1];window=(max(0,b[0]-2),max(0,b[1]-2),min(im.width,b[2]+2),min(im.height,b[3]+2))
        crop=im.crop(window)
        result.append(save_asset(key,crop,'raw/'+filename,note=note,status=status,extra={'sheet_search_window':list(window),'sheet_despill_pixels':changed}))
    return result

def split_windows(filename, keys, cols, rows, note='', status='待确认'):
    im,changed=key_magenta(Image.open(ROOT/'raw'/filename))
    result=[]
    for n,key in enumerate(keys):
        x=n%cols;y=n//cols
        box=(round(x*im.width/cols),round(y*im.height/rows),round((x+1)*im.width/cols),round((y+1)*im.height/rows))
        crop=im.crop(box);cs=components(crop)
        if not cs:raise RuntimeError('No subject '+key)
        big=[b for area,b in cs if area>max(15,cs[0][0]*.005)]
        b=(min(z[0] for z in big),min(z[1] for z in big),max(z[2] for z in big),max(z[3] for z in big))
        crop=crop.crop((max(0,b[0]-2),max(0,b[1]-2),min(crop.width,b[2]+2),min(crop.height,b[3]+2)))
        result.append(save_asset(key,crop,'raw/'+filename,note=note,status=status,extra={'sheet_search_window':list(box),'sheet_despill_pixels':changed}))
    return result

def nine_extend(im, target, margins=None):
    """Expand a blank UI centre; corners retain their native source pixels."""
    w,h=im.size;tw,th=target
    l,t,r,b=margins or [min(48,w//4),min(48,h//4),min(48,w//4),min(48,h//4)]
    xs=[0,l,w-r,w];ys=[0,t,h-b,h];xx=[0,l,tw-r,tw];yy=[0,t,th-b,th]
    out=Image.new('RGBA',(tw,th))
    for row in range(3):
        for col in range(3):
            crop=im.crop((xs[col],ys[row],xs[col+1],ys[row+1]))
            crop=crop.resize((xx[col+1]-xx[col],yy[row+1]-yy[row]),Image.Resampling.LANCZOS)
            out.paste(crop,(xx[col],yy[row]))
    return out,[l+8,t+8,r+8,b+8]

def extend_ui():
    data=json.loads((ROOT/'manifest.json').read_text(encoding='utf-8'))
    for r in data:
        if r['category']!='ui' or r['key'] in ('bubble_me','bubble_name'):continue
        im=Image.open(ROOT/r['file']).convert('RGBA');bb=im.getchannel('A').getbbox();crop=im.crop(bb)
        target=[max(crop.width,2*r['display'][0]),max(crop.height,2*r['display'][1])]
        if target==list(crop.size):continue
        out,margins=nine_extend(crop,target)
        save_asset(r['key'],out,r['raw'],origin=r['origin'],note=r['difference']+' 空白中心九宫格延展，未增加角部细节。',status=r['quality_status'],extra={'nine_slice':True,'slice_margin':margins,'native_sprite_size':r['native_sprite_size'],'centre_extended':True})

def font(size=19):
    for name in ('C:/Windows/Fonts/msyh.ttc','C:/Windows/Fonts/arial.ttf'):
        if Path(name).exists():return ImageFont.truetype(name,size)
    return ImageFont.load_default()

def fit(im, box):
    im=im.copy();im.thumbnail(box,Image.Resampling.LANCZOS);return im

def reports():
    records=json.loads((ROOT/'manifest.json').read_text(encoding='utf-8'))
    inv=json.loads((ROOT/'inventory.json').read_text(encoding='utf-8'))
    for rec in records:
        im=Image.open(ROOT/rec['file']).convert('RGBA');rec['qa']=audit(im)
        rec['density_2x']=all(n>=2*d for n,d in zip(rec['qa']['size'],rec['display']))
    dump(ROOT/'manifest.json',records)
    backgrounds=['#fff5df','#12243b','#1684aa']
    categories=dict.fromkeys(r['category'] for r in records)
    for cat in [*categories,'all']:
        data=[r for r in records if cat=='all' or r['category']==cat]
        cell=(330,270);cols=4;sheet=Image.new('RGB',(cols*cell[0],math.ceil(len(data)/cols)*cell[1]),'#dfe7ef');d=ImageDraw.Draw(sheet)
        for n,r in enumerate(data):
            x=n%cols*cell[0];y=n//cols*cell[1]
            im=fit(Image.open(ROOT/r['file']).convert('RGBA'),(100,185))
            for j,bg in enumerate(backgrounds):
                tile=Image.new('RGBA',(106,192),bg);tile.alpha_composite(im,((106-im.width)//2,(192-im.height)//2));sheet.paste(tile.convert('RGB'),(x+j*108,y))
            d.text((x+4,y+198),r['key'],font=font(15),fill='#11243a')
            d.text((x+4,y+220),r['quality_status']+' | '+str(r['qa']['size'])+' | edge '+str(r['qa']['magenta_edge_candidates']),font=font(14),fill='#11243a')
        sheet.save(ROOT/'previews'/('preview_'+cat+'.png'))
        if cat=='all':sheet.save(ROOT/'preview_all.png')
    for cat in categories:
        data=[r for r in records if r['category']==cat]
        sheet=Image.new('RGB',(960,len(data)*310),'#eef1f5');d=ImageDraw.Draw(sheet)
        for n,r in enumerate(data):
            y=n*310
            d.text((14,y+5),r['key']+' | 原图区域 / 单件素材',font=font(20),fill='#123456')
            if r.get('source') and r.get('bbox'):
                im=fit(Image.open(SCREEN/r['source']).convert('RGB').crop(r['bbox']),(410,210));sheet.paste(im,(15+(410-im.width)//2,y+40))
            else:d.text((30,y+100),'设计图未提供独立造型',font=font(21),fill='#882a2a')
            im=fit(Image.open(ROOT/r['file']).convert('RGBA'),(430,210));tile=Image.new('RGBA',(460,216),'#25384e');tile.alpha_composite(im,((460-im.width)//2,(216-im.height)//2));sheet.paste(tile.convert('RGB'),(485,y+35))
            text=r['difference']
            for line in range(2):d.text((15,y+255+line*23),text[line*57:(line+1)*57],font=font(17),fill='#653827')
        sheet.save(ROOT/'comparisons'/('compare_'+cat+'.png'))
    done={r['key'] for r in records}
    counts=Counter(r['quality_status'] for r in records)
    stagecounts=Counter(r['stage'] for r in records)
    lines=['# assets-v3 单件素材交付清单','',f'本次已输出 {len(records)} 件：{dict(counts)}。阶段一 {stagecounts[1]} 件，阶段二 {stagecounts[2]} 件。',
           '','状态说明：可用指本地技术与视觉自检通过；待确认指造型、等级或新补图案需用户评审；均未接入游戏、未自动标记为用户确认。',
           '','透明 PNG 保留 8px 空边；raw 原图保留。尺寸含空边。九宫格边距顺序 L/T/R/B，单位为输出像素；仅建议，未在 Cocos 验证。',
           '','## 完成度与限制','',f'- 阶段一登记目标 {sum(i["stage"]==1 for i in inv)} 件；已产出 {stagecounts[1]} 件。',f'- 阶段二登记目标 {sum(i["stage"]==2 for i in inv)} 件；已产出 {stagecounts[2]} 件。',
           '- 事件卡正面五类待补设计；不生成内容。','- 自动检查包含 RGBA、8px 透明边、品红候选计数、2x 显示像素及哈希。几何保真和动画连贯性仍需人工评审。',
           '- previews/ 含浅、深、彩色底；comparisons/ 每类含原设计区域和单件素材及差异说明。','',
           '## 单件登记','', '|文件|尺寸|来源与位置|720显示尺寸|九宫格 L/T/R/B|边缘品红/全图品红|状态与差异|','|---|---|---|---|---|---|---|']
    for r in records:
        q=r['qa'];src=f'{r["origin"]}；{r.get("source") or "设计未覆盖"} {r.get("bbox") or ""}；{r["raw"]}'
        nine=str(r['slice_margin']) if r.get('nine_slice') else '否'
        lines.append(f'|[{r["key"]}](./{r["file"]})|{q["size"]}|{src}|{r["display"]}|{nine}|{q["magenta_edge_candidates"]}/{q["magenta_visible_candidates"]}|{r["quality_status"]}；{r["difference"]}|')
    lines+=['','## 尚未制作／待补设计','']
    for i in inv:
        if i['key'] not in done:lines.append(f'- {i["key"]}（阶段{i["stage"]}）：{i["name"]}；{i["status"]}。')
    (ROOT/'MANIFEST.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    dump(ROOT/'previews'/'qa_summary.json',dict(count=len(records),status=dict(counts),stages=dict(stagecounts),edge_candidates=sum(r['qa']['magenta_edge_candidates'] for r in records),visible_magenta=sum(r['qa']['magenta_visible_candidates'] for r in records),border_fail=[r['key'] for r in records if not r['qa']['border_8_clear']],density_fail=[r['key'] for r in records if not r['density_2x']]))
    print(json.dumps({'count':len(records),'status':dict(counts),'stages':dict(stagecounts)},ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['initial','reports']);args=p.parse_args()
    if args.action=='initial':
        split_sheet('avatars_sheet.png',['avatar_xiaolin','avatar_keke','avatar_ajie','avatar_naicha','avatar_akai','avatar_yuanyuan','avatar_doudou','avatar_maomao'],4,2,note='沿用已生成头像表；脸型、发丝更写实，圆圆未保留参考眼镜，奶茶帽上有新增花纹。')
        split_sheet('cards_sheet.png',['card_roadblock','card_free_rent','card_build','card_downgrade','card_fixed_move','card_jail_release','card_auction','card_trade','card_refuse_purchase','card_house_protection','card_query','card_forced_purchase','card_demolish','card_clear_land'],7,2,note='沿用已生成道具表；六种图标有参考，其余为同风格补图；对照说明不能视为已确认。')
    reports()
