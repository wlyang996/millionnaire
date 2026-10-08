"""Sample-accurate reference cuts; no synthesis or source separation."""
import wave
import struct
import json
import hashlib
import math
from pathlib import Path
root = Path(__file__).parent
with wave.open(str(root / 'reference-full.wav'), 'rb') as w:
    rate = w.getframerate()
    assert w.getnchannels() == 1 and w.getsampwidth() == 2
    data = w.readframes(w.getnframes())
jobs = [
    ('骰子投掷-视频参考片段-v1.wav', 1.28, 2.70, 'Dice animation; includes initial action sound and mixed background'),
    ('人物移动-连续逐格-视频参考片段-v1.wav', 2.76, 4.43, 'Six movement contacts; original mixed background retained'),
    ('人物移动-单格-视频参考片段-v1.wav', 3.12, 3.32, 'One movement contact; not a separated clean master'),
]
items = []
for name,start,end,note in jobs:
    lo,hi = round(start * rate),round(end * rate)
    clip = list(struct.unpack('<'+'h'*(hi-lo), data[lo*2:hi*2]))
    fade = round(rate * .002)
    for i in range(fade):
        clip[i] = round(clip[i] * i / fade)
        clip[-i-1] = round(clip[-i-1] * i / fade)
    dest = root/name
    with wave.open(str(dest),'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(struct.pack('<'+'h'*len(clip),*clip))
    with wave.open(str(dest),'rb') as w:
        assert w.getnframes() == hi-lo
    peak = max(abs(x) for x in clip)/32768
    items.append({'file':name,'start_seconds':start,'end_seconds':end,'duration_seconds':(hi-lo)/rate,'sample_rate':rate,'channels':1,'sample_bits':16,'edge_fade_ms':2,'peak_dbfs':round(20*math.log10(peak),2),'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'note':note,'status':'reference cut; not adopted or integrated'})
(root/'clips.json').write_text(json.dumps(items,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(items,ensure_ascii=True,indent=2))
