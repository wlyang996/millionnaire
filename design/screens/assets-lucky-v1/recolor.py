"""幸运卡背：把事件卡背 / 扇形牌堆里的蓝色部分改成红色（金边、奶油色问号不变）。
用户 2026-10-08 选定"蓝卡背改色成红"，没有单独的设计稿。运行：python recolor.py"""
import colorsys
import json
import pathlib

from PIL import Image

HERE = pathlib.Path(__file__).parent
SRC = HERE.parent / 'assets-supplement-v1' / 'png' / 'event_art'
JOBS = [('06-event_card_back.png', 'lucky_card_back.png', 'lucky_card_back'),
        ('07-event_card_fan.png', 'lucky_card_fan.png', 'lucky_card_fan')]


def recolor(img):
    img = img.convert('RGBA')
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            deg = h * 360
            if 165 <= deg <= 255 and s > 0.2:  # 蓝 / 青蓝 → 红（保留明暗与饱和度）
                nh = ((deg - 205) * 0.4 + 357) % 360 / 360
                nr, ng, nb = colorsys.hsv_to_rgb(nh, min(1.0, s * 1.05), v * 0.95)
                px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return img


sprites = []
for src, dst, name in JOBS:
    recolor(Image.open(SRC / src)).save(HERE / 'png' / dst)
    sprites.append({'name': name, 'file': 'png/' + dst, 'source': 'assets-supplement-v1/png/event_art/' + src,
                    'note': '蓝色部分改为红色（recolor.py）'})
(HERE / 'sprites.json').write_text(json.dumps({'sprites': sprites}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
