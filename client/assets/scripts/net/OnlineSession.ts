/**
 * 联机会话：把 GameClient 接到 MockStore 上。纯 TS，不依赖 cc（页面跳转、提示经回调交给界面层）。
 * - 每条 UPDATE：服务端视图经 ViewAdapter 转换后写入 store.session 并 emit()，页面照常重绘；
 * - 本步事件里的掷骰、移动转成动画提示（cues），棋盘页逐个播放，所有人看到的动画一致；
 * - 对局开始 / 结束时请求跳页（board / result）。
 */
import type { MockStore } from '../core/MockStore';
import { OpenWindow, RoomSettings, SessionView } from '../core/Models';
import { serverUrl } from './Config';
import { GameClient, LinkState } from './GameClient';
import { BoardTemplate, GameCommandName, ResultMsg, UpdateMsg } from './Protocol';
import { adaptSession, lastDiceFrom } from './ViewAdapter';

export type Cue =
    | { kind: 'dice'; playerId: string; value: number }
    | { kind: 'move'; playerId: string; from: number; steps: number };

export type Route = 'lobby' | 'room' | 'board' | 'spectator' | 'result';

/** 常见错误码的中文说明（未列出的显示原码）。 */
const MESSAGES: Record<string, string> = {
    ROOM_NOT_FOUND: '房间不存在或已关闭', ROOM_FULL: '房间已满', ALREADY_IN_ROOM: '你已在其他房间，请先离开',
    GAME_IN_PROGRESS: '对局已开始', NOT_HOST: '只有房主可以操作', NOT_ALL_READY: '需全员准备后才能开局',
    NOT_ENOUGH_PLAYERS: '至少 2 人才能开局', CAPACITY_EXCEEDED: '人数超过该地图上限', INVALID_SETTINGS: '设置不合法',
    UNCHANGED: '设置没有变化', INVALID_NICKNAME: '昵称不合法', NOT_YOUR_TURN: '还没轮到你', NOT_YOUR_WINDOW: '还没轮到你',
    WINDOW_NOT_OPEN: '请稍等动画结束', WRONG_STAGE: '当前不能这样操作', INSUFFICIENT_CASH: '现金不足',
    NOT_AVAILABLE: '该功能尚未开放', CONTROL_NOT_MANUAL: '托管中，请先恢复手动', NO_ACTIVE_WINDOW: '操作已过期',
    WINDOW_MISMATCH: '操作已过期', TIMEOUT: '网络超时，请重试', OFFLINE: '网络未连接', UNAUTHENTICATED: '请重新登录',
    ROOM_HALTED: '房间出现故障已关闭', MAX_LEVEL: '已满级', MORTGAGED: '已抵押的资产不能这样操作',
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

    constructor(private readonly store: MockStore, baseUrl: string = serverUrl()) {
        this.client = new GameClient(baseUrl);
        this.client.onUpdate = (u) => this.update(u);
        this.client.onState = (s) => {
            this.link = s;
            this.store.emit();
        };
        this.client.onNotice = (m) => {
            if (m.type === 'HELLO' && !m.roomCode) this.leaveLocally();
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
    async login(nickname: string): Promise<void> {
        await this.client.login(nickname);
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

    async start(): Promise<ResultMsg> {
        return this.check(await this.client.startGame());
    }

    /** 对局命令；被拒时自动提示原因。 */
    async act(command: GameCommandName, args: Record<string, number> = {}): Promise<ResultMsg> {
        return this.check(await this.client.game(command, args));
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
        for (const e of u.events) {
            const d = e.data ?? {};
            if (e.kind === 'DiceRolled') {
                this.cues.push({ kind: 'dice', playerId: String(d.playerId), value: Number(d.value) });
            } else if (e.kind === 'PlayerMoved' && Number(d.steps) > 0) {
                this.cues.push({ kind: 'move', playerId: String(d.playerId), from: Number(d.from), steps: Number(d.steps) });
            }
        }
        this.lastDice = lastDiceFrom(u.events, this.lastDice);
        const s = adaptSession(u.view, this.boards, this.lastDice);
        s.roomId = u.roomCode; // 界面上的"房间号"是六位房间号
        this.store.session = s;
        const hasGame = !!s.game;
        if (hasGame && !this.hadGame) {
            this.cues.length = 0; // 开局前的事件不做动画
            this.onRoute?.('board');
        } else if (!hasGame && this.hadGame) {
            this.cues.length = 0;
            this.onRoute?.('result');
        }
        this.hadGame = hasGame;
        this.store.emit();
    }

    private leaveLocally(): void {
        this.hadGame = false;
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
