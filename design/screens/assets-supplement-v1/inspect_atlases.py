"""Read-only PNG QA and region metadata. Never changes image pixels."""
from pathlib import Path
import hashlib
import json
from PIL import Image

root = Path(__file__).resolve().parent
jobs_file = root.parent / 'ui' / 'supplement-v1' / 'jobs.json'
jobs = json.loads(jobs_file.read_text(encoding='utf-8-sig'))['jobs']
extra = root.parent / 'ui' / 'supplement-v1' / 'event-art-job.json'
if extra.exists():
    jobs.append(json.loads(extra.read_text(encoding='utf-8-sig')))
records = []
missing = []
for job in jobs:
    if not job.get('alpha'):
        continue
    path = root.parent / job['file']
    if not path.exists():
        missing.append(job['id'])
        continue
    with Image.open(path) as im:
        w, h = im.size
        alpha = im.getchannel('A') if 'A' in im.getbands() else None
        hist = alpha.histogram() if alpha is not None else [0] * 255 + [w * h]
        cols, rows = job['grid']
        frames = []
        for index, name in enumerate(job['names']):
            col, row = index % cols, index // cols
            x0, y0 = col * w // cols, row * h // rows
            x1, y1 = (col + 1) * w // cols, (row + 1) * h // rows
            bbox = alpha.crop((x0, y0, x1, y1)).getbbox() if alpha else (0, 0, x1-x0, y1-y0)
            touches = bool(bbox and (bbox[0] == 0 or bbox[1] == 0 or bbox[2] == x1-x0 or bbox[3] == y1-y0))
            frames.append({'name': name, 'cell_rect_xywh': [x0, y0, x1-x0, y1-y0], 'alpha_bbox_in_cell': bbox, 'touches_cell_edge': touches})
        records.append({'id': job['id'], 'png': 'atlases/' + path.name, 'size': [w,h], 'mode': im.mode,
                        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                        'transparent_pixels': hist[0], 'partial_alpha_pixels': sum(hist[1:255]),
                        'native_alpha_present': alpha is not None and hist[0] > 0,
                        'grid': [cols,rows], 'frames': frames,
                        'status': 'candidate; inspect before import; frame cells include transparent margins'})
manifest = {'date': '2026-10-06', 'source': 'built-in image_gen; no raster edits', 'applied_to_game': False,
            'atlas_count': len(records), 'region_count': sum(len(x['frames']) for x in records),
            'missing': missing, 'coordinate_origin': 'top-left; integer pixels', 'atlases': records}
(root / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'atlas_count': len(records), 'region_count': manifest['region_count'], 'missing': missing,
                  'opaque_files': [x['png'] for x in records if not x['native_alpha_present']],
                  'edge_checks': {x['id']: [f['name'] for f in x['frames'] if f['touches_cell_edge']] for x in records}}, ensure_ascii=False))
