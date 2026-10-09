"""Conservative spectral background suppression, not clean source separation."""
import hashlib
import json
import math
import wave
from pathlib import Path
import numpy as np

ROOT = Path(__file__).parent
DEST = ROOT / 'background-reduced-v1'
DEST.mkdir(exist_ok=True)
with wave.open(str(ROOT / 'reference-full.wav'), 'rb') as w:
    RATE = w.getframerate()
    assert w.getnchannels() == 1 and w.getsampwidth() == 2
    raw = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float64) / 32768

N, HOP = 1024, 256
window = np.hanning(N)
padding = N // 2
padded = np.pad(raw, (padding, padding + N))
frames = np.lib.stride_tricks.sliding_window_view(padded, N)[::HOP]
spec = np.fft.rfft(frames * window, axis=1)
mag = np.abs(spec)
# Persistent frequency components are often music. Broad, brief components are
# often impacts. This distinction is imperfect and can also remove tonal SFX.
harmonic = np.empty_like(mag)
for k in range(mag.shape[1]):
    row = np.pad(mag[:,k], (30,30), mode='edge')
    harmonic[:,k] = np.median(np.lib.stride_tricks.sliding_window_view(row,61), axis=1)
freq = np.pad(mag, ((0,0),(7,7)), mode='edge')
percussive = np.median(np.lib.stride_tricks.sliding_window_view(freq,15,axis=1),axis=2)
mask = percussive**2 / (percussive**2 + 2*harmonic**2 + 1e-12)
# Keep some original spectrum to avoid a hard musical-noise gate.
mask = .14 + .86 * mask
# Only suppress a persistent low-energy floor; do not remove peaks by amplitude.
floor = np.quantile(mag, .18, axis=0)
floor_gain = np.sqrt(np.maximum(.08, 1 - .7*floor**2/(mag**2+1e-12)))
filtered = np.fft.irfft(spec * mask * floor_gain, n=N, axis=1)
y = np.zeros(len(padded))
normal = np.zeros(len(padded))
for j,frame in enumerate(filtered):
    pos = j*HOP
    y[pos:pos+N] += frame*window
    normal[pos:pos+N] += window**2
y = (y/np.maximum(normal,1e-12))[padding:padding+len(raw)]

jobs = [('骰子投掷-背景抑制试听-v1.wav',1.28,2.70,False),
        ('人物移动-连续逐格-背景抑制试听-v1.wav',2.76,4.43,True),
        ('人物移动-单格-背景抑制试听-v1.wav',3.10,3.32,True)]
result=[]
contacts = [2.810,3.110,3.410,3.710,4.010,4.310]
for name,start,end,movement in jobs:
    lo,hi = round(start*RATE),round(end*RATE)
    clip=y[lo:hi].copy()
    if movement:
        times=np.arange(lo,hi)/RATE
        gate=np.full(len(clip),.06)
        # Soft windows follow each recorded impact. Low background stays quiet
        # between footsteps; the impact tail is not looped or repeated.
        for event in contacts:
            rel=times-event
            attack=np.clip((rel+.015)/.008,0,1)
            release=np.clip((.145-rel)/.050,0,1)
            gate=np.maximum(gate,attack*release)
        clip*=gate
    fade=round(.003*RATE)
    ramp=np.linspace(0,1,fade)
    clip[:fade]*=ramp
    clip[-fade:]*=ramp[::-1]
    peak=float(np.max(np.abs(clip)))
    gain=min(1.,.89/max(peak,1e-12))
    clip*=gain
    pcm=np.round(np.clip(clip,-1,1)*32767).astype('<i2')
    out=DEST/name
    with wave.open(str(out),'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    with wave.open(str(out),'rb') as w:
        assert w.getnframes()==hi-lo and w.getframerate()==RATE
    result.append({'file':name,'start':start,'end':end,'duration':(hi-lo)/RATE,
      'rate':RATE,'channels':1,'bits':16,'gain':gain,
      'peak_dbfs':round(20*math.log10(max(float(np.max(np.abs(clip))),1e-12)),2),
      'input_rms':float(np.sqrt(np.mean(raw[lo:hi]**2))),
      'output_rms':float(np.sqrt(np.mean(clip**2))),
      'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),
      'status':'background-reduced preview; not auditioned or adopted',
      'method':'harmonic/percussive spectral mask, low floor attenuation, movement time gate if applicable'})
(DEST/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=True,indent=2))
