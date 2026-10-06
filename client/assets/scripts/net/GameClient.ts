/**
 * 联机客户端（纯 TS，不依赖 cc；浏览器与微信小游戏都用全局 WebSocket，Cocos 适配层会转成 wx.connectSocket）。
 * - 登录：测试身份 POST /api/auth/test-login，令牌只在内存（服务端重启后需重新登录）；
 * - 连接：/ws?token=…，断线后按 1/2/4/8/10 秒退避自动重连，重连后服务端会推当前房间快照；
 * - 请求：每条改变状态的消息带 requestId，等待同号 RESULT；断线期间未完成的请求在重连后用同一 requestId 重发
 *   （服务端同一 requestId 只执行一次）；
 * - 时间：用每条 UPDATE / PONG 的 serverTime 估算与服务器的时钟偏差，倒计时用 serverNow()。
 */
import { requestJson } from './Http';
import { BoardTemplate, GameCommandName, HelloMsg, ResultMsg, SSettings, UpdateMsg } from './Protocol';

export type LinkState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface Socket {
    readyState: number;
    send(data: string): void;
    close(code?: number, reason?: string): void;
    onopen: ((ev?: unknown) => void) | null;
    onclose: ((ev?: unknown) => void) | null;
    onerror: ((ev?: unknown) => void) | null;
    onmessage: ((ev: { data: unknown }) => void) | null;
}

export type SocketFactory = (url: string) => Socket;

interface Pending {
    msg: Record<string, unknown>;
    resolve: (r: ResultMsg) => void;
    timer: ReturnType<typeof setTimeout>;
}

const OPEN = 1;
const REQUEST_TIMEOUT_MS = 15000;
const PING_MS = 10000;
const BACKOFF_MS = [1000, 2000, 4000, 8000, 10000];

export class GameClient {
    token: string | null = null;
    userId: string | null = null;
    nickname = '';
    roomCode: string | null = null;
    latest: UpdateMsg | null = null;
    state: LinkState = 'idle';

    /** 每条 UPDATE（含重连后的快照）。 */
    onUpdate: ((u: UpdateMsg) => void) | null = null;
    /** 连接状态变化。 */
    onState: ((s: LinkState) => void) | null = null;
    /** 其他服务端消息：HELLO / CHAT / ROOM_CLOSED / NO_ROOM / REPLACED。 */
    onNotice: ((m: { type: string; [k: string]: unknown }) => void) | null = null;

    private ws: Socket | null = null;
    private offset = 0;
    private seq = 0;
    private attempts = 0;
    private wantOpen = false;
    private pingTimer: ReturnType<typeof setInterval> | null = null;
    private retryTimer: ReturnType<typeof setTimeout> | null = null;
    private readonly pending = new Map<string, Pending>();

    constructor(readonly baseUrl: string, private readonly socketFactory: SocketFactory = defaultSocket) {}

    /** 测试身份登录；失败时抛出带 code 的错误（如 INVALID_NICKNAME）。 */
    async login(nickname: string): Promise<void> {
        const r = await requestJson<{ token: string; userId: string; nickname: string; code?: string }>(
            'POST', this.baseUrl + '/api/auth/test-login', { nickname });
        if (r.status !== 200 || !r.body?.token) {
            throw Object.assign(new Error('login failed'), { code: r.body?.code ?? 'HTTP_' + r.status });
        }
        this.token = r.body.token;
        this.userId = r.body.userId;
        this.nickname = r.body.nickname;
    }

    async boards(): Promise<BoardTemplate[]> {
        const r = await requestJson<BoardTemplate[]>('GET', this.baseUrl + '/api/boards');
        if (r.status !== 200 || !r.body) throw new Error('cannot load boards');
        return r.body;
    }

    connect(): void {
        if (!this.token) throw new Error('login first');
        this.wantOpen = true;
        this.open(this.attempts === 0 ? 'connecting' : 'reconnecting');
    }

    close(): void {
        this.wantOpen = false;
        this.clearTimers();
        this.ws?.close(1000, 'bye');
        this.ws = null;
        this.setState('closed');
        this.failPending('OFFLINE');
    }

    /** 估算的服务器当前时间（毫秒）。 */
    serverNow(): number {
        return Date.now() + this.offset;
    }

    // ------------------------------------------------------------ 命令

    createRoom(settings?: SSettings): Promise<ResultMsg> {
        return this.request('CREATE_ROOM', settings ? { settings } : {});
    }

    joinRoom(roomCode: string): Promise<ResultMsg> {
        return this.request('JOIN_ROOM', { roomCode });
    }

    leaveRoom(): Promise<ResultMsg> {
        return this.request('LEAVE_ROOM');
    }

    ready(ready: boolean): Promise<ResultMsg> {
        return this.request('READY', { ready });
    }

    updateSettings(settings: SSettings): Promise<ResultMsg> {
        return this.request('UPDATE_SETTINGS', { settings });
    }

    kick(target: string): Promise<ResultMsg> {
        return this.request('KICK', { target });
    }

    startGame(): Promise<ResultMsg> {
        return this.request('START_GAME');
    }

    game(command: GameCommandName, args: Record<string, number> = {}): Promise<ResultMsg> {
        return this.request('GAME', { command, args });
    }

    setControl(mode: 'MANUAL' | 'AWAY' | 'HOSTED'): Promise<ResultMsg> {
        return this.request('SET_CONTROL', { mode });
    }

    /** 房间聊天（服务端推 CHAT 完整列表，经 onNotice 交出）。 */
    chat(text: string): Promise<ResultMsg> {
        return this.request('CHAT', { text });
    }

    sync(): void {
        this.sendRaw({ type: 'SYNC' });
    }

    /** 发送带 requestId 的消息并等待 RESULT；未连接时等重连后自动发出，超时返回 outcome=TIMEOUT。 */
    request(type: string, fields: Record<string, unknown> = {}): Promise<ResultMsg> {
        const requestId = Date.now().toString(36) + '-' + (++this.seq).toString(36) + Math.random().toString(36).slice(2, 6);
        const msg = { type, requestId, ...fields };
        return new Promise((resolve) => {
            const timer = setTimeout(() => {
                this.pending.delete(requestId);
                resolve(local(requestId, 'TIMEOUT'));
            }, REQUEST_TIMEOUT_MS);
            this.pending.set(requestId, { msg, resolve, timer });
            if (this.ws && this.ws.readyState === OPEN) this.sendRaw(msg);
        });
    }

    // ------------------------------------------------------------ 连接

    private open(state: LinkState): void {
        this.setState(state);
        const url = this.baseUrl.replace(/^http/, 'ws') + '/ws?token=' + encodeURIComponent(this.token!);
        const ws = this.socketFactory(url);
        this.ws = ws;
        ws.onopen = () => {
            if (this.ws !== ws) return;
            this.attempts = 0;
            this.setState('open');
            this.pingTimer = setInterval(() => this.sendRaw({ type: 'PING' }), PING_MS);
            // 断线期间未完成的请求用同一 requestId 重发（服务端幂等）
            this.pending.forEach((p) => this.sendRaw(p.msg));
        };
        ws.onmessage = (ev) => {
            if (this.ws === ws) this.receive(typeof ev.data === 'string' ? ev.data : String(ev.data));
        };
        ws.onclose = () => {
            if (this.ws !== ws) return;
            this.clearTimers();
            this.ws = null;
            if (!this.wantOpen) return;
            const delay = BACKOFF_MS[Math.min(this.attempts, BACKOFF_MS.length - 1)];
            this.attempts++;
            this.setState('reconnecting');
            this.retryTimer = setTimeout(() => this.open('reconnecting'), delay);
        };
        ws.onerror = () => {
            /* 随后会收到 onclose */
        };
    }

    private receive(text: string): void {
        let m: { type: string; [k: string]: unknown };
        try {
            m = JSON.parse(text);
        } catch {
            return;
        }
        if (typeof m.serverTime === 'number') this.offset = m.serverTime - Date.now();
        switch (m.type) {
            case 'UPDATE': {
                const u = m as unknown as UpdateMsg;
                this.latest = u;
                this.roomCode = u.roomCode;
                this.onUpdate?.(u);
                return;
            }
            case 'RESULT': {
                const r = m as unknown as ResultMsg;
                if (r.ok && r.roomCode) this.roomCode = r.roomCode;
                const p = r.requestId ? this.pending.get(r.requestId) : undefined;
                if (p) {
                    clearTimeout(p.timer);
                    this.pending.delete(r.requestId!);
                    p.resolve(r);
                }
                return;
            }
            case 'HELLO': {
                const h = m as unknown as HelloMsg;
                this.roomCode = h.roomCode;
                if (!h.roomCode) this.latest = null;
                break;
            }
            case 'ROOM_CLOSED':
            case 'NO_ROOM':
                this.roomCode = null;
                this.latest = null;
                break;
            case 'REPLACED':
                this.wantOpen = false; // 被同一账号的新连接取代：不再自动重连
                break;
            case 'PONG':
                return;
        }
        this.onNotice?.(m);
    }

    private sendRaw(msg: Record<string, unknown>): void {
        if (this.ws && this.ws.readyState === OPEN) this.ws.send(JSON.stringify(msg));
    }

    private setState(s: LinkState): void {
        if (this.state === s) return;
        this.state = s;
        this.onState?.(s);
    }

    private clearTimers(): void {
        if (this.pingTimer) clearInterval(this.pingTimer);
        if (this.retryTimer) clearTimeout(this.retryTimer);
        this.pingTimer = null;
        this.retryTimer = null;
    }

    private failPending(outcome: string): void {
        this.pending.forEach((p, id) => {
            clearTimeout(p.timer);
            p.resolve(local(id, outcome));
        });
        this.pending.clear();
    }
}

function local(requestId: string, outcome: string): ResultMsg {
    return { type: 'RESULT', requestId, ok: false, outcome, code: outcome };
}

function defaultSocket(url: string): Socket {
    const WS = (globalThis as unknown as { WebSocket: new (u: string) => Socket }).WebSocket;
    return new WS(url);
}
