/**
 * 弃牌（15 秒），按设计稿 17"手牌已满"：标题"手牌已满 7/6"（超出的数字红色），3 列道具大图卡（最新获得的一张带红色"新"角标），
 * 底部说明"选一张弃掉 · 超时放弃新卡"和红色"弃掉某某"按钮。超时放弃新获得的那张（联机由服务端处理）。
 * 联机时传入服务端弃牌窗口：点选任意一张后发送 DiscardCard（index 为手牌下标）。
 */
import { Node } from 'cc';
import { Card, CARD_NAMES } from '../core/Models';
import { MAX_HAND, SECONDS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { art, CARD_ART } from '../ui/Art';
import { Button, dangerButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCardIcon } from '../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { box } from './Common';

const W = 600;
const H = 900;
const CW = 168;
const CH = 172;
const GAP = 16;

export class DiscardPopup extends Popup {
    private sel = -1;

    /** @param windowId 联机时为服务端的弃牌窗口；演示时不传 */
    constructor(private readonly windowId?: number) {
        super('discard', '手牌已满', W, H, SECONDS.discard);
    }

    private hand(): Card[] {
        const h = ctx.store.game.myHand.slice();
        // 演示：保证有 7 张
        if (this.windowId === undefined) while (h.length < MAX_HAND + 1) h.push({ id: 'x' + h.length, type: 'QUERY' });
        return h;
    }

    /** 标题：手牌已满 + 红色的当前张数 / 上限 */
    protected buildTitle(): void {
        const n = this.hand().length;
        const side = Theme.popupRing.size + Theme.popupRing.inset;
        text(this.panel, '手牌已满', 30, 14, 170, 64, 36, Theme.c.navy, { bold: true, align: 'l' });
        text(this.panel, String(n), 200, 14, 40, 64, 40, Theme.c.payRed, { bold: true, align: 'r' });
        text(this.panel, '/' + MAX_HAND, 242, 14, Math.max(60, W - side - 242), 64, 40, Theme.c.navy, { bold: true, align: 'l' });
    }

    protected buildBody(p: Node): void {
        const hand = this.hand();
        const newest = hand.length - 1;
        const rows = Math.ceil(Math.min(hand.length, 9) / 3);
        const x0 = (W - (3 * CW + 2 * GAP)) / 2;
        hand.slice(0, 9).forEach((c, i) => {
            // 最后一行不满 3 张时居中
            const row = Math.floor(i / 3);
            const inRow = Math.min(3, hand.length - row * 3);
            const rx = x0 + ((3 - inRow) * (CW + GAP)) / 2;
            const x = rx + (i % 3) * (CW + GAP);
            const y = 92 + row * (CH + GAP);
            const n = mk(p, 'Card' + i, x, y, CW, CH);
            const g = gfx(n);
            const on = this.sel === i;
            const isNew = i === newest;
            fillRR(g, 0, 4, CW, CH, 22, '#00000022');
            fillRR(g, 0, 0, CW, CH, 22, on ? '#FFE3DF' : '#EAF4FF');
            strokeRR(g, 1, 1, CW - 2, CH - 2, 22, on || isNew ? Theme.c.payRed : '#9CC3EC', on ? 5 : 3);
            const key = CARD_ART[c.type];
            if (!key || !art(n, key, 14, 8, CW - 28, CH - 52)) drawCardIcon(g, c.type, CW / 2, 66, 84);
            text(n, CARD_NAMES[c.type], 0, CH - 46, CW, 40, 26, Theme.c.navy, { bold: true });
            if (isNew) {
                const tag = mk(n, 'New', 10, -6, 44, 36);
                fillRR(gfx(tag), 0, 0, 44, 36, 12, Theme.c.payRed);
                text(tag, '新', 0, 0, 44, 36, 22, Theme.c.white, { bold: true });
            }
            onTap(n, () => {
                this.sel = i;
                this.rebuildBody();
            }, false);
        });
        const ny = 92 + rows * (CH + GAP) + 6;
        box(p, 70, ny, W - 140, 52, Theme.c.boxBeige, 26);
        text(p, '选一张弃掉 · 超时放弃新卡', 70, ny, W - 140, 52, 24, Theme.c.navy, { bold: true });
        const label = this.sel >= 0 ? '弃掉' + CARD_NAMES[hand[this.sel].type] : '请选择要弃掉的卡';
        const b: Button = dangerButton(p, label, 60, H - 116, W - 120, 92, () => this.discard(), 36);
        b.setEnabled(this.sel >= 0, '请先点选一张卡');
    }

    private discard(): void {
        const st = ctx.store;
        const i = this.sel;
        this.close();
        if (i < 0) return;
        if (st.online && this.windowId !== undefined) {
            void st.online.act('DiscardCard', { windowId: this.windowId, index: i });
            return;
        }
        st.game.myHand.splice(Math.min(i, st.game.myHand.length - 1), 1);
        st.emit();
    }

    protected onExpire(): void {
        // 联机：服务端在窗口截止时放弃新卡
        this.close();
    }
}
