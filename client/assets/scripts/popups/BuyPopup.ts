/**
 * 买地（15 秒），按设计稿 04"购买地产"：地产插画 + 档位胶囊、购买价格、三档价格、未升级租金，底部"放弃 / 购买 (金币) 价格"。
 * 现金不足时购买按钮置灰，并提示"现金 X，不足以购买"。不弹任何成功 / 放弃 / 超时提示。超时视为放弃（联机由服务端处理）。
 * 指定拍卖地（联机）另有"发起拍卖"：放弃本次购买资格，所有其他玩家竞拍，发起人得成交价 10%。设计稿 04 没有这一项，按同一版式补在按钮上方。
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
/** 指定拍卖地多出"发起拍卖"一行的高度。 */
const AUCTION_ROW = 86;

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
            secondaryButton(p, '发起拍卖（放弃购买资格）', 12, by, W - 24, 70, () => this.auction(), 26);
            by += AUCTION_ROW;
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
