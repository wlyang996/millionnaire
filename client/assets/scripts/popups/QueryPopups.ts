/**
 * 查询卡（设计稿 13 右两张）：
 * - 选择查询玩家：玩家头像网格（自己与已出局者置灰不可选），选中蓝框；取消 / 使用查询；
 * - 查询结果："某某的手牌"、卡牌横排（图 + 名）、"仅你可见 · 使用时快照"、关闭。
 */
import { Node } from 'cc';
import { CARD_NAMES, CardType } from '../core/Models';
import { Theme } from '../core/Theme';
import { art, CARD_ART } from '../ui/Art';
import { primaryButton, softButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCardIcon } from '../ui/Icons';
import { fillCircle, fillRR, gfx, line, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { avatar } from '../ui/Widgets';
import { sendCard } from './CardUse';

const W = 660;

function closeButton(p: Node, w: number, fn: () => void): void {
    const x = mk(p, 'Close', w - 76, 18, 56, 56);
    const g = gfx(x);
    fillCircle(g, 28, 28, 26, Theme.c.white);
    line(g, 19, 19, 37, 37, Theme.c.navy, 4);
    line(g, 37, 19, 19, 37, Theme.c.navy, 4);
    onTap(x, fn, false);
}

export class QueryTargetPopup extends Popup {
    private sel = '';

    constructor() {
        super('query-target', '选择查询玩家', W, 300 + Math.ceil(ctx.store.game.players.length / 4) * 170);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const st = ctx.store;
        closeButton(p, w, () => this.close());
        const cell = (w - 48) / 4;
        st.game.players.forEach((pl, i) => {
            const x = 24 + (i % 4) * cell;
            const y = 92 + Math.floor(i / 4) * 170;
            const self = pl.playerId === st.myId;
            const ok = !self && pl.life === 'ALIVE';
            const n = mk(p, 'P' + i, x + 4, y, cell - 8, 160);
            const g = gfx(n);
            fillRR(g, 0, 0, cell - 8, 160, 18, ok ? Theme.c.white : '#E6E9EE');
            if (this.sel === pl.playerId) strokeRR(g, 1, 1, cell - 10, 158, 18, '#2F86E8', 5);
            avatar(n, (cell - 8 - 100) / 2, 10, 100, pl.avatar, pl.nickname, { dim: !ok });
            text(n, pl.nickname, 0, 112, cell - 8, 30, 24, Theme.c.navy, { bold: true });
            if (self) text(n, '自己', 0, 138, cell - 8, 20, 16, Theme.c.noteGray);
            if (ok) onTap(n, () => {
                this.sel = pl.playerId;
                this.rebuildBody();
            }, false);
        });
        softButton(p, '取消', 24, h - 112, 260, 88, () => this.close(), 32);
        const b = primaryButton(p, '使用查询', 300, h - 112, w - 324, 88, () => {
            const target = this.sel;
            this.close();
            sendCard('QUERY', target);
        }, 34);
        b.setEnabled(!!this.sel, '请先选择一名玩家');
    }
}

export class QueryResultPopup extends Popup {
    constructor(private readonly targetName: string, private readonly cards: CardType[]) {
        super('query-result', targetName + '的手牌', W, 470);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        closeButton(p, w, () => this.close());
        const n = this.cards.length;
        if (n === 0) text(p, '没有道具', 24, 100, w - 48, 170, Theme.font.lg, Theme.c.noteGray);
        const cw = Math.min(118, (w - 48) / Math.max(1, n));
        const x0 = (w - cw * n) / 2;
        this.cards.forEach((c, i) => {
            const card = mk(p, 'Card' + i, x0 + i * cw + 4, 96, cw - 8, 170);
            const g = gfx(card);
            fillRR(g, 0, 0, cw - 8, 170, 16, '#EAF4FF');
            strokeRR(g, 1, 1, cw - 10, 168, 16, '#9CC3EC', 3);
            if (!art(card, CARD_ART[c], 6, 8, cw - 20, 116)) drawCardIcon(g, c, (cw - 8) / 2, 66, 76);
            text(card, CARD_NAMES[c], 0, 128, cw - 8, 36, 20, Theme.c.navy, { bold: true });
        });
        const tip = mk(p, 'Tip', 24, 286, w - 48, 56);
        fillRR(gfx(tip), 0, 0, w - 48, 56, 18, Theme.c.boxBeige);
        text(tip, '仅你可见 · 使用时快照', 0, 0, w - 48, 56, 24, Theme.c.noteGray);
        softButton(p, '关闭', (w - 300) / 2, h - 110, 300, 84, () => this.close(), 32);
    }
}
