/** 买地（15 秒）：购买 / 放弃；指定拍卖地产另有"发起拍卖"。超时视为放弃。 */
import { Node } from 'cc';
import { landPrice, rentOf, stationRent, STATION, SECONDS, TIERS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { Button, ghostButton, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { AuctionPopup } from './AuctionPopup';
import { coinText, infoRow, rentTable, tileHero, tileSubtitle } from './Common';

export class BuyPopup extends Popup {
    constructor(private readonly tileIndex: number) {
        super('buy', ctx.store.tile(tileIndex).type === 'STATION' ? '购买车站' : '购买地产', 640, 780, SECONDS.buy);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const st = ctx.store;
        const tile = st.tile(this.tileIndex);
        const station = tile.type === 'STATION';
        const price = landPrice(station, tile.tier);
        const cash = st.me().cash;
        tileHero(p, tile, 40, 92, w - 80, 220, tileSubtitle(tile, undefined));
        infoRow(p, 40, 328, w - 80, '购买价格', price, Theme.c.ivoryDark);
        if (station) {
            text(p, '车站租金 = 持有未抵押车站数 × ' + STATION.rentEach + '（现持有 0 个：' + stationRent(1) + '）', 40, 400, w - 80, 56, Theme.font.sm, Theme.c.inkSoft, { wrap: true, lineHeight: 30 });
        } else {
            text(p, '低价 ' + TIERS.LOW.price + ' · 中价 ' + TIERS.MID.price + ' · 高价 ' + TIERS.HIGH.price, 40, 402, w - 80, 42, Theme.font.sm, Theme.c.inkSoft);
            infoRow(p, 40, 446, w - 80, '未升级租金', rentOf(tile.tier ?? 'LOW', 0));
        }
        text(p, '现金 ' + cash + '，买后剩余 ' + (cash - price), 40, 508, w - 80, 36, Theme.font.sm, cash >= price ? Theme.c.inkSoft : Theme.c.red, { bold: true });
        const lot = !!tile.auctionLot && !station;
        const buy: Button = primaryButton(p, '购买 ' + price, 40, 556, w - 80, 92, () => {
            if (!st.spend(price)) return Toast.show('现金不足');
            const prop = st.prop(this.tileIndex);
            if (prop) prop.owner = 'p1';
            Toast.show('已购买，花费 ' + price);
            this.close();
            st.emit();
        }, Theme.font.lg);
        buy.setEnabled(cash >= price, '现金不足，无法购买');
        if (lot) {
            secondaryButton(p, '发起拍卖', 40, 664, (w - 100) / 2, 80, () => {
                this.close();
                ctx.popups.open(new AuctionPopup(this.tileIndex, true));
            }, Theme.font.md);
            ghostButton(p, '放弃', 40 + (w - 100) / 2 + 20, 664, (w - 100) / 2, 80, () => this.giveUp(), Theme.font.md);
        } else {
            ghostButton(p, '放弃', 40, 664, w - 80, 80, () => this.giveUp(), Theme.font.md);
            text(p, station ? '放弃后车站保持无主，不自动拍卖' : '放弃后保持无主', 40, 742, w - 80, 30, Theme.font.xs, Theme.c.inkFaint);
        }
        if (lot) text(p, '发起拍卖即放弃本次购买资格；发起者得成交价 10%', 40, 744, w - 80, 30, Theme.font.xs, Theme.c.inkFaint);
        void h;
        void coinText;
    }

    private giveUp(): void {
        Toast.show('已放弃购买');
        this.close();
    }

    protected onExpire(): void {
        Toast.show('操作超时，视为放弃');
        this.close();
    }
}
