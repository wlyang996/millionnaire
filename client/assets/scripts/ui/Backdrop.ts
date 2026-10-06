/** 明亮卡通背景：天蓝渐变 + 云 + 草地丘陵（全部 Graphics 绘制，可整体替换为正式美术）。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { col, fillCircle, gfx, mk } from './Kit';

function lerpHex(a: string, b: string, t: number): string {
    const pa = [1, 3, 5].map((i) => parseInt(a.substr(i, 2), 16));
    const pb = [1, 3, 5].map((i) => parseInt(b.substr(i, 2), 16));
    const h = pa.map((v, i) => Math.round(v + (pb[i] - v) * t).toString(16).padStart(2, '0'));
    return '#' + h.join('');
}

export type BackdropKind = 'sky' | 'plain' | 'night';

export function paintBackdrop(parent: Node, kind: BackdropKind = 'sky'): Node {
    const n = mk(parent, 'Backdrop', 0, 0, Theme.W, Theme.H);
    const g = gfx(n);
    const bands = 20;
    const top = kind === 'night' ? '#2B4A6B' : Theme.c.skyTop;
    const bot = kind === 'night' ? '#6E8FB0' : Theme.c.skyBottom;
    for (let i = 0; i < bands; i++) {
        g.fillColor = col(lerpHex(top, bot, i / (bands - 1)));
        const y0 = (i * Theme.H) / bands;
        g.rect(0, -(y0 + Theme.H / bands + 1), Theme.W, Theme.H / bands + 1);
        g.fill();
    }
    if (kind === 'plain') return n;
    // 云
    const cloud = (cx: number, cy: number, s: number) => {
        fillCircle(g, cx, cy, 34 * s, '#FFFFFFD0');
        fillCircle(g, cx + 36 * s, cy + 8 * s, 26 * s, '#FFFFFFD0');
        fillCircle(g, cx - 36 * s, cy + 10 * s, 24 * s, '#FFFFFFD0');
    };
    cloud(150, 230, 1);
    cloud(560, 170, 0.8);
    cloud(420, 400, 0.7);
    // 草地丘陵
    g.fillColor = col(Theme.c.grassDark);
    g.ellipse(160, -(Theme.H - 30), 420, 190);
    g.fill();
    g.fillColor = col(Theme.c.grass);
    g.ellipse(560, -(Theme.H - 10), 440, 170);
    g.fill();
    return n;
}
