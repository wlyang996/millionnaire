/** 交易确认（买家，15 秒）：价格范围 = 标准价值的 50%～2.5 倍；同意且现金足额才成交，超时拒绝。 */
import { Node } from 'cc';
import { SECONDS, standardValue, tradeRange } from '../core/Rules';
import { Theme } from '../core/Theme';
import { ghostButton, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillPoly, gfx, mk, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { avatar } from '../ui/Widgets';
import { coinText, infoRow, tileHero, tileSubtitle } from './Common';

export class TradePopup extends Popup {
    constructor(private readonly tileIndex: number, private readonly sellerId: string, private readonly price: number) {
        super('trade', '交易确认', 640, 820, SECONDS.trade);
    }

    protected buildBody(p: Node, w: number): void {
        const st = ctx.store;
        const seller = st.player(this.sellerId) ?? st.game.players[1];
        const me = st.me();
        const tile = st.tile(this.tileIndex);
        const prop = st.prop(this.tileIndex);
        const sv = standardValue(tile.type === 'STATION', tile.tier, prop ? prop.upgradeSpent : 0);
        const r = tradeRange(sv);
        const inRange = this.price >= r.min && this.price <= r.max;
        avatar(p, 120, 92, 100, seller.avatar, seller.nickname);
        avatar(p, w - 220, 92, 100, me.avatar, me.nickname, { ring: Theme.c.blue });
        fillPoly(gfx(mk(p, 'Arrow', 0, 0, w, 100)), [[w / 2 - 24, 124], [w / 2 + 14, 124], [w / 2 + 14, 112], [w / 2 + 40, 142], [w / 2 + 14, 172], [w / 2 + 14, 160], [w / 2 - 24, 160]], Theme.c.orange);
        text(p, '卖家：' + seller.nickname, 70, 198, 200, 32, Theme.font.sm, Theme.c.ink, { bold: true });
        text(p, '买家：' + me.nickname, w - 270, 198, 200, 32, Theme.font.sm, Theme.c.ink, { bold: true });
        tileHero(p, tile, 160, 236, w - 320, 140, tileSubtitle(tile, prop));
        infoRow(p, 40, 392, w - 80, '标准价值', sv);
        infoRow(p, 40, 456, w - 80, '卖家出价', this.price, Theme.c.ivoryDark, inRange ? Theme.c.ink : Theme.c.red);
        text(p, '价格范围', 60, 520, 200, 44, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        text(p, r.min + ' ～ ' + r.max, w - 340, 520, 300, 44, Theme.font.md, Theme.c.ink, { bold: true, align: 'r' });
        text(p, '标准价值的 50%～2.5 倍（不允许免费赠送）', 40, 560, w - 80, 28, Theme.font.xs, Theme.c.inkFaint);
        infoRow(p, 40, 596, w - 80, '我的可用现金', me.cash - me.frozen);
        const bw = (w - 100) / 2;
        ghostButton(p, '拒绝', 40, 676, bw * 0.8, 88, () => this.refuse('已拒绝交易'), Theme.font.lg);
        const ok = primaryButton(p, '同意', 40 + bw * 0.8 + 20, 676, w - 80 - bw * 0.8 - 20, 88, () => {
            if (me.cash - me.frozen < this.price) return Toast.show('现金不足，交易不成立');
            st.spend(this.price);
            if (prop) prop.owner = 'p1';
            Toast.show('交易成交：花费 ' + this.price);
            this.close();
            st.emit();
        }, Theme.font.lg);
        ok.setEnabled(inRange && me.cash - me.frozen >= this.price, inRange ? '现金不足' : '价格超出允许范围');
        text(p, '同意且现金足额才成交 · 超时拒绝', 40, 772, w - 80, 28, Theme.font.xs, Theme.c.inkFaint);
        void coinText;
    }

    private refuse(msg: string): void {
        Toast.show(msg);
        this.close();
    }

    protected onExpire(): void {
        this.refuse('交易超时，视为拒绝');
    }
}
