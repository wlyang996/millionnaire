/** Separate layouts from screens 20,21,22,23,25; supplied sprites, live values. */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { BAIL_COST, landPrice, START_BONUS, STATION, stationRent } from '../core/Rules';
import { art } from '../ui/Art';
import { Button } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { mk, onTap, text } from '../ui/Kit';
import { Toast } from '../ui/Toast';
import { luckyEffectText } from '../core/EventDraw';
import { ScrollList } from '../ui/ScrollList';
import { avatar, chip } from '../ui/Widgets';
import { informationCard, informationClose, informationPanel, redeemOrClose, shopKey } from './InformationPage';
import { boardHooks } from '../screens/board/BoardHooks';

/**
 * 银行办理窗口：联机为我停在这块银行格、服务端开着的银行窗口（返回窗口号）；演示为我的回合且我站在这块银行格（-1）。
 * 不能办理时返回 null，详情页只做查看。
 */
function bankWindow(index: number): number | null {
    const st = ctx.store;
    if (st.online) {
        const w = st.online.myWindow('TURN');
        const l = st.game.landing;
        return w && st.online.isOpen(w) && st.game.stage === 'LANDING' && l && l.step === 'BANK' && l.tile === index ? w.windowId : null;
    }
    return st.isMyTurn() && st.me().position === index ? -1 : null;
}

/** 出狱判定窗口：联机为服务端开着的出狱判定（返回窗口号）；演示为我的回合且被关押（-1）。不能办理时返回 null。 */
function jailWindow(): number | null {
    const st = ctx.store;
    if (st.online) {
        const w = st.online.myWindow('TURN');
        return w && st.online.isOpen(w) && st.game.stage === 'JAIL_DECISION' ? w.windowId : null;
    }
    return st.isMyTurn() && st.me().inJail ? -1 : null;
}

export interface SpecialLandState { bankMode: 'mortgage' | 'redeem'; selected: number | null }

function caption(p: Node, value: string, x: number, y: number, w: number, h = 46, size = 28): void {
    text(p, value, x, y, w, h, size, Theme.c.ink, { bold: true, align: 'l', wrap: true });
}
function rule(p: Node, label: string, y: number, key?: string): void {
    const row = informationPanel(p, 'info_asset_panel', 20, y, 616, 54);
    if (key) art(row, key, 16, 7, 40, 40);
    caption(row, label, key ? 76 : 20, 3, key ? 518 : 576, 48, 24);
}

export function buildSpecialLand(p: Node, index: number, state: SpecialLandState, redraw: () => void, close: () => void): void {
    const st = ctx.store, tile = st.tile(index), me = st.me();
    const hero: Record<string, string> = { START: 'scene_start', REST: 'scene_rest', BANK: 'scene_bank',
        JAIL: 'scene_jail', GAME_ZONE: 'scene_game_center', STATION: 'scene_station' };
    if (hero[tile.type]) art(p, hero[tile.type], 32, 208, 656, tile.type === 'REST' ? 580 : 392);

    if (tile.type === 'START') {
        // Screen25: identity, reward block and restrictions, no owner or property pricing.
        const panel = informationPanel(p, 'info_rent_panel', 32, 590, 656, 550);
        art(panel, 'scene_start', 18, 14, 168, 132);
        caption(panel, tile.name, 210, 18, 422, 62, 42);
        chip(panel, 210, 94, '起点', Theme.c.greenSoft, Theme.c.greenDark, 24, 38);
        caption(panel, '特殊土地', 344, 96, 240, 40, 24);
        const bonus = informationPanel(panel, 'info_summary_panel', 20, 162, 616, 122);
        art(bonus, 'info_cash', 20, 16, 90, 88);
        caption(bonus, '起点奖励 ' + START_BONUS + ' 金币', 132, 14, 462, 52, 34);
        caption(bonus, '正常前进经过或停在起点可获奖励', 132, 70, 462, 40, 23);
        rule(panel, '每回合最多领取一次。', 302, 'icon_clock_normal');
        rule(panel, '开局不领取。', 360, 'icon_active_card');
        rule(panel, '定点移动仅落在起点领奖。', 418, 'dice_1');
        rule(panel, '后退或送监狱途中不领奖。', 476, 'icon_jail');
        informationClose(p, 44, 1156, 632, close);
    } else if (tile.type === 'REST') {
        // Screen22: scene + a short stay card, not a property table.
        const panel = informationPanel(p, 'info_rent_panel', 32, 780, 656, 354);
        art(panel, 'scene_rest', 24, 20, 176, 132);
        caption(panel, tile.name, 230, 28, 390, 62, 42);
        chip(panel, 230, 108, '安心停留', Theme.c.greenSoft, Theme.c.greenDark, 28, 44);
        caption(panel, '不收租，无额外奖励。', 40, 190, 576, 54, 34);
        caption(panel, '本次停留结束后继续对局。', 40, 252, 576, 46, 27);
        informationClose(p, 44, 1156, 632, close);
    } else if (tile.type === 'STATION') {
        // Screen23: owner and original price in the identity card; no upgrade block.
        const prop = st.prop(index), owner = prop?.owner ? st.player(prop.owner) : undefined;
        const badge = informationPanel(p, 'info_asset_panel', 44, 192, 250, 76);
        if (owner) { avatar(badge, 12, 8, 58, owner.avatar, owner.nickname); caption(badge, owner.nickname, 84, 16, 154); }
        else caption(badge, '无主', 24, 16, 200);
        const info = informationPanel(p, 'info_asset_panel', 32, 590, 656, 194);
        art(info, 'scene_station', 18, 18, 186, 150);
        caption(info, tile.name, 228, 16, 400, 62, 42);
        chip(info, 228, 82, prop?.mortgaged ? '已抵押' : '未抵押', prop?.mortgaged ? Theme.c.redSoft : Theme.c.greenSoft, Theme.c.ink, 24, 34);
        caption(info, '原价', 228, 132, 100, 42, 24);
        art(info, 'info_cash', 336, 132, 36, 40); caption(info, String(STATION.price), 388, 128, 214, 50, 34);
        const panel = informationPanel(p, 'info_rent_panel', 32, 796, 656, 342);
        caption(panel, '租金（拥有的未抵押车站数量生效）', 24, 12, 608, 46, 26);
        const count = owner ? st.assetsOf(owner.playerId).filter(a => a.tile.type === 'STATION' && !a.p.mortgaged).length : 0;
        const list = new ScrollList(panel, 20, 62, 616, 176);
        const total = st.game.tiles.filter(t => t.type === 'STATION').length;
        for (let i = 1; i <= total; i++) {
            const row = informationPanel(list.content, 'info_asset_panel', 0, (i - 1) * 44, 616, 42);
            caption(row, (!prop?.mortgaged && i === count ? '当前 · ' : '') + i + '座', 28, 0, 274, 42, 24);
            art(row, 'info_cash', 344, 6, 30, 30); caption(row, String(stationRent(i)), 390, 0, 194, 42, 26);
        }
        list.setContentHeight(total * 44);
        caption(panel, '基础 = 未抵押车站数 × ' + STATION.rentEach + '；已含本局租金倍率', 24, 248, 608, 38, 22);
        caption(panel, prop?.mortgaged ? '抵押本金 ' + prop.mortgagePaid + ' · 抵押期间不收租' : '当前租金 ' + stationRent(count), 24, 286, 608, 38, 22);
        redeemOrClose(p, index, 44, 1156, 632, close);
    } else if (tile.type === 'BANK') {
        // Screen20: cash, two tabs, selectable owned assets, totals, two footer actions.
        const cash = informationPanel(p, 'info_summary_panel', 462, 202, 224, 100);
        art(cash, 'info_cash', 12, 18, 52, 62); caption(cash, '现金', 82, 6, 126, 32, 22); caption(cash, String(me.cash), 82, 40, 126, 50, 30);
        const panel = informationPanel(p, 'info_rent_panel', 32, 590, 656, 548);
        new Button(panel, '抵押', 20, 12, 302, 70, state.bankMode === 'mortgage' ? 'primary' : 'ghost', () => { state.bankMode = 'mortgage'; state.selected = null; redraw(); }, 32);
        new Button(panel, '赎回', 334, 12, 302, 70, state.bankMode === 'redeem' ? 'secondary' : 'ghost', () => { state.bankMode = 'redeem'; state.selected = null; redraw(); }, 32);
        const redeem = state.bankMode === 'redeem';
        caption(panel, '选择要' + (redeem ? '赎回' : '抵押') + '的房产', 24, 94, 608, 42, 28);
        const assets = st.assetsOf(me.playerId).filter(a => a.p.mortgaged === redeem);
        const list = new ScrollList(panel, 20, 144, 616, 266);
        assets.forEach((a, i) => {
            const row = informationPanel(list.content, 'info_asset_panel', 0, i * 130, 616, 122);
            caption(row, state.selected === a.tile.index ? '●' : '○', 12, 38, 46, 44, 30);
            art(row, shopKey(a.tile.type, a.tile.tier), 62, 10, 106, 98);
            caption(row, a.tile.name, 182, 12, 226, 42, 28);
            caption(row, a.tile.type === 'STATION' ? '车站' : (a.p.level ? a.p.level + '级' : '未升级'), 182, 60, 226, 36, 23);
            caption(row, redeem ? '抵押本金' : '原价／可获', 428, 12, 166, 38, 22);
            caption(row, String(redeem ? a.p.mortgagePaid : landPrice(a.tile.type === 'STATION', a.tile.tier)), 428, 60, 166, 44, 30);
            onTap(row, () => { state.selected = a.tile.index; redraw(); }, false);
        });
        list.setContentHeight(Math.max(150, assets.length * 130));
        if (!assets.length) caption(list.content, '暂无可' + (redeem ? '赎回' : '抵押') + '房产', 20, 42, 576, 64, 28);
        const selected = assets.find(a => a.tile.index === state.selected);
        const amount = selected ? redeem ? selected.p.mortgagePaid : landPrice(selected.tile.type === 'STATION', selected.tile.tier) : 0;
        caption(panel, redeem ? '银行赎回 · 手续费 0' : '选中 ' + (selected ? 1 : 0) + ' 处 · 可获 ' + amount, 24, 422, 608, 46, 28);
        caption(panel, '原价100%抵押，抵押期间不收租。', 24, 484, 608, 38, 23);
        // 停在这块银行格、银行窗口开着时可办理（与银行办理弹窗发同样的命令）；否则只做查看
        const win = bankWindow(index);
        new Button(p, win === null ? '返回棋盘' : '结束办理', 44, 1156, 302, 86, 'disabled', () => {
            close();
            if (win !== null && win >= 0) void st.online?.act('FinishBank', { windowId: win });
        }, 32);
        const confirm = new Button(p, redeem ? '确认赎回' : '确认抵押', 364, 1156, 312, 86, redeem ? 'secondary' : 'primary', () => {
            if (!selected || win === null) return;
            if (win >= 0 && st.online) {
                void st.online.act(redeem ? 'Redeem' : 'BankMortgage', { windowId: win, tile: selected.tile.index }).then(() => {
                    state.selected = null;
                    redraw();
                });
                return;
            }
            if (redeem) {
                if (!st.spend(amount)) return;
                selected.p.mortgaged = false;
                selected.p.mortgagePaid = 0;
            } else {
                selected.p.mortgaged = true;
                selected.p.mortgagePaid = amount;
                me.cash += amount;
            }
            state.selected = null;
            st.emit();
            redraw();
        }, 32);
        if (win === null) confirm.setEnabled(false, '停在银行时才能办理');
        else if (!selected) confirm.setEnabled(false, '先选择一处房产');
        else if (redeem && me.cash < amount) confirm.setEnabled(false, '现金不足 ' + amount);
    } else if (tile.type === 'FIXED_EVENT' || tile.type === 'UNLUCKY_EVENT') {
        // 用户 2026-10-08：幸运格 / 不幸格踩到即从红色幸运卡 / 紫色不幸卡里随机抽一张，自动生效；详情页列出奖池
        const unlucky = tile.type === 'UNLUCKY_EVENT';
        const panel = informationPanel(p, 'info_rent_panel', 32, 300, 656, 440);
        caption(panel, tile.name, 40, 30, 576, 62, 44);
        chip(panel, 40, 112, unlucky ? '不幸' : '幸运', unlucky ? '#ECE0F5' : '#FFE3D6', unlucky ? '#5B2A86' : '#C2461C', 24, 38);
        caption(panel, '踩到自动抽一张', 156, 114, 300, 40, 24);
        const body = informationPanel(panel, 'info_summary_panel', 20, 180, 616, 220);
        const pool = tile.lucky ?? [];
        caption(body, pool.length ? pool.map(luckyEffectText).join('\n') : '踩到即随机抽一张' + (unlucky ? '不幸卡' : '幸运卡'), 24, 14, 568, 192, 26);
        new Button(p, '知道了', 204, 800, 312, 86, 'primary', close, 32);
    } else if (tile.type === 'JAIL') {
        // Screen21 has large illustrated operation cards, not small gray capsules.
        art(p, 'scene_jail_closeup', 0, 0, 720, 648);
        const panel = informationCard(p, 22, 636, 676, 624, '#FFFEF6', 44);
        const failure = informationCard(panel, 174, 18, 328, 64, '#FFF3D6', 32, false);
        text(failure, '已失败', 28, 6, 138, 52, 32, '#101A50', { bold: true });
        text(failure, String(me.inJail ? me.jailFailures : 0), 174, 0, 64, 62, 50, '#E9302D', { bold: true });
        text(failure, '次', 246, 6, 54, 52, 32, '#101A50', { bold: true });
        art(panel, 'dice_5', 206, 90, 264, 188);
        const instructions = informationCard(panel, 26, 286, 624, 62, '#EFF8FF', 24, false);
        text(instructions, '掷出偶数可出狱，第三次失败自动释放。', 12, 4, 600, 54, 26, '#101A50', { bold: true });
        const cards = st.game.myHand.filter(c => c.type === 'JAIL_RELEASE').length;
        const actions = [
            { skin: 'button_flat_yellow', icon: 'dice_5', label: '掷骰判定', color: '#101A50' },
            { skin: 'button_flat_blue', icon: 'info_cash', label: '支付' + BAIL_COST + '\n出狱', color: '#FFFFFF' },
            { skin: 'button_flat_green', icon: 'card_jail_release', label: '使用出狱卡', color: '#101A50' },
        ];
        actions.forEach((a, i) => {
            const card = mk(panel, 'JailAction:' + i, 26 + i * 212, 360, 200, 188);
            art(card, a.skin, 0, 0, 200, 188, 'panel');
            art(card, a.icon, 52, 12, 96, 70);
            text(card, a.label, 10, 88, 180, i === 1 ? 90 : 46, 30, a.color, { bold: true, wrap: true });
            if (i === 2) {
                const count = informationCard(card, 34, 140, 132, 36, '#E2F6C8', 18, false);
                text(count, cards + '张', 8, 0, 116, 36, 25, '#245B24', { bold: true });
            }
            onTap(card, () => jailAction(i, cards, close));
        });
        const note = informationCard(panel, 26, 560, 624, 48, '#F4EDDF', 22, false);
        text(note, '出狱后再投骰移动。', 12, 2, 600, 44, 26, '#101A50', { bold: true });
    }
}

/** 监狱详情页的三张操作卡：出狱判定窗口开着时直接办理（与出狱判定页相同），否则说明原因。 */
function jailAction(i: number, cards: number, close: () => void): void {
    const st = ctx.store;
    const me = st.me();
    if (!me.inJail) return Toast.show('当前未被关押；路过监狱不关押');
    const win = jailWindow();
    if (win === null) return Toast.show('轮到你的回合时才能办理出狱');
    if (i === 0) {
        close();
        boardHooks.jailRoll?.();
    } else if (i === 1) {
        if (me.cash < BAIL_COST) return Toast.show('现金不足 ' + BAIL_COST);
        close();
        if (win >= 0) void st.online?.act('PayBail', { windowId: win });
        else if (st.spend(BAIL_COST)) {
            me.inJail = false;
            st.emit();
        }
    } else {
        if (cards <= 0) return Toast.show('没有出狱卡');
        if (st.online && st.game.cards?.chanceUsed.includes(st.myId)) return Toast.show('本回合已经用过道具了');
        close();
        if (win >= 0) void st.online?.act('UseCard', { windowId: win, card: 'JAIL_RELEASE', target: null, steps: 0 });
        else {
            const k = st.game.myHand.findIndex((c) => c.type === 'JAIL_RELEASE');
            if (k >= 0) st.game.myHand.splice(k, 1);
            me.inJail = false;
            st.emit();
        }
    }
}
