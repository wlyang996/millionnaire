/**
 * 对局记录（用户 2026-10-08）：把服务端推送的对局事件翻成一句话，棋盘页"记录"面板按时间倒序列出。
 * 纯 TS，不依赖 cc。只记对玩家有意义的结果（买地、租金、事件、道具、抵押、拍卖、交易、入狱、破产等），
 * 窗口、计时、落点步骤之类的内部事件不记。记录只保存在本机内存里，重连或重开小程序后从当时开始记。
 */
import { CARD_NAMES, CardType } from '../core/Models';
import { EVENT_KIND_LABEL, EventKind } from '../core/EventDraw';
import { SEvent } from './Protocol';

export interface LogLine {
    /** 本局内递增的编号 */
    seq: number;
    text: string;
    /** 回合分隔行（"第 N 回合 · 某某"），面板里画成小标题 */
    header?: boolean;
    /** 和我有关（我付钱 / 我收钱 / 我出局…），面板里加粗 */
    mine?: boolean;
}

export interface LogNames {
    myId: string;
    name(playerId: unknown): string;
    tile(index: unknown): string;
}

/** 一局最多保留的条数（超出丢最早的）。 */
export const LOG_LIMIT = 300;

const card = (c: unknown): string => CARD_NAMES[String(c) as CardType] ?? String(c);
const money = (v: unknown): string => String(Number(v ?? 0));

/** 一个事件对应的记录（不需要记的返回 null）。 */
export function describeEvent(e: SEvent, n: LogNames): { text: string; header?: boolean; who?: string[] } | null {
    const d = e.data ?? {};
    const who = (id: unknown) => n.name(id);
    switch (e.kind) {
        case 'GameStarted':
            return { text: '对局开始', header: true };
        case 'TurnStarted':
            return { text: '第 ' + Number(d.turnNo) + ' 回合 · ' + who(d.playerId), header: true, who: [String(d.playerId)] };
        case 'DiceRolled':
            return { text: who(d.playerId) + '投出 ' + Number(d.value) + ' 点' + (d.auto ? '（自动）' : ''), who: [String(d.playerId)] };
        case 'StartRewardPaid':
            return { text: who(d.playerId) + '经过起点，获得 ' + money(d.amount), who: [String(d.playerId)] };
        case 'PropertyBought':
            return { text: who(d.playerId) + '以 ' + money(d.price) + ' 买下' + n.tile(d.tile), who: [String(d.playerId)] };
        case 'PropertyUpgraded':
            return { text: who(d.playerId) + '花 ' + money(d.cost) + ' 把' + n.tile(d.tile) + '升到 ' + Number(d.level) + ' 级', who: [String(d.playerId)] };
        case 'RentPaid':
            return { text: who(d.payer) + '向' + who(d.owner) + '支付' + n.tile(d.tile) + '的租金 ' + money(d.amount), who: [String(d.payer), String(d.owner)] };
        case 'RentWaived':
            return { text: who(d.payer) + '用免租卡免付' + n.tile(d.tile) + '的租金', who: [String(d.payer), String(d.owner)] };
        case 'EventDrawn': {
            const label = EVENT_KIND_LABEL[String(d.kind) as EventKind] ?? String(d.kind);
            const amt = d.kind === 'CASH_REWARD' || d.kind === 'CASH_FINE' ? ' ' + money(d.amount) : '';
            return { text: who(d.playerId) + '抽到事件：' + label + amt, who: [String(d.playerId)] };
        }
        case 'FixedEventTriggered': {
            const label = EVENT_KIND_LABEL[String(d.kind) as EventKind] ?? String(d.kind);
            const amt = d.kind === 'CASH_REWARD' || d.kind === 'CASH_FINE' ? ' ' + money(d.amount) : '';
            return { text: who(d.playerId) + '踩到特殊格：' + label + amt, who: [String(d.playerId)] };
        }
        case 'StartPickDrawn':
            return {
                text: who(d.playerId) + '在起点三选一' + (d.auto ? '（自动）' : '') + '：'
                    + (d.kind === 'CASH_REWARD' ? '现金 ' + money(d.amount) : '道具'),
                who: [String(d.playerId)],
            };
        case 'EventRewardPaid':
            return { text: who(d.playerId) + '获得事件奖励 ' + money(d.amount), who: [String(d.playerId)] };
        case 'FeePaid': {
            const s = (d.source ?? {}) as Record<string, unknown>;
            if (s.kind === 'RENT') return null; // 租金另有 RentPaid
            return { text: who(d.payer) + '支付罚款 ' + money(s.amount), who: [String(d.payer)] };
        }
        case 'EventPropertyChanged':
            return Number(d.tile) < 0 ? null
                : { text: who(d.playerId) + '的' + n.tile(d.tile) + '变为 ' + Number(d.level) + ' 级', who: [String(d.playerId)] };
        case 'EventHandCount':
            return d.discarded ? { text: who(d.playerId) + '手牌已满，弃掉一张', who: [String(d.playerId)] }
                : { text: who(d.playerId) + '获得一张道具（共 ' + Number(d.handCount) + ' 张）', who: [String(d.playerId)] };
        case 'CardUsed':
            if (!d.active) return null;
            return {
                text: who(d.playerId) + '使用了' + card(d.card) + (d.target ? '（对' + who(d.target) + '）' : '')
                    + (Number(d.tile) >= 0 && d.tile !== undefined && d.tile !== null ? ' · ' + n.tile(d.tile) : ''),
                who: [String(d.playerId), String(d.target ?? '')],
            };
        case 'AttackBlocked':
            return { text: who(d.owner) + '用' + card(d.response) + '挡住了' + who(d.attacker) + '的' + card(d.attack), who: [String(d.owner), String(d.attacker)] };
        case 'PropertyBuilt':
            return { text: who(d.playerId) + '用建造卡把' + n.tile(d.tile) + '升到 ' + Number(d.level) + ' 级', who: [String(d.playerId)] };
        case 'PropertyDowngraded':
            return { text: who(d.attacker) + '把' + who(d.owner) + '的' + n.tile(d.tile) + '降到 ' + Number(d.level) + ' 级', who: [String(d.attacker), String(d.owner)] };
        case 'PropertyDemolished':
            return { text: who(d.attacker) + '拆光了' + who(d.owner) + '在' + n.tile(d.tile) + '的楼', who: [String(d.attacker), String(d.owner)] };
        case 'PropertyCleared':
            return { text: who(d.attacker) + '清掉了' + who(d.owner) + '的' + n.tile(d.tile), who: [String(d.attacker), String(d.owner)] };
        case 'PropertyForceBought':
            return { text: who(d.buyer) + '以 ' + money(d.price) + ' 强制买下' + who(d.owner) + '的' + n.tile(d.tile), who: [String(d.buyer), String(d.owner)] };
        case 'AssetMortgaged':
            return { text: who(d.playerId) + (d.emergency ? '应急' : '') + '抵押' + n.tile(d.tile) + '，得到 ' + money(d.principal), who: [String(d.playerId)] };
        case 'AssetRedeemed':
            return { text: who(d.playerId) + '赎回' + n.tile(d.tile) + '，花费 ' + (Number(d.principal) + Number(d.fee ?? 0)), who: [String(d.playerId)] };
        case 'AuctionSettled':
            return { text: who(d.winner) + '以 ' + money(d.price) + ' 拍下' + n.tile(d.tile), who: [String(d.winner)] };
        case 'AuctionPassed':
            return { text: n.tile(d.tile) + '流拍' };
        case 'TradeCompleted':
            return { text: who(d.buyer) + '以 ' + money(d.price) + ' 从' + who(d.seller) + '手里买下' + n.tile(d.tile), who: [String(d.buyer), String(d.seller)] };
        case 'TradeDeclined':
            return { text: who(d.buyer) + (d.auto ? '没有回应' : '拒绝了') + n.tile(d.tile) + '的交易', who: [String(d.buyer)] };
        case 'MinigameEnded':
            return { text: '虎口拔牙结束：' + who(d.loser) + '拔到了危险牙' + (Number(d.reward) > 0 ? '，其余玩家各得 ' + money(d.reward) : ''), who: [String(d.loser)] };
        case 'PlayerJailed':
            return { text: who(d.playerId) + '进了监狱', who: [String(d.playerId)] };
        case 'BailPaid':
            return { text: who(d.playerId) + '支付 ' + money(d.amount) + ' 保释', who: [String(d.playerId)] };
        case 'JailReleased':
            return { text: who(d.playerId) + '出狱', who: [String(d.playerId)] };
        case 'PlayerEliminated':
            return { text: who(d.playerId) + (d.life === 'SURRENDERED' ? '认输出局' : '破产出局'), who: [String(d.playerId)] };
        case 'GameEnded':
            return { text: '对局结束', header: true };
        default:
            return null;
    }
}

/** 把一批事件追加到记录（就地修改，超过上限丢最早的）。返回是否有新增。 */
export function appendLog(log: LogLine[], events: SEvent[], n: LogNames, limit = LOG_LIMIT): boolean {
    let added = false;
    let seq = log.length ? log[log.length - 1].seq : 0;
    for (const e of events) {
        const r = describeEvent(e, n);
        if (!r) continue;
        log.push({ seq: ++seq, text: r.text, header: r.header, mine: !!r.who && r.who.indexOf(n.myId) >= 0 });
        added = true;
    }
    if (log.length > limit) log.splice(0, log.length - limit);
    return added;
}
