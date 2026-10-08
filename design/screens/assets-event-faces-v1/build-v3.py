"""Assemble fresh high-resolution v3 art into the unchanged v2 shell.

Generation uses built-in image_gen; Pillow only crops/downsamples/composites
the selected high-resolution results and verifies the preserved pixels.
Run: python build-v3.py
"""
from pathlib import Path
import json
import hashlib
from PIL import Image, ImageDraw, ImageFont, ImageChops
from split import NAMES, SIZE, WINDOW, art_mask, shell_alpha, tint_header

ROOT = Path(__file__).resolve().parent
SCREENS = ROOT.parent
RAW = ROOT / 'raw'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def subject_bounds(image, skip_reference_rim=False):
    # Saturated foreground objects differ from pale cream rays. Exclude
    # cream house walls and dark cloud here; colorful bounding accessories,
    # roof, grass and drops still span the complete group's visible extent.
    points = []
    for y in range(image.height):
        for x in range(image.width):
            if skip_reference_rim and (y < 40 or x < 4 or x >= image.width-4):
                continue
            r, g, b = image.getpixel((x, y))[:3]
            if (g > r * 1.10 and g > b * 1.16 and g-r > 18) or \
               (r > g * 1.35 and r > b * 1.35 and r-g > 35) or \
               (b > r * 1.2 and b-r > 25) or \
               (r > 190 and 80 < g < 195 and b < 100):
                points.append((x, y))
    return [min(x for x, _ in points), min(y for _, y in points),
            max(x for x, _ in points)+1, max(y for _, y in points)+1]


def main():
    reference = Image.open(RAW/'frame-reference.png').convert('RGBA')
    alpha = shell_alpha(reference)
    mask = art_mask()
    unchanged_mask = mask.point(lambda v: 255 if v == 0 else 0)
    reports = []
    for name in NAMES:
        old = Image.open(RAW/'png-v2'/f'{name}.png').convert('RGBA')
        src = Image.open(RAW/f'art-v3-{name}.png').convert('RGB')
        assert src.width >= 1000 and src.height >= 800
        assert abs(src.width / src.height - 321 / 265) < 0.01
        art = src.resize((321, 265), Image.Resampling.LANCZOS).convert('RGBA')
        layer = old.copy()
        layer.paste(art, WINDOW[:2])
        card = Image.composite(layer, old, mask)
        assert card.getchannel('A').tobytes() == alpha.tobytes()
        assert ImageChops.multiply(ImageChops.difference(card, old).convert('RGB'),
                                  unchanged_mask.convert('RGB')).getbbox() is None
        assert card.crop((0, 362, 369, 473)).tobytes() == old.crop((0, 362, 369, 473)).tobytes()
        expected = reference.copy()
        if name == 'lucky_face':
            tint_header(expected, 0.993, 0.96)
        elif name == 'unlucky_face':
            tint_header(expected, 0.765, 0.66)
        visible_shell = ImageChops.multiply(unchanged_mask, alpha.point(lambda a: 255 if a else 0))
        assert ImageChops.multiply(ImageChops.difference(card.convert('RGB'), expected.convert('RGB')),
                                  visible_shell.convert('RGB')).getbbox() is None
        assert all(p == (0,0,0,0) for p in card.get_flattened_data() if p[3] == 0)
        destination = ROOT/'png'/f'{name}.png'
        card.save(destination)
        new_bounds = subject_bounds(art)
        old_bounds = subject_bounds(old.crop(WINDOW), skip_reference_rim=True)
        assert new_bounds[0] > 0 and new_bounds[2] < 321
        assert new_bounds[1] > 0 and new_bounds[3] < 265
        reports.append({'name':name, 'file':str(destination.relative_to(ROOT)),
                        'mode':'RGBA','size':list(SIZE),'generated_size':list(src.size),
                        'v2_color_foreground_bounds':old_bounds,
                        'v3_color_foreground_bounds':new_bounds,
                        'foreground_width_fraction':round((new_bounds[2]-new_bounds[0])/321,3),
                        'foreground_height_fraction':round((new_bounds[3]-new_bounds[1])/265,3),
                        'shell_matches_v2':True, 'shell_matches_reward_template':True,
                        'alpha_matches_template':True, 'transparent_pixels':alpha.tobytes().count(0),
                        'blank_description_unchanged':True,'sha256':digest(destination)})

    # Only illustration pixels change in the design; preserve all copy/layout.
    old_design = Image.open(RAW/'design-final-v2.png').convert('RGB')
    design = old_design.copy()
    total_mask = Image.new('L', design.size)
    width, height, scale = 282, 225, 4
    design_mask = Image.new('L', (width*scale, height*scale))
    ImageDraw.Draw(design_mask).rounded_rectangle((0,0,width*scale-1,(height+30)*scale),
                                                  radius=29*scale, fill=255)
    design_mask = design_mask.resize((width,height),Image.Resampling.LANCZOS)
    for i, name in enumerate(NAMES):
        art = Image.open(ROOT/'png'/f'{name}.png').crop(WINDOW).convert('RGB')
        art = art.resize((width,height), Image.Resampling.LANCZOS)
        xy = ((52,374,695)[i % 3], (542,993)[i//3])
        design.paste(art, xy, design_mask)
        total_mask.paste(design_mask, xy)
    outside = total_mask.point(lambda v:255 if v == 0 else 0).convert('RGB')
    assert ImageChops.multiply(ImageChops.difference(design,old_design),outside).getbbox() is None
    version_dir = SCREENS/'ui/event-faces-v3'
    version_dir.mkdir(exist_ok=True)
    design.save(version_dir/'事件卡-新增卡面与幸运不幸-v3.png')
    design.save(SCREENS/'31-事件卡-新增卡面与幸运不幸.png')
    design.save(RAW/'design-final-v3.png')

    # Original five normalized only on this sheet; source files stay untouched.
    tw, th = 389, 523
    compare = Image.new('RGB',(tw*6,th*2),(232,234,238))
    d = ImageDraw.Draw(compare)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    old_names = ['01-event_reward','02-event_fine','03-event_tool','04-event_move','05-event_jail']
    old_labels = ['原卡：奖励（对齐模板）','原卡：罚款','原卡：道具','原卡：位移','原卡：入狱']
    labels = ['第3版：加盖','第3版：降级','第3版：车站','第3版：起点','第3版：幸运','第3版：不幸']
    for row, names, texts in [(0,old_names,old_labels),(1,NAMES,labels)]:
        for i,(name,label) in enumerate(zip(names,texts)):
            path = SCREENS/'assets-supplement-v1/png/event_art'/f'{name}.png' if row == 0 else ROOT/'png'/f'{name}.png'
            im = Image.open(path).convert('RGBA').resize(SIZE,Image.Resampling.LANCZOS)
            compare.paste(im,(i*tw+10,row*th+40),im.getchannel('A'))
            d.text((i*tw+12,row*th+10),label,font=font,fill=(20,30,55))
    for yy, text in [(90,'同尺寸对比：369 × 473'),(130,'上排：五张现有风格参考'),
                     (170,'下排：第3版六张无字卡面'),(230,'原卡尺寸不同，仅展示归一化'),
                     (270,'金框像素对齐以奖励卡为准')]:
        d.text((5*tw+16,yy),text,font=font,fill=(20,30,55))
    compare.save(RAW/'compare-v3.png')
    report={'date':'2026-10-08','version':3,'generation':'built-in image_gen',
            'method':'fresh high-resolution art, downsampled once; unchanged shell',
            'bounds_method':'color foreground proxy, not a precise semantic silhouette',
            'sprites':reports,'design_copy_and_layout_outside_six_windows_unchanged':True,
            'design_sha256':digest(RAW/'design-final-v3.png')}
    (RAW/'validation-v3.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
