/** 代码绘制的简单图标（局部坐标：左上角原点 y 向下）。14 种道具图标 + 常用 UI 图标。 */
import { Graphics } from 'cc';
import { CardType } from '../core/Models';
import { Theme } from '../core/Theme';
import { fillCircle, fillPoly, fillRR, line, strokeCircle, strokeRR } from './Kit';

const C = Theme.c;

export function drawHouse(g: Graphics, cx: number, cy: number, s: number, roof: string, wall: string): void {
    fillPoly(g, [[cx - s * 0.5, cy - s * 0.05], [cx, cy - s * 0.5], [cx + s * 0.5, cy - s * 0.05]], roof);
    fillRR(g, cx - s * 0.38, cy - s * 0.05, s * 0.76, s * 0.5, 4, wall);
    fillRR(g, cx - s * 0.1, cy + s * 0.2, s * 0.2, s * 0.25, 3, roof);
}

function shield(g: Graphics, cx: number, cy: number, s: number, color: string): void {
    fillPoly(g, [[cx - s * 0.42, cy - s * 0.4], [cx, cy - s * 0.52], [cx + s * 0.42, cy - s * 0.4],
        [cx + s * 0.4, cy + s * 0.1], [cx, cy + s * 0.52], [cx - s * 0.4, cy + s * 0.1]], color);
}

/** 画道具图标，s 为图标外框边长。 */
export function drawCardIcon(g: Graphics, type: CardType, cx: number, cy: number, s: number): void {
    switch (type) {
        case 'ROADBLOCK':
            fillRR(g, cx - s * 0.45, cy - s * 0.18, s * 0.9, s * 0.36, 6, C.white);
            for (let i = 0; i < 3; i++) fillRR(g, cx - s * 0.4 + i * s * 0.3, cy - s * 0.18, s * 0.14, s * 0.36, 2, C.red);
            line(g, cx - s * 0.3, cy + s * 0.18, cx - s * 0.3, cy + s * 0.45, C.grayDark, 5);
            line(g, cx + s * 0.3, cy + s * 0.18, cx + s * 0.3, cy + s * 0.45, C.grayDark, 5);
            break;
        case 'RENT_WAIVER':
            shield(g, cx, cy, s, C.blue);
            line(g, cx - s * 0.2, cy, cx - s * 0.04, cy + s * 0.18, C.white, 6);
            line(g, cx - s * 0.04, cy + s * 0.18, cx + s * 0.22, cy - s * 0.16, C.white, 6);
            break;
        case 'BUILD':
            drawHouse(g, cx, cy, s, C.red, C.ivory);
            fillCircle(g, cx + s * 0.34, cy - s * 0.34, s * 0.14, C.green);
            line(g, cx + s * 0.34, cy - s * 0.42, cx + s * 0.34, cy - s * 0.26, C.white, 3);
            line(g, cx + s * 0.26, cy - s * 0.34, cx + s * 0.42, cy - s * 0.34, C.white, 3);
            break;
        case 'DOWNGRADE':
            drawHouse(g, cx, cy, s, C.orange, C.ivory);
            fillPoly(g, [[cx + s * 0.2, cy - s * 0.32], [cx + s * 0.48, cy - s * 0.32], [cx + s * 0.34, cy - s * 0.1]], C.red);
            break;
        case 'FIXED_MOVE':
            fillCircle(g, cx, cy - s * 0.1, s * 0.28, C.red);
            fillPoly(g, [[cx - s * 0.2, cy + s * 0.06], [cx + s * 0.2, cy + s * 0.06], [cx, cy + s * 0.46]], C.red);
            fillCircle(g, cx, cy - s * 0.1, s * 0.11, C.white);
            break;
        case 'JAIL_RELEASE':
            strokeCircle(g, cx - s * 0.2, cy - s * 0.1, s * 0.2, C.yellowDark, 7);
            line(g, cx - s * 0.02, cy + s * 0.05, cx + s * 0.42, cy + s * 0.42, C.yellowDark, 7);
            line(g, cx + s * 0.26, cy + s * 0.26, cx + s * 0.4, cy + s * 0.12, C.yellowDark, 6);
            break;
        case 'AUCTION':
            line(g, cx - s * 0.4, cy + s * 0.4, cx + s * 0.1, cy - s * 0.1, '#8B5A2B', 9);
            fillRR(g, cx - s * 0.02, cy - s * 0.46, s * 0.44, s * 0.26, 6, '#B07A3E');
            fillRR(g, cx - s * 0.4, cy + s * 0.34, s * 0.3, s * 0.12, 4, C.gray);
            break;
        case 'TRADE':
            line(g, cx - s * 0.4, cy - s * 0.16, cx + s * 0.38, cy - s * 0.16, C.green, 7);
            fillPoly(g, [[cx + s * 0.32, cy - s * 0.34], [cx + s * 0.5, cy - s * 0.16], [cx + s * 0.32, cy + s * 0.02]], C.green);
            line(g, cx + s * 0.4, cy + s * 0.2, cx - s * 0.38, cy + s * 0.2, C.blue, 7);
            fillPoly(g, [[cx - s * 0.32, cy + s * 0.02], [cx - s * 0.5, cy + s * 0.2], [cx - s * 0.32, cy + s * 0.38]], C.blue);
            break;
        case 'REFUSE_PURCHASE':
            strokeCircle(g, cx, cy, s * 0.36, C.red, 8);
            line(g, cx - s * 0.25, cy + s * 0.25, cx + s * 0.25, cy - s * 0.25, C.red, 8);
            break;
        case 'HOUSE_PROTECTION':
            shield(g, cx, cy, s, C.green);
            drawHouse(g, cx, cy + s * 0.04, s * 0.5, C.white, '#FFFFFFCC');
            break;
        case 'QUERY':
            strokeCircle(g, cx - s * 0.08, cy - s * 0.08, s * 0.28, C.blueDark, 8);
            line(g, cx + s * 0.12, cy + s * 0.12, cx + s * 0.42, cy + s * 0.42, C.blueDark, 9);
            break;
        case 'FORCED_PURCHASE':
            fillCircle(g, cx - s * 0.12, cy, s * 0.3, C.yellow);
            strokeCircle(g, cx - s * 0.12, cy, s * 0.2, C.yellowDark, 4);
            fillPoly(g, [[cx + s * 0.18, cy - s * 0.22], [cx + s * 0.5, cy], [cx + s * 0.18, cy + s * 0.22]], C.red);
            break;
        case 'DEMOLISH':
            drawHouse(g, cx, cy, s, C.grayDark, C.gray);
            line(g, cx - s * 0.3, cy - s * 0.1, cx + s * 0.3, cy + s * 0.4, C.red, 7);
            line(g, cx + s * 0.3, cy - s * 0.1, cx - s * 0.3, cy + s * 0.4, C.red, 7);
            break;
        case 'CLEAR_LAND':
            fillRR(g, cx - s * 0.42, cy - s * 0.2, s * 0.84, s * 0.5, 8, C.green);
            fillRR(g, cx - s * 0.42, cy + s * 0.1, s * 0.84, s * 0.2, 8, '#8B6B3A');
            line(g, cx - s * 0.2, cy - s * 0.4, cx + s * 0.2, cy + s * 0.1, C.red, 7);
            break;
    }
}

export function drawMic(g: Graphics, cx: number, cy: number, s: number, color: string): void {
    fillRR(g, cx - s * 0.16, cy - s * 0.4, s * 0.32, s * 0.56, s * 0.16, color);
    line(g, cx - s * 0.28, cy - s * 0.02, cx - s * 0.28, cy + s * 0.06, color, 4);
    line(g, cx + s * 0.28, cy - s * 0.02, cx + s * 0.28, cy + s * 0.06, color, 4);
    line(g, cx - s * 0.28, cy + s * 0.06, cx, cy + s * 0.26, color, 4);
    line(g, cx + s * 0.28, cy + s * 0.06, cx, cy + s * 0.26, color, 4);
    line(g, cx, cy + s * 0.26, cx, cy + s * 0.42, color, 4);
}

export function drawChat(g: Graphics, cx: number, cy: number, s: number, color: string): void {
    fillRR(g, cx - s * 0.4, cy - s * 0.32, s * 0.8, s * 0.55, s * 0.18, color);
    fillPoly(g, [[cx - s * 0.2, cy + s * 0.2], [cx - s * 0.28, cy + s * 0.44], [cx, cy + s * 0.2]], color);
    for (let i = -1; i <= 1; i++) fillCircle(g, cx + i * s * 0.2, cy - s * 0.05, s * 0.05, C.white);
}

export function drawBack(g: Graphics, cx: number, cy: number, s: number, color: string): void {
    line(g, cx + s * 0.12, cy - s * 0.28, cx - s * 0.16, cy, color, 6);
    line(g, cx - s * 0.16, cy, cx + s * 0.12, cy + s * 0.28, color, 6);
}

export function drawCheck(g: Graphics, cx: number, cy: number, s: number, color: string, lw = 5): void {
    line(g, cx - s * 0.3, cy, cx - s * 0.08, cy + s * 0.24, color, lw);
    line(g, cx - s * 0.08, cy + s * 0.24, cx + s * 0.34, cy - s * 0.22, color, lw);
}

export function drawCoin(g: Graphics, cx: number, cy: number, r: number): void {
    fillCircle(g, cx, cy, r, C.yellowDark);
    fillCircle(g, cx, cy - r * 0.06, r * 0.9, C.yellow);
    strokeCircle(g, cx, cy - r * 0.06, r * 0.6, '#FFFFFF99', Math.max(2, r * 0.18));
}

export function drawCopy(g: Graphics, cx: number, cy: number, s: number, color: string): void {
    strokeRR(g, cx - s * 0.3, cy - s * 0.3, s * 0.45, s * 0.5, 4, color, 3);
    strokeRR(g, cx - s * 0.12, cy - s * 0.12, s * 0.45, s * 0.5, 4, color, 3);
}

export function drawClock(g: Graphics, cx: number, cy: number, r: number, color: string): void {
    strokeCircle(g, cx, cy, r, color, 3);
    line(g, cx, cy, cx, cy - r * 0.6, color, 3);
    line(g, cx, cy, cx + r * 0.45, cy, color, 3);
}

/** 骰面点（1..6），d 为骰子边长，原点为骰子左上角。 */
export function drawPips(g: Graphics, x: number, y: number, d: number, n: number, color: string): void {
    const a = d * 0.26;
    const m = d / 2;
    const b = d - a;
    const P: Record<number, number[][]> = {
        1: [[m, m]], 2: [[a, a], [b, b]], 3: [[a, a], [m, m], [b, b]], 4: [[a, a], [b, a], [a, b], [b, b]],
        5: [[a, a], [b, a], [m, m], [a, b], [b, b]], 6: [[a, a], [b, a], [a, m], [b, m], [a, b], [b, b]],
    };
    for (const p of P[Math.max(1, Math.min(6, n))]) fillCircle(g, x + p[0], y + p[1], d * 0.085, color);
}
