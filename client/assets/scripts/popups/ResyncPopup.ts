/** 重连同步遮罩：转圈 + "正在同步最新状态…"。同步完成后恢复当前操作，不补做旧动作。点击遮罩（演示）可关闭。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { fillCircle, gfx, mk, strokeCircle, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';


export class ResyncPopup extends Popup {
    private spinner: Node | null = null;
    private angle = 0;

    constructor() {
        super('resync', '', 600, 520, 0, true);
    }

    protected buildBody(p: Node, w: number): void {
        this.spinner = mk(p, 'Spinner', (w - 110) / 2, 50, 110, 110);
        this.paintSpinner();
        text(p, '正在同步最新状态…', 0, 190, w, 70, Theme.font.xl - 6, Theme.c.ink, { bold: true });
        text(p, '同步完成后恢复当前操作', 0, 290, w, 40, Theme.font.md, Theme.c.inkSoft);
        text(p, '不会补做旧动作', 0, 340, w, 40, Theme.font.md, Theme.c.inkSoft);
        text(p, '（演示：点击遮罩外区域关闭）', 0, 440, w, 30, Theme.font.xs, Theme.c.inkFaint);
    }

    private paintSpinner(): void {
        if (!this.spinner) return;
        const g = gfx(this.spinner);
        g.clear();
        // 旋转效果：用起始点不同的两段弧模拟（避免依赖旋转组件）
        strokeCircle(g, 55, 55, 44, Theme.c.blueSoft, 10);
        const k = (this.angle % 360) / 360;
        const n = 20;
        for (let i = 0; i < n; i++) {
            const a = k * Math.PI * 2 + (i / n) * Math.PI * 1.3;
            const x = 55 + 44 * Math.sin(a);
            const y = 55 - 44 * Math.cos(a);
            fillCircle(g, x, y, 3 + (i / n) * 5, Theme.c.blue);
        }
    }

    tick(): void {
        this.angle += 7;
        this.paintSpinner();
        super.tick();
    }
}
