/**
 * 主动用卡（设计稿 13 / 14）：判断此刻能否用某张卡、发送 UseCard，以及棋盘底部的确认面板：
 * 路障（在当前位置放置）、定点移动（前进 1～6 格，列出各格地名）、建造 / 降级 / 拆楼 / 清地 / 强制购房（当前格前后对比）。
 * 查询的选人与结果见 QueryPopups。规则：自己的回合投骰前（狱中只能出狱、查询）或落点结算后的用卡阶段，两阶段共一次；
 * 目标格一律为当前位置；条件不满足的卡不消耗。演示模式只在本地扣掉手牌并做简单效果。
 */
import { EventTouch, Node } from 'cc';
import { BoardTile, CARD_NAMES, CardType, PlayerView, PropertyState } from '../core/Models';
import { MAX_LEVEL, rentOf, standardValue, stationRent, TIERS } from '../core/Rules';
import { textWidth, Theme } from '../core/Theme';
import { art, CARD_ART } from '../ui/Art';
import { primaryButton, softButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillCircle, fillRR, gfx, line, mk, onTap, place, strokeCircle, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { avatar } from '../ui/Widgets';
import { inlineRow, propertyArtKey } from './Common';
import { QueryTargetPopup } from './QueryPopups';
import { ScrollList } from '../ui/ScrollList';
import { boardMarks } from '../screens/board/BoardView';

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
    if (!g.myHand.some((c) => c.type === type)) return no('手里没有这张道具');
    if (type === 'AUCTION') return auctionUsable();
    if (type === 'TRADE') return tradeUsable();
    if (!st.isMyTurn()) return no('只能在自己的回合使用');
    if (me.control !== 'MANUAL' || me.conn === 'OFFLINE') return no('托管中不能用卡');
    const online = st.online;
    if (online) {
        const w = online.myWindow('TURN');
        if (!w || !online.isOpen(w)) return no('当前不是用卡时机');
        if (g.cards?.chanceUsed.includes(st.myId)) return no('本回合已经用过道具了');
    }
    const pre = g.stage === 'PRE_ROLL';
    const jail = g.stage === 'JAIL_DECISION';
    // 主动卡只在投骰前用（落点后没有用卡阶段，用户 2026-10-07 裁决）
    if (!pre && !jail) return no('投骰前才能用卡');
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

/** 拍卖卡：可非自己回合申请（排队到回合交界开拍），占用主动用卡机会；欠款处理中不能用；要有未抵押的地产或车站。 */
function auctionUsable(): Usable {
    const st = ctx.store;
    const g = st.game;
    const me = st.me();
    if (me.control !== 'MANUAL' || me.conn === 'OFFLINE') return no('托管中不能用卡');
    if (!st.online) return no('拍卖卡需联机使用');
    if (g.cards?.chanceUsed.includes(st.myId)) return no('本回合已经用过道具了');
    if (g.debt && g.debt.debtor === st.myId) return no('处理欠款时不能用拍卖卡');
    return st.assetsOf(st.myId).some((a) => !a.p.mortgaged) ? yes : no('没有未抵押的地产或车站');
}

/** 交易卡：可非自己回合申请（排队到回合交界开始），占用主动用卡机会；要有未抵押的资产和其他存活玩家。 */
function tradeUsable(): Usable {
    const st = ctx.store;
    const me = st.me();
    if (me.control !== 'MANUAL' || me.conn === 'OFFLINE') return no('托管中不能用卡');
    if (!st.online) return no('交易卡需联机使用');
    if (st.game.cards?.chanceUsed.includes(st.myId)) return no('本回合已经用过道具了');
    if (!st.game.players.some((p) => p.life === 'ALIVE' && p.playerId !== st.myId)) return no('没有可交易的玩家');
    return st.assetsOf(st.myId).some((a) => !a.p.mortgaged) ? yes : no('没有未抵押的地产或车站');
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
    if (type === 'AUCTION') {
        ctx.popups.open(new AuctionAssetPopup());
        return;
    }
    if (type === 'TRADE') {
        ctx.popups.open(new TradeSetupPopup());
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

/**
 * 设计稿 13 第二张：骰子插画 +"前进 N 格" + 目标地名；1～6 格地名列表（点选）；取消 / 确认移动。
 * 棋盘上前方 1～6 格同时标号高亮，点棋盘上的标号也能选（面板的遮罩接住点击，再交给棋盘判定点中哪一格）。
 */
class FixedMoveSheet extends CardSheet {
    private steps = 1;

    constructor() {
        super('card-fixed-move', 380);
    }

    mount(layer: Node): void {
        super.mount(layer);
        boardMarks.current = { from: ctx.store.me().position, selected: this.steps };
        boardMarks.redraw?.();
        boardMarks.reveal?.(1192 - this.ph);
        boardMarks.onToggle?.(true);
        const mask = this.root.getChildByName('Mask');
        mask?.on(Node.EventType.TOUCH_END, (e: EventTouch) => {
            const k = boardMarks.hit?.(e.getUILocation()) ?? 0;
            if (k > 0 && k !== this.steps) {
                this.steps = k;
                this.rebuildBody();
            }
        });
    }

    close(): void {
        if (!this.closed) boardMarks.clear();
        super.close();
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const pos = st.me().position;
        const n = st.game.tiles.length;
        if (boardMarks.current && boardMarks.current.selected !== this.steps) {
            boardMarks.current = { from: pos, selected: this.steps };
            boardMarks.redraw?.();
        }
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
                note = '仅限自己未抵押 · 未满级的地产 · 投骰前使用';
                {
                    // 设计稿 26：标题右侧"免费升一级"红字胶囊
                    const pill = mk(p, 'Free', SHEET_W - 230, 24, 206, 48);
                    fillRR(gfx(pill), 0, 0, 206, 48, 14, '#FDECEC');
                    text(pill, '免费升一级', 0, 0, 206, 48, 26, Theme.c.payRed, { bold: true });
                }
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

// ================================================================ 拍卖卡 / 交易卡：底部面板与资产行

/** 不随回合关闭的底部面板（拍卖卡、交易卡可在非自己回合申请）：位置同主动卡面板。 */
abstract class BottomSheet extends Popup {
    constructor(id: string, h: number) {
        super(id, '', SHEET_W, h, 0, false);
    }

    mount(layer: Node): void {
        super.mount(layer);
        place(this.panel, (Theme.W - SHEET_W) / 2, 1192 - this.ph);
    }

    protected buildTitle(): void {
        // 标题画在主体里
    }
}

interface AssetRow { tile: BoardTile; p: PropertyState; std: number }

/** 我的全部资产（含已抵押，已抵押的置灰不可选），按棋盘顺序。 */
function myAssets(): AssetRow[] {
    const st = ctx.store;
    return st.assetsOf(st.myId).map((a) => {
        const station = a.tile.type === 'STATION';
        return { tile: a.tile, p: a.p, std: standardValue(station, a.tile.tier, station ? 0 : TIERS[a.tile.tier ?? 'LOW'].upgrade * a.p.level) };
    });
}

/** 单选圆圈：选中为蓝底白勾。 */
function radio(parent: Node, x: number, y: number, on: boolean, disabled = false): void {
    const g = gfx(mk(parent, 'Radio', x, y, 36, 36));
    if (on) {
        fillCircle(g, 18, 18, 18, '#2F86E8');
        line(g, 10, 19, 16, 25, Theme.c.white, 4);
        line(g, 16, 25, 27, 12, Theme.c.white, 4);
    } else strokeCircle(g, 18, 18, 16, disabled ? '#C5CCD6' : '#AEB8C6', 3);
}

/** 小锁（已抵押）。 */
function lock(parent: Node, x: number, y: number): void {
    const g = gfx(mk(parent, 'Lock', x, y, 28, 32));
    strokeRR(g, 7, 0, 14, 18, 7, '#8C96A6', 3);
    fillRR(g, 2, 12, 24, 20, 5, '#8C96A6');
}

/**
 * 资产行（设计稿 27 / 28）：缩略图、地名 + 等级（灰），第二行标准价值等；右侧单选。已抵押：灰底、锁、"已抵押"，不可点。
 * extra 为第二行标准价值之后的内容（拍卖卡：起拍价 / 封顶价）。
 */
function assetRow(parent: Node, y: number, w: number, h: number, a: AssetRow, on: boolean, extra: boolean, onPick: () => void): void {
    const n = mk(parent, 'Asset' + a.tile.index, 0, y, w, h - 8);
    const g = gfx(n);
    const dead = a.p.mortgaged;
    fillRR(g, 0, 0, w, h - 8, 16, dead ? '#ECEFF3' : on ? '#EAF4FF' : Theme.c.white);
    strokeRR(g, 1, 1, w - 2, h - 10, 16, on ? '#2F86E8' : Theme.c.panelLine, on ? 4 : 2);
    if (dead) lock(n, 22, (h - 8) / 2 - 16);
    else art(n, propertyArtKey(a.tile), 8, 4, 84, h - 16);
    const lv = a.tile.type === 'STATION' ? '车站' : dead ? '已抵押' : a.p.level ? a.p.level + '级' : '未升级';
    const ink = dead ? '#8C96A6' : Theme.c.navy;
    text(n, a.tile.name, 104, 4, 140, (h - 8) / 2, 26, ink, { bold: true, align: 'l' });
    text(n, lv, 104 + textWidth(a.tile.name, 26) + 14, 4, 120, (h - 8) / 2, 20, '#8C96A6', { bold: true, align: 'l' });
    const sub = '标准价值 ' + a.std + (extra ? '  |  起拍价 ' + Math.ceil(a.std / 2) + '  |  封顶价 ' + Math.floor(a.std * 5 / 2) : '');
    text(n, sub, 104, (h - 8) / 2, w - 160, (h - 8) / 2 - 4, 20, dead ? '#9AA3B0' : Theme.c.noteGray, { align: 'l' });
    radio(n, w - 52, (h - 8) / 2 - 18, on, dead);
    if (!dead) onTap(n, onPick, false);
}

/** 标题：房子图标 + 文字（left = 左对齐，否则居中）。 */
function sheetTitle(p: Node, title: string, left: boolean): void {
    const tw = textWidth(title, 36);
    const x0 = left ? 24 : (SHEET_W - tw - 60) / 2;
    art(p, 'icon_house', x0, 18, 48, 48);
    text(p, title, x0 + 58, 12, tw + 10, 60, 36, Theme.c.navy, { bold: true, align: 'l' });
}

// ================================================================ 拍卖卡：选择要拍卖的资产

/**
 * 拍卖卡选资产（设计稿 27 第二张）：底部面板，标题"选择要拍卖的资产"，资产行显示标准价值 / 起拍价 / 封顶价，已抵押置灰；
 * "取消 / 申请拍卖"，底注"当前操作结束后开始 · 占用本回合用卡机会 · 卖家不能出价 · 成交款全部归你"。申请发送 RequestAuction。
 */
export class AuctionAssetPopup extends BottomSheet {
    private sel = -1;

    constructor() {
        super('auction-asset', 520);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const list = myAssets();
        sheetTitle(p, '选择要拍卖的资产', true);
        const rowH = 96;
        const sl = new ScrollList(p, 24, 84, w - 48, 3 * rowH);
        list.forEach((a, i) => assetRow(sl.content, i * rowH, w - 48, rowH, a, this.sel === a.tile.index, true, () => {
            this.sel = this.sel === a.tile.index ? -1 : a.tile.index;
            this.rebuildBody();
        }));
        sl.setContentHeight(list.length * rowH);
        softButton(p, '取消', 24, h - 124, 220, 80, () => this.close(), 32);
        const b = primaryButton(p, '申请拍卖', 264, h - 124, w - 288, 80, () => {
            const tile = this.sel;
            this.close();
            if (tile >= 0) void ctx.store.online?.act('RequestAuction', { tile });
        }, 34);
        b.setEnabled(this.sel >= 0, '请先选择要拍卖的资产');
        text(p, '当前操作结束后开始 · 占用本回合用卡机会 · 卖家不能出价 · 成交款全部归你', 0, h - 38, w, 28, 18, Theme.c.noteGray);
    }
}

// ================================================================ 交易卡：发起交易

/**
 * 发起交易（设计稿 28 第一、二张）：底部面板，① 选资产（我的资产，已抵押置灰）② 选买家（其他存活玩家）③ 定价（蓝色 −/+，每次 ±50，
 * 标准价值 50% 向上取整 ～ 2.5 倍向下取整，选中资产时默认标准价值）。没选全时显示红条"请先选择资产和买家"、"申请交易"置灰。
 * 申请发送 RequestTrade，排队到安全点开始。
 */
export class TradeSetupPopup extends BottomSheet {
    private sel = -1;
    private buyer = '';
    private price = 0;

    constructor() {
        super('trade-setup', TradeSetupPopup.height());
    }

    private static others(): PlayerView[] {
        const st = ctx.store;
        return st.game.players.filter((x) => x.life === 'ALIVE' && x.playerId !== st.myId).slice(0, 7);
    }

    private static height(): number {
        return 760 + (Math.ceil(TradeSetupPopup.others().length / 4) - 1) * 112;
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const list = myAssets();
        const others = TradeSetupPopup.others();
        sheetTitle(p, '发起交易', false);
        const step = (n: number, label: string, y: number) => {
            const c = gfx(mk(p, 'No' + n, 24, y + 4, 32, 32));
            fillCircle(c, 16, 16, 16, '#2F86E8');
            text(p, String(n), 24, y + 4, 32, 32, 20, Theme.c.white, { bold: true });
            text(p, label, 66, y, w - 90, 40, 24, Theme.c.navy, { bold: true, align: 'l' });
        };
        step(1, '选资产（我的资产）', 76);
        const rowH = 72;
        const sl = new ScrollList(p, 24, 120, w - 48, 3 * rowH);
        list.forEach((a, i) => assetRow(sl.content, i * rowH, w - 48, rowH, a, this.sel === a.tile.index, false, () => {
            this.sel = a.tile.index;
            this.price = a.std;
            this.rebuildBody();
        }));
        sl.setContentHeight(list.length * rowH);
        let y = 120 + 3 * rowH + 8;
        step(2, '选买家（其他玩家）', y);
        y += 44;
        const cw = (w - 48) / 4;
        others.forEach((pl, i) => {
            const n = mk(p, 'B' + i, 24 + (i % 4) * cw + 4, y + Math.floor(i / 4) * 112, cw - 8, 104);
            const g = gfx(n);
            const on = this.buyer === pl.playerId;
            fillRR(g, 0, 0, cw - 8, 104, 16, on ? '#EAF4FF' : Theme.c.white);
            strokeRR(g, 1, 1, cw - 10, 102, 16, on ? '#2F86E8' : Theme.c.panelLine, on ? 4 : 2);
            avatar(n, (cw - 8 - 64) / 2, 6, 64, pl.avatar, pl.nickname);
            text(n, pl.nickname, 0, 72, cw - 8, 28, 20, Theme.c.navy, { bold: true });
            onTap(n, () => {
                this.buyer = pl.playerId;
                this.rebuildBody();
            }, false);
        });
        y += Math.ceil(others.length / 4) * 112 + 4;
        step(3, '定价', y);
        const a = list.find((x) => x.tile.index === this.sel);
        const ok = !!a;
        const min = a ? Math.ceil(a.std / 2) : 0;
        const max = a ? Math.floor(a.std * 5 / 2) : 0;
        const sx = 150;
        const btn = (label: string, x: number, d: number) => {
            const b = mk(p, 'Step' + label, x, y, 48, 40);
            fillRR(gfx(b), 0, 0, 48, 40, 12, ok ? '#2F86E8' : '#C5CCD6');
            text(b, label, 0, 0, 48, 40, 32, Theme.c.white, { bold: true });
            onTap(b, () => {
                if (!ok) return;
                this.price = Math.max(min, Math.min(max, this.price + d * 50));
                this.rebuildBody();
            });
        };
        btn('−', sx, -1);
        const field = mk(p, 'Field', sx + 56, y, 180, 40);
        fillRR(gfx(field), 0, 0, 180, 40, 10, Theme.c.white);
        text(field, ok ? String(this.price) : '—', 0, 0, 180, 40, 30, Theme.c.navy, { bold: true });
        btn('+', sx + 244, 1);
        text(p, '每次 ±50', sx + 304, y, 140, 40, 20, Theme.c.noteGray, { align: 'l' });
        const range = a ? '可定价 ' + min + ' ～ ' + max + '（标准价值 50% ～ 2.5 倍）' : '可定价为标准价值的 50% ～ 2.5 倍';
        text(p, range, 0, y + 44, w, 28, 20, Theme.c.noteGray);
        y += 78;
        const ready = ok && !!this.buyer;
        if (!ready) {
            const bar = mk(p, 'Err', 24, y, w - 48, 36);
            fillRR(gfx(bar), 0, 0, w - 48, 36, 10, '#FDE2E2');
            const ic = gfx(mk(bar, 'I', 12, 6, 24, 24));
            fillCircle(ic, 12, 12, 12, Theme.c.payRed);
            text(bar, '!', 12, 6, 24, 24, 18, Theme.c.white, { bold: true });
            text(bar, !ok && !this.buyer ? '请先选择资产和买家' : !ok ? '请先选择资产' : '请先选择买家', 46, 0, w - 100, 36, 20, Theme.c.payRed,
                { bold: true, align: 'l' });
        }
        softButton(p, '取消', 24, h - 116, 220, 76, () => this.close(), 32);
        const b = primaryButton(p, '申请交易', 264, h - 116, w - 288, 76, () => {
            const tile = this.sel;
            const buyer = this.buyer;
            const price = this.price;
            this.close();
            if (tile >= 0) void ctx.store.online?.act('RequestTrade', { tile, buyer, price });
        }, 34);
        b.setEnabled(ready, !ok ? '请先选择资产' : '请选择买家');
        text(p, '买家 15 秒内同意且现金足额才成交 · 拒绝或超时保留交易卡', 0, h - 36, w, 26, 18, Theme.c.noteGray);
    }
}
