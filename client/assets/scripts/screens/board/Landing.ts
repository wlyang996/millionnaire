/** 落点处理（演示）：按落点格弹出买地/升级/租金响应/欠款/事件/虎口拔牙；不弹文字提示（扣钱、路过等都不提示）。 */
import { Card } from '../../core/Models';
import { rentOf, stationRent } from '../../core/Rules';
import { BankPopup } from '../../popups/BankPopup';
import { BuyPopup } from '../../popups/BuyPopup';
import { beginDebt } from '../../popups/DebtPopup';
import { RentPopup } from '../../popups/RentPopup';
import { UpgradePopup } from '../../popups/UpgradePopup';
import { ctx } from '../../ui/Ctx';

export function handleLanding(i: number): void {
    const st = ctx.store;
    const t = st.tile(i);
    const prop = st.prop(i);
    const me = st.me();
    if (t.type === 'PROPERTY' || t.type === 'STATION') {
        if (!prop || !prop.owner) ctx.popups.open(new BuyPopup(i));
        else if (prop.owner === st.myId) {
            if (t.type === 'PROPERTY' && !prop.mortgaged) ctx.popups.open(new UpgradePopup(i));
        } else if (!prop.mortgaged) {
            const owner = st.player(prop.owner);
            const stations = st.assetsOf(prop.owner).filter((a) => a.tile.type === 'STATION' && !a.p.mortgaged).length;
            const amount = t.type === 'STATION' ? stationRent(stations) : rentOf(t.tier ?? 'LOW', prop.level);
            const hasCard = st.game.myHand.some((c: Card) => c.type === 'RENT_WAIVER');
            if (hasCard) ctx.popups.open(new RentPopup(i, owner ? owner.nickname : '对手', amount));
            else if (me.cash >= amount) st.spend(amount);
            else beginDebt(amount, prop.owner);
        }
    } else if (t.type === 'BANK') {
        ctx.popups.open(new BankPopup());
    } else if (t.type === 'GAME_ZONE') {
        ctx.screens.push('teeth');
    } else if (t.type === 'EVENT') {
        // 事件格：触发事件卡抽卡流程（仅本人界面中央出现问号卡背，点击卡片翻开；见 core/EventDraw.ts）
        st.eventStart(st.myId);
    } else if (t.type === 'FIXED_EVENT') {
        // 固定事件格：踩到即自动翻开效果（不抽卡）
        st.eventFixed(st.myId, i);
    }
}
