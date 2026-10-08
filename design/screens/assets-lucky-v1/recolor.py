"""幸运 / 不幸卡背：把事件卡背 / 扇形牌堆里的蓝色部分改成红色（幸运）或紫黑色（不幸），金边、奶油色问号不变。
用户 2026-10-08 选定"蓝卡背改色成红""紫黑色"，没有单独的设计稿。运行：python recolor.py"""
import colorsys
import json
import pathlib

from PIL import Image

HERE = pathlib.Path(__file__).parent
SRC = HERE.parent / 'assets-supplement-v1' / 'png' / 'event_art'
# (源图, 输出, 资源名, 目标色相°, 明度系数)
JOBS = [('06-event_card_back.png', 'lucky_card_back.png', 'lucky_card_back', 357, 0.95),
        ('07-event_card_fan.png', 'lucky_card_fan.png', 'lucky_card_fan', 357, 0.95),
        ('06-event_card_back.png', 'unlucky_card_back.png', 'unlucky_card_back', 275, 0.55),
        ('07-event_card_fan.png', 'unlucky_card_fan.png', 'unlucky_card_fan', 275, 0.55)]


def recolor(img, hue, value):
    img = img.convert('RGBA')
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            deg = h * 360
            if 165 <= deg <= 255 and s > 0.2:  # 蓝 / 青蓝 → 目标色（保留明暗与饱和度）
                nh = ((deg - 205) * 0.4 + hue) % 360 / 360
                nr, ng, nb = colorsys.hsv_to_rgb(nh, min(1.0, s * 1.05), v * value)
                px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return img


sprites = []
for src, dst, name, hue, value in JOBS:
    recolor(Image.open(SRC / src), hue, value).save(HERE / 'png' / dst)
    sprites.append({'name': name, 'file': 'png/' + dst, 'source': 'assets-supplement-v1/png/event_art/' + src,
                    'note': '蓝色部分改色（recolor.py）'})
(HERE / 'sprites.json').write_text(json.dumps({'sprites': sprites}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
