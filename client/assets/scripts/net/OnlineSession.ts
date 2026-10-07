/**
 * 联机会话：把 GameClient 接到 MockStore 上。纯 TS，不依赖 cc（页面跳转、提示经回调交给界面层）。
 * - 每条 UPDATE：服务端视图经 ViewAdapter 转换后写入 store.session 并 emit()，页面照常重绘；
 * - 本步事件里的掷骰、移动转成动画提示（cues），棋盘页逐个播放，所有人看到的动画一致；
 * - 对局开始 / 结束时请求跳页（board / result）。
 */
import type { MockStore } from '../core/MockStore';
import { CARD_NAMES, ChatLine, OpenWindow, RoomSettings, SessionView, CardType } from '../core/Models';
import { EVENT_IDLE, EventKind, EventResult } from '../core/EventDraw';
import { serverUrl } from './Config';
import { GameClient, LinkState } from './GameClient';
import { BoardTemplate, GameArgs, GameCommandName, ResultMsg, UpdateMsg } from './Protocol';
import { adaptSession, lastDiceFrom } from './ViewAdapter';

export type Cue =
    | { kind: 'dice'; playerId: string; value: number }
    | { kind: 'move'; playerId: string; from: number; steps: number }
    | { kind: 'jail'; playerId: string }
    /** 事件卡：waiting = 落到事件格等待抽卡；否则为翻牌结果。排在走棋提示之后，人物落地后才显示。 */
    | { kind: 'event'; playerId: string; actor: string; waiting: boolean; result: EventResult | null };

/** 拍卖 / 交易结果卡显示时长。 */
const RESULT_MS = 3500;

export type Route = 'lobby' | 'room' | 'board' | 'spectator' | 'result';

/** 常见错误码的中文说明（未列出的显示原码）。 */
const MESSAGES: Record<string, string> = {
    ROOM_NOT_FOUND: '房间不存在或已关闭', ROOM_FULL: '房间已满', ALREADY_IN_ROOM: '你已在其他房间，请先离开',
    GAME_IN_PROGRESS: '对局已开始', NOT_HOST: '只有房主可以操作', NOT_ALL_READY: '需全员准备后才能开局',
    NOT_ENOUGH_PLAYERS: '至少 2 人才能开局', CAPACITY_EXCEEDED: '人数超过该地图上限', INVALID_SETTINGS: '设置不合法',
    UNCHANGED: '设置没有变化', INVALID_NICKNAME: '昵称不合法', NOT_YOUR_TURN: '还没轮到你', NOT_YOUR_WINDOW: '还没轮到你',
    WINDOW_NOT_OPEN: '请稍等动画结束', WRONG_STAGE: '当前不能这样操作', INSUFFICIENT_CASH: '现金不足',
    NOT_AVAILABLE: '该功能尚未开放', CONTROL_NOT_MANUAL: '托管中，请先恢复手动', NO_ACTIVE_WINDOW: '操作已过期',
    WINDOW_MISMATCH: '操作已过期', TIMEOUT: '网络超时，请重试', OFFLINE: '网络未连接', UNAUTHENTICATED: '请重新登录', TOO_FAST: '发言太快了，歇一下', NOT_IN_ROOM: '你不在房间里',
    UNKNOWN_TYPE: '服务器版本较旧，请先发布最新后台', BOTS_DISABLED: '机器人只在测试环境可用', IN_GAME: '对局进行中不能加机器人',
    ROOM_HALTED: '房间出现故障已关闭', MAX_LEVEL: '已满级', MORTGAGED: '已抵押的资产不能这样操作',
    NO_CARD: '手里没有这张道具', CARD_USED: '本回合已经用过道具了', NO_TARGET: '当前位置不能使用这张道具',
    NOT_ALLOWED: '这张道具不能这样使用', DRAINING: '本局时间已到，不能再用道具',
};

export function describe(code: string | null | undefined): string {
    if (!code) return '操作失败';
    return MESSAGES[code] ?? '操作失败（' + code + '）';
}

export class OnlineSession {
    readonly client: GameClient;
    boards: BoardTemplate[] | null = null;
    /** 待播放的动画提示（棋盘页消费）。 */
    readonly cues: Cue[] = [];
    lastDice = 1;
    link: LinkState = 'idle';
    onRoute: ((r: Route) => void) | null = null;
    onToast: ((msg: string) => void) | null = null;
    private hadGame = false;
    /** 本条推送里抽到事件卡时动画队列的长度：翻牌插在这里（之前的走棋播完、之后的事件位移等翻牌看完）。 */
    private eventCueAt = -1;
    /** 已为当前的"等待抽卡"排过提示（避免每条推送重复排）。 */
    private eventWaitQueued = false;
    /** 房间最近聊天（服务端 CHAT 推送的完整列表）。 */
    chat: ChatLine[] = [];

    constructor(private readonly store: MockStore, baseUrl: string = serverUrl()) {
        this.client = new GameClient(baseUrl);
        this.client.onUpdate = (u) => this.update(u);
        this.client.onState = (s) => {
            this.link = s;
            this.store.emit();
        };
        this.client.onNotice = (m) => {
            if (m.type === 'HELLO' && !m.roomCode) this.leaveLocally();
            else if (m.type === 'CHAT') this.receiveChat(m.lines);
            else if (m.type === 'ROOM_CLOSED') {
                this.onToast?.('房间已关闭');
                this.leaveLocally();
                this.onRoute?.('lobby');
            } else if (m.type === 'REPLACED') this.onToast?.('账号在别处登录，本连接已断开');
        };
    }

    get myId(): string {
        return this.client.userId ?? '';
    }

    /** 测试身份登录并连接；失败时抛出带 code 的错误。 */
    async login(nickname: string, avatar?: number): Promise<void> {
        await this.client.login(nickname, avatar);
        this.boards = await this.client.boards();
        this.store.myId = this.client.userId!;
        this.leaveLocally();
        this.client.connect();
    }

    // ------------------------------------------------------------ 房间
    async createRoom(): Promise<ResultMsg> {
        return this.check(await this.client.createRoom());
    }

    async join(code: string): Promise<ResultMsg> {
        return this.client.joinRoom(code); // 由加入弹窗自己显示错误
    }

    async leave(): Promise<ResultMsg> {
        const r = this.check(await this.client.leaveRoom());
        if (r.ok) this.leaveLocally();
        return r;
    }

    async ready(ready: boolean): Promise<ResultMsg> {
        return this.check(await this.client.ready(ready));
    }

    async settings(s: RoomSettings): Promise<ResultMsg> {
        return this.check(await this.client.updateSettings(s));
    }

    async kick(playerId: string): Promise<ResultMsg> {
        return this.check(await this.client.kick(playerId));
    }

    async addBot(): Promise<ResultMsg> {
        return this.check(await this.client.addBot());
    }

    /** 从服务端读取我的最近 20 局，写入 store.history（失败时保留空列表并返回 false）。 */
    async loadHistory(): Promise<boolean> {
        try {
            const rows = await this.client.history();
            this.store.history = rows.map((r) => ({
                mode: r.endMode === 'TIME_LIMIT' ? '限时模式' : '破产模式',
                players: r.playerCount,
                minutes: Math.max(1, Math.round((r.endedAt - r.startedAt) / 60000)),
                rank: r.rank ?? 0,
                finalAssets: r.netWorth ?? r.cash ?? 0,
                endedAt: r.endedAt,
            }));
            return true;
        } catch {
            this.store.history = [];
            return false;
        }
    }

    async start(): Promise<ResultMsg> {
        return this.check(await this.client.startGame());
    }

    /** 对局命令；被拒时自动提示原因。 */
    async act(command: GameCommandName, args: GameArgs = {}): Promise<ResultMsg> {
        return this.check(await this.client.game(command, args));
    }

    /** 发一句聊天；过快、过长由服务端拒绝或截断。 */
    async say(text: string): Promise<ResultMsg> {
        const t = text.trim();
        if (!t) return { type: 'RESULT', requestId: null, ok: false, outcome: 'ERROR', code: 'BAD_REQUEST' } as ResultMsg;
        return this.check(await this.client.chat(t));
    }

    async setControl(mode: 'MANUAL' | 'AWAY' | 'HOSTED'): Promise<ResultMsg> {
        return this.check(await this.client.setControl(mode));
    }

    // ------------------------------------------------------------ 查询
    now(): number {
        return this.client.serverNow();
    }

    /** 我当前的窗口（可指定类别），不论是否已开启。 */
    myWindow(kind?: string): OpenWindow | undefined {
        const g = this.store.session.game;
        return g?.windows.find((w) => w.owner === this.myId && (!kind || w.kind === kind));
    }

    /** 某玩家当前的回合窗口（倒计时用）。 */
    turnWindow(playerId: string): OpenWindow | undefined {
        const g = this.store.session.game;
        return g?.windows.find((w) => w.owner === playerId && w.kind === 'TURN');
    }

    isOpen(w: OpenWindow | undefined): boolean {
        if (!w || w.paused) return false;
        const t = this.now();
        return t >= w.opensAt && t < w.deadline;
    }

    // ------------------------------------------------------------ 内部
    private update(u: UpdateMsg): void {
        this.eventCueAt = -1;
        for (const e of u.events) {
            const d = e.data ?? {};
            if (e.kind === 'DiceRolled') {
                this.cues.push({ kind: 'dice', playerId: String(d.playerId), value: Number(d.value) });
            } else if (e.kind === 'PlayerMoved' && Number(d.steps) > 0) {
                // 事件后退：步数记为负，棋盘页逐格往回跳
                const back = String(d.kind ?? '').indexOf('BACK') >= 0;
                this.cues.push({ kind: 'move', playerId: String(d.playerId), from: Number(d.from), steps: (back ? -1 : 1) * Number(d.steps) });
            } else if (e.kind === 'EventDrawn') {
                this.eventCueAt = this.cues.length;
            } else if (e.kind === 'PlayerJailed') {
                this.cues.push({ kind: 'jail', playerId: String(d.playerId) });
            }
        }
        this.trackMinigame(u);
        this.lastDice = lastDiceFrom(u.events, this.lastDice);
        const s = adaptSession(u.view, this.boards, this.lastDice, u.avatars ?? {});
        s.roomId = u.roomCode; // 界面上的"房间号"是六位房间号
        if (s.game) s.game.chat = this.chat;
        this.store.session = s;
        this.trackCards(u, s);
        const hasGame = !!s.game;
        if (hasGame && !this.hadGame) {
            this.cues.length = 0; // 开局前的事件不做动画
            this.onRoute?.('board');
        } else if (!hasGame && this.hadGame) {
            this.cues.length = 0;
            this.onRoute?.('result');
        }
        this.hadGame = hasGame;
        // 抽卡结果：排进动画队列（在本步之前的走棋之后翻牌，之后的事件位移等翻牌看完再走）
        this.trackEventDraw(u);
        // 当前玩家踩到事件格、等待抽卡：所有人都显示中央卡牌（只有本人能点），同样排在走棋动画之后
        const g = s.game;
        const waiting = !!g && !!g.landing && g.landing.step === 'EVENT' && g.landing.decisionPending;
        if (!g) {
            this.store.eventDraw = EVENT_IDLE;
            this.store.myRequest = null;
            this.eventWaitQueued = false;
        } else if (!waiting) {
            this.eventWaitQueued = false;
        } else if (!this.eventWaitQueued && this.store.eventDraw.phase === 'IDLE') {
            this.eventWaitQueued = true;
            this.cues.push({ kind: 'event', playerId: g.currentPlayer, actor: g.currentPlayer, waiting: true, result: null });
        }
        this.store.emit();
    }

    /** 服务端抽卡结果 → 翻牌动画的结果（道具种类只有本人收得到，其他人看到"获得道具"）。 */
    private trackEventDraw(u: UpdateMsg): void {
        let result: EventResult | null = null;
        let actor: string | null = null;
        for (const e of u.events) {
            const d = e.data ?? {};
            if (e.kind === 'EventDrawn') {
                actor = String(d.playerId);
                const back = String(d.moveKind ?? '').indexOf('BACK') >= 0;
                result = {
                    kind: String(d.kind) as EventKind, amount: Number(d.amount ?? 0), card: null,
                    steps: (back ? -1 : 1) * Number(d.distance ?? 0),
                };
            } else if (e.kind === 'EventCardReceived' && result) {
                result.card = String(d.card) as CardType;
            }
        }
        if (result && actor) {
            // 插在本步的事件位移之前：先翻牌、看完结果再走
            const cue: Cue = { kind: 'event', playerId: actor, actor, waiting: false, result };
            this.cues.splice(Math.min(Math.max(this.eventCueAt, 0), this.cues.length), 0, cue);
        }
    }

    /** 道具事件：查询结果只给我（弹结果页）；其余用卡、抵挡、免租等给所有人一句提示。 */
    private trackCards(u: UpdateMsg, s: SessionView): void {
        const g = s.game;
        if (!g) return;
        const name = (id: unknown) => (String(id) === this.myId ? '你' : g.players.find((p) => p.playerId === String(id))?.nickname ?? '玩家');
        const tile = (i: unknown) => g.tiles[Number(i)]?.name ?? '';
        const card = (c: unknown) => CARD_NAMES[String(c) as CardType] ?? String(c);
        let notice = '';
        for (const e of u.events) {
            const d = e.data ?? {};
            switch (e.kind) {
                case 'QueryRevealed':
                    this.store.queryResult = {
                        target: String(d.target), cards: Array.isArray(d.cards) ? (d.cards as unknown[]).map((c) => String(c) as CardType) : [],
                        seen: false,
                    };
                    break;
                case 'CardUsed':
                    if (!d.active) break;
                    if (d.card === 'ROADBLOCK') notice = name(d.playerId) + '在' + tile(d.tile) + '放置了路障';
                    else if (d.card === 'FIXED_MOVE') notice = name(d.playerId) + '使用了定点移动';
                    else if (d.card === 'JAIL_RELEASE') notice = name(d.playerId) + '使用出狱卡出狱';
                    else if (d.card === 'QUERY') notice = name(d.playerId) + '查询了' + name(d.target) + '的手牌';
                    else if (d.card !== 'BUILD') notice = name(d.playerId) + '对' + name(d.target) + '的' + tile(d.tile) + '使用了' + card(d.card);
                    break;
                case 'ResponseOffered':
                    notice = '等待' + name(d.owner) + '决定是否使用' + card(d.response);
                    break;
                case 'AttackBlocked':
                    notice = name(d.owner) + '用' + card(d.response) + '挡住了' + name(d.attacker) + '的' + card(d.attack);
                    break;
                case 'PropertyBuilt':
                    notice = name(d.playerId) + '用建造卡把' + tile(d.tile) + '升到 ' + Number(d.level) + ' 级';
                    break;
                case 'PropertyDowngraded':
                    notice = tile(d.tile) + '被降到 ' + Number(d.level) + ' 级';
                    break;
                case 'PropertyDemolished':
                    notice = tile(d.tile) + '的楼被拆光了，所有者仍是' + name(d.owner);
                    break;
                case 'PropertyCleared':
                    notice = tile(d.tile) + '被清地，变为无主';
                    break;
                case 'PropertyForceBought':
                    notice = name(d.buyer) + '以 ' + Number(d.price) + ' 强制买下了' + tile(d.tile);
                    break;
                case 'RentWaived':
                    notice = name(d.payer) + '用免租卡免除了本次租金';
                    break;
                case 'LandAuctionChosen':
                    notice = name(d.playerId) + '对' + tile(d.tile) + '发起了拍卖';
                    break;
                // 设计稿 29：自己的申请显示排队横幅；拍卖 / 交易结果显示结果卡（不再用文字提示）
                case 'AuctionRequested':
                    if (String(d.applicant) === this.myId) this.store.myRequest = { kind: 'AUCTION', requestId: Number(d.requestId) };
                    else notice = name(d.applicant) + '申请拍卖' + tile(d.tile) + '，当前操作结束后开拍';
                    break;
                case 'TradeRequested':
                    if (String(d.seller) === this.myId) this.store.myRequest = { kind: 'TRADE', requestId: Number(d.requestId) };
                    break;
                case 'AuctionStarted':
                case 'TradeStarted':
                    this.store.myRequest = null;
                    break;
                case 'FlowRequestCancelled':
                    if (this.store.myRequest && this.store.myRequest.requestId === Number(d.requestId)) {
                        this.store.myRequest = null;
                        notice = '申请已失效（资产或玩家状态变化），未退还用卡机会';
                    }
                    break;
                case 'AuctionSettled':
                    this.store.flowResult = { kind: 'AUCTION_SOLD', tile: Number(d.tile), price: Number(d.price), a: String(d.winner), b: '', until: Date.now() + RESULT_MS };
                    break;
                case 'AuctionPassed':
                    this.store.flowResult = { kind: 'AUCTION_PASSED', tile: Number(d.tile), price: 0, a: '', b: '', until: Date.now() + RESULT_MS };
                    break;
                case 'TradeCompleted':
                    this.store.flowResult = { kind: 'TRADE_DONE', tile: Number(d.tile), price: Number(d.price), a: String(d.seller), b: String(d.buyer), until: Date.now() + RESULT_MS };
                    break;
                case 'TradeDeclined':
                    this.store.flowResult = { kind: d.auto ? 'TRADE_TIMEOUT' : 'TRADE_DECLINED', tile: Number(d.tile), price: 0, a: '', b: String(d.buyer), until: Date.now() + RESULT_MS };
                    break;
                default:
                    break;
            }
        }
        if (notice) this.onToast?.(notice);
    }

    /** 小游戏结束（MinigameEnded）：记下输家、危险牙与全部按牙顺序，供结果页展示（视图里小游戏已清除）。 */
    private trackMinigame(u: UpdateMsg): void {
        const prev = this.store.session.game?.minigame ?? null;
        let participants = prev ? prev.participants.slice() : [];
        let picks = prev ? prev.picks.slice() : [];
        for (const e of u.events) {
            const d = e.data ?? {};
            if (e.kind === 'MinigameStarted') {
                participants = Array.isArray(d.participants) ? (d.participants as unknown[]).map(String) : [];
                picks = [];
            } else if (e.kind === 'ToothPicked') {
                picks.push(Number(d.tooth));
            } else if (e.kind === 'MinigameEnded') {
                this.store.toothResult = {
                    minigameId: Number(d.landingId), loser: String(d.loser), danger: Number(d.danger), reward: Number(d.reward),
                    participants, picks, seen: false,
                };
            }
        }
    }

    private receiveChat(raw: unknown): void {
        const lines = Array.isArray(raw) ? raw : [];
        this.chat = lines.map((l: { nickname?: unknown; text?: unknown }) => ({
            from: String(l.nickname ?? ''), text: String(l.text ?? ''),
        }));
        this.store.roomChat = this.chat;
        if (this.store.session.game) this.store.session.game.chat = this.chat;
        this.store.emit();
    }

    private leaveLocally(): void {
        this.hadGame = false;
        this.chat = [];
        this.store.roomChat = this.chat;
        this.store.eventDraw = EVENT_IDLE;
        this.store.toothResult = null;
        this.store.queryResult = null;
        this.store.myRequest = null;
        this.store.flowResult = null;
        this.cues.length = 0;
        this.store.session = emptySession(this.store.session?.settings);
        this.store.emit();
    }

    private check(r: ResultMsg): ResultMsg {
        if (!r.ok && r.outcome !== 'DUPLICATE') this.onToast?.(describe(r.code));
        return r;
    }
}

export function emptySession(settings?: RoomSettings): SessionView {
    return {
        roomId: '', status: 'LOBBY', hostId: '', members: [], game: null, gamesPlayed: 0, lastResult: null,
        settings: settings ?? { boardId: 'classic-30', initialCash: 3000, endMode: 'TIME_LIMIT', timeLimitMinutes: 30, rollSeconds: 15 },
    };
}
