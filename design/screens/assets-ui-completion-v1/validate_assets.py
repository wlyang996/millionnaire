"""File/alpha integrity only, not runtime or approval verification."""
import hashlib, json
from pathlib import Path
from PIL import Image, ImageDraw
root=Path(__file__).resolve().parent
data=json.loads((root/'sprites.json').read_text(encoding='utf-8'))
errors=[]; motions={}
for s in data['sprites']:
    path=root/s['file']
    if not path.exists(): errors.append('missing '+s['name']); continue
    if hashlib.sha256(path.read_bytes()).hexdigest()!=s['sha256']: errors.append('hash '+s['name'])
    im=Image.open(path);im.verify(); im=Image.open(path)
    if list(im.size)!=s['size'] or im.mode!='RGBA': errors.append('size/mode '+s['name'])
    if s['transparent_padding']:
        a=im.getchannel('A'); w,h=im.size
        for box in ((0,0,w,8),(0,h-8,w,h),(0,0,8,h),(w-8,0,w,h)):
            if a.crop(box).getextrema()[1]!=0: errors.append('padding '+s['name'])
    if s['motion_fixed_cell']:
        key=s['name'].rsplit('_',1)[0];motions.setdefault(key,set()).add(tuple(im.size))
if any(len(sizes)!=1 for sizes in motions.values()): errors.append('inconsistent motion canvas')
expected=sum(len(j['names']) for j in json.loads((root/'jobs.json').read_text(encoding='utf-8-sig'))['jobs'])
if len(data['sprites'])!=expected: errors.append('count mismatch')
if len(list((root/'png').glob('*.png')))!=expected: errors.append('unlisted PNG')
if data['missing_atlases']: errors.append('missing atlas')
report={'date':'2026-10-06','sprites':len(data['sprites']),'expected':expected,'errors':errors,
        'checks':['file existence','decodable RGBA','native size','SHA-256','8px transparent padding','same motion frame canvas'],
        'runtime_imported':False,'runtime_tested':False,'visual_review':'16 category contact sheets inspected; source corrections preserved'}
(root/'qa-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
# All individual sprites are visible in one preview. Names use resource keys, not numerical identifiers.
cols=6; rows=(len(data['sprites'])+cols-1)//cols
out=Image.new('RGB',(cols*200,rows*195),'#e6edf4');draw=ImageDraw.Draw(out)
for i,s in enumerate(data['sprites']):
    im=Image.open(root/s['file']);im.thumbnail((184,158),Image.Resampling.LANCZOS)
    x,y=(i%cols)*200,(i//cols)*195
    out.paste(im,(x+(200-im.width)//2,y+(162-im.height)//2),im)
    draw.rectangle((x,y+165,x+199,y+194),fill='#fafcff')
    draw.text((x+3,y+168),s['name'],fill='#193752')
out.save(root/'素材总览.jpg',quality=94)
print(json.dumps(report,ensure_ascii=False))
if errors: raise SystemExit(1)
