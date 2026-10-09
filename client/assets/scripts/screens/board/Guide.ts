/**
 * 新手引导（用户 2026-10-08）：前几局在棋盘页按当时的情形弹一个提示气泡（看棋盘、投骰、抽事件、道具、监狱、同组地产、租金上涨），
 * 每条只出现一次；点"知道了"记下，点"不再提示"全部跳过。已看过的记在本机（微信小游戏与浏览器都用 localStorage）。
 */
import { Node, sys } from 'cc';
import { GameView, PlayerView } from '../../core/Models';
import { EventDrawState } from '../../core/EventDraw';
import { groupOf } from '../../core/SetBonus';
import { Theme } from '../../core/Theme';
import { fillRR, gfx, mk, onTap, text } from '../../ui/Kit';

const KEY = 'millionnaire.guide.v1';

export interface GuideStep {
    id: string;
    title: string;
    body: string;
    /** 气泡的 y（设计坐标）；默认在棋盘下半部、手牌栏上方，不挡中央的回合提示与事件卡 */
    y?: number;
}

const STEPS: Record<string, GuideStep> = {
    board: {
        id: 'board', title: '欢迎来到大富翁小镇',
        body: '顶部是玩家和现金，下方是你的手牌。点左下角自己的头像，可以查看「游戏规则」和「对局记录」。',
    },
    roll: { id: 'roll', title: '轮到你了', body: '点棋盘中间的骰子投骰，棋子按点数前进。停在无主地产可以买下，别人停下就要向你付租金。' },
    event: { id: 'event', title: '事件格', body: '点中间的卡片翻开事件：可能是奖励、罚款、道具、移动……看看运气吧！' },
    hand: { id: 'hand', title: '你有道具了', body: '点底部的手牌可以查看说明。主动卡在自己回合投骰前使用，每回合一次。' },
    jail: { id: 'jail', title: '被关进监狱', body: '每回合掷骰，偶数出狱；也可以支付保释金或使用出狱卡。关押期间照常收租。' },
    set: {
        id: 'set', title: '同组地产',
        body: '格子顶部颜色相同的地产是一组。把一整组都买下（且不抵押），组内每块地的租金都会上涨。',
    },
    rise: { id: 'rise', title: '物价上涨', body: '破产模式下租金会随轮数上涨，页眉显示当前倍率。越往后收租越多，记得留好现金！' },
};

function load(): string[] {
    try {
        const raw = sys.localStorage.getItem(KEY);
        const v = raw ? JSON.parse(raw) : [];
        return Array.isArray(v) ? v.map(String) : [];
    } catch {
        return [];
    }
}

function save(seen: string[]): void {
    try {
        sys.localStorage.setItem(KEY, JSON.stringify(seen));
    } catch {
        // 存不了就只在本次运行内记住
    }
}

let seen: string[] | null = null;

function seenList(): string[] {
    if (!seen) seen = load();
    return seen;
}

export function markSeen(id: string): void {
    const s = seenList();
    if (s.indexOf(id) < 0) s.push(id);
    save(s);
}

export function skipAll(): void {
    seen = Object.keys(STEPS).concat('off');
    save(seen);
}

/** 当前该显示的提示（没有则 null）。按此刻的情形挑第一条没看过的。 */
export function currentStep(game: GameView, me: PlayerView | undefined, myId: string, ev: EventDrawState, spectator: boolean): GuideStep | null {
    const s = seenList();
    if (spectator || !me || s.indexOf('off') >= 0) return null;
    const myTurn = game.currentPlayer === myId;
    const ownsGrouped = game.properties.some((p) => p.owner === myId && groupOf(game, p.tileIndex) > 0);
    const order: [string, boolean][] = [
        ['board', true],
        ['event', ev.phase === 'WAITING' && ev.actor === myId],
        ['jail', me.inJail],
        ['roll', myTurn && game.stage === 'PRE_ROLL'],
        ['hand', game.myHand.length > 0],
        ['set', ownsGrouped],
        ['rise', (game.rentPercent ?? 100) > 100],
    ];
    for (const [id, when] of order) {
        if (when && s.indexOf(id) < 0) return STEPS[id];
    }
    return null;
}

/** 画提示气泡；点"知道了"/"不再提示"后调用 done 让页面重建。 */
export function drawGuide(parent: Node, step: GuideStep, done: () => void): void {
    const w = 600;
    const h = 196;
    const x = 24;
    const y = step.y ?? 820;
    const n = mk(parent, 'Guide', x + 36, y, w, h);
    const g = gfx(n);
    fillRR(g, 0, 4, w, h, 22, '#00000033');
    fillRR(g, 0, 0, w, h, 22, '#1F3B66F2');
    text(n, '新手提示 · ' + step.title, 24, 12, w - 48, 40, 25, '#FFD86B', { bold: true, align: 'l' });
    text(n, step.body, 24, 52, w - 48, 86, 22, Theme.c.white, { align: 'l', wrap: true, lineHeight: 29 });
    const skip = mk(n, 'GuideSkip', 24, h - 52, 160, 44);
    text(skip, '不再提示', 0, 0, 160, 44, 21, '#AFC3E0', { align: 'l' });
    onTap(skip, () => {
        skipAll();
        done();
    }, false);
    const ok = mk(n, 'GuideOk', w - 184, h - 56, 160, 46);
    fillRR(gfx(ok), 0, 0, 160, 46, 23, '#FFD86B');
    text(ok, '知道了', 0, 0, 160, 46, 23, '#1F3B66', { bold: true });
    onTap(ok, () => {
        markSeen(step.id);
        done();
    }, false);
}
