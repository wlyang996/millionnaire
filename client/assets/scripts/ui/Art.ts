/** Design PNGs are loaded once before the first screen. Text and hit areas remain live UI. */
import { Node, resources, Sprite, SpriteFrame } from 'cc';
import { ART_PATHS } from './ArtCatalog';
import { mk, setOpacity } from './Kit';

const frames = new Map<string, SpriteFrame>();
const capsuleFrames = new Map<string, SpriteFrame>();
export const AVATAR_NAMES = ['糖糖', '可可', '阿杰', '奶茶', '阿凯', '圆圆', '豆豆', '毛毛'];
const characters = ['tangtang', 'keke', 'ajie', 'naicha', 'akai', 'yuanyuan', 'doudou', 'maomao'];
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

export async function preloadArt(): Promise<string[]> {
    const missing: string[] = [];
    await Promise.all(Object.entries(ART_PATHS).map(([key, path]) => new Promise<void>((resolve) => {
        resources.load(path + '/spriteFrame', SpriteFrame, (err, frame) => {
            if (err || !frame) { missing.push(key); console.warn('UI art missing:', key, err); }
            else frames.set(key, frame);
            resolve();
        });
    })));
    return missing;
}

/** The outer box uses our top-left coordinates; the sprite inside preserves its aspect ratio. */
export function art(parent: Node, key: string, x: number, y: number, w: number, h: number,
    fit: 'contain' | 'stretch' | 'capsule' | 'panel' = 'contain', dim = false): Node | null {
    const frame = frames.get(key);
    if (!frame) return null;
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
    const scale = Math.min(w / size.width, h / size.height);
    const sw = fit === 'contain' ? size.width * scale : w;
    const sh = fit === 'contain' ? size.height * scale : h;
    const image = mk(box, 'Image', (w - sw) / 2, (h - sh) / 2, sw, sh);
    const sprite = image.addComponent(Sprite);
    sprite.sizeMode = Sprite.SizeMode.CUSTOM;
    sprite.spriteFrame = frame;
    sprite.grayscale = dim;
    if (dim) setOpacity(box, 160);
    return box;
}
