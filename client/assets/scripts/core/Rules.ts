/**
 * 规则常量与纯函数（无 cc 依赖，可用 node 直接断言）。数值取自 requirements.md / open-decisions.md，不得自创。
 * 取整：向上取整（open-decisions #1 / #12 的口径）。
 */
import { PropertyState, Standing, Tier } from './Models';
import { LUCKY_POOL, UNLUCKY_POOL } from './EventDraw';

export interface TierRule {
    name: string;
    price: number;
    upgrade: number;
    rent: [number, number, number, number]; // 未升级 / 一级 / 二级 / 三级
    mortgageRatio: number; // 应急抵押比例
}

export const TIERS: Record<Tier, TierRule> = {
    LOW: { name: '低价', price: 500, upgrade: 300, rent: [100, 250, 450, 700], mortgageRatio: 0.8 },
    MID: { name: '中价', price: 1000, upgrade: 600, rent: [200, 500, 900, 1400], mortgageRatio: 0.7 },
    HIGH: { name: '高价', price: 1500, upgrade: 900, rent: [300, 750, 1350, 2100], mortgageRatio: 0.6 },
};

export const STATION = { price: 1000, rentEach: 200, mortgageRatio: 0.7 };
// 下面三项与 TIERS / STATION 的价格、租金可由后台发布的参数覆盖（applyServerRules），默认值与内置配置一致
export let START_BONUS = 1000;
export const MAX_LEVEL = 3;
export const MAX_HAND = 6;
export let MINIGAME_REWARD = 500;
export let BAIL_COST = 500;

/** 幸运格奖池（内置默认见 EventDraw.LUCKY_POOL；联机按房间绑定的参数版本覆盖）。 */
type PoolItem = { kind: string; amount: number; label: string; weight: number; unlucky?: boolean };
let luckyOverride: PoolItem[] | null = null;

/** 幸运（默认）或不幸格的奖池，去掉概率为 0 的项。 */
export function luckyPool(unlucky = false): PoolItem[] {
    return (luckyOverride ?? ([...LUCKY_POOL, ...UNLUCKY_POOL] as PoolItem[])).filter((f) => !!f.unlucky === unlucky && f.weight > 0);
}

/** 后台参数里客户端显示要用的部分（GET /api/configs/{id}/client）。 */
export interface ServerRules {
    lucky?: { kind: string; amount: number; label: string; weight: number; unlucky?: boolean }[];
    tiers: { tier: Tier; basePrice: number; upgradeCost: number; rents: number[] }[];
    station: { price: number; rentPerStation: number };
    fees: { startReward: number; miniGameWinReward: number; bailCost: number };
}

/** 用房间绑定的参数版本覆盖价格、租金与固定费用（就地修改，已引用 TIERS / STATION 的地方随之生效）。 */
export function applyServerRules(r: ServerRules): void {
    for (const t of r.tiers) {
        const rule = TIERS[t.tier];
        if (!rule || t.rents.length !== 4) continue;
        rule.price = t.basePrice;
        rule.upgrade = t.upgradeCost;
        rule.rent = [t.rents[0], t.rents[1], t.rents[2], t.rents[3]];
    }
    STATION.price = r.station.price;
    STATION.rentEach = r.station.rentPerStation;
    START_BONUS = r.fees.startReward;
    MINIGAME_REWARD = r.fees.miniGameWinReward;
    BAIL_COST = r.fees.bailCost;
    if (r.lucky && r.lucky.length) luckyOverride = r.lucky.map((f) => ({ ...f }));
}
export const INITIAL_CASH_OPTIONS = [2000, 3000, 5000];
export const TIME_LIMIT_OPTIONS = [15, 30, 60];
export const ROLL_SECONDS_OPTIONS = [15, 30, 45, 60];

/** 弹窗/流程时长（秒） */
export const SECONDS = {
    buy: 15, upgrade: 15, rent: 10, trade: 15, auction: 20, auctionMax: 40, auctionTail: 3,
    debt1: 30, debtTotal: 60, discard: 15, tooth: 10, suspect: 15, offline: 30,
};

export function maxPlayers(boardSize: 30 | 50): number {
    return boardSize === 30 ? 4 : 8;
}

export function boardSizeOf(boardId: string): 30 | 50 {
    return boardId === 'classic-30' ? 30 : 50;
}

/** 标准价值 = 土地原价 + 当前保留累计升级费 × 50%（向上取整）。车站为原价。 */
export function standardValue(isStation: boolean, tier: Tier | undefined, upgradeSpent: number): number {
    if (isStation) return STATION.price;
    const t = TIERS[tier ?? 'LOW'];
    return t.price + Math.ceil(upgradeSpent * 0.5);
}

export function landPrice(isStation: boolean, tier: Tier | undefined): number {
    return isStation ? STATION.price : TIERS[tier ?? 'LOW'].price;
}

export function rentOf(tier: Tier, level: number): number {
    return TIERS[tier].rent[Math.max(0, Math.min(MAX_LEVEL, level))];
}

/** 车站租金 = 所有者持有未抵押车站数 × 200 */
export function stationRent(unmortgagedStations: number): number {
    return unmortgagedStations * STATION.rentEach;
}

/** 应急抵押可得金额（按土地原价 × 比例，非银行 100%） */
export function emergencyMortgage(isStation: boolean, tier: Tier | undefined): number {
    const price = landPrice(isStation, tier);
    const ratio = isStation ? STATION.mortgageRatio : TIERS[tier ?? 'LOW'].mortgageRatio;
    return Math.floor(price * ratio);
}

export function emergencyRatioLabel(isStation: boolean, tier: Tier | undefined): string {
    const ratio = isStation ? STATION.mortgageRatio : TIERS[tier ?? 'LOW'].mortgageRatio;
    return Math.round(ratio * 100) + '%';
}

export interface AuctionParams { start: number; cap: number; minRaise: number }

/** 拍卖参数：起拍=基准 50%，封顶（=一口价）=基准 2.5 倍，最低加价=基准 10%。 */
export function auctionParams(base: number): AuctionParams {
    return { start: Math.ceil(base * 0.5), cap: Math.ceil(base * 2.5), minRaise: Math.ceil(base * 0.1) };
}

/** 交易价格范围：标准价值 50%～2.5 倍（不允许免费赠送）。 */
export function tradeRange(base: number): { min: number; max: number } {
    return { min: Math.ceil(base * 0.5), max: Math.ceil(base * 2.5) };
}

export function clampTradePrice(base: number, price: number): number {
    const r = tradeRange(base);
    return Math.max(r.min, Math.min(r.max, price));
}

/** 欠款：已筹 = 已抵押所得之和，还差 = max(0, 欠款 - 现金 - 已筹)。 */
export function debtShortfall(amount: number, cash: number, raised: number): number {
    return Math.max(0, amount - cash - raised);
}

/** 净资产 = 现金 + 未抵押地产标准价值 + 未抵押车站原价 + 已抵押资产（标准价值 - 赎回本金） */
export function netWorth(
    cash: number,
    props: { isStation: boolean; tier?: Tier; p: PropertyState }[],
): number {
    let sum = cash;
    for (const { isStation, tier, p } of props) {
        const sv = standardValue(isStation, tier, p.upgradeSpent);
        sum += p.mortgaged ? sv - p.mortgagePaid : sv;
    }
    return sum;
}

/** 排名：净资产降序，同值比现金，仍同则并列；并列按 1、1、3 编号。破产者排在存活者之后（保持传入顺序）。 */
export function rankStandings(list: Omit<Standing, 'rank'>[]): Standing[] {
    const alive = list.filter((s) => !s.bankrupt);
    const out = list.filter((s) => s.bankrupt);
    alive.sort((a, b) => b.netWorth - a.netWorth || b.cash - a.cash);
    const res: Standing[] = [];
    let rank = 1;
    alive.forEach((s, i) => {
        if (i > 0) {
            const prev = alive[i - 1];
            if (prev.netWorth !== s.netWorth || prev.cash !== s.cash) rank = i + 1;
        }
        res.push({ ...s, rank });
    });
    out.forEach((s, i) => {
        // 破产者：越晚出局越靠前（传入顺序即出局先后的逆序），各占独立名次
        res.push({ ...s, rank: alive.length + i + 1 });
    });
    return res;
}

/** 虎口拔牙：牙齿数 = 参与人数 × 2 */
export function toothCount(participants: number): number {
    return participants * 2;
}

// ---------------- 昵称检查 ----------------
export const NICK_MAX = 10; // 按码点计，演示口径；"trim 与长度口径"待规则规格最终确定
export const NICK_MIN = 1;

export interface NickResult { ok: boolean; reason: string }

function isForbiddenInvisible(cp: number): boolean {
    return (
        (cp >= 0x200b && cp <= 0x200f) || // 零宽空格/非连接符/连接符/LRM/RLM（200D 另行判定）
        (cp >= 0x202a && cp <= 0x202e) || // 双向嵌入/覆盖
        (cp >= 0x2060 && cp <= 0x2064) || // word joiner 等
        (cp >= 0x2066 && cp <= 0x2069) || // 双向隔离
        cp === 0x061c || cp === 0xfeff || cp === 0x180e || cp === 0x00ad || cp === 0x034f
    );
}

function isEmojiLike(cp: number): boolean {
    return cp >= 0x1f000 || (cp >= 0x2190 && cp <= 0x2bff) || cp === 0xfe0f;
}

/** 昵称检查：拒绝零宽/双向控制符与纯空白；保留正常 emoji 序列中的 ZWJ（前后均为 emoji 时放行）。 */
export function checkNickname(raw: string): NickResult {
    const cps = Array.from(raw);
    if (cps.length === 0) return { ok: false, reason: '请输入昵称' };
    for (let i = 0; i < cps.length; i++) {
        const cp = cps[i].codePointAt(0) ?? 0;
        if (cp === 0x200d) {
            const prev = i > 0 ? (cps[i - 1].codePointAt(0) ?? 0) : 0;
            const next = i + 1 < cps.length ? (cps[i + 1].codePointAt(0) ?? 0) : 0;
            if (isEmojiLike(prev) && isEmojiLike(next)) continue;
            return { ok: false, reason: '昵称含不可见字符' };
        }
        if (isForbiddenInvisible(cp)) {
            const bidi = (cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069) || cp === 0x200e || cp === 0x200f || cp === 0x061c;
            return { ok: false, reason: bidi ? '昵称含方向控制符' : '昵称含零宽字符' };
        }
        if (cp < 0x20 || (cp >= 0x7f && cp <= 0x9f)) return { ok: false, reason: '昵称含控制字符' };
    }
    const visible = cps.filter((ch) => !/\s/.test(ch));
    if (visible.length === 0) return { ok: false, reason: '昵称不能全是空白' };
    const trimmed = raw.trim();
    if (Array.from(trimmed).length > NICK_MAX) return { ok: false, reason: '昵称最多 ' + NICK_MAX + ' 个字' };
    if (Array.from(trimmed).length < NICK_MIN) return { ok: false, reason: '请输入昵称' };
    return { ok: true, reason: '昵称可用' };
}

/** 手牌按种类合并（保持首次出现顺序），带数量。 */
export function groupCards(types: string[]): { type: string; count: number }[] {
    const out: { type: string; count: number }[] = [];
    for (const t of types) {
        const g = out.find((x) => x.type === t);
        if (g) g.count++;
        else out.push({ type: t, count: 1 });
    }
    return out;
}
