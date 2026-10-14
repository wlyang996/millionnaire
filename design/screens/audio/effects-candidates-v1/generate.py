"""Original procedural, isolated game cues. No sampled audio, speech or music bed."""
from pathlib import Path
import hashlib,json,wave
import numpy as np
SR=44100
ROOT=Path(__file__).resolve().parent
rng=np.random.default_rng(20261013)
def tone(freq,dur=.16,level=.35):
    t=np.arange(round(dur*SR))/SR
    envelope=(1-np.exp(-t/.003))*np.exp(-t/(dur*.25))*np.clip((dur-t)/.014,0,1)
    return level*(np.sin(2*np.pi*freq*t)+.08*np.sin(4*np.pi*freq*t))*envelope
def cue(notes,gap=.10,dur=.22):
    out=np.zeros(round((len(notes)*gap+dur)*SR))
    for i,f in enumerate(notes):
        y=tone(f,dur);start=round(i*gap*SR);out[start:start+len(y)]+=y
    return out
def ticks(count):
    out=np.zeros(round((.06*count+.12)*SR))
    for i in range(count):
        t=np.arange(round(.025*SR))/SR
        y=rng.normal(0,.15,len(t))*np.exp(-t/.006)+.16*np.sin(2*np.pi*(800+i*60)*t)*np.exp(-t/.009)
        start=round(.06*i*SR);out[start:start+len(y)]+=y
    return out
specs=[('dice','01-骰子轻碰',ticks(7)),('move','02-棋子落地',tone(280,.11,.24)),
 ('cashGain','03-收入金币',cue([880,1108,1320],.065)),('cashLoss','04-支付金币',cue([660,554,440],.075)),
 ('card','05-翻牌',ticks(2)),('purchase','06-购买成功',cue([523,659,784],.09)),
 ('upgrade','07-升级成功',cue([659,784,1047],.085)),('auction','08-拍卖落槌',tone(160,.24,.42)+tone(320,.24,.10)),
 ('jail','09-入狱提示',cue([330,277,220],.13,.3)),('release','10-出狱提示',cue([440,554,660],.11)),
 ('reward','11-全员奖励',cue([523,659,784,1047],.105,.32))]
def save(path,y):
    # Consistent peak -9 dBFS, short silent boundaries, no clipping.
    y=np.pad(y,(round(.025*SR),round(.05*SR)))
    y=y*(10**(-9/20)/max(np.max(np.abs(y)),1e-10))
    pcm=np.round(y*32767).astype('<i2')
    with wave.open(str(path),'wb') as f:f.setnchannels(1);f.setsampwidth(2);f.setframerate(SR);f.writeframes(pcm.tobytes())
    return dict(durationMs=round(len(y)*1000/SR),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),peakDbfs=-9)
existing = json.loads((ROOT/'manifest.json').read_text(encoding='utf-8')) if (ROOT/'manifest.json').exists() else []
previous = {item['id']:item for item in existing}
manifest=[];preview=[]
for key,name,y in specs:
    path=ROOT/(name+'.wav');meta=save(path,y)
    item=dict(id=key,name=name,file=path.name,status='pending-user-review',source='original-procedural-synthesis',**meta)
    old=previous.get(key,{})
    if old.get('sha256')==meta['sha256']:
        for field in ['status','approvedAt']:
            if field in old:item[field]=old[field]
    manifest.append(item)
    preview.extend([y,np.zeros(round(.8*SR))])
save(ROOT/'00-十一项音效-按序试听.wav',np.concatenate(preview))
(ROOT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Generated 11 isolated original cues; unchanged approval records preserved.')
