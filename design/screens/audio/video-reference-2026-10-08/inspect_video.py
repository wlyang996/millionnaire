"""Extract reference audio and timestamped frames, without changing the source."""
import sys
import subprocess
import json
import hashlib
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[4] / 'tmp/audio-tools'))
import imageio_ffmpeg
from PIL import Image, ImageDraw

source = Path(sys.argv[1])
root = Path(__file__).parent
analysis = Path(__file__).resolve().parents[4] / 'tmp/audio-reference-analysis/video-reference-2026-10-08'
frames = analysis / 'frames'
frames.mkdir(parents=True, exist_ok=True)
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
def run(args):
    p = subprocess.run([ffmpeg, '-hide_banner', '-y', *args], capture_output=True)
    log = p.stderr.decode('utf-8', errors='replace')
    if p.returncode:
        raise RuntimeError(log)
    return log
log = run(['-i', str(source), '-vn', '-acodec', 'pcm_s16le', str(root / 'reference-full.wav')])
(root / 'decode.log').write_text(log, encoding='utf-8')
run(['-i', str(source), '-vf', 'fps=2,scale=224:-1', str(frames / '%04d.png')])
files = sorted(frames.glob('*.png'))
for page in range((len(files) + 23) // 24):
    batch = files[page * 24:(page + 1) * 24]
    first = Image.open(batch[0])
    w,h = first.size
    sheet = Image.new('RGB', (w * 6, (h+22) * 4), '#eeeeee')
    draw = ImageDraw.Draw(sheet)
    for j,f in enumerate(batch):
        x,y = (j%6)*w, (j//6)*(h+22)
        sheet.paste(Image.open(f).convert('RGB'), (x,y+22))
        draw.text((x+4,y+4), f'{(page*24+j)*0.5:.1f}s', fill='black')
    sheet.save(analysis / f'contact-sheet-{page+1}.jpg')
(root/'source.json').write_text(json.dumps({'source':str(source),'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'frame_sampling_fps':2,'frame_times_approximate':True,'source_unchanged':True},ensure_ascii=False,indent=2),encoding='utf-8')
print(log)
print('frames',len(files))
