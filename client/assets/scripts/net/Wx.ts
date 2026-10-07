/**
 * 微信小游戏能力的薄封装（纯 TS，不依赖 cc）。在 H5 / 浏览器里 wx 不存在，各函数退化为浏览器做法或返回 null：
 * - 登录：wx.login 拿一次性 code，交给服务端换 openid（POST /api/auth/wx-login）；
 * - 分享：右上角菜单与"分享邀请"按钮都带 query room=房间号；好友从分享卡片进入时读启动参数自动加房；
 * - 复制：wx.setClipboardData / navigator.clipboard。
 */

interface WxShareOptions { title: string; query?: string; imageUrl?: string }
interface WxLaunch { query?: Record<string, string> }
interface WxApi {
    login(o: { success(r: { code: string }): void; fail(e: unknown): void; timeout?: number }): void;
    showShareMenu?(o: { withShareTicket?: boolean; menus?: string[] }): void;
    onShareAppMessage?(fn: () => WxShareOptions): void;
    onShareTimeline?(fn: () => { title: string; query?: string }): void;
    shareAppMessage?(o: WxShareOptions): void;
    getLaunchOptionsSync?(): WxLaunch;
    onShow?(fn: (r: WxLaunch) => void): void;
    setClipboardData?(o: { data: string; success?(): void; fail?(): void }): void;
}

/** 当前运行在微信小游戏里时返回 wx，否则 null。 */
export function wxApi(): WxApi | null {
    const g = globalThis as unknown as { wx?: WxApi };
    return g.wx && typeof g.wx.login === 'function' ? g.wx : null;
}

export function isWechat(): boolean {
    return wxApi() !== null;
}

/** wx.login 的一次性 code（5 分钟有效、只能用一次）。 */
export function wxLoginCode(): Promise<string> {
    const wx = wxApi();
    if (!wx) return Promise.reject(Object.assign(new Error('not in wechat'), { code: 'NOT_WECHAT' }));
    return new Promise((resolve, reject) => {
        wx.login({
            timeout: 10000,
            success: (r) => (r && r.code ? resolve(r.code) : reject(Object.assign(new Error('no code'), { code: 'WECHAT_LOGIN_FAILED' }))),
            fail: () => reject(Object.assign(new Error('wx.login failed'), { code: 'WECHAT_LOGIN_FAILED' })),
        });
    });
}

const TITLE = '来大富翁小镇一起玩吧';

/**
 * 打开右上角"转发 / 分享到朋友圈"，转发内容随当前房间变化（在房间里时带房间号）。
 * roomOf 每次转发时调用，返回当前房间号与我的昵称。
 */
export function setupShareMenu(roomOf: () => { room: string | null; nickname: string }): void {
    const wx = wxApi();
    if (!wx) return;
    wx.showShareMenu?.({ withShareTicket: false, menus: ['shareAppMessage', 'shareTimeline'] });
    wx.onShareAppMessage?.(() => shareOptions(roomOf()));
    wx.onShareTimeline?.(() => ({ title: TITLE }));
}

function shareOptions(o: { room: string | null; nickname: string }): WxShareOptions {
    return o.room
        ? { title: (o.nickname || '好友') + '邀请你加入房间 ' + o.room, query: 'room=' + o.room }
        : { title: TITLE };
}

/**
 * "分享邀请"：微信里拉起转发面板（返回 'wechat'）；浏览器里把带 ?room= 的链接复制到剪贴板（返回 'copied'），失败返回 'failed'。
 */
export function shareRoom(room: string, nickname: string): Promise<'wechat' | 'copied' | 'failed'> {
    const wx = wxApi();
    if (wx && wx.shareAppMessage) {
        wx.shareAppMessage(shareOptions({ room, nickname }));
        return Promise.resolve('wechat');
    }
    const loc = (globalThis as unknown as { location?: { origin?: string; pathname?: string } }).location;
    const link = loc && loc.origin ? loc.origin + (loc.pathname ?? '/') + '?room=' + room : room;
    return copyText(link).then((ok) => (ok ? 'copied' : 'failed'));
}

export function copyText(textValue: string): Promise<boolean> {
    const wx = wxApi();
    if (wx && wx.setClipboardData) {
        return new Promise((resolve) => wx.setClipboardData!({ data: textValue, success: () => resolve(true), fail: () => resolve(false) }));
    }
    const nav = (globalThis as unknown as { navigator?: { clipboard?: { writeText(t: string): Promise<void> } } }).navigator;
    if (nav && nav.clipboard) return nav.clipboard.writeText(textValue).then(() => true, () => false);
    return Promise.resolve(false);
}

function validRoom(v: unknown): string | null {
    const s = typeof v === 'string' ? v.trim() : '';
    return /^\d{6}$/.test(s) ? s : null;
}

/** 启动参数里的房间号（微信：分享卡片 query；浏览器：?room=）。 */
export function launchRoom(): string | null {
    const wx = wxApi();
    if (wx && wx.getLaunchOptionsSync) return validRoom(wx.getLaunchOptionsSync().query?.room);
    const loc = (globalThis as unknown as { location?: { search?: string } }).location;
    const m = loc && typeof loc.search === 'string' ? loc.search.match(/[?&]room=(\d{6})/) : null;
    return m ? m[1] : null;
}

/** 小游戏已在后台、又从分享卡片切回来时（热启动）带来的房间号。 */
export function onShowRoom(fn: (room: string) => void): void {
    const wx = wxApi();
    wx?.onShow?.((r) => {
        const room = validRoom(r && r.query ? r.query.room : null);
        if (room) fn(room);
    });
}
