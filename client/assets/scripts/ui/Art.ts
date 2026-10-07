/**
 * 美术图加载与绘制。文字与点击区域仍是实时 UI。
 * - 启动：只加载首屏（昵称 / 头像页）必需的图（BOOT_KEYS，约 5MB），加载页显示进度；
 * - 进入首屏后：其余图按"大厅 → 棋盘 → 其他"的顺序在后台慢慢加载（同时最多 4 张）；
 * - 页面画到还没加载的图时先用代码绘制的占位，并把这张图插到队首立即加载，到达后通知界面重绘；
 * - 每张图 20 秒超时、失败重试一次，单张图卡住不会让整个游戏卡在加载页。
 */
import { Node, resources, Sprite, SpriteFrame } from 'cc';
import { ART_PATHS } from './ArtCatalog';
import { mk, setOpacity } from './Kit';

const frames = new Map<string, SpriteFrame>();
const capsuleFrames = new Map<string, SpriteFrame>();
export const AVATAR_NAMES = ['糖糖', '可可', '阿杰', '奶茶', '阿凯', '圆圆', '豆豆', '毛毛'];
const characters = ['tangtang', 'keke', 'ajie', 'naicha', 'akai', 'yuanyuan', 'doudou', 'maomao'];
/** 原图宽高比（宽 / 高）；未加载时 null。 */
export function artRatio(key: string): number | null {
    const frame = frames.get(key);
    if (!frame) {
        want(key);
        return null;
    }
    return frame.originalSize.width / frame.originalSize.height;
}

export function characterKey(idx: number, pawn = false): string {
    return (pawn ? 'pawn_' : 'avatar_') + characters[((idx % 8) + 8) % 8];
}
export function informationCharacterKey(idx: number): string {
    return 'information_host_' + characters[((idx % 8) + 8) % 8];
}
export const CARD_ART: Record<string, string> = {
    ROADBLOCK: 'card_roadblock', RENT_WAIVER: 'card_free_rent', BUILD: 'card_build', DOWNGRADE: 'card_downgrade',
    FIXED_MOVE: 'card_fixed_move', JAIL_RELEASE: 'card_jail_release', AUCTION: 'card_auction', TRADE: 'card_trade',
    REFUSE_PURCHASE: 'card_refuse_purchase', HOUSE_PROTECTION: 'card_house_protection', QUERY: 'card_query',
    FORCED_PURCHASE: 'card_forced_purchase', DEMOLISH: 'card_demolish', CLEAR_LAND: 'card_clear_land',
};

// ------------------------------------------------------------ 加载

/** 首屏（昵称 / 头像页）必需：背景、面板、标题木牌、8 个头像、按钮皮肤。 */
const BOOT_KEYS = [
    'information_background', 'info_asset_panel', 'brand_logo', 'icon_back',
    'avatar_tangtang', 'avatar_keke', 'avatar_ajie', 'avatar_naicha',
    'avatar_akai', 'avatar_yuanyuan', 'avatar_doudou', 'avatar_maomao',
    'button_flat_yellow', 'button_flat_blue', 'button_flat_green', 'button_flat_ivory', 'button_flat_red', 'button_flat_gray',
];

/** 后台加载的先后：先大厅 / 房间，再棋盘与对局弹窗，其余（详情页、场景大图等）最后。 */
const BACKGROUND_ORDER: (string | RegExp)[] = [
    'lobby_friends', 'board_town', 'icon_settings', 'icon_house', 'icon_copy', 'icon_share', 'icon_mic', 'icon_chat', 'icon_coin',
    /^pawn_[a-z]+$/, /^house_lv\d$/, 'icon_bank', 'icon_jail', 'event_card_back', 'event_card_fan', 'croc_open',
    /^dice_/, /^event_/, /^card_(?!detail|face)/, /^icon_clock/, /^info_/, /^shop_/, 'icon_shield', 'scene_jail_closeup',
];

const LOAD_TIMEOUT_MS = 20000;
const BACKGROUND_PARALLEL = 4;

const queue: string[] = [];
const queued = new Set<string>();
const inflight = new Set<string>();
const attempts = new Map<string, number>();
/** 页面画过但当时还没加载的图：到达后需要重绘。 */
const requested = new Set<string>();
let onArrived: (() => void) | null = null;
let arrivedTimer: ReturnType<typeof setTimeout> | null = null;
let backgroundStarted = false;

function rank(key: string): number {
    const i = BACKGROUND_ORDER.findIndex((p) => (typeof p === 'string' ? p === key : p.test(key)));
    return i < 0 ? BACKGROUND_ORDER.length : i;
}

/** 加载一张图；超时或失败返回 false（超时后若图片仍然到达，照样收下并通知重绘）。 */
function loadOne(key: string): Promise<boolean> {
    return new Promise((resolve) => {
        let settled = false;
        const settle = (ok: boolean) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(ok);
        };
        const timer = setTimeout(() => {
            console.warn('UI art timeout:', key);
            settle(false);
        }, LOAD_TIMEOUT_MS);
        resources.load(ART_PATHS[key] + '/spriteFrame', SpriteFrame, (err, frame) => {
            if (err || !frame) {
                console.warn('UI art missing:', key, err);
                settle(false);
                return;
            }
            frames.set(key, frame);
            arrived(key);
            settle(true);
        });
    });
}

function arrived(key: string): void {
    if (!requested.delete(key) || arrivedTimer) return;
    // 同一批到达的图合并成一次重绘
    arrivedTimer = setTimeout(() => {
        arrivedTimer = null;
        onArrived?.();
    }, 250);
}

function start(key: string): void {
    queued.delete(key);
    if (frames.has(key) || inflight.has(key)) return;
    inflight.add(key);
    void loadOne(key).then((ok) => {
        inflight.delete(key);
        if (!ok && !frames.has(key) && (attempts.get(key) ?? 0) < 1) {
            attempts.set(key, 1);
            queued.add(key);
            queue.push(key); // 失败的放到队尾再试一次
        }
        pump();
    });
}

function pump(): void {
    if (!backgroundStarted) return;
    while (inflight.size < BACKGROUND_PARALLEL && queue.length) start(queue.shift()!);
}

/** 页面要用但还没加载：插队立即加载，到达后通知重绘。 */
function want(key: string): void {
    if (frames.has(key) || !ART_PATHS[key]) return;
    requested.add(key);
    if (inflight.has(key)) return;
    const i = queue.indexOf(key);
    if (i >= 0) queue.splice(i, 1);
    start(key);
}

/** 启动加载：只加载首屏必需的图，onProgress(已完成, 总数)。返回加载失败的图。 */
export async function bootArt(onProgress: (done: number, total: number) => void): Promise<string[]> {
    const keys = BOOT_KEYS.filter((k) => ART_PATHS[k] && !frames.has(k));
    const missing: string[] = [];
    let done = 0;
    onProgress(0, keys.length);
    await Promise.all(keys.map(async (k) => {
        inflight.add(k);
        if (!(await loadOne(k))) missing.push(k);
        inflight.delete(k);
        onProgress(++done, keys.length);
    }));
    return missing;
}

/** 首屏出来之后：其余图按优先级在后台加载；页面用到的图到达后调用 onArrive（已合并、节流）。 */
export function startBackgroundArt(onArrive: () => void): void {
    onArrived = onArrive;
    backgroundStarted = true;
    const rest = Object.keys(ART_PATHS).filter((k) => !frames.has(k) && !inflight.has(k) && !queued.has(k));
    rest.sort((a, b) => rank(a) - rank(b));
    for (const k of rest) {
        queued.add(k);
        queue.push(k);
    }
    pump();
}

/** 后台加载进度（调试 / 显示用）。 */
export function artLoadState(): { loaded: number; total: number } {
    return { loaded: frames.size, total: Object.keys(ART_PATHS).length };
}

/** The outer box uses our top-left coordinates; the sprite inside preserves its aspect ratio. */
export interface ArtOpts {
    /** 父节点的纵横缩放比（显示时 y 方向相对 x 方向的拉伸，如棋盘世界的纵向拉伸）；contain 时据此抵消，保持原图比例。 */
    aspect?: number;
    /** contain 时贴底对齐（人物脚踩地面），默认居中。 */
    bottom?: boolean;
}

export function art(parent: Node, key: string, x: number, y: number, w: number, h: number,
    fit: 'contain' | 'stretch' | 'capsule' | 'panel' = 'contain', dim = false, opts: ArtOpts = {}): Node | null {
    const frame = frames.get(key);
    if (!frame) {
        want(key); // 还没加载：先用占位，插队加载，到达后界面重绘
        return null;
    }
    const box = mk(parent, 'Art:' + key, x, y, w, h);
    if (fit === 'panel') {
        const cacheKey = 'panel:' + key;
        let sliced = capsuleFrames.get(cacheKey);
        if (!sliced) {
            sliced = frame.clone();
            const inset = Math.floor(Math.min(frame.rect.height / 3, frame.rect.width / 4));
            sliced.insetLeft = sliced.insetRight = sliced.insetTop = sliced.insetBottom = inset;
            capsuleFrames.set(cacheKey, sliced);
        }
        const scale = w / frame.rect.width;
        const image = mk(box, 'Image', 0, 0, w / scale, h / scale);
        image.setScale(scale, scale, 1);
        const sprite = image.addComponent(Sprite);
        sprite.sizeMode = Sprite.SizeMode.CUSTOM;
        sprite.type = Sprite.Type.SLICED;
        sprite.spriteFrame = sliced;
        return box;
    }
    if (fit === 'capsule') {
        // Preserve both rounded ends of the original PNG. Stretch only its center.
        let sliced = capsuleFrames.get(key);
        if (!sliced) {
            sliced = frame.clone();
            const cap = Math.floor(frame.rect.height / 2) - 1;
            sliced.insetLeft = sliced.insetRight = cap;
            sliced.insetTop = sliced.insetBottom = cap;
            capsuleFrames.set(key, sliced);
        }
        const scale = h / frame.rect.height;
        const image = mk(box, 'Image', 0, 0, w / scale, frame.rect.height);
        image.setScale(scale, scale, 1);
        const sprite = image.addComponent(Sprite);
        sprite.sizeMode = Sprite.SizeMode.CUSTOM;
        sprite.type = Sprite.Type.SLICED;
        sprite.spriteFrame = sliced;
        sprite.grayscale = dim;
        if (dim) setOpacity(box, 160);
        return box;
    }
    const size = frame.originalSize;
    const a = opts.aspect && opts.aspect > 0 ? opts.aspect : 1;
    // 在"显示空间"里等比缩放（框高按 a 换算成显示高度），再换回本地坐标
    const scale = Math.min(w / size.width, (h * a) / size.height);
    const sw = fit === 'contain' ? size.width * scale : w;
    const sh = fit === 'contain' ? (size.height * scale) / a : h;
    const image = mk(box, 'Image', (w - sw) / 2, opts.bottom ? h - sh : (h - sh) / 2, sw, sh);
    const sprite = image.addComponent(Sprite);
    sprite.sizeMode = Sprite.SizeMode.CUSTOM;
    sprite.spriteFrame = frame;
    sprite.grayscale = dim;
    if (dim) setOpacity(box, 160);
    return box;
}
