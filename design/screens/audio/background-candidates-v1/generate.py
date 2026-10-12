"""Original note scores + procedural instrument synthesis; not an AI music model.

Requires numpy. WAV masters are generated in ignored tmp; MP3 auditions are archived here.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import subprocess
import wave
import numpy as np

SR = 44100
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
TEMP = ROOT / 'tmp/audio-reference-analysis/background-candidates-v1'

SCORES = [
    dict(id='morning-town', name='01-晨光小镇-试听', bpm=112, bars=16,
         mood='轻快明亮；弹拨和弦、木琴旋律、轻打击；大厅/普通棋盘候选',
         lead='wood', chord='pluck', bass=43,
         chords=[[55,59,62,66],[52,55,59,62],[48,52,55,59],[50,54,57,62]],
         themes=[[74,76,79,None,76,74,71,74],[71,None,74,76,79,76,74,None],
                 [72,76,79,76,74,None,72,71],[69,74,78,None,76,74,69,None]],
         variants=[[79,78,76,74,71,74,76,None],[79,76,74,71,67,None,71,74],
                   [76,79,81,79,76,74,72,None],[74,76,78,81,78,76,74,None]]),
    dict(id='riverside-afternoon', name='02-河畔午后-试听', bpm=84, bars=12,
         mood='温暖悠闲；柔和电钢琴、气息感旋律、稀疏节奏；大厅/长局候选',
         lead='air', chord='piano', bass=41,
         chords=[[53,57,60,64],[50,53,57,60],[46,50,53,57],[48,52,55,62]],
         themes=[[69,None,72,74,None,72,69,None],[65,None,69,72,None,69,65,None],
                 [65,69,None,72,74,None,72,69],[67,None,72,74,76,None,72,None]],
         variants=[[72,None,76,74,72,69,None,65],[69,None,72,74,72,None,69,None],
                   [72,None,74,77,74,None,72,69],[67,72,None,76,74,None,72,None]]),
    dict(id='friends-play', name='03-好友开局-试听', bpm=108, bars=12,
         mood='俏皮活泼；玩具木琴、拨弦、轻柔沙锤；好友桌游候选',
         lead='toy', chord='pluck', bass=36,
         chords=[[48,52,55,59],[45,48,52,55],[53,57,60,64],[55,59,62,65]],
         themes=[[72,None,76,79,76,None,74,72],[69,72,None,76,74,72,None,69],
                 [72,None,77,76,74,None,72,69],[71,74,79,None,77,74,71,None]],
         variants=[[79,76,72,None,74,76,79,None],[76,74,72,69,None,72,74,None],
                   [77,76,74,None,72,74,77,76],[74,None,77,79,77,74,71,None]]),
]

def voice(note, duration, kind):
    t = np.arange(max(1, round(duration * SR))) / SR
    f = 440 * 2 ** ((note - 69) / 12)
    p = 2 * np.pi * f * t
    attack = 1 - np.exp(-t / (0.055 if kind == 'air' else 0.008))
    release = np.minimum(1, np.maximum(0, (duration - t) / 0.09))
    if kind == 'wood':
        y = np.sin(p + 0.9 * np.exp(-t / 0.075) * np.sin(2 * p))
        y += 0.10 * np.sin(3 * p) * np.exp(-t / 0.065)
        env = np.exp(-t / 0.30)
    elif kind == 'toy':
        y = np.sin(p) + 0.22 * np.sin(2.76 * p) * np.exp(-t / 0.06)
        env = np.exp(-t / 0.22)
    elif kind == 'piano':
        y = np.sin(p) + 0.21 * np.sin(2 * p) + 0.08 * np.sin(3 * p)
        env = np.exp(-t / 1.25)
    elif kind == 'air':
        y = np.sin(p + 0.025 * np.sin(2 * np.pi * 4.2 * t)) + 0.1 * np.sin(2*p)
        env = np.exp(-t / 1.3)
    elif kind == 'bass':
        y = np.sin(p) + 0.17 * np.sin(2*p)
        env = np.exp(-t / 0.42)
    else:
        y = np.sin(p) + 0.28 * np.sin(2*p) + 0.12 * np.sin(3*p)
        env = np.exp(-t / 0.43)
    return y * attack * release * env

def render(score, seed):
    rng = np.random.default_rng(seed)
    beat = 60 / score['bpm']
    length = round(score['bars'] * 4 * beat * SR)
    mix = np.zeros(length, dtype=np.float64)

    def add(sound, when, volume):
        indices = (round(when * SR) + np.arange(len(sound))) % length
        np.add.at(mix, indices, sound * volume)

    for bar in range(score['bars']):
        chord = score['chords'][bar % 4]
        when = bar * 4 * beat
        warm = score['lead'] == 'air'
        for onset in ([0,2] if warm else [0,1.5,2.5,3.5]):
            for i,note in enumerate(chord):
                add(voice(note,1.75*beat,score['chord']),when+onset*beat+i*.015,.075 if warm else .038)
        for onset,note in [(0,chord[0]-12),(2,chord[0]-5)]:
            add(voice(note,.85*beat,'bass'),when+onset*beat,.14 if warm else .17)
        theme = (score['variants'] if (bar // 4) % 2 else score['themes'])[bar % 4]
        for i,note in enumerate(theme):
            if note is not None:
                add(voice(note,(.7 if warm else .5)*beat,score['lead']),when+i*.5*beat,
                    (.18 if warm else .16) * (1 if i%2 == 0 else .88))
        # Quiet independent percussion; no samples extracted from the prior mixed recording.
        for onset in ([1,3] if warm else np.arange(0,4,.5)):
            t = np.arange(round(SR*.05)) / SR
            noise = rng.normal(0,1,len(t))
            noise = np.r_[0,np.diff(noise)] * np.exp(-t/.012) * (1-np.exp(-t/.002))
            add(noise,when+onset*beat,.009 if warm else .012)
        if not warm:
            for onset in [0,2]:
                t = np.arange(round(SR*.14))/SR
                kick = np.sin(2*np.pi*(70*t+1.2*(1-np.exp(-t/.025)))) * np.exp(-t/.04) * (1-np.exp(-t/.003))
                add(kick,when+onset*beat,.06)
    dry = mix.copy()
    for seconds,gain in [(.071,.11),(.137,.07),(.223,.045)]:
        mix += np.roll(dry,round(seconds*SR))*gain
    rms = math.sqrt(float(np.mean(mix*mix)))
    mix *= min(.10/rms,.70/float(np.max(np.abs(mix))))
    return mix

def write_wav(path, samples):
    data = np.rint(samples*32767).astype('<i2')
    with wave.open(str(path),'wb') as output:
        output.setparams((1,2,SR,len(data),'NONE','not compressed'))
        output.writeframes(data.tobytes())

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ffmpeg', required=True)
    args = parser.parse_args()
    TEMP.mkdir(parents=True,exist_ok=True)
    tracks = []
    for i,score in enumerate(SCORES):
        loop = render(score,90210+i)
        master = TEMP / (score['id']+'-loop.wav')
        write_wav(master,loop)
        preview = loop.copy()
        fade = round(SR*.14)
        preview[:fade] *= np.linspace(0,1,fade)
        fade = round(SR*.65)
        preview[-fade:] *= np.linspace(1,0,fade)
        audition = TEMP / (score['id']+'-audition.wav')
        write_wav(audition,preview)
        dest = HERE / (score['name']+'.mp3')
        subprocess.run([args.ffmpeg,'-hide_banner','-loglevel','error','-y','-i',str(audition),'-codec:a','libmp3lame','-b:a','128k',str(dest)],check=True)
        # Decode the delivered MP3, rather than only checking the source waveform.
        raw = subprocess.run([args.ffmpeg,'-v','error','-i',str(dest),'-f','f32le','-ac','1','-ar',str(SR),'-'],check=True,capture_output=True).stdout
        decoded = np.frombuffer(raw,dtype='<f4')
        peak = float(np.max(np.abs(decoded)))
        assert np.isfinite(decoded).all() and peak < 0.95 and len(decoded) > SR*20
        tracks.append(dict(id=score['id'],file=dest.name,bpm=score['bpm'],bars=score['bars'],
            seconds=round(len(decoded)/SR,3),peak_dbfs=round(20*math.log10(peak),2),
            rms_dbfs=round(20*math.log10(float(np.sqrt(np.mean(decoded**2)))),2),
            sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),bytes=dest.stat().st_size,
            channels=1,sample_rate=SR,mood=score['mood'],status='awaiting-review',
            source='original-note-score-procedural-synthesis-no-external-samples'))
    (HERE/'manifest.json').write_text(json.dumps(tracks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(tracks,ensure_ascii=False,indent=2))

if __name__ == '__main__':
    main()
