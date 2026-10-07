/** 弹窗目录：演示面板据此列出并打开任一弹窗（用当前场景的数据造一个合适的实例）。 */
import { BoardTile, PropertyState } from '../core/Models';
import { standardValue } from '../core/Rules';
import { ctx } from '../ui/Ctx';
import { Popup } from '../ui/Popup';
import { AssetsPopup } from './AssetsPopup';
import { AuctionPopup } from './AuctionPopup';
import { BankPopup } from './BankPopup';
import { BuyPopup } from './BuyPopup';
import { ChatPopup } from './ChatPopup';
import { surrenderConfirm } from './ConfirmPopup';
import { DebtPopup } from './DebtPopup';
import { DebtSecondPopup } from './DebtSecondPopup';
import { DiscardPopup } from './DiscardPopup';
import { HistoryPopup } from './HistoryPopup';
import { JoinRoomPopup } from './JoinRoomPopup';
import { RentPopup } from './RentPopup';
import { ResyncPopup } from './ResyncPopup';
import { TradePopup } from './TradePopup';
import { TileInfoPopup } from './TileInfoPopup';
import { UpgradePopup } from './UpgradePopup';

export interface PopupEntry {
    id: string;
    label: string;
    make: () => Popup;
}

function pick(pred: (t: BoardTile, p: PropertyState | undefined) => boolean, fallback: number): number {
    const st = ctx.store;
    const t = st.game.tiles.find((x) => pred(x, st.prop(x.index)));
    return t ? t.index : fallback;
}

const mineProp = (t: BoardTile, p: PropertyState | undefined) => t.type === 'PROPERTY' && !!p && p.owner === 'p1' && !p.mortgaged;

export const POPUP_CATALOG: PopupEntry[] = [
    { id: 'buy', label: '买地 15秒', make: () => new BuyPopup(pick((t, p) => t.type === 'PROPERTY' && !t.auctionLot && !(p && p.owner), 1)) },
    { id: 'buy-lot', label: '买地(拍卖地)', make: () => new BuyPopup(pick((t, p) => !!t.auctionLot && !(p && p.owner), 1)) },
    { id: 'buy-station', label: '买地(车站)', make: () => new BuyPopup(pick((t, p) => t.type === 'STATION' && !(p && p.owner), 4)) },
    { id: 'upgrade', label: '升级 15秒', make: () => new UpgradePopup(pick((t, p) => mineProp(t, p) && (p as PropertyState).level < 3, 1)) },
    {
        id: 'rent', label: '租金响应 10秒', make: () => {
            const i = pick((t, p) => t.type === 'PROPERTY' && !!p && !!p.owner && p.owner !== 'p1' && !p.mortgaged, 3);
            const p = ctx.store.prop(i);
            const o = p && p.owner ? ctx.store.player(p.owner) : undefined;
            return new RentPopup(i, o ? o.nickname : '可可', 500);
        },
    },
    {
        id: 'trade', label: '交易确认 15秒', make: () => {
            const i = pick((t, p) => t.type === 'PROPERTY' && !!p && !!p.owner && p.owner !== 'p1', 3);
            const t = ctx.store.tile(i);
            const p = ctx.store.prop(i);
            const sv = standardValue(false, t.tier, p ? p.upgradeSpent : 0);
            return new TradePopup(i, p && p.owner ? p.owner : 'p2', Math.ceil(sv * 1.2));
        },
    },
    { id: 'auction', label: '拍卖 20秒', make: () => new AuctionPopup(pick((t, p) => !!t.auctionLot && !(p && p.owner), 1)) },
    { id: 'debt1', label: '欠款·首段 30秒', make: () => new DebtPopup(ctx.store.me().cash + 700, 'p2') },
    { id: 'debt2', label: '欠款·第二段', make: () => new DebtSecondPopup(ctx.store.me().cash + 700, 'p2') },
    { id: 'bank', label: '银行 抵押/赎回', make: () => new BankPopup() },
    { id: 'assets', label: '资产总览', make: () => new AssetsPopup() },
    { id: 'discard', label: '弃牌 15秒', make: () => new DiscardPopup() },
    { id: 'resync', label: '重连同步遮罩', make: () => new ResyncPopup() },
    { id: 'surrender', label: '二次确认(认输)', make: () => surrenderConfirm(() => ctx.store.surrender()) },
    { id: 'join', label: '房号加入', make: () => new JoinRoomPopup() },
    { id: 'history', label: '我的战绩', make: () => new HistoryPopup() },
    { id: 'tile', label: '格子详情', make: () => new TileInfoPopup(pick((t, p) => t.type === 'PROPERTY' && !!p && !!p.owner, 1)) },
    { id: 'chat', label: '聊天', make: () => new ChatPopup() },
];
