/** 弹窗共用部件：金币数字、地产头图、租金表、信息行。 */
import { Node } from 'cc';
import { BoardTile, PropertyState, Tier } from '../core/Models';
import { TIERS } from '../core/Rules';
import { Theme, textWidth } from '../core/Theme';
import { drawCardIcon, drawCoin } from '../ui/Icons';
import { fillPoly, fillRR, gfx, mk, strokeRR, text } from '../ui/Kit';
import { art } from '../ui/Art';

export function tierColor(tier: Tier | undefined, station = false): string {
    if (station) return Theme.c.station;
    return tier === 'LOW' ? Theme.c.tierLow : tier === 'MID' ? Theme.c.tierMid : Theme.c.tierHigh;
}

export function tierName(tile: BoardTile): string {
    if (tile.type === 'STATION') return '车站';
    return tile.tier ? TIERS[tile.tier].name + '地产' : '地产';
}

/** 金币 + 数字（左对齐，返回占用宽度） */
export function coinText(parent: Node, x: number, y: number, value: number | string, size: number, color: string = Theme.c.ink): number {
    const r = Math.round(size * 0.5);
    const c = gfx(mk(parent, 'Coin', x, y + (size * 1.25 - 2 * r) / 2, 2 * r, 2 * r));
    drawCoin(c, r, r, r - 1);
    const s = String(value);
    const w = textWidth(s, size) + 6;
    text(parent, s, x + 2 * r + 6, y, w + 20, Math.round(size * 1.25), size, color, { bold: true, align: 'l' });
    return 2 * r + 6 + w;
}

/** 地产头图：色块 + 房子图标；title 如"中价地产 · 一级" */
export function tileHero(parent: Node, tile: BoardTile, x: number, y: number, w: number, h: number, subtitle: string): void {
    const n = mk(parent, 'Hero', x, y, w, h);
    const g = gfx(n);
    const station = tile.type === 'STATION';
    fillRR(g, 0, 0, w, h - 44, 24, '#DFF3D2');
    fillRR(g, 0, 0, w, 20, 10, tierColor(tile.tier, station));
    if (!station) art(n, 'house_lv' + (subtitle.match(/([1-3])级/)?.[1] ?? '0'), w / 2 - 70, 26, 140, h - 84);
    if (station) {
        fillRR(g, w / 2 - 44, (h - 44) / 2 - 18, 88, 52, 10, Theme.c.station);
        fillRR(g, w / 2 - 32, (h - 44) / 2 - 8, 64, 20, 5, '#BFE3FF');
    }
    const pw = Math.min(w - 40, textWidth(subtitle, Theme.font.md) + 48);
    const pill = mk(n, 'Pill', (w - pw) / 2, h - 50, pw, 44);
    fillRR(gfx(pill), 0, 0, pw, 44, 22, Theme.c.ivoryDark);
    text(pill, subtitle, 0, 0, pw, 44, Theme.font.md, Theme.c.ink, { bold: true });
}

/** 弹窗/详情里的统一标题：三字地名 + 档位 + 等级（名称一律取自 tile.name，即 core/BoardNames）。 */
export function tileSubtitle(tile: BoardTile, prop: PropertyState | undefined): string {
    if (tile.type !== 'PROPERTY') return tile.name;
    const lv = prop ? prop.level : 0;
    return tile.name + ' · ' + tierName(tile) + ' · ' + (lv === 0 ? '未升级' : lv + '级');
}

/** 信息行：左标签、右金币数字 */
export function infoRow(parent: Node, x: number, y: number, w: number, label: string, value: number | string, bg: string | null = null, valueColor: string = Theme.c.ink): void {
    if (bg) fillRR(gfx(mk(parent, 'Row', x, y, w, 64)), 0, 0, w, 64, 16, bg);
    text(parent, label, x + 20, y, 240, 64, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
    const s = String(value);
    const approx = textWidth(s, Theme.font.lg) + 40;
    coinText(parent, x + w - approx - 20, y + 10, value, Theme.font.lg, valueColor);
}

/** 租金表：未升级/一级/二级/三级，highlight 为要突出的等级（可多个）。 */
export function rentTable(parent: Node, x: number, y: number, w: number, tier: Tier, highlight: number[] = []): void {
    const t = TIERS[tier];
    const n = mk(parent, 'RentTable', x, y, w, 96);
    fillRR(gfx(n), 0, 0, w, 96, 16, '#FFFFFF99');
    strokeRR(gfx(n), 0, 0, w, 96, 16, Theme.c.ivoryLine, 2);
    const names = ['未升级', '一级', '二级', '三级'];
    const cw = w / 4;
    for (let i = 0; i < 4; i++) {
        if (highlight.indexOf(i) >= 0) fillRR(gfx(n), i * cw + 6, 6, cw - 12, 84, 12, Theme.c.yellow + '55');
        text(n, names[i], i * cw, 8, cw, 36, Theme.font.sm, Theme.c.inkSoft);
        text(n, String(t.rent[i]), i * cw, 44, cw, 44, Theme.font.lg, Theme.c.ink, { bold: true });
    }
}

// ------------------------------------------------------------ 设计稿 04 的弹窗部件

export const LEVEL_NAMES = ['未升级', '一级', '二级', '三级'];

/** 地产 / 车站插画（与已采纳的 18 稿格子详情同一组素材）。 */
export function propertyArtKey(tile: BoardTile): string {
    if (tile.type === 'STATION') return 'shop_station';
    return tile.tier === 'HIGH' ? 'shop_high' : tile.tier === 'MID' ? 'shop_mid' : 'shop_low';
}

/** 圆角底块（米色数值底 / 浅灰说明底）。 */
export function box(parent: Node, x: number, y: number, w: number, h: number, color: string, r = 18): Node {
    const n = mk(parent, 'Box', x, y, w, h);
    fillRR(gfx(n), 0, 0, w, h, r, color);
    return n;
}

/** 米色胶囊标签，以 cx 居中。 */
export function pillLabel(parent: Node, s: string, cx: number, y: number, h = 44, size = 26): void {
    const w = textWidth(s, size) + 56;
    const n = mk(parent, 'Pill', cx - w / 2, y, w, h);
    fillRR(gfx(n), 0, 0, w, h, h / 2, Theme.c.pill);
    text(n, s, 0, 0, w, h, size, Theme.c.navy, { bold: true });
}

/** 行内片段：文字 / 金币（直径）/ 右箭头（宽度）。 */
export type Seg =
    | { t: string; size: number; color?: string; bold?: boolean }
    | { coin: number }
    | { arrow: number; color?: string };

/** 一行文字与金币整体以 cx 居中（设计稿 04 的"购买价格 (金币) 1000"等）。 */
export function inlineRow(parent: Node, cx: number, y: number, h: number, segs: Seg[], gap = 10): void {
    const widthOf = (s: Seg) => ('t' in s ? textWidth(s.t, s.size) + 2 : 'coin' in s ? s.coin : s.arrow);
    const total = segs.reduce((a, s) => a + widthOf(s), 0) + gap * (segs.length - 1);
    let x = cx - total / 2;
    for (const s of segs) {
        const w = widthOf(s);
        if ('t' in s) {
            text(parent, s.t, x, y, w + 8, h, s.size, s.color ?? Theme.c.navy, { bold: s.bold ?? true, align: 'l' });
        } else if ('coin' in s) {
            drawCoin(gfx(mk(parent, 'Coin', x, y + (h - s.coin) / 2, s.coin, s.coin)), s.coin / 2, s.coin / 2, s.coin / 2);
        } else {
            const g = gfx(mk(parent, 'Arrow', x, y, w, h));
            const c = s.color ?? '#3AA58C';
            fillRR(g, 0, h / 2 - 5, w * 0.62, 10, 4, c);
            fillPoly(g, [[w * 0.55, h / 2 - 14], [w, h / 2], [w * 0.55, h / 2 + 14]], c);
        }
        x += w + gap;
    }
}
