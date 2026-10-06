/** 弃牌（15 秒）：手牌超过 6 张时选择一张丢弃；超时放弃新获得的那张。 */
import { Node } from 'cc';
import { CARD_NAMES } from '../core/Models';
import { MAX_HAND, SECONDS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { Button, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCardIcon } from '../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';

export class DiscardPopup extends Popup {
    private sel = -1;

    constructor() {
        super('discard', '手牌已满，请弃一张', 640, 760, SECONDS.discard);
    }

    private hand() {
        const h = ctx.store.game.myHand.slice();
        // 保证演示时有 7 张
        while (h.length < MAX_HAND + 1) h.push({ id: 'x' + h.length, type: 'QUERY' });
        return h;
    }

    protected buildBody(p: Node, w: number): void {
        const hand = this.hand();
        text(p, '最多持有 ' + MAX_HAND + ' 张，当前 ' + hand.length + ' 张；超时将放弃新获得的卡', 28, 88, w - 56, 56, Theme.font.sm, Theme.c.inkSoft, { wrap: true, align: 'l', lineHeight: 30 });
        const cw = 176;
        const ch = 150;
        hand.slice(0, 9).forEach((c, i) => {
            const x = 28 + (i % 3) * (cw + 10);
            const y = 156 + Math.floor(i / 3) * (ch + 12);
            const n = mk(p, 'Card' + i, x, y, cw, ch);
            const g = gfx(n);
            const on = this.sel === i;
            fillRR(g, 0, 0, cw, ch, 18, on ? Theme.c.redSoft : Theme.c.white);
            strokeRR(g, 0, 0, cw, ch, 18, on ? Theme.c.red : Theme.c.ivoryLine, on ? 4 : 2);
            drawCardIcon(g, c.type, cw / 2, 56, 72);
            text(n, CARD_NAMES[c.type] + (i === hand.length - 1 ? '（新）' : ''), 0, 104, cw, 36, Theme.font.sm, Theme.c.ink, { bold: true });
            onTap(n, () => {
                this.sel = i;
                this.rebuildBody();
            }, false);
        });
        const b: Button = primaryButton(p, this.sel >= 0 ? '弃掉「' + CARD_NAMES[hand[this.sel].type] + '」' : '请选择要弃掉的卡', 28, 650, w - 56, 88, () => {
            this.close();
        }, Theme.font.lg);
        b.setEnabled(this.sel >= 0, '请先点选一张卡');
    }

    protected onExpire(): void {
        this.close();
    }
}
