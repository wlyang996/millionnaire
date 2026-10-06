/**
 * 演示数据仓库：不连后端，用可切换的"场景"生成 SessionView / GameView 等。
 * 接入后端时，用 WebSocket 推送的 SessionView 替换 `session` 并调用 emit() 即可，UI 只读本类的数据。
 */
import { buildBoard } from './BoardLayout';
import { Clock } from './Clock';
import { remainingSecFromMs } from './MatchClock';
import { advanceEvent, clickCard, closeResult, EVENT_IDLE, EventDrawState, EventResult, triggerEvent } from './EventDraw';
import { Theme } from './Theme';
import type { OnlineSession } from '../net/OnlineSession';
import {
    AuctionView, BoardTile, Card, CardType, ChatLine, ConnState, ControlMode, DebtView, GameResult, GameView,
    HistoryEntry, Member, PlayerView, Profile, PropertyState, RoomSettings, SessionView,
} from './Models';
import {
    auctionParams, boardSizeOf, emergencyMortgage, landPrice, maxPlayers, netWorth, rankStandings, standardValue, START_BONUS,
} from './Rules';

export const NAMES = ['糖糖', '可可', '阿杰', '奶茶', '阿凯', '圆圆', '豆豆', '毛毛'];
export const ME = 'p1';

/** 事件卡结果默认展示时长（可手动关闭）。 */
export const EVENT_RESULT_SHOW_MS = 5000;

export type ConnPreset = 'normal' | 'mixed' | 'mySuspect';

/** 演示场景开关（演示面板修改它，store 据此重建数据） */
export interface Scenario {
    players: number; // 2..8
    boardSize: 30 | 50;
    turn: 'me' | 'other';
    spectator: boolean; // 我已破产，观战
    conn: ConnPreset;
    host: boolean; // 我是房主
    handCount: number; // 我的手牌张数 0..7
}

type Listener = () => void;

export class MockStore {
    /** 时间源：演示为本机时间；联机后换成服务器时间（倒计时按服务端截止时刻显示）。 */
    nowSource: () => number = () => Date.now();
    readonly clock = new Clock(() => this.nowSource());
    /** 我的 playerId：演示为 'p1'；联机登录后为服务端 userId。 */
    myId: string = ME;
    /** 联机会话；为 null 时是演示模式（本类的数据全部本地生成）。 */
    online: OnlineSession | null = null;
    profile: Profile = { nickname: '', avatar: 0, loggedIn: false };
    scenario: Scenario = { players: 8, boardSize: 50, turn: 'me', spectator: false, conn: 'mixed', host: true, handCount: 6 };
    session!: SessionView;
    history: HistoryEntry[] = [];
    auction!: AuctionView;
    debt!: DebtView;
    /** 房间里 3 名成员的语音/聊天片段 */
    roomChat: ChatLine[] = [{ from: '可可', text: '大家准备好了吗？' }];
    /** 演示：对手回合自动演进、我方超时自动投骰（默认关，避免评审时画面自己变化） */
    autoplay = false;
    /** 演示：固定"本局剩余时间"（秒）；null 表示按 globalEndsAt 实时计算 */
    matchOverrideSec: number | null = null;
    /** 事件卡抽卡状态机（core/EventDraw.ts）；动画/超时用 Date.now()（不受演示暂停影响） */
    eventDraw: EventDrawState = EVENT_IDLE;
    private listeners: Listener[] = [];

    constructor() {
        this.history = makeHistory();
        this.rebuild();
    }

    onChange(fn: Listener): void {
        this.listeners.push(fn);
    }

    emit(): void {
        for (const l of this.listeners) l();
    }

    // ---------- 场景 ----------
    patchScenario(p: Partial<Scenario>): void {
        if (this.online) return; // 联机时数据只来自服务端
        const next = { ...this.scenario, ...p };
        if (next.boardSize === 30 && next.players > maxPlayers(30)) {
            if (p.boardSize === 30) next.players = 4; // 切到 30 格：人数收敛
            else next.boardSize = 50; // 先要 >4 人：地图保持 50
        }
        next.players = Math.max(2, Math.min(8, next.players));
        this.scenario = next;
        this.rebuild();
        this.emit();
    }

    rebuild(): void {
        if (this.online) return;
        const sc = this.scenario;
        const prevSettings = this.session?.settings;
        const settings: RoomSettings = prevSettings
            ? { ...prevSettings, boardId: sc.boardSize === 30 ? 'classic-30' : 'classic-50' }
            : { boardId: sc.boardSize === 30 ? 'classic-30' : 'classic-50', initialCash: 3000, endMode: 'TIME_LIMIT', timeLimitMinutes: 30, rollSeconds: 15 };
        const members: Member[] = [];
        for (let i = 0; i < sc.players; i++) {
            members.push({
                playerId: 'p' + (i + 1),
                nickname: i === 0 && this.profile.nickname ? this.profile.nickname : NAMES[i],
                ready: i < sc.players - 1 || sc.players === 2 ? true : false,
                avatar: i === 0 ? this.profile.avatar : i,
                speaking: i === 1,
            });
        }
        if (!sc.host) members[0].ready = true;
        const hostId = sc.host ? ME : 'p2';
        const game = this.makeGame(members, settings);
        this.session = {
            roomId: '826315', status: 'PLAYING', hostId, members, settings, game, gamesPlayed: 1, lastResult: null,
        };
        this.auction = this.makeAuction(game);
        this.debt = this.makeDebt(game);
    }

    private makeGame(members: Member[], settings: RoomSettings): GameView {
        const sc = this.scenario;
        const tiles = buildBoard(sc.boardSize);
        const n = members.length;
        const connOf = (i: number): ConnState => {
            if (sc.conn === 'mixed') return i === 2 ? 'SUSPECT' : i === 3 ? 'OFFLINE' : 'ONLINE';
            if (sc.conn === 'mySuspect' && i === 0) return 'SUSPECT';
            return 'ONLINE';
        };
        const controlOf = (i: number): ControlMode => {
            if (sc.conn === 'mixed') return i === 4 ? 'HOSTED' : i === 5 ? 'AWAY' : 'MANUAL';
            return 'MANUAL';
        };
        const players: PlayerView[] = members.map((m, i) => ({
            playerId: m.playerId,
            nickname: m.nickname,
            avatar: m.avatar,
            position: (i * 7 + 3) % tiles.length,
            cash: settings.initialCash + ((i * 137) % 5) * 100 - 200,
            handCount: 2 + (i % 4),
            life: i === 0 && sc.spectator ? 'BANKRUPT' : 'ALIVE',
            inJail: false,
            jailFailures: 0,
            control: controlOf(i),
            conn: connOf(i),
            frozen: 0,
        }));
        if (n >= 6) players[5].inJail = true;
        if (sc.spectator) players[0].cash = 0;
        const hand = sc.spectator ? [] : makeHand(sc.handCount);
        players[0].handCount = hand.length;
        const properties = makeProperties(tiles, players);
        const now = this.clock.now();
        const current = sc.turn === 'me' && !sc.spectator ? ME : sc.players > 1 ? 'p2' : ME;
        return {
            gameNo: 1,
            phase: 'PLAYING',
            players,
            orderDraws: players.map((p, i) => ({ playerId: p.playerId, value: 90 - i * 7 })),
            boardId: settings.boardId,
            turnNo: 12,
            currentPlayer: current,
            stage: 'PRE_ROLL',
            globalEndsAt: now + 23 * 60 * 1000 + 46 * 1000,
            windows: [],
            myHand: hand,
            tiles,
            properties,
            lastDice: 4,
            chat: [
                { from: '可可', text: '好～' },
                { from: '阿杰', text: '稳住，这把能赢！' },
            ],
        };
    }

    private makeAuction(game: GameView): AuctionView {
        const idx = game.tiles.findIndex((t) => t.auctionLot && !game.properties.find((p) => p.tileIndex === t.index && p.owner));
        const tile = game.tiles[idx >= 0 ? idx : 1];
        const base = standardValue(false, tile.tier, 0);
        const a = auctionParams(base);
        return {
            tileIndex: tile.index, base, start: a.start, cap: a.cap, minRaise: a.minRaise,
            highBid: a.start + a.minRaise, highBidder: 'p3', myFrozen: 0, seller: null,
        };
    }

    private makeDebt(game: GameView): DebtView {
        return { debtor: ME, amount: Math.max(2000, this.me().cash + 900), creditor: 'p2', segment: 1, selected: [] };
    }

    // ---------- 查询 ----------
    get game(): GameView {
        return this.session.game as GameView;
    }

    /** 本局剩余整秒（向上取整）。 */
    matchRemainingSec(): number {
        if (this.matchOverrideSec !== null) return this.matchOverrideSec;
        return remainingSecFromMs(this.game.globalEndsAt - this.clock.now());
    }

    me(): PlayerView {
        return this.game.players.find((p) => p.playerId === this.myId) as PlayerView;
    }

    player(id: string): PlayerView | undefined {
        return this.game.players.find((p) => p.playerId === id);
    }

    tile(i: number): BoardTile {
        return this.game.tiles[i];
    }

    prop(i: number): PropertyState | undefined {
        return this.game.properties.find((p) => p.tileIndex === i);
    }

    isMyTurn(): boolean {
        return this.game.currentPlayer === this.myId && this.me()?.life === 'ALIVE';
    }

    /** 某玩家拥有的资产（含格子信息） */
    assetsOf(playerId: string): { tile: BoardTile; p: PropertyState }[] {
        const r: { tile: BoardTile; p: PropertyState }[] = [];
        for (const p of this.game.properties) {
            if (p.owner === playerId) r.push({ tile: this.game.tiles[p.tileIndex], p });
        }
        return r;
    }

    netWorthOf(playerId: string): number {
        const pl = this.player(playerId);
        if (!pl) return 0;
        return netWorth(pl.cash, this.assetsOf(playerId).map((a) => ({ isStation: a.tile.type === 'STATION', tier: a.tile.tier, p: a.p })));
    }

    /** 结算：净资产排名（并列 1/1/3），破产者在存活者之后。 */
    buildResult(): GameResult {
        const list = this.game.players.map((p) => ({
            playerId: p.playerId, nickname: p.nickname, avatar: p.avatar, cash: p.cash,
            netWorth: this.netWorthOf(p.playerId), bankrupt: p.life !== 'ALIVE',
        }));
        // 演示：8 人局里让最后一名破产（资产被系统回收，净资产与现金归零）
        const last = list[list.length - 1];
        if (list.length >= 5 && !last.bankrupt) {
            last.bankrupt = true;
            last.netWorth = 0;
            last.cash = 0;
        }
        // 演示：让第 1、2 名净资产与现金并列，展示 1/1/3 编号
        const alive = list.filter((s) => !s.bankrupt).sort((a, b) => b.netWorth - a.netWorth);
        if (alive.length >= 3) {
            alive[1].netWorth = alive[0].netWorth;
            alive[1].cash = alive[0].cash;
        }
        const standings = rankStandings(list);
        return { gameNo: 1, reason: this.session.settings.endMode === 'TIME_LIMIT' ? '到时结束' : '破产结束', standings };
    }

    // ---------- 动作（演示用的本地状态变更） ----------
    setReady(id: string, ready: boolean): void {
        if (this.online) {
            if (id === this.myId) void this.online.ready(ready);
            return;
        }
        const m = this.session.members.find((x) => x.playerId === id);
        if (m) m.ready = ready;
        this.emit();
    }

    /** 修改设置：全员需重新准备（房主视为已准备）。 */
    setSetting(patch: Partial<RoomSettings>): void {
        if (this.online) {
            void this.online.settings({ ...this.session.settings, ...patch });
            return;
        }
        this.session.settings = { ...this.session.settings, ...patch };
        for (const m of this.session.members) m.ready = m.playerId === this.session.hostId;
        if (patch.boardId) {
            this.scenario = { ...this.scenario, boardSize: boardSizeOf(patch.boardId) };
        }
        this.emit();
    }

    removeMember(id: string): void {
        if (id === this.myId) return;
        if (this.online) {
            void this.online.kick(id);
            return;
        }
        this.session.members = this.session.members.filter((m) => m.playerId !== id);
        this.scenario = { ...this.scenario, players: this.session.members.length };
        this.emit();
    }

    allReady(): boolean {
        const ms = this.session.members;
        return ms.length >= 2 && ms.every((m) => m.ready);
    }

    setProfile(p: Partial<Profile>): void {
        this.profile = { ...this.profile, ...p };
        if (this.online) {
            this.emit();
            return;
        }
        const m = this.session.members[0];
        if (m && this.profile.nickname) m.nickname = this.profile.nickname;
        if (m) m.avatar = this.profile.avatar;
        const me = this.player(ME);
        if (me) {
            me.avatar = this.profile.avatar;
            if (this.profile.nickname) me.nickname = this.profile.nickname;
        }
        this.emit();
    }

    /** 我已破产？ */
    isSpectator(): boolean {
        const me = this.me();
        return !!me && me.life !== 'ALIVE';
    }

    // ---------- 事件卡 ----------
    /** 把某玩家移到最近的事件格（演示用，使画面与设计图 10 一致：棋子站在事件格上）。 */
    moveToEventTile(id: string): number {
        const p = this.player(id) as PlayerView;
        const n = this.game.tiles.length;
        for (let k = 0; k < n; k++) {
            const i = (p.position + k) % n;
            if (this.game.tiles[i].type === 'EVENT') {
                p.position = i;
                return i;
            }
        }
        return p.position;
    }

    /** 走到事件格：开始抽卡流程（仅 IDLE 时有效）。 */
    eventStart(actor: string): void {
        const next = triggerEvent(this.eventDraw, actor, Date.now());
        if (next === this.eventDraw) return;
        this.eventDraw = next;
        this.emit();
    }

    /** 点击卡片（仅触发者本人有效；重复点击被忽略）。返回是否被接受。 */
    eventClick(viewer: string): boolean {
        if (this.online) {
            // 联机：点卡只发命令；结果由服务端 EventDrawn 推回后再翻开
            const w = this.online.myWindow('TURN');
            if (viewer !== this.myId || this.eventDraw.phase !== 'WAITING' || this.eventDraw.actor !== viewer || !w) return false;
            void this.online.act('DrawEventCard', { windowId: w.windowId });
            this.eventDraw = { ...this.eventDraw, phase: 'FLIPPING', since: Date.now(), result: null };
            this.emit();
            return true;
        }
        const r = clickCard(this.eventDraw, viewer, Date.now(), Math.random);
        if (!r.accepted) return false;
        this.eventDraw = r.state;
        this.emit();
        return true;
    }

    /** 每帧推进；到达结果时返回一次结算（并应用到演示数据）。 */
    eventTick(): { actor: string; result: EventResult } | null {
        if (this.online) {
            // 联机：不在本地抽取或结算，只按时间推进画面（翻牌 → 结果展示 5 秒 → 收起）
            const e = this.eventDraw;
            const now = Date.now();
            if (e.phase === 'FLIPPING' && e.result && now - e.since >= Theme.anim.eventFlipMs) {
                this.eventDraw = { ...e, phase: 'RESULT', since: now };
                this.emit();
            } else if (e.phase === 'RESULT' && now - e.since >= EVENT_RESULT_SHOW_MS) {
                this.eventDraw = { ...EVENT_IDLE, settled: e.settled };
                this.emit();
            }
            return null;
        }
        const r = advanceEvent(this.eventDraw, Date.now(), { flipMs: Theme.anim.eventFlipMs, autoMs: Theme.anim.eventAutoMs }, Math.random);
        if (r.state === this.eventDraw) return null;
        this.eventDraw = r.state;
        const actor = r.state.actor;
        if (r.settle && actor) this.applyEvent(actor, r.settle); // 先结算再通知界面，保证现金/位置同帧更新
        this.emit();
        return r.settle && actor ? { actor, result: r.settle } : null;
    }

    eventClose(viewer: string | null): void {
        if (this.online && this.eventDraw.phase === 'RESULT') {
            this.eventDraw = { ...EVENT_IDLE, settled: this.eventDraw.settled }; // 联机：任何观看者都可以自己关掉结果
            this.emit();
            return;
        }
        const next = closeResult(this.eventDraw, viewer);
        if (next === this.eventDraw) return;
        this.eventDraw = next;
        this.emit();
    }

    /** 事件结果落到演示数据（奖励/罚款不足/位移/入狱/道具）；罚款不足由调用方走欠款流程。 */
    private applyEvent(actor: string, r: EventResult): void {
        const p = this.player(actor);
        if (!p) return;
        const n = this.game.tiles.length;
        if (r.kind === 'CASH_REWARD') p.cash += r.amount;
        else if (r.kind === 'CASH_FINE' && p.cash >= r.amount) p.cash -= r.amount;
        else if (r.kind === 'MOVE') p.position = (((p.position + r.steps) % n) + n) % n; // 演示：直接位移，不再触发落点/第二个事件格
        else if (r.kind === 'JAIL') {
            const j = this.game.tiles.findIndex((t) => t.type === 'JAIL');
            if (j >= 0) p.position = j;
            p.inJail = true;
        } else if (r.kind === 'CARD' && r.card) {
            p.handCount++;
            if (actor === ME) this.game.myHand.push({ id: 'e' + Date.now(), type: r.card });
        }
    }

    /** 本次投骰点数（演示取随机；真实由服务端对局结果决定，动画只是表现）。 */
    rollValue(): number {
        const v = 1 + Math.floor(Math.random() * 6);
        this.game.lastDice = v;
        return v;
    }

    /** 移动棋子：返回新位置与是否经过/到达起点（前进才领 1000，每次移动最多一次）。 */
    moveBy(id: string, steps: number): { pos: number; passedStart: boolean } {
        const p = this.player(id) as PlayerView;
        const n = this.game.tiles.length;
        const old = p.position;
        p.position = (old + steps) % n;
        const passedStart = old + steps >= n;
        if (passedStart) p.cash += START_BONUS;
        return { pos: p.position, passedStart };
    }

    /** 轮到下一位存活玩家（破产者跳过）。 */
    advanceTurn(): void {
        const ps = this.game.players;
        let i = ps.findIndex((p) => p.playerId === this.game.currentPlayer);
        for (let k = 0; k < ps.length; k++) {
            i = (i + 1) % ps.length;
            if (ps[i].life === 'ALIVE') break;
        }
        this.game.currentPlayer = ps[i].playerId;
        this.game.turnNo++;
        this.game.stage = 'PRE_ROLL';
        this.emit();
    }

    /** 认输/破产：现金与资产系统回收，转为观战。 */
    surrender(life: 'SURRENDERED' | 'BANKRUPT' = 'SURRENDERED'): void {
        if (this.online) {
            if (this.session.game) void this.online.act('Surrender', { gameNo: this.session.game.gameNo });
            return;
        }
        const me = this.me();
        me.life = life;
        me.cash = 0;
        me.handCount = 0;
        this.game.myHand = [];
        for (const p of this.game.properties) {
            if (p.owner === ME) Object.assign(p, { owner: null, level: 0, mortgaged: false, mortgagePaid: 0, upgradeSpent: 0 });
        }
        if (this.game.currentPlayer === ME) this.advanceTurn();
        else this.emit();
    }

    /** 买地、升级等：演示用的简化现金变动，返回是否成功。 */
    spend(amount: number): boolean {
        const me = this.me();
        if (me.cash < amount) return false;
        me.cash -= amount;
        this.emit();
        return true;
    }

    /** 我能筹到的最大金额 = 现金 + 全部未抵押资产按应急比例的抵押所得（不足以偿债则立即破产，不开 30+30 秒窗口）。 */
    debtCapacity(): number {
        let sum = this.me().cash;
        for (const a of this.assetsOf(ME)) if (!a.p.mortgaged) sum += emergencyMortgage(a.tile.type === 'STATION', a.tile.tier);
        return sum;
    }

    /** 我所处格子是否为他人拥有的地产（供租金弹窗） */
    unownedLandPrice(i: number): number {
        const t = this.tile(i);
        return landPrice(t.type === 'STATION', t.tier);
    }

    emergencyAmount(i: number): number {
        const t = this.tile(i);
        return emergencyMortgage(t.type === 'STATION', t.tier);
    }
}

function makeHand(count: number): Card[] {
    // 设计图示例顺序：免租、建造、拍卖、定点移动、路障、出狱（首屏前 5 项，横滑后见出狱）；第 7 张为重复免租，用于演示数量角标与弃牌
    const seq: CardType[] = ['RENT_WAIVER', 'BUILD', 'AUCTION', 'FIXED_MOVE', 'ROADBLOCK', 'JAIL_RELEASE', 'RENT_WAIVER'];
    return seq.slice(0, Math.max(0, Math.min(7, count))).map((type, i) => ({ id: 'c' + i, type }));
}

function makeProperties(tiles: BoardTile[], players: PlayerView[]): PropertyState[] {
    const n = players.length;
    const out: PropertyState[] = [];
    let k = 0;
    for (const t of tiles) {
        if (t.type !== 'PROPERTY' && t.type !== 'STATION') continue;
        const slot = k % (n + 2);
        const cand = slot < n ? players[slot] : null;
        const owner = cand && cand.life === 'ALIVE' ? cand.playerId : null;
        const station = t.type === 'STATION';
        const level = !station && owner ? (k * 5) % 4 : 0;
        const upgradeSpent = level * (t.tier === 'LOW' ? 300 : t.tier === 'MID' ? 600 : 900);
        const mortgaged = !!owner && k % 9 === 4;
        out.push({
            tileIndex: t.index, owner, level, mortgaged,
            mortgagePaid: mortgaged ? landPrice(station, t.tier) : 0,
            upgradeSpent,
        });
        k++;
    }
    return out;
}

function makeHistory(): HistoryEntry[] {
    const r: HistoryEntry[] = [];
    for (let i = 0; i < 20; i++) {
        const players = 2 + ((i * 3) % 7);
        r.push({
            mode: i % 3 === 0 ? '破产模式' : '限时模式',
            players,
            minutes: 12 + ((i * 7) % 40),
            rank: 1 + ((i * 5) % players),
            finalAssets: 1500 + ((i * 731) % 6000),
        });
    }
    return r;
}
