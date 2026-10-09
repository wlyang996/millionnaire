/**
 * 起点三选一：前进经过或停在起点时暂停，选牌显示结果后继续剩余步数。
 * 点牌后发送 PickStartCard（windowId + 第几张），服务端抽出结果（StartPickDrawn，道具种类只发给本人），弹窗翻开所选的牌，
 * 稍候自动关闭。超时由服务端代选第一张，结果以提示显示。现金范围与比例取后台「起点三选一」的参数。
 */
import { PRESENTATION } from '../core/Rules';
import { Node } from 'cc';
import { CARD_NAMES } from '../core/Models';
import { SECONDS, START_PICK } from '../core/Rules';
import { animMs, Theme } from '../core/Theme';
import { art, CARD_ART } from '../ui/Art';
import { ctx } from '../ui/Ctx';
import { drawCardIcon } from '../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';

const W = 620;
const H = 540;
const CW = 168;
const CH = 236;
const GAP = 22;


export class StartPickPopup extends Popup {
    private sel = -1;
    /** 打开时已有结果的编号：只翻开之后到达的结果 */
    private readonly seq0: number;
    private revealed = false;
    private readonly watch = () => this.onStore();

    /** @param windowId 联机时为服务端的落点窗口；演示时不传 */
    constructor(private readonly windowId?: number) {
        super('start-pick', '起点好运 · 三选一', W, H, SECONDS.buy);
        this.seq0 = ctx.store.startPick?.seq ?? 0;
        ctx.store.onChange(this.watch);
    }

    close(): void {
        ctx.store.offChange(this.watch);
        super.close();
    }

    private onStore(): void {
        const r = ctx.store.startPick;
        if (this.revealed || !r || r.seq <= this.seq0 || r.playerId !== ctx.store.me().playerId) return;
        this.revealed = true;
        this.sel = r.index;
        this.rebuildBody();
        setTimeout(() => this.close(), animMs(PRESENTATION.start));
    }

    protected buildBody(p: Node): void {
        const sp = START_PICK;
        const sum = sp.cashWeight + sp.cardWeight;
        const hint = sum <= 0 ? '翻开一张看看' : sp.cardWeight <= 0 ? '每张都是现金 ' + sp.cashMin + '～' + sp.cashMax
            : sp.cashWeight <= 0 ? '每张都是一张随机道具'
            : '可能是现金 ' + sp.cashMin + '～' + sp.cashMax + '，也可能是一张道具';
        text(p, hint, 30, 88, W - 60, 44, 24, Theme.c.inkSoft);
        const r = this.revealed ? ctx.store.startPick : null;
        const x0 = (W - (3 * CW + 2 * GAP)) / 2;
        const y = 150;
        for (let i = 0; i < 3; i++) {
            const n = mk(p, 'Pick' + i, x0 + i * (CW + GAP), y, CW, CH);
            const g = gfx(n);
            const on = this.sel === i;
            const dim = this.sel >= 0 && !on;
            fillRR(g, 0, 5, CW, CH, 22, '#00000026');
            if (r && on) {
                // 翻开：现金或道具
                fillRR(g, 0, 0, CW, CH, 22, '#FFF6DA');
                strokeRR(g, 2, 2, CW - 4, CH - 4, 20, '#F2B630', 5);
                if (r.cash) {
                    text(n, '💰', 0, 40, CW, 80, 64, Theme.c.ink);
                    text(n, '+' + r.amount, 0, 128, CW, 50, 40, Theme.c.payRed, { bold: true });
                    text(n, '现金', 0, CH - 52, CW, 40, 24, Theme.c.navy, { bold: true });
                } else if (r.card) {
                    const key = CARD_ART[r.card];
                    if (!key || !art(n, key, 16, 18, CW - 32, CH - 84)) drawCardIcon(g, r.card, CW / 2, 92, 100);
                    text(n, CARD_NAMES[r.card], 0, CH - 58, CW, 44, 26, Theme.c.navy, { bold: true });
                } else {
                    text(n, '🎁', 0, 50, CW, 90, 64, Theme.c.ink);
                    text(n, '道具', 0, CH - 58, CW, 44, 26, Theme.c.navy, { bold: true });
                }
                continue;
            }
            // 背面
            fillRR(g, 0, 0, CW, CH, 22, dim ? '#B9C4D6' : '#2F6FD6');
            strokeRR(g, 8, 8, CW - 16, CH - 16, 16, dim ? '#DDE3EC' : '#FFD86B', 3);
            if (on) strokeRR(g, -4, -4, CW + 8, CH + 8, 26, '#FFD86B', 6);
            text(n, '?', 0, 0, CW, CH, 92, dim ? '#E9EEF5' : '#FFD86B', { bold: true });
            if (this.sel < 0) onTap(n, () => this.pick(i), false);
        }
        const tip = this.revealed ? (r?.cash ? '获得 ' + r.amount + ' 现金！' : '获得一张道具！')
            : this.sel >= 0 ? '翻牌中…' : '点一张牌翻开 · 超时自动选第一张';
        text(p, tip, 30, y + CH + 30, W - 60, 56, this.revealed ? 32 : 26, this.revealed ? Theme.c.payRed : Theme.c.navy, { bold: true });
    }

    private pick(i: number): void {
        const st = ctx.store;
        if (this.sel >= 0) return;
        this.sel = i;
        this.rebuildBody();
        if (!st.online || this.windowId === undefined) {
            // 演示：本地随机一个结果
            const cash = Math.random() < 0.6;
            st.startPick = {
                playerId: st.me().playerId, index: i, cash, amount: cash ? 500 : 0, card: cash ? null : 'ROADBLOCK', auto: false,
                seq: (st.startPick?.seq ?? 0) + 1,
            };
            setTimeout(() => st.emit(), 400);
            return;
        }
        void st.online.act('PickStartCard', { windowId: this.windowId, index: i }).then((r) => {
            if (!r.ok && !this.revealed) this.close(); // 窗口已过期（服务端已代选）等：结果以提示显示
        });
    }

    protected onExpire(): void {
        // 服务端在窗口截止时代选；已经点过的等结果翻开
        if (this.sel < 0) this.close();
    }
}
