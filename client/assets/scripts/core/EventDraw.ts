/**
 * 事件卡牌堆抽卡状态机（纯 TS，无 cc 依赖，SelfCheck 直接断言）。
 * 设计依据：design/screens/10-event-card-preview.png（走到事件格时，仅触发玩家自己的界面中央显示问号卡背，点击卡片翻开，
 * 无独立"抽取事件"按钮）、11-event-idle-preview.png（默认状态：左上角三张扇形问号卡牌堆）。
 *
 * 状态：IDLE → WAITING(等待点击) → FLIPPING(翻牌中) → RESULT(结果展示) → IDLE。
 * 规则：只有触发者(actor)能点击/确认；他人只看到默认状态与轻提示；重复点击不重复抽取、不重复结算。
 *
 * 事件概率与数值取自 requirements.md 第 12/14 节：现金奖励 30% / 现金罚款 25% / 道具 25% / 位置移动 15% / 入狱 5%；
 * 奖励/罚款 100～500 按 50 的倍数等概率；位置移动前后各半、距离 1～3 等概率；道具按 14 种道具概率。
 * 待确认（规则未写）：WAITING 超时是否由系统代抽（此处按 autoMs 代抽，演示取 15 秒，沿用其他选择窗口的 15 秒口径）。
 * 事件卡"卡面"设计图未画，UI 只显示中性结果面板（卡面待用户补设计）。
 */
import { CardType } from './Models';

export type EventDrawPhase = 'IDLE' | 'WAITING' | 'FLIPPING' | 'RESULT';
export type EventKind = 'CASH_REWARD' | 'CASH_FINE' | 'CARD' | 'MOVE' | 'JAIL'
    | 'BUILD' | 'DOWNGRADE' | 'TO_STATION' | 'TO_START';

export interface EventResult {
    kind: EventKind;
    /** 奖励/罚款金额（其他种类为 0） */
    amount: number;
    /** 道具种类（仅 CARD） */
    card: CardType | null;
    /** 位移格数：前进为正、后退为负（仅 MOVE，绝对值 1～3） */
    steps: number;
    /** 选描述文案用的种子（联机为落点编号，所有人一致）；演示为 0 */
    seed?: number;
    /** 幸运卡的名字（如"好人好事"），有则作为描述文案；抽卡事件为空 */
    label?: string;
    /** 幸运格抽到的（红色幸运卡，自动翻开） */
    lucky?: boolean;
}

/** 奖励 / 罚款的事件描述（用户 2026-10-08：不能只写加减，要有缘由）。按种子挑一条，所有客户端相同。 */
export const REWARD_STORIES = [
    '扶老奶奶过马路，好心有好报', '捡到钱包交还失主，获得酬谢', '参加社区义务植树，获得奖励', '小镇征文比赛获奖',
    '帮邻居找回走失的小狗', '街头义演收到打赏', '买彩票中了小奖', '帮面包店搬货，老板发了红包',
];
export const FINE_STORIES = [
    '随地吐痰，被罚款', '闯红灯被交警拦下', '乱扔垃圾被城管发现', '深夜喧哗被邻居投诉',
    '违章停车被贴罚单', '在公园踩踏草坪', '图书馆的书逾期未还', '不小心打碎了商店的花瓶',
];

export function eventStory(r: EventResult): string {
    if (r.label) return r.label;
    const list = r.kind === 'CASH_REWARD' ? REWARD_STORIES : r.kind === 'CASH_FINE' ? FINE_STORIES : null;
    if (!list) return '';
    const i = Math.abs(((r.seed ?? 0) * 31 + r.amount / 10) | 0) % list.length;
    return list[i];
}

/** 幸运卡的效果短说明（格子详情页列出奖池用）。 */
export function luckyEffectText(f: { kind: string; amount: number; label: string }): string {
    switch (f.kind) {
        case 'CASH_REWARD': return f.label + '：奖励 ' + f.amount + ' 金币';
        case 'CASH_FINE': return f.label + '：罚款 ' + f.amount + ' 金币';
        case 'BUILD': return f.label + '：自己随机一处房产免费加盖一级';
        case 'DOWNGRADE': return f.label + '：自己随机一处房产降一级';
        case 'TO_STATION': return f.label + '：前进到随机一个车站';
        case 'TO_START': return f.label + '：前进回到起点，领起点奖励';
        default: return f.label;
    }
}

/** 内置幸运奖池（与服务端 RuleConfigs.LUCKY 一致；联机以后台发布的参数为准）。 */
export const LUCKY_POOL = [
    { kind: 'CASH_REWARD', amount: 300, label: '好人好事', weight: 25 },
    { kind: 'BUILD', amount: 0, label: '免费加盖', weight: 25 },
    { kind: 'TO_STATION', amount: 0, label: '搭乘快车', weight: 25 },
    { kind: 'TO_START', amount: 0, label: '回到起点', weight: 25 },
];

export interface EventDrawState {
    phase: EventDrawPhase;
    actor: string | null;
    /** 进入当前阶段的时间戳(ms) */
    since: number;
    result: EventResult | null;
    /** 已结算次数（每次抽卡只会 +1） */
    settled: number;
}

export interface EventTiming { flipMs: number; autoMs: number }

export const EVENT_IDLE: EventDrawState = { phase: 'IDLE', actor: null, since: 0, result: null, settled: 0 };

export const EVENT_KIND_LABEL: Record<EventKind, string> = {
    CASH_REWARD: '现金奖励', CASH_FINE: '现金罚款', CARD: '获得道具', MOVE: '位置移动', JAIL: '入狱',
    BUILD: '免费加盖', DOWNGRADE: '房屋降级', TO_STATION: '前往车站', TO_START: '回到起点',
};

/** 事件类别概率（累计阈值，总和 100%）。 */
export const EVENT_KIND_ODDS: [EventKind, number][] = [
    ['CASH_REWARD', 0.3], ['CASH_FINE', 0.25], ['CARD', 0.25], ['MOVE', 0.15], ['JAIL', 0.05],
];

/** 14 种道具概率（百分比，总和 100）。 */
export const CARD_ODDS: [CardType, number][] = [
    ['ROADBLOCK', 12], ['RENT_WAIVER', 12], ['BUILD', 12], ['DOWNGRADE', 12], ['FIXED_MOVE', 12], ['JAIL_RELEASE', 10], ['AUCTION', 5],
    ['TRADE', 5], ['REFUSE_PURCHASE', 5], ['HOUSE_PROTECTION', 5], ['QUERY', 5], ['FORCED_PURCHASE', 2], ['DEMOLISH', 1.5], ['CLEAR_LAND', 1.5],
];

/** 抽取事件结果。rng 返回 [0,1)；每次调用按固定顺序取 1～3 个随机数（便于测试注入）。 */
export function rollEvent(rng: () => number): EventResult {
    const r = rng();
    let acc = 0;
    let kind: EventKind = 'JAIL';
    for (const [k, p] of EVENT_KIND_ODDS) {
        acc += p;
        if (r < acc - 1e-9) {
            kind = k;
            break;
        }
    }
    const res: EventResult = { kind, amount: 0, card: null, steps: 0 };
    if (kind === 'CASH_REWARD' || kind === 'CASH_FINE') {
        res.amount = 100 + 50 * Math.min(8, Math.floor(rng() * 9));
    } else if (kind === 'CARD') {
        const c = rng() * 100;
        let a = 0;
        res.card = CARD_ODDS[CARD_ODDS.length - 1][0];
        for (const [t, p] of CARD_ODDS) {
            a += p;
            if (c < a - 1e-9) {
                res.card = t;
                break;
            }
        }
    } else if (kind === 'MOVE') {
        const dist = 1 + Math.min(2, Math.floor(rng() * 3));
        res.steps = rng() < 0.5 ? dist : -dist;
    }
    return res;
}

export type EventViewMode = 'DEFAULT' | 'MY_DRAW' | 'OTHER_DRAWING';

/** 某个观察者看到的状态：只有触发者本人看到抽卡界面；他人保持默认并显示轻提示。 */
export function eventViewMode(s: EventDrawState, viewer: string): EventViewMode {
    if (s.phase === 'IDLE' || s.actor === null) return 'DEFAULT';
    return s.actor === viewer ? 'MY_DRAW' : 'OTHER_DRAWING';
}

export function canClickCard(s: EventDrawState, viewer: string): boolean {
    return s.phase === 'WAITING' && s.actor === viewer;
}

/** 走到事件格：仅 IDLE 时可触发（已有抽卡进行中则忽略）。 */
export function triggerEvent(s: EventDrawState, actor: string, now: number): EventDrawState {
    if (s.phase !== 'IDLE') return s;
    return { phase: 'WAITING', actor, since: now, result: null, settled: s.settled };
}

/** 点击卡片：仅触发者在 WAITING 时有效；重复点击（已在翻牌/结果）被忽略，不会再次抽取。 */
export function clickCard(s: EventDrawState, viewer: string, now: number, rng: () => number): { state: EventDrawState; accepted: boolean } {
    if (!canClickCard(s, viewer)) return { state: s, accepted: false };
    return { state: { ...s, phase: 'FLIPPING', since: now, result: rollEvent(rng) }, accepted: true };
}

/**
 * 推进时间：WAITING 超过 autoMs 由系统代抽；FLIPPING 满 flipMs 进入 RESULT 并结算（settle 仅在这一次转移里返回）。
 * RESULT/IDLE 下重复调用不会再次结算。
 */
export function advanceEvent(s: EventDrawState, now: number, t: EventTiming, rng: () => number): { state: EventDrawState; settle: EventResult | null } {
    if (s.phase === 'WAITING' && s.actor !== null && now - s.since >= t.autoMs) {
        return { state: { ...s, phase: 'FLIPPING', since: now, result: rollEvent(rng) }, settle: null };
    }
    if (s.phase === 'FLIPPING' && now - s.since >= t.flipMs) {
        return { state: { ...s, phase: 'RESULT', since: now, settled: s.settled + 1 }, settle: s.result };
    }
    return { state: s, settle: null };
}

/** 确认结果：仅触发者可关闭（他人界面由系统在展示时长后关闭），回到 IDLE。 */
export function closeResult(s: EventDrawState, viewer: string | null): EventDrawState {
    if (s.phase !== 'RESULT') return s;
    if (viewer !== null && viewer !== s.actor) return s;
    return { ...EVENT_IDLE, settled: s.settled };
}

/** 中性结果文字（不含具体图案；卡面待用户补设计）。 */
export function eventResultText(r: EventResult): { title: string; detail: string } {
    switch (r.kind) {
        case 'CASH_REWARD': return { title: EVENT_KIND_LABEL.CASH_REWARD, detail: '获得 ' + r.amount };
        case 'CASH_FINE': return { title: EVENT_KIND_LABEL.CASH_FINE, detail: '罚款 ' + r.amount };
        case 'CARD': return { title: EVENT_KIND_LABEL.CARD, detail: '获得一张道具' };
        case 'MOVE': return { title: EVENT_KIND_LABEL.MOVE, detail: (r.steps > 0 ? '前进 ' : '后退 ') + Math.abs(r.steps) + ' 格' };
        case 'BUILD': return { title: EVENT_KIND_LABEL.BUILD, detail: '随机一处房产免费加盖一级' };
        case 'DOWNGRADE': return { title: EVENT_KIND_LABEL.DOWNGRADE, detail: '随机一处房产降一级' };
        case 'TO_STATION': return { title: EVENT_KIND_LABEL.TO_STATION, detail: '前往随机一个车站' };
        case 'TO_START': return { title: EVENT_KIND_LABEL.TO_START, detail: '回到起点' };
        default: return { title: EVENT_KIND_LABEL.JAIL, detail: '被送入监狱' };
    }
}
