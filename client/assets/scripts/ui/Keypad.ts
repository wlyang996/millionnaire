/** 数字键盘（房号输入）：0-9、退格、清空。显示区由调用方负责。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { fillRR, gfx, mk, onTap, text } from './Kit';

export class Keypad {
    readonly node: Node;
    constructor(parent: Node, x: number, y: number, w: number, keyH: number, onKey: (k: string) => void) {
        const gap = 14;
        const keyW = (w - gap * 2) / 3;
        this.node = mk(parent, 'Keypad', x, y, w, keyH * 4 + gap * 3);
        const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '清空', '0', '⌫'];
        keys.forEach((k, i) => {
            const cx = (i % 3) * (keyW + gap);
            const cy = Math.floor(i / 3) * (keyH + gap);
            const n = mk(this.node, 'Key:' + k, cx, cy, keyW, keyH);
            const g = gfx(n);
            const special = k.length > 1 || k === '⌫';
            fillRR(g, 0, 5, keyW, keyH - 5, 18, special ? Theme.c.ivoryLine : '#D9CBA0');
            fillRR(g, 0, 0, keyW, keyH - 5, 18, special ? Theme.c.ivoryDark : Theme.c.white);
            text(n, k, 0, 0, keyW, keyH - 5, special ? Theme.font.md : Theme.font.xl, Theme.c.ink, { bold: true });
            onTap(n, () => onKey(k));
        });
    }
}
