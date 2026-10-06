"""User-authorized PIL cleanup and splitting; atlas originals stay unchanged.

Only alpha noise is removed. RGB artwork is never redrawn. Small independent
decorations are retained above the documented threshold. All outputs are
candidates until Cocos anchor/scale checks; this is not animation implementation.
"""
from pathlib import Path
from collections import deque
import json
import hashlib
from PIL import Image, ImageDraw, ImageFilter, ImageChops

ROOT = Path(__file__).resolve().parent
atlas_manifest = json.loads((ROOT / 'manifest.json').read_text(encoding='utf-8'))
output = ROOT / 'png'
output.mkdir(exist_ok=True)
previews = ROOT / 'previews'
previews.mkdir(exist_ok=True)
records = []

def clean_alpha(im, largest_only=False):
    alpha = im.getchannel('A')
    w, h = im.size
    src = alpha.tobytes()
    visited = bytearray(w*h)
    components = []
    for index, value in enumerate(src):
        if value < 16 or visited[index]:
            continue
        visited[index] = 1
        pending = deque([index])
        component = []
        while pending:
            p = pending.popleft()
            component.append(p)
            x, y = p % w, p // w
            for n in ((p-1 if x else -1), (p+1 if x+1<w else -1),
                      (p-w if y else -1), (p+w if y+1<h else -1)):
                if n >= 0 and not visited[n] and src[n] >= 16:
                    visited[n] = 1
                    pending.append(n)
        components.append(component)
    largest = max(map(len, components), default=0)
    threshold = max(16, round(largest * 0.0001))
    mask_bytes = bytearray(w*h)
    # Some generated rows are not perfectly aligned to mathematical cells.
    # Reject disconnected neighbour fragments that touch a cell boundary.
    def touches(c):
        return any(p % w in (0,w-1) or p // w in (0,h-1) for p in c)
    kept = [c for c in components if len(c) >= threshold and (len(c)==largest or (not largest_only and not touches(c)))]
    for c in kept:
        for p in c:
            mask_bytes[p] = 255
    # Preserve original antialiasing for two pixels around accepted components.
    mask = Image.frombytes('L', (w,h), bytes(mask_bytes)).filter(ImageFilter.MaxFilter(5))
    final_alpha = ImageChops.multiply(alpha, mask)
    result = im.copy()
    result.putalpha(final_alpha)
    return result, {'alpha_core_threshold':16, 'component_min_pixels':threshold,
                    'removed_components':len(components)-len(kept),
                    'preserved_components':len(kept),
                    'removed_alpha_pixels':sum(a>0 and b==0 for a,b in zip(src,final_alpha.tobytes()))}

def checker(size):
    bg = Image.new('RGB', size, '#ffffff')
    d = ImageDraw.Draw(bg)
    for y in range(0,size[1],16):
        for x in range(0,size[0],16):
            if (x//16+y//16)%2:
                d.rectangle((x,y,x+15,y+15),fill='#e3e8ec')
    return bg

for atlas in atlas_manifest['atlases']:
    folder = output / atlas['id']
    folder.mkdir(exist_ok=True)
    im = Image.open(ROOT / atlas['png']).convert('RGBA')
    frames = []
    for index, frame in enumerate(atlas['frames']):
        x,y,w,h = frame['cell_rect_xywh']
        # Explicit larger windows for images that cross nominal grid cells.
        # The connected alpha filter removes separate neighbour fragments.
        if atlas['id'] == 'cards':
            y = max(0,y-48)
            h = min(im.height-y,frame['cell_rect_xywh'][3]+96)
        if atlas['id'] == 'houses':
            y = max(0,y-48)
            h = min(im.height-y,frame['cell_rect_xywh'][3]+96)
        if atlas['id'] == 'event_art' and frame['name'] == 'event_card_fan':
            x,y,w,h = 728,554,462,356
        if atlas['id'] == 'event_art' and frame['name'] == 'event_card_highlight':
            x,w = 1188,348
        cell = im.crop((x,y,x+w,y+h))
        if frame['name'] == 'event_card_fan':
            # Exclude the neighbouring frame's glow along the slanted fan edge.
            cut = Image.new('L',cell.size,0)
            ImageDraw.Draw(cut).polygon([(0,65),(122,17),(144,0),(337,0),(345,28),
                                        (462,70),(462,126),(395,356),(355,341),
                                        (337,356),(140,356),(121,341),(70,356),
                                        (40,349),(0,117)],fill=255)
            cell.putalpha(ImageChops.multiply(cell.getchannel('A'),cut))
        cleaned, stats = clean_alpha(cell, largest_only=frame['name'] in ('event_card_fan','event_card_highlight'))
        bbox = cleaned.getchannel('A').getbbox()
        if not bbox:
            raise RuntimeError(f"empty sprite {atlas['id']} {frame['name']}")
        # Eight pixels of true transparency around the original painted bounds.
        sprite = cleaned.crop(bbox)
        result = Image.new('RGBA', (sprite.width+16,sprite.height+16), (0,0,0,0))
        result.paste(sprite,(8,8))
        path = folder / f"{index+1:02d}-{frame['name']}.png"
        result.save(path)
        hist = result.getchannel('A').histogram()
        records.append({'category':atlas['id'],'name':frame['name'],
                        'file':path.relative_to(ROOT).as_posix(),
                        'source':atlas['png'],'source_cell_xywh':[x,y,w,h],
                        'trim_bbox_in_cell':list(bbox),'transparent_padding':8,
                        'size':list(result.size),'cleanup':stats,
                        'transparent_pixels':hist[0], 'partial_alpha_pixels':sum(hist[1:255]),
                        'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                        'status':'split PNG candidate; Cocos anchors/scale not tested'})
        frames.append((frame['name'],result))
    cols = min(4,len(frames))
    rows = (len(frames)+cols-1)//cols
    preview = checker((cols*240,rows*270))
    d = ImageDraw.Draw(preview)
    for index,(name,sprite) in enumerate(frames):
        thumb = sprite.copy()
        thumb.thumbnail((220,230),Image.Resampling.LANCZOS)
        xx,yy = (index%cols)*240,(index//cols)*270
        preview.paste(thumb,(xx+(240-thumb.width)//2,yy+(240-thumb.height)//2),thumb)
        d.rectangle((xx,yy+240,xx+239,yy+269),fill='white')
        d.text((xx+6,yy+247),name,fill='#153855')
    preview.save(previews/f"{atlas['id']}.jpg",quality=94)

result_manifest = {'date':'2026-10-06','authorization':'User explicitly allowed PIL cleanup and splitting in this conversation',
                   'original_atlases_unchanged':True,'applied_to_game':False,
                   'sprite_count':len(records),'sprites':records}
(ROOT/'sprites.json').write_text(json.dumps(result_manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'sprites':len(records),'categories':len(atlas_manifest['atlases']),
                  'removed_alpha_pixels':sum(x['cleanup']['removed_alpha_pixels'] for x in records)},ensure_ascii=False))
