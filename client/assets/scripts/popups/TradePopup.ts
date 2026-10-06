/**
 * 交易确认（买家，15 秒），按设计稿 04"交易确认"：卖家（金色圈）→ 买家（蓝色圈）、地产插画与"档位 · 等级"胶囊、
 * 白色信息卡（标准价值 / 卖家出价（红）/ 价格范围 + 说明）、我的可用现金，底部"拒绝 / 同意 (金币) 价格"及说明。
 * 价格范围 = 标准价值的 50%～2.5 倍；同意且现金足额才成交，超时拒绝。
 */
import { Node } from 'cc';
import { SECONDS, standardValue, tradeRange } from '../core/Rules';
import { Theme } from '../core/Theme';
import { art } from '../ui/Art';
import { primaryButton, softButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCoin } from '../ui/Icons';
import { fillPoly, fillRR, gfx, line, mk, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { avatar } from '../ui/Widgets';
import { box, LEVEL_NAMES, pillLabel, propertyArtKey, tierName } from './Common';

const W = 480;
const H = 700;

export class TradePopup extends Popup {
    constructor(private readonly tileIndex: number, private readonly sellerId: string, private readonly price: number) {
        super('trade', '交易确认', W, H, SECONDS.trade);
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const seller = st.player(this.sellerId) ?? st.game.players[1];
        const me = st.me();
        const tile = st.tile(this.tileIndex);
        const prop = st.prop(this.tileIndex);
        const sv = standardValue(tile.type === 'STATION', tile.tier, prop ? prop.upgradeSpent : 0);
        const r = tradeRange(sv);
        const inRange = this.price >= r.min && this.price <= r.max;
        const available = me.cash - me.frozen;
        avatar(p, 82, 66, 124, seller.avatar, seller.nickname, { ring: '#F5B82E' });
        avatar(p, 273, 66, 124, me.avatar, me.nickname, { ring: Theme.c.blue });
        const arrows = gfx(mk(p, 'Arrows', 214, 104, 50, 50));
        fillRR(arrows, 0, 8, 30, 12, 4, '#FF9A3D');
        fillPoly(arrows, [[26, 0], [46, 14], [26, 28]], '#FF9A3D');
        fillRR(arrows, 6, 34, 22, 8, 3, '#FFB347');
        fillPoly(arrows, [[26, 28], [38, 38], [26, 48]], '#FFB347');
        text(p, '卖家：' + seller.nickname, 24, 194, 240, 30, 24, Theme.c.navy, { bold: true });
        text(p, '买家：' + me.nickname, 216, 194, 240, 30, 24, Theme.c.navy, { bold: true });
        art(p, propertyArtKey(tile), (W - 188) / 2, 220, 188, 90);
        const level = tile.type === 'PROPERTY' ? ' · ' + LEVEL_NAMES[prop ? prop.level : 0] : '';
        pillLabel(p, tierName(tile) + level, W / 2, 316, 36, 24);
        // 白色信息卡
        const card = mk(p, 'Info', 20, 358, W - 40, 164);
        const g = gfx(card);
        fillRR(g, 0, 0, W - 40, 164, 18, Theme.c.white);
        strokeRR(g, 0, 0, W - 40, 164, 18, '#EFE6D6', 2);
        line(g, 16, 46, W - 56, 46, '#F0EADF', 2);
        line(g, 16, 90, W - 56, 90, '#F0EADF', 2);
        this.row(card, 2, '标准价值', String(sv), Theme.c.navy, true);
        this.row(card, 46, '卖家出价', String(this.price), Theme.c.payRed, true);
        this.row(card, 90, '价格范围', r.min + ' ～ ' + r.max, Theme.c.navy, false);
        text(card, '标准价值的50%～2.5倍', 0, 132, W - 40, 26, 18, Theme.c.noteGray);
        box(p, 20, 534, W - 40, 42, Theme.c.boxGray, 14);
        const mine = mk(p, 'Mine', 20, 534, W - 40, 42);
        this.row(mine, 0, '我的可用现金', String(available), Theme.c.navy, true, 42);
        softButton(p, '拒绝', 15, 586, 170, 66, () => this.close(), 30);
        const ok = primaryButton(p, '同意', 200, 586, W - 200 - 14, 66, () => {
            if (available < this.price) return;
            st.spend(this.price);
            if (prop) prop.owner = st.myId;
            this.close();
            st.emit();
        }, 30).withCoin(this.price);
        ok.setEnabled(inRange && available >= this.price);
        text(p, '同意且现金足额才成交 · 超时拒绝', 0, 660, W, 26, 20, Theme.c.noteGray);
    }

    /** 信息卡一行：左标签，右（金币 +）数值右对齐。 */
    private row(parent: Node, y: number, label: string, value: string, color: string, coin: boolean, h = 44): void {
        const w = W - 40;
        text(parent, label, 22, y, 220, h, 22, Theme.c.navy, { bold: true, align: 'l' });
        text(parent, value, w - 22 - 200, y, 200, h, 26, color, { bold: true, align: 'r' });
        if (coin) {
            const vw = value.length * 26 * 0.56 + 4;
            drawCoin(gfx(mk(parent, 'Coin', w - 22 - vw - 38, y + (h - 30) / 2, 30, 30)), 15, 15, 15);
        }
    }

    protected onExpire(): void {
        this.close();
    }
}
