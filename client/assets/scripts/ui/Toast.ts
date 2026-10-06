/** 通用提示 Toast：单例，挂在最上层；用真实时间（不受演示"暂停倒计时"影响）。 */
import { Node } from 'cc';
import { Theme, textWidth } from '../core/Theme';
import { fillRR, gfx, mk, text } from './Kit';

export class Toast {
    private static layer: Node | null = null;
    private static cur: Node | null = null;
    private static until = 0;

    static init(layer: Node): void {
        Toast.layer = layer;
    }

    static show(msg: string, ms = 1800): void {
        if (!Toast.layer) return;
        Toast.clear();
        const size = Theme.font.md;
        const w = Math.min(Theme.W - 2 * Theme.pad, Math.max(220, textWidth(msg, size) + 64));
        const h = 72;
        const n = mk(Toast.layer, 'Toast', (Theme.W - w) / 2, 150, w, h);
        const g = gfx(n);
        fillRR(g, 0, 4, w, h, 36, Theme.c.shadow);
        fillRR(g, 0, 0, w, h, 36, '#2D3B4AEE');
        text(n, msg, 16, 0, w - 32, h, size, Theme.c.white, { bold: true });
        Toast.cur = n;
        Toast.until = Date.now() + ms;
    }

    static clear(): void {
        if (Toast.cur) {
            Toast.cur.removeFromParent();
            Toast.cur.destroy();
            Toast.cur = null;
        }
    }

    /** 每帧调用 */
    static update(): void {
        if (Toast.cur && Date.now() >= Toast.until) Toast.clear();
    }
}
