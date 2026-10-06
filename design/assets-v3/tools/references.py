from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[1]
SCREEN=ROOT.parent/'screens'

def make(keys,name,columns=4,cell=(320,300)):
    items={i['key']:i for i in json.loads((ROOT/'inventory.json').read_text(encoding='utf-8'))}
    sheet=Image.new('RGB',(columns*cell[0],((len(keys)+columns-1)//columns)*cell[1]),'#E6EDF4')
    draw=ImageDraw.Draw(sheet)
    for n,key in enumerate(keys):
        it=items[key]
        x=(n%columns)*cell[0];y=(n//columns)*cell[1]
        if it['bbox']:
            crop=Image.open(SCREEN/it['source']).convert('RGB').crop(it['bbox'])
            crop.save(ROOT/'references'/(key+'_source.png'))
            ratio=min((cell[0]-28)/crop.width,(cell[1]-48)/crop.height)
            crop=crop.resize((round(crop.width*ratio),round(crop.height*ratio)),Image.Resampling.LANCZOS)
            sheet.paste(crop,(x+(cell[0]-crop.width)//2,y+12+(cell[1]-48-crop.height)//2))
        draw.text((x+12,y+cell[1]-25),key,fill='#102040')
    sheet.save(ROOT/'references'/name)

if __name__=='__main__':
    data=json.loads((ROOT/'inventory.json').read_text(encoding='utf-8'))
    for cat in ('avatars','cards','ui','icons','tiles','tile_icons','houses','pawns','dice','motion','town','states','decor'):
        make([i['key'] for i in data if i['category']==cat],cat+'_reference.png')
