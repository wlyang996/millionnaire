"""User-authorized PIL alpha cleanup and sprite splitting; originals preserved."""
import ast, hashlib, json
from collections import deque
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageChops, ImageOps

root=Path(__file__).resolve().parent
module=ast.parse((root.parent/'assets-supplement-v1/split_clean.py').read_text(encoding='utf-8'))
function=next(x for x in module.body if isinstance(x,ast.FunctionDef) and x.name=='clean_alpha')
exec(compile(ast.Module(body=[function],type_ignores=[]),'<existing alpha cleanup>','exec'))
jobs=json.loads((root/'jobs.json').read_text(encoding='utf-8-sig'))['jobs']
overrides=json.loads((root/'crop-overrides.json').read_text(encoding='utf-8-sig'))['overrides']
records=[]

def checker(size):
    im=Image.new('RGB',size,'#f7f9fc'); d=ImageDraw.Draw(im)
    for y in range(0,size[1],16):
        for x in range(0,size[0],16):
            if (x//16+y//16)%2: d.rectangle((x,y,x+15,y+15),fill='#dce4ec')
    return im

missing=[]
for job in jobs:
    source=root/'atlases'/(job['id']+'.png')
    if not source.exists():
        missing.append(job['id']); continue
    atlas=Image.open(source).convert('RGBA'); override=overrides.get(job['id'],{})
    cols,rows=override.get('grid',job['grid'])
    category=[]
    for i,name in enumerate(job['names']):
        box=((i%cols)*atlas.width//cols,(i//cols)*atlas.height//rows,
             ((i%cols)+1)*atlas.width//cols,((i//cols)+1)*atlas.height//rows)
        if 'rects_normalized' in override:
            x0,y0,x1,y1=override['rects_normalized'][i]
            box=(round(x0*atlas.width),round(y0*atlas.height),round(x1*atlas.width),round(y1*atlas.height))
        piece=atlas.crop(box); original_size=piece.size
        if job['alpha']:
            if piece.getchannel('A').getextrema()[0]==255:
                raise RuntimeError('Opaque cutout: '+name)
            piece,cleanup=clean_alpha(piece,largest_only=False)
            bounds=piece.getchannel('A').getbbox()
            if not bounds: raise RuntimeError('Empty sprite: '+name)
            # Motion frames retain the identical cell canvas, avoiding per-frame anchor jitter.
            if not job.get('motion'): piece=piece.crop(bounds)
            result=Image.new('RGBA',(piece.width+16,piece.height+16))
            result.paste(piece,(8,8)); piece=result
        else:
            bounds=(0,0,*piece.size); cleanup={}
            # Preserve the native background too; exact production design canvas is additional output.
            piece=ImageOps.fit(piece,(720,1280),method=Image.Resampling.LANCZOS)
        path=root/'png'/(name+'.png');piece.save(path)
        hist=piece.getchannel('A').histogram()
        rec={'name':name,'category':job['id'],'file':'png/'+path.name,'source':'atlases/'+source.name,
             'source_rect':list(box),'native_cell_size':list(original_size),'alpha_bounds':list(bounds),
             'size':list(piece.size),'motion_fixed_cell':bool(job.get('motion')),'transparent_padding':8 if job['alpha'] else 0,
             'transparent_pixels':hist[0],'partial_alpha_pixels':sum(hist[1:255]),'cleanup':cleanup,
             'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'status':'generated candidate; not imported'}
        records.append(rec);category.append(rec)
    count=len(category); pc=min(4,count);pr=(count+pc-1)//pc
    preview=checker((pc*250,pr*270));d=ImageDraw.Draw(preview)
    for i,rec in enumerate(category):
        im=Image.open(root/rec['file']);im.thumbnail((235,225),Image.Resampling.LANCZOS)
        x,y=(i%pc)*250,(i//pc)*270
        preview.paste(im,(x+(250-im.width)//2,y+(230-im.height)//2),im)
        d.rectangle((x,y+235,x+249,y+269),fill='white');d.text((x+4,y+245),rec['name'],fill='#183955')
    preview.save(root/'previews'/(job['id']+'.jpg'),quality=95)

(root/'sprites.json').write_text(json.dumps({'date':'2026-10-06','sprite_count':len(records),
 'original_atlases_preserved':True,'applied_to_game':False,'missing_atlases':missing,'sprites':records},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'sprites':len(records),'missing_atlases':missing}))
