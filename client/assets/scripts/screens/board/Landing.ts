/** 落点处理（演示）：按落点格弹出买地/升级/租金响应/欠款/事件/虎口拔牙。 */
import { Card } from '../../core/Models';
import { rentOf, stationRent } from '../../core/Rules';
import { BuyPopup } from '../../popups/BuyPopup';
import { beginDebt } from '../../popups/DebtPopup';
import { RentPopup } from '../../popups/RentPopup';
import { UpgradePopup } from '../../popups/UpgradePopup';
import { ctx } from '../../ui/Ctx';
import { Toast } from '../../ui/Toast';

export function handleLanding(i: number): void {
    const st = ctx.store;
    const t = st.tile(i);
    const prop = st.prop(i);
    const me = st.me();
    if (t.type === 'PROPERTY' || t.type === 'STATION') {
        if (!prop || !prop.owner) ctx.popups.open(new BuyPopup(i));
        else if (prop.owner === st.myId) {
            if (t.type === 'PROPERTY' && !prop.mortgaged) ctx.popups.open(new UpgradePopup(i));
            else Toast.show('自己的资产：无需缴租');
        } else if (!prop.mortgaged) {
            const owner = st.player(prop.owner);
            const stations = st.assetsOf(prop.owner).filter((a) => a.tile.type === 'STATION' && !a.p.mortgaged).length;
            const amount = t.type === 'STATION' ? stationRent(stations) : rentOf(t.tier ?? 'LOW', prop.level);
            const hasCard = st.game.myHand.some((c: Card) => c.type === 'RENT_WAIVER');
            if (hasCard) ctx.popups.open(new RentPopup(i, owner ? owner.nickname : '对手', amount));
            else if (me.cash >= amount) {
                st.spend(amount);
                Toast.show('向 ' + (owner ? owner.nickname : '对手') + ' 支付租金 ' + amount);
            } else beginDebt(amount, prop.owner);
        }
    } else if (t.type === 'GAME_ZONE') {
        Toast.show('进入' + t.name + '：虎口拔牙');
        ctx.screens.push('teeth');
    } else if (t.type === 'EVENT') {
        // 事件格：触发事件卡抽卡流程（仅本人界面中央出现问号卡背，点击卡片翻开；见 core/EventDraw.ts）
        st.eventStart(st.myId);
    } else if (t.type === 'BANK') Toast.show(t.name + '：可手动抵押 / 赎回（点"我的资产"）');
    else if (t.type === 'JAIL') Toast.show('路过' + t.name + '，不关押');
    else Toast.show(t.type === 'REST' ? t.name + '：无事发生' : '回到' + t.name);
}
