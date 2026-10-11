/** Stable identities: 0..7 bundled, 8 robot, 9..998 managed. No Cocos dependency. */
export const CHARACTER_SLOTS = ['avatar', 'pawn', 'portrait', 'give', 'receive', 'worry', 'celebrate'] as const;
export type CharacterSlot = typeof CHARACTER_SLOTS[number];
export interface CharacterImage { url: string; sha256: string; width: number; height: number }
export interface CharacterEntry {
    id: number; name: string; sort: number; enabled: boolean; fallback: number; bundled: boolean;
    images: Partial<Record<CharacterSlot, CharacterImage>>;
}
export interface CharacterCatalog { version: number; characters: CharacterEntry[] }
const NAMES = ['糖糖', '可可', '阿杰', '奶茶', '阿凯', '圆圆', '豆豆', '毛毛'];
export function defaultCharacterCatalog(): CharacterCatalog {
    return { version: 0, characters: NAMES.map((name, id) => ({ id, name, sort: id, enabled: true, fallback: id, bundled: true, images: {} })) };
}
export function validCharacterId(id: number): boolean { return Number.isInteger(id) && id >= 0 && id <= 998; }
export function validateCharacterCatalog(c: CharacterCatalog): boolean {
    if (!c || !Number.isSafeInteger(c.version) || c.version < 0 || !Array.isArray(c.characters) || c.characters.length < 1 || c.characters.length > 998) return false;
    const ids = new Set<number>();
    return c.characters.every(e => {
        if (!e || !validCharacterId(e.id) || e.id === 8 || ids.has(e.id) || typeof e.name !== 'string' || !e.name.length || !Number.isInteger(e.sort)
            || typeof e.enabled !== 'boolean' || typeof e.bundled !== 'boolean' || !Number.isInteger(e.fallback) || e.fallback < 0 || e.fallback > 7) return false;
        ids.add(e.id);
        if (e.bundled) return e.id < 8;
        return CHARACTER_SLOTS.every(slot => {
            const a = e.images?.[slot];
            return !!a && /^\/api\/characters\/assets\/[0-9a-f-]{36}\/[a-z]+\.png$/.test(a.url)
                && /^[0-9a-f]{64}$/.test(a.sha256) && Number.isInteger(a.width) && Number.isInteger(a.height)
                && a.width >= 64 && a.width <= 2048 && a.height >= 64 && a.height <= 2048;
        });
    });
}
