/**
 * 主动用卡（设计稿 13 / 14）：判断此刻能否用某张卡、发送 UseCard，以及棋盘底部的确认面板：
 * 路障（在当前位置放置）、定点移动（前进 1～6 格，列出各格地名）、建造 / 降级 / 拆楼 / 清地 / 强制购房（当前格前后对比）。
 * 查询的选人与结果见 QueryPopups。规则：自己的回合投骰前（狱中只能出狱、查询）或落点结算后的用卡阶段，两阶段共一次；
 * 目标格一律为当前位置；条件不满足的卡不消耗。演示模式只在本地扣掉手牌并做简单效果。
 */
import { Node } from 'cc';
import { CARD_NAMES, CardType, PlayerView, PropertyState } from '../core/Models';
import { MAX_LEVEL, rentOf, standardValue, stationRent, TIERS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { art, CARD_ART } from '../ui/Art';
import { primaryButton, softButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, onTap, place, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { avatar } from '../ui/Widgets';
import { inlineRow } from './Common';
import { QueryTargetPopup } from './QueryPopups';

export const RESPONSE_CARDS: CardType[] = ['RENT_WAIVER', 'REFUSE_PURCHASE', 'HOUSE_PROTECTION'];

export interface Usable { ok: boolean; reason: string }

const yes: Usable = { ok: true, reason: '' };
const no = (reason: string): Usable => ({ ok: false, reason });

/** 当前格的地产与所有者。 */
function here(): { index: number; prop: PropertyState | undefined; owner: PlayerView | undefined } {
    const st = ctx.store;
    const index = st.me().position;
    const prop = st.prop(index);
    return { index, prop, owner: prop && prop.owner ? st.player(prop.owner) : undefined };
}

/** 强制购房价：标准价值 × 1.5，向下取整。 */
export function forcedPrice(index: number): number {
    const st = ctx.store;
    const tile = st.tile(index);
    const prop = st.prop(index);
    const station = tile.type === 'STATION';
    return Math.floor(standardValue(station, tile.tier, prop ? (station ? 0 : TIERS[tile.tier ?? 'LOW'].upgrade * prop.level) : 0) * 3 / 2);
}

/** 某格当前应付的租金（地产按档位与等级，车站按所有者未抵押车站数）。 */
export function rentAt(index: number): number {
    const st = ctx.store;
    const tile = st.tile(index);
    const prop = st.prop(index);
    if (!prop || !prop.owner) return 0;
    if (tile.type === 'STATION') {
        return stationRent(st.assetsOf(prop.owner).filter((a) => a.tile.type === 'STATION' && !a.p.mortgaged).length);
    }
    return rentOf(tile.tier ?? 'LOW', prop.level);
}

/** 此刻能否使用这张卡（不能时给出原因，按钮置灰并提示）。 */
export function cardUsable(type: CardType): Usable {
    const st = ctx.store;
    const g = st.game;
    const me = st.me();
    if (!me || me.life !== 'ALIVE') return no('你已出局');
    if (RESPONSE_CARDS.includes(type)) return no('响应时使用');
    if (type === 'AUCTION' || type === 'TRADE') return no('拍卖 / 交易需另行申请');
    if (!g.myHand.some((c) => c.type === type)) return no('手里没有这张道具');
    if (!st.isMyTurn()) return no('只能在自己的回合使用');
    if (me.control !== 'MANUAL' || me.conn === 'OFFLINE') return no('托管中不能用卡');
    const online = st.online;
    let post = g.stage === 'LANDING';
    if (online) {
        const w = online.myWindow('TURN');
        if (!w || !online.isOpen(w)) return no('当前不是用卡时机');
        if (g.cards?.chanceUsed.includes(st.myId)) return no('本回合已经用过道具了');
        post = g.stage === 'LANDING' && !g.landing;
    }
    const pre = g.stage === 'PRE_ROLL';
    const jail = g.stage === 'JAIL_DECISION';
    if (!pre && !jail && !post) return no('投骰前或落点结算后才能用卡');
    const h = here();
    const tile = st.tile(h.index);
    const other = !!h.prop && !!h.prop.owner && h.prop.owner !== st.myId && !h.prop.mortgaged;
    switch (type) {
        case 'FIXED_MOVE':
            return pre && !me.inJail ? yes : no('仅正常投骰前使用');
        case 'JAIL_RELEASE':
            return jail ? yes : no('被关押时才能使用');
        case 'QUERY':
            return g.players.some((p) => p.life === 'ALIVE' && p.playerId !== st.myId) ? yes : no('没有可查询的玩家');
        case 'ROADBLOCK':
            if (jail || tile.type === 'JAIL') return no('监狱里不能放路障');
            return (g.roadblocks ?? []).includes(h.index) ? no('这里已经有路障了') : yes;
        case 'BUILD':
            return !jail && tile.type === 'PROPERTY' && h.prop?.owner === st.myId && !h.prop.mortgaged && h.prop.level < MAX_LEVEL
                ? yes : no('需站在自己未抵押、未满级的地产上');
        case 'DOWNGRADE':
        case 'DEMOLISH':
            return !jail && tile.type === 'PROPERTY' && other && (h.prop?.level ?? 0) >= 1 ? yes : no('需站在他人有等级、未抵押的地产上');
        case 'CLEAR_LAND':
            return !jail && tile.type === 'PROPERTY' && other ? yes : no('需站在他人未抵押的地产上');
        case 'FORCED_PURCHASE':
            if (jail || !other || (tile.type !== 'PROPERTY' && tile.type !== 'STATION')) return no('需站在他人未抵押的地产或车站上');
            return me.cash >= forcedPrice(h.index) ? yes : no('现金不足 ' + forcedPrice(h.index));
        default:
            return no('当前不是这张道具的使用时机');
    }
}

/** 发送用卡（联机）或在演示里本地结算。 */
export function sendCard(type: CardType, target: string | null = null, steps = 0): void {
    const st = ctx.store;
    const online = st.online;
    if (online) {
        const w = online.myWindow('TURN');
        if (!w) return;
        void online.act('UseCard', { windowId: w.windowId, card: type, target, steps });
        return;
    }
    // 演示：扣掉一张卡，做最简单的本地效果
    const i = st.game.myHand.findIndex((c) => c.type === type);
    if (i >= 0) st.game.myHand.splice(i, 1);
    const h = here();
    if (h.prop) {
        if (type === 'BUILD') h.prop.level = Math.min(MAX_LEVEL, h.prop.level + 1);
        else if (type === 'DOWNGRADE') h.prop.level = Math.max(0, h.prop.level - 1);
        else if (type === 'DEMOLISH') h.prop.level = 0;
        else if (type === 'CLEAR_LAND') { h.prop.owner = null; h.prop.level = 0; }
        else if (type === 'FORCED_PURCHASE' && h.owner) {
            const price = forcedPrice(h.index);
            st.me().cash -= price;
            h.owner.cash += price;
            h.prop.owner = st.myId;
        }
    }
    Toast.show('已使用' + CARD_NAMES[type] + '（演示）');
    st.emit();
}

/** 卡牌详情里点"使用 / 选择落点"：按卡种打开确认面板或选人页。 */
export function openCardUse(type: CardType): void {
    const u = cardUsable(type);
    if (!u.ok) {
        Toast.show(u.reason);
        return;
    }
    if (type === 'QUERY') {
        ctx.popups.open(new QueryTargetPopup());
        return;
    }
    if (type === 'JAIL_RELEASE') {
        sendCard(type);
        return;
    }
    if (type === 'ROADBLOCK') ctx.popups.open(new RoadblockSheet());
    else if (type === 'FIXED_MOVE') ctx.popups.open(new FixedMoveSheet());
    else ctx.popups.open(new PropertyCardSheet(type));
}

// ================================================================ 底部确认面板

const SHEET_W = 692;

/** 棋盘底部的确认面板：盖住手牌栏，贴在页脚上方；不压暗棋盘。 */
abstract class CardSheet extends Popup {
    constructor(id: string, h: number) {
        super(id, '', SHEET_W, h, 0, false);
    }

    mount(layer: Node): void {
        super.mount(layer);
        place(this.panel, (Theme.W - SHEET_W) / 2, 1192 - this.ph);
    }

    protected buildTitle(): void {
        // 标题画在主体里（左侧插画、右侧标题）
    }

    /** 底部"取消 / 确认"两个按钮。 */
    protected buttons(p: Node, y: number, confirm: string, fn: () => void, enabled = true, reason = ''): void {
        softButton(p, '取消', 24, y, 220, 84, () => this.close(), 32);
        const b = primaryButton(p, confirm, 264, y, SHEET_W - 288, 84, () => {
            this.close();
            fn();
        }, 34);
        b.setEnabled(enabled, reason);
    }

    /** 联机：用卡时机过去（窗口关闭、换人）就收起。 */
    tick(): void {
        super.tick();
        if (this.closed) return;
        const st = ctx.store;
        if (st.online && (!st.isMyTurn() || !st.online.myWindow('TURN'))) this.close();
    }
}

/** 设计稿 13 左：路障插画 +"在当前位置放置路障" + 地名；取消 / 放置路障。 */
class RoadblockSheet extends CardSheet {
    constructor() {
        super('card-roadblock', 250);
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        art(p, 'icon_roadblock', 36, 26, 120, 120);
        text(p, '在当前位置放置路障', 170, 28, SHEET_W - 190, 56, 34, Theme.c.navy, { bold: true, align: 'l' });
        text(p, '位置：' + st.tile(st.me().position).name, 170, 86, SHEET_W - 190, 44, 28, Theme.c.navy, { bold: true, align: 'l' });
        this.buttons(p, 150, '放置路障', () => sendCard('ROADBLOCK'));
    }
}

/** 设计稿 13 第二张：骰子插画 +"前进 N 格" + 目标地名；1～6 格地名列表（点选）；取消 / 确认移动。 */
class FixedMoveSheet extends CardSheet {
    private steps = 1;

    constructor() {
        super('card-fixed-move', 380);
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const pos = st.me().position;
        const n = st.game.tiles.length;
        art(p, 'dice_' + this.steps, 36, 24, 110, 110);
        text(p, '前进 ' + this.steps + ' 格', 170, 22, SHEET_W - 190, 60, 40, Theme.c.navy, { bold: true, align: 'l' });
        text(p, '到达：' + st.tile((pos + this.steps) % n).name, 170, 82, SHEET_W - 190, 44, 28, Theme.c.payRed, { bold: true, align: 'l' });
        const box = mk(p, 'Steps', 24, 146, SHEET_W - 48, 120);
        fillRR(gfx(box), 0, 0, SHEET_W - 48, 120, 18, Theme.c.boxBeige);
        const cw = (SHEET_W - 48) / 3;
        for (let k = 1; k <= 6; k++) {
            const cx = ((k - 1) % 3) * cw;
            const cy = Math.floor((k - 1) / 3) * 60;
            const cell = mk(box, 'Step' + k, cx, cy, cw, 60);
            if (k === this.steps) {
                fillRR(gfx(cell), 6, 6, cw - 12, 48, 14, '#FFE38A');
                strokeRR(gfx(cell), 6, 6, cw - 12, 48, 14, Theme.c.yellowDark, 3);
            }
            text(cell, String(k), 14, 0, 36, 60, 28, Theme.c.payRed, { bold: true });
            text(cell, st.tile((pos + k) % n).name, 52, 0, cw - 60, 60, 24, Theme.c.navy, { bold: k === this.steps, align: 'l' });
            onTap(cell, () => {
                this.steps = k;
                this.rebuildBody();
            }, false);
        }
        this.buttons(p, 280, '确认移动', () => sendCard('FIXED_MOVE', null, this.steps));
    }
}

/** 设计稿 14：建造 / 降级 / 拆楼 / 清地 / 强制购房，针对当前格。 */
class PropertyCardSheet extends CardSheet {
    constructor(private readonly type: CardType) {
        super('card-' + type.toLowerCase(), 330);
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const h = here();
        const tile = st.tile(h.index);
        const level = h.prop ? h.prop.level : 0;
        const ownerName = h.owner ? h.owner.nickname : '';
        art(p, CARD_ART[this.type], 24, 22, 160, 190);
        art(p, 'icon_house', 200, 22, 52, 52);
        text(p, CARD_NAMES[this.type], 260, 18, SHEET_W - 280, 60, 40, Theme.c.navy, { bold: true, align: 'l' });
        const info = mk(p, 'Info', 200, 86, SHEET_W - 224, 126);
        fillRR(gfx(info), 0, 0, SHEET_W - 224, 126, 18, this.type === 'CLEAR_LAND' ? '#FDECEC' : Theme.c.boxBeige);
        const iw = SHEET_W - 224;
        const tier = tile.tier ?? 'LOW';
        let confirm = '确认使用';
        let note = '房屋保护可抵挡 · 已抵押不可用';
        switch (this.type) {
            case 'BUILD':
                confirm = '确认建造';
                note = '免费升一级 · 不能用在本回合刚买下的地';
                this.change(info, iw, tile.name, level, level + 1);
                text(info, '租金 ' + rentOf(tier, level) + ' → ' + rentOf(tier, Math.min(MAX_LEVEL, level + 1)), 0, 66, iw, 50, 28, Theme.c.navy, { bold: true });
                break;
            case 'DOWNGRADE':
                confirm = '使用降级';
                this.change(info, iw, tile.name, level, level - 1);
                text(info, '租金 ' + rentOf(tier, level) + ' → ' + rentOf(tier, Math.max(0, level - 1)), 0, 66, iw, 50, 28, Theme.c.navy, { bold: true });
                break;
            case 'DEMOLISH':
                confirm = '确认拆楼';
                this.change(info, iw, tile.name, level, 0);
                if (h.owner) avatar(info, 24, 70, 46, h.owner.avatar, ownerName);
                text(info, '所有者仍是 ' + ownerName, 80, 66, iw - 90, 54, 26, Theme.c.navy, { bold: true, align: 'l' });
                break;
            case 'CLEAR_LAND':
                confirm = '确认清地';
                text(info, '清除等级与所有权', 0, 8, iw, 54, 30, Theme.c.payRed, { bold: true });
                text(info, tile.name + ' 变为 无主', 0, 64, iw, 54, 28, Theme.c.navy, { bold: true });
                break;
            case 'FORCED_PURCHASE': {
                confirm = '确认强购';
                note = '原主 ' + ownerName + ' 可用拒绝购买抵挡 · 款归原主';
                const station = tile.type === 'STATION';
                const std = standardValue(station, tile.tier, h.prop && !station ? TIERS[tier].upgrade * h.prop.level : 0);
                text(info, '标准价值', 20, 6, 180, 54, 28, Theme.c.navy, { align: 'l' });
                text(info, String(std), iw - 200, 6, 180, 54, 32, Theme.c.navy, { bold: true, align: 'r' });
                text(info, '购买价', 20, 62, 180, 54, 28, Theme.c.navy, { align: 'l' });
                text(info, String(forcedPrice(h.index)), iw - 200, 62, 180, 54, 36, Theme.c.payRed, { bold: true, align: 'r' });
                break;
            }
            default:
                break;
        }
        const u = cardUsable(this.type);
        this.buttons(p, 222, confirm, () => sendCard(this.type), u.ok, u.reason);
        text(p, note, 0, 306, SHEET_W, 24, 18, Theme.c.noteGray);
    }

    /** "青麦路 2级 → 1级"。 */
    private change(parent: Node, w: number, name: string, from: number, to: number): void {
        inlineRow(parent, w / 2, 8, 54, [
            { t: name, size: 28 }, { t: from + '级', size: 32, color: '#2F86E8' }, { t: '→', size: 28 },
            { t: to + '级', size: 32, color: Theme.c.payRed },
        ], 10);
    }
}
