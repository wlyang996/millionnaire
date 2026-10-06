"""PIL cleanup explicitly authorized by the user; original atlases stay intact."""
import ast, hashlib, json
from collections import deque
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageChops
root=Path(__file__).resolve().parent
# Reuse the already-reviewed alpha cleanup without running its batch entrypoint.
module=ast.parse((root.parent/'assets-supplement-v1/split_clean.py').read_text(encoding='utf-8'))
function=next(x for x in module.body if isinstance(x,ast.FunctionDef) and x.name=='clean_alpha')
exec(compile(ast.Module(body=[function],type_ignores=[]),'<existing alpha cleanup>','exec'))
jobs=json.loads((root/'jobs.json').read_text(encoding='utf-8'))['jobs']
sprites=[]
for job in jobs:
    source=root/'atlases'/f"{job['id']}.png"
    image=Image.open(source).convert('RGBA')
    cols,rows=job['grid']
    for i,name in enumerate(job['names']):
        x0,y0=(i%cols)*image.width//cols,(i//cols)*image.height//rows
        x1,y1=((i%cols)+1)*image.width//cols,((i//cols)+1)*image.height//rows
        if job['id']=='information_shops':
            y0=max(0,y0-32); y1=min(image.height,y1+32)
        piece=image.crop((x0,y0,x1,y1))
        if job['alpha']:
            piece,stats=clean_alpha(piece,largest_only=job['id'] in ('information_shops','information_panels','information_host'))
            bbox=piece.getchannel('A').getbbox()
            piece=piece.crop(bbox)
            padded=Image.new('RGBA',(piece.width+16,piece.height+16))
            padded.paste(piece,(8,8)); piece=padded
        else:
            bbox=[0,0,piece.width,piece.height];stats={}
        target=root/'png'/f'{name}.png'
        piece.save(target)
        sprites.append({'name':name,'file':'png/'+target.name,'size':list(piece.size),
                        'source':'atlases/'+source.name,'source_rect':[x0,y0,x1,y1],
                        'trim':bbox,'cleanup':stats,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
(root/'sprites.json').write_text(json.dumps({'date':'2026-10-06','sprites':sprites,'originals_preserved':True},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
preview=Image.new('RGB',(1000,((len(sprites)+4)//5)*235),'#e5eef4')
d=ImageDraw.Draw(preview)
for i,s in enumerate(sprites):
    im=Image.open(root/s['file']);im.thumbnail((190,190))
    x,y=(i%5)*200,(i//5)*235
    preview.paste(im,(x+(200-im.width)//2,y+(200-im.height)//2),im)
    d.text((x+4,y+202),s['name'],fill='#17344c')
preview.save(root/'素材清理拆分预览.jpg',quality=95)
print(json.dumps({'sprites':len(sprites),'names':[s['name'] for s in sprites]}))
