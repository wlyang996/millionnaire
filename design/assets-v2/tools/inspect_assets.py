"""Read-only image metadata inspection. Does not edit or re-encode images."""
from pathlib import Path
from PIL import Image
import json

root = Path(__file__).resolve().parents[1]
report = []
for path in sorted((root / 'png').glob('*.png')):
    with Image.open(path) as im:
        im.load()
        alpha = im.getchannel('A') if 'A' in im.getbands() else None
        histogram = alpha.histogram() if alpha is not None else None
        report.append({
            'file': path.name,
            'size': list(im.size),
            'mode': im.mode,
            'alpha_extrema': list(alpha.getextrema()) if alpha else None,
            'fully_transparent_pixels': histogram[0] if histogram else 0,
            'partially_transparent_pixels': sum(histogram[1:255]) if histogram else 0,
            'note': 'Metadata does not prove clean edges or runtime readiness.'
        })
(root / 'qa_metadata.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
