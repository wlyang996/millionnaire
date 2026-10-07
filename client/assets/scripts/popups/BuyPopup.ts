/**
 * 买地（15 秒），按设计稿 04"购买地产"：地产插画 + 档位胶囊、购买价格、三档价格、未升级租金，底部"放弃 / 购买 (金币) 价格"。
 * 现金不足时购买按钮置灰，并提示"现金 X，不足以购买"。不弹任何成功 / 放弃 / 超时提示。超时视为放弃（联机由服务端处理）。
 * 指定拍卖地（联机，设计稿 27 第一张）：插画旁"拍卖地"红色角标；按钮上方红底说明"发起拍卖即放弃本次购买资格"、起拍价 / 封顶价、
 * 发起者得成交价 10%；按钮为"放弃 / 发起拍卖 / 购买"三个。版式保持 04 号稿居中弹窗。
 */
import { Node } from 'cc';
import { landPrice, rentOf, stationRent, STATION, SECONDS, TIERS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { primaryButton, secondaryButton, softButton } from '../ui/Buttons';
import { art } from '../ui/Art';
import { ctx } from '../ui/Ctx';
import { text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { box, inlineRow, pillLabel, propertyArtKey, tierName } from './Common';

const W = 480;
const H = 666;
/** 指定拍卖地多出的拍卖说明块高度。 */
const AUCTION_ROW = 118;

export class BuyPopup extends Popup {
    /** @param windowId 联机时为服务端的落点决策窗口；演示时不传 */
    constructor(private readonly tileIndex: number, private readonly windowId?: number) {
        super('buy', ctx.store.tile(tileIndex).type === 'STATION' ? '购买车站' : '购买地产', W,
            H + (BuyPopup.auctionable(tileIndex, windowId) ? AUCTION_ROW : 0), SECONDS.buy);
    }

    /** 联机、指定拍卖地：可以发起土地拍卖。 */
    private static auctionable(tileIndex: number, windowId?: number): boolean {
        return !!ctx.store.online && windowId !== undefined && !!ctx.store.tile(tileIndex).auctionLot;
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const tile = st.tile(this.tileIndex);
        const station = tile.type === 'STATION';
        const price = landPrice(station, tile.tier);
        const cash = st.me().cash;
        const enough = cash >= price;
        art(p, propertyArtKey(tile), (W - 338) / 2, 74, 338, 181);
        pillLabel(p, tierName(tile), W / 2, 258);
        box(p, 32, 328, W - 64, 77, Theme.c.boxBeige);
        inlineRow(p, W / 2, 328, 77, [{ t: '购买价格', size: 26 }, { coin: 40 }, { t: String(price), size: 52 }], 14);
        box(p, 32, 419, W - 64, 40, Theme.c.boxGray, 14);
        const note = station
            ? '车站租金 = 持有车站数 × ' + STATION.rentEach
            : '低价 ' + TIERS.LOW.price + ' · 中价 ' + TIERS.MID.price + ' · 高价 ' + TIERS.HIGH.price;
        text(p, note, 32, 419, W - 64, 40, 20, Theme.c.noteGray, { bold: true });
        inlineRow(p, W / 2, 478, 46, [
            { t: station ? '车站租金' : '未升级租金', size: 26 }, { coin: 36 },
            { t: String(station ? stationRent(1) : rentOf(tile.tier ?? 'LOW', 0)), size: 36 },
        ]);
        if (!enough) text(p, '现金 ' + cash + '，不足以购买', 0, 526, W, 30, 22, Theme.c.payRed, { bold: true });
        let by = 563;
        if (BuyPopup.auctionable(this.tileIndex, this.windowId)) {
            // 设计稿 27：拍卖地角标 + 红底说明 + 三个按钮
            const tag = box(p, W - 132, 82, 100, 40, Theme.c.payRed, 12);
            text(tag, '拍卖地', 0, 0, 100, 40, 22, Theme.c.white, { bold: true });
            box(p, 24, by - 4, W - 48, 104, '#FDECEC', 16);
            art(p, 'icon_auction', 40, by + 6, 40, 40);
            text(p, '发起拍卖即放弃本次购买资格', 88, by + 4, W - 120, 44, 24, Theme.c.payRed, { bold: true, align: 'l' });
            inlineRow(p, W / 2, by + 50, 30, [
                { t: '起拍价', size: 20, bold: false }, { t: String(Math.ceil(price / 2)), size: 24, color: '#2F86E8' },
                { t: '|', size: 20, color: Theme.c.noteGray }, { t: '封顶价', size: 20, bold: false },
                { t: String(Math.floor(price * 5 / 2)), size: 24, color: Theme.c.payRed },
            ], 8);
            text(p, '发起者得成交价 10%', 0, by + 76, W, 24, 20, Theme.c.navy, { bold: true });
            by += AUCTION_ROW;
            softButton(p, '放弃', 12, by, 132, 77, () => this.giveUp(), 28);
            secondaryButton(p, '发起拍卖', 152, by, 150, 77, () => this.auction(), 28);
            const b = primaryButton(p, '购买', 310, by, W - 310 - 12, 77, () => this.buy(price), 28).withCoin(price);
            b.setEnabled(enough);
            return;
        }
        softButton(p, '放弃', 12, by, 178, 77, () => this.giveUp(), 30);
        const buy = primaryButton(p, '购买', 207, by, W - 207 - 12, 77, () => this.buy(price), 30).withCoin(price);
        buy.setEnabled(enough);
    }

    private buy(price: number): void {
        const st = ctx.store;
        this.close();
        if (st.online && this.windowId !== undefined) {
            // 联机：由服务端扣款、转产权，结果随推送刷新
            void st.online.act('BuyProperty', { windowId: this.windowId });
            return;
        }
        if (!st.spend(price)) return;
        const prop = st.prop(this.tileIndex);
        if (prop) prop.owner = st.myId;
        st.emit();
    }

    private auction(): void {
        const online = ctx.store.online;
        this.close();
        if (online && this.windowId !== undefined) void online.act('StartLandAuction', { windowId: this.windowId });
    }

    private giveUp(): void {
        const online = ctx.store.online;
        if (online && this.windowId !== undefined) void online.act('DeclinePurchase', { windowId: this.windowId });
        this.close();
    }

    protected onExpire(): void {
        // 联机时由服务端在窗口截止时按放弃处理
        this.close();
    }
}
