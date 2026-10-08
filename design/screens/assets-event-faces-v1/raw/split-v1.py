"""Split the generated 2 x 3 atlas and preserve the reference card shell.

Run: python split.py
Requires Pillow. All inputs and outputs stay within this asset directory.
The illustration crops and template mask are explicit for raw/atlas.png.
"""
from collections import deque
from pathlib import Path
import colorsys
import json
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent
SIZE = (369, 473)
NAMES = ["event_build", "event_downgrade", "event_station", "event_start",
         "lucky_face", "unlucky_face"]
# Illustration-only rectangles, excluding generated gold frame and text bands.
CROPS = [(135, 107, 471, 401), (555, 107, 891, 401),
         (135, 615, 471, 911), (555, 615, 891, 911),
         (135, 1122, 471, 1418), (555, 1122, 891, 1418)]
WINDOW = (24, 97, 345, 362)


def largest_component(mask):
    """Remove isolated alpha debris without using image colors as background."""
    w, h = mask.size
    p = mask.load()
    visited = bytearray(w * h)
    best = []
    for y in range(h):
        for x in range(w):
            i = y * w + x
            if visited[i] or not p[x, y]:
                continue
            visited[i] = 1
            queue = deque([(x, y)])
            component = []
            while queue:
                xx, yy = queue.popleft()
                component.append((xx, yy))
                for nx, ny in ((xx-1, yy), (xx+1, yy), (xx, yy-1), (xx, yy+1)):
                    if 0 <= nx < w and 0 <= ny < h:
                        ni = ny * w + nx
                        if not visited[ni] and p[nx, ny]:
                            visited[ni] = 1
                            queue.append((nx, ny))
            if len(component) > len(best):
                best = component
    result = Image.new("L", mask.size)
    rp = result.load()
    for xy in best:
        rp[xy] = 255
    return result


def shell_alpha(template):
    original = template.getchannel("A")
    solid = largest_component(original.point(lambda a: 255 if a >= 128 else 0))
    # One-pixel antialias fringe allowed only next to the connected card.
    allowed = solid.filter(ImageFilter.MaxFilter(3))
    ap, sp = original.load(), allowed.load()
    result = Image.new("L", SIZE)
    rp = result.load()
    for y in range(SIZE[1]):
        for x in range(SIZE[0]):
            if sp[x, y] and ap[x, y] >= 8:
                rp[x, y] = 255 if ap[x, y] >= 248 else ap[x, y]
    return result


def art_mask():
    # Reference window top is rounded; keep its fine gold outline intact.
    scale = 4
    mask = Image.new("L", (SIZE[0]*scale, SIZE[1]*scale))
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle((24*scale, 97*scale, 345*scale, 442*scale),
                           radius=40*scale, fill=255)
    draw.rectangle((0, 362*scale, SIZE[0]*scale, SIZE[1]*scale), fill=0)
    result = mask.resize(SIZE, Image.Resampling.LANCZOS)
    # Clip resampling ringing to the illustration's declared bounds.
    rp = result.load()
    for y in range(SIZE[1]):
        for x in range(SIZE[0]):
            if x < 24 or x >= 345 or y < 97 or y >= 362:
                rp[x, y] = 0
    return result


def tint_header(template, hue, value_scale):
    p = template.load()
    for y in range(20, 125):
        for x in range(20, 350):
            r, g, b, a = p[x, y]
            if b > r + 35 and b > g + 8:
                _, saturation, value = colorsys.rgb_to_hsv(r/255, g/255, b/255)
                nr, ng, nb = colorsys.hsv_to_rgb(hue, saturation,
                                               min(1, value*value_scale))
                p[x, y] = (round(nr*255), round(ng*255), round(nb*255), a)


def main():
    (ROOT / "png").mkdir(exist_ok=True)
    atlas = Image.open(ROOT / "raw/atlas.png").convert("RGBA")
    reference = Image.open(ROOT / "raw/frame-reference.png").convert("RGBA")
    assert reference.size == SIZE
    alpha = shell_alpha(reference)
    mask = art_mask()
    x0, y0, x1, y1 = WINDOW
    report = {"date": "2026-10-08", "size": list(SIZE), "sprites": []}
    for index, (name, box) in enumerate(zip(NAMES, CROPS)):
        card = reference.copy()
        if name == "lucky_face":
            tint_header(card, 0.993, 0.96)
        elif name == "unlucky_face":
            tint_header(card, 0.765, 0.66)
        art = atlas.crop(box).resize((x1-x0, y1-y0), Image.Resampling.LANCZOS)
        # Reference supplies the card silhouette; generated alpha is irrelevant
        # inside the opaque illustration window and can carry noisy rim pixels.
        art = art.convert("RGB").convert("RGBA")
        layer = card.copy()
        layer.paste(art, (x0, y0))
        card = Image.composite(layer, card, mask)
        card.putalpha(alpha)
        pixels = card.load()
        for y in range(SIZE[1]):
            for x in range(SIZE[0]):
                if pixels[x, y][3] == 0:
                    pixels[x, y] = (0, 0, 0, 0)
        output = ROOT / "png" / f"{name}.png"
        card.save(output)
        reopened = Image.open(output)
        assert reopened.mode == "RGBA" and reopened.size == SIZE
        assert reopened.getchannel("A").tobytes() == alpha.tobytes()
        assert all(reopened.getpixel(p)[3] == 0 for p in
                   [(0, 0), (368, 0), (0, 472), (368, 472), (184, 0), (0, 236)])
        # Verify lower blank band and golden edge against the source template.
        assert card.crop((30, 365, 339, 417)).convert("RGB").tobytes() == \
               reference.crop((30, 365, 339, 417)).convert("RGB").tobytes()
        assert card.crop((11, 150, 23, 350)).convert("RGB").tobytes() == \
               reference.crop((11, 150, 23, 350)).convert("RGB").tobytes()
        if index < 4:
            assert card.crop((75, 35, 295, 85)).convert("RGB").tobytes() == \
                   reference.crop((75, 35, 295, 85)).convert("RGB").tobytes()
        report["sprites"].append({"name": name, "file": f"png/{name}.png",
            "mode": reopened.mode, "size": list(reopened.size),
            "alpha_extrema": list(alpha.getextrema()),
            "transparent_pixels": alpha.tobytes().count(0),
            "template_alpha_matches": True, "blank_description_matches": True,
            "atlas_crop": list(box)})
    manifest = {"sprites": [{"name": n, "file": f"png/{n}.png"} for n in NAMES]}
    (ROOT / "sprites.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    (ROOT / "raw/validation.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    # Preview only: checkerboard is never baked into the actual sprite PNGs.
    preview = Image.new("RGB", (2*389, 3*493), (231, 234, 239))
    for index, name in enumerate(NAMES):
        preview.paste(Image.open(ROOT/"png"/f"{name}.png"),
                      (10+(index % 2)*389, 10+(index // 2)*493), alpha)
    preview.save(ROOT / "raw/final-preview.png")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
