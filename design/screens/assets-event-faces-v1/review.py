"""Compose the review sheet from the selected sprite art; compare native-size cards."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont
from split import NAMES, SIZE, WINDOW

ROOT = Path(__file__).resolve().parent
SCREENS = ROOT.parent
FONT = 'C:/Windows/Fonts/msyh.ttc'


def main():
    # Only replace illustration windows: retain all generated Chinese copy,
    # town background, avatars, title, gold frames and footer unchanged.
    design = Image.open(ROOT/'raw/design-generated-v2.png').convert('RGB')
    width, height = 282, 225
    scale = 4
    mask = Image.new('L', (width*scale, height*scale))
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle((0, 0, width*scale-1, (height+30)*scale),
                        radius=29*scale, fill=255)
    mask = mask.resize((width, height), Image.Resampling.LANCZOS)
    for i, name in enumerate(NAMES):
        art = Image.open(ROOT/'png'/f'{name}.png').crop(WINDOW).convert('RGB')
        art = art.resize((width, height), Image.Resampling.LANCZOS)
        xy = ((52, 374, 695)[i % 3], (542, 993)[i // 3])
        design.paste(art, xy, mask)
    design.save(SCREENS/'31-事件卡-新增卡面与幸运不幸.png')
    design.save(ROOT/'raw/design-final-v2.png')

    # Same 369x473 display dimensions, old references on first row, new six
    # on second row. Source originals remain untouched and are not identical
    # in native dimensions; reward alone is the canonical pixel template.
    tile_w, tile_h = 389, 523
    compare = Image.new('RGB', (tile_w*6, tile_h*2), (232, 234, 238))
    draw = ImageDraw.Draw(compare)
    font = ImageFont.truetype(FONT, 20)
    old_names = ['01-event_reward', '02-event_fine', '03-event_tool',
                 '04-event_move', '05-event_jail']
    old_labels = ['原卡：奖励（对齐模板）', '原卡：罚款', '原卡：道具',
                  '原卡：位移', '原卡：入狱']
    new_labels = ['第2版：加盖', '第2版：降级', '第2版：车站',
                  '第2版：起点', '第2版：幸运', '第2版：不幸']
    for row, names, labels in [(0, old_names, old_labels), (1, NAMES, new_labels)]:
        for i, (name, label) in enumerate(zip(names, labels)):
            path = (SCREENS/'assets-supplement-v1/png/event_art'/f'{name}.png'
                    if row == 0 else ROOT/'png'/f'{name}.png')
            im = Image.open(path).convert('RGBA').resize(SIZE, Image.Resampling.LANCZOS)
            xy = (i*tile_w+10, row*tile_h+40)
            compare.paste(im, xy, im.getchannel('A'))
            draw.text((i*tile_w+12, row*tile_h+10), label, font=font, fill=(20, 30, 55))
    draw.text((5*tile_w+16, 90), '同尺寸对比：369 × 473', font=font, fill=(20, 30, 55))
    draw.text((5*tile_w+16, 130), '上排：五张现有风格参考', font=font, fill=(20, 30, 55))
    draw.text((5*tile_w+16, 170), '下排：六张无字新卡面', font=font, fill=(20, 30, 55))
    draw.text((5*tile_w+16, 230), '原卡尺寸不同，仅展示归一化', font=font, fill=(20, 30, 55))
    draw.text((5*tile_w+16, 270), '框像素对齐以奖励卡为准', font=font, fill=(20, 30, 55))
    compare.save(ROOT/'raw/compare-v2.png')
    print('Updated design and 11-card comparison.')


if __name__ == '__main__':
    main()
