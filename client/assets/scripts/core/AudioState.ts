/** Audio presentation state; no money, game timers or movement speed is changed here. */
export const AUDIO_SCENES = ['lobby', 'room', 'board', 'game', 'result'] as const;
export const AUDIO_EFFECTS = ['dice','move','cashGain','cashLoss','card','purchase','upgrade','auction','jail','release','reward'] as const;
export type AudioScene = typeof AUDIO_SCENES[number];
export type AudioEffect = typeof AUDIO_EFFECTS[number];
export interface AudioSettings {
    musicEnabled: boolean; musicVolume: number; effectsEnabled: boolean; effectsVolume: number; fadeMs: number;
    scenes: Record<AudioScene, { assetId: string; loop: boolean }>;
    effects: Record<AudioEffect, string>;
}
export interface AudioCatalog {
    version: number; settings: AudioSettings;
    assets: { id: string; name: string; url: string; sha256: string; seconds: number }[];
}
export interface AudioPreferences { musicEnabled?: boolean; musicVolume?: number; effectsEnabled?: boolean; effectsVolume?: number }
export interface AudioDriver<T> {
    load(url: string): Promise<T>; play(clip: T, loop: boolean): void; resume(): void;
    pause(): void; stop(): void; volume(value: number): void;
    effect(clip: T, volume: number): void; stopEffects(): void;
}
const inRange = (v: unknown, max: number) => typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= max;
export function validAudioCatalog(c: AudioCatalog): boolean {
    if (!c || !Number.isSafeInteger(c.version) || c.version < 0 || !c.settings || !Array.isArray(c.assets) || c.assets.length > 16) return false;
    const s = c.settings;
    if (typeof s.musicEnabled !== 'boolean' || typeof s.effectsEnabled !== 'boolean' || !inRange(s.musicVolume,100) || !inRange(s.effectsVolume,100) || !inRange(s.fadeMs,3000)) return false;
    const ids = new Set<string>();
    for (const a of c.assets) {
        if (!a || ids.has(a.id) || !/^(morning-town|friends-play|[a-f0-9-]{36})$/.test(a.id) || a.url !== '/api/audio/assets/' + a.id + '.wav' || !/^[a-f0-9]{64}$/.test(a.sha256)) return false;
        ids.add(a.id);
    }
    return AUDIO_SCENES.every(id => !!s.scenes?.[id] && typeof s.scenes[id].loop === 'boolean' && (s.scenes[id].assetId === '' || ids.has(s.scenes[id].assetId)))
        && AUDIO_EFFECTS.every(id => typeof s.effects?.[id] === 'string' && (s.effects[id] === '' || ids.has(s.effects[id])));
}
export function cleanAudioPreferences(raw: unknown): AudioPreferences {
    const out: AudioPreferences = {};
    if (!raw || typeof raw !== 'object') return out;
    const p = raw as AudioPreferences;
    if (typeof p.musicEnabled === 'boolean') out.musicEnabled = p.musicEnabled;
    if (typeof p.effectsEnabled === 'boolean') out.effectsEnabled = p.effectsEnabled;
    if (inRange(p.musicVolume,100)) out.musicVolume = p.musicVolume;
    if (inRange(p.effectsVolume,100)) out.effectsVolume = p.effectsVolume;
    return out;
}

/** Single music channel, bounded stale-load protection, player overrides and explicit gesture gating. */
export class AudioState<T> {
    catalog: AudioCatalog | null = null;
    private base = '';
    private scene: AudioScene | null = null;
    private visible = true;
    private unlocked = false;
    private preferences: AudioPreferences;
    private wanted = '';
    private active = '';
    private loading = '';
    private failed = '';
    private pending: { key: string; clip: T; loop: boolean } | null = null;
    private paused = false;
    private value = 0;
    private generation = 0;
    private destroyed = false;
    private effectsSeen = new Set<string>();
    private effectsWasAllowed = false;
    constructor(private driver: AudioDriver<T>, preferences: unknown = {}, private persist: (p: AudioPreferences) => void = () => undefined) {
        this.preferences = cleanAudioPreferences(preferences);
    }
    get values(): Required<AudioPreferences> {
        const s = this.catalog?.settings;
        return { musicEnabled: this.preferences.musicEnabled ?? s?.musicEnabled ?? false, musicVolume: this.preferences.musicVolume ?? s?.musicVolume ?? 0,
            effectsEnabled: this.preferences.effectsEnabled ?? s?.effectsEnabled ?? false, effectsVolume: this.preferences.effectsVolume ?? s?.effectsVolume ?? 0 };
    }
    get diagnostics(): object { return { active:this.active,wanted:this.wanted,loading:this.loading,failed:this.failed,visible:this.visible,unlocked:this.unlocked,paused:this.paused,volume:this.value }; }
    configure(catalog: AudioCatalog, base: string): void {
        if (!validAudioCatalog(catalog) || this.destroyed) return;
        this.catalog = catalog; this.base = base.replace(/\/$/,''); this.update(0);
    }
    setScene(scene: AudioScene | null): void { this.scene = scene; this.update(0); }
    gesture(): void { this.unlocked = true; this.failed = ''; this.update(0); }
    setVisible(visible: boolean): void { this.visible = visible; this.update(0); }
    setPreferences(p: AudioPreferences): void {
        this.preferences = { ...this.preferences, ...cleanAudioPreferences(p) }; this.persist(this.preferences); this.update(0);
    }
    reset(): void { this.preferences = {}; this.persist({}); this.update(0); }
    private musicAllowed(): boolean { return this.visible && this.unlocked && !!this.catalog?.settings.musicEnabled && this.values.musicEnabled && this.values.musicVolume > 0; }
    private effectsAllowed(): boolean { return this.visible && this.unlocked && !!this.catalog?.settings.effectsEnabled && this.values.effectsEnabled && this.values.effectsVolume > 0; }
    update(dt: number): void {
        if (this.destroyed) return;
        const binding = this.scene ? this.catalog?.settings.scenes[this.scene] : null;
        const asset = this.catalog?.assets.find(a => a.id === binding?.assetId);
        const key = asset && binding ? this.base + asset.url + ':' + binding.loop : '';
        if (key !== this.wanted) { this.wanted = key; this.generation++; this.pending = null; this.loading = ''; this.failed = ''; }
        const effectsAllowed = this.effectsAllowed();
        if (!effectsAllowed && this.effectsWasAllowed) this.driver.stopEffects();
        this.effectsWasAllowed = effectsAllowed;
        if (!this.musicAllowed()) {
            if (this.active && !this.paused) { this.driver.pause(); this.paused = true; }
            this.value = 0; this.driver.volume(0);
            return;
        }
        if (key && key !== this.active && this.loading !== key && this.failed !== key && !this.pending && asset && binding) {
            this.loading = key; const sequence = this.generation;
            void this.driver.load(this.base + asset.url).then(clip => {
                if (this.destroyed || sequence !== this.generation || key !== this.wanted) return;
                this.loading = ''; this.pending = { key, clip, loop: binding.loop }; this.update(0);
            }).catch(() => { if (sequence === this.generation) { this.loading = ''; this.failed = key; } });
        }
        if (this.paused && this.active && this.active === key) {
            try { this.driver.resume(); this.paused = false; } catch { return; }
        }
        const target = this.active && this.active === key ? this.values.musicVolume / 100 : 0;
        const seconds = (this.catalog?.settings.fadeMs ?? 0) / 1000;
        const delta = seconds > 0 ? Math.max(0,dt) / seconds : 1;
        this.value = this.value < target ? Math.min(target,this.value+delta) : Math.max(target,this.value-delta);
        this.driver.volume(this.value);
        if (this.active !== key && this.value === 0) {
            if (this.active) this.driver.stop();
            this.active = ''; this.paused = false;
            if (this.pending?.key === key) {
                try { this.driver.play(this.pending.clip,this.pending.loop); this.active = key; }
                catch { this.failed = key; }
                this.pending = null;
                if (seconds === 0) { this.value = this.values.musicVolume/100; this.driver.volume(this.value); }
            }
        }
    }
    async effect(effect: AudioEffect, identity?: string): Promise<void> {
        if (this.destroyed || !this.effectsAllowed()) return;
        const id = this.catalog?.settings.effects[effect], asset = this.catalog?.assets.find(a=>a.id===id);
        if (!asset) return;
        if (identity) { if (this.effectsSeen.has(identity)) return; this.effectsSeen.add(identity); if (this.effectsSeen.size>128)this.effectsSeen.delete(this.effectsSeen.values().next().value!); }
        const version = this.catalog?.version, since = Date.now();
        try {
            const clip = await this.driver.load(this.base+asset.url);
            if (!this.destroyed && this.effectsAllowed() && this.catalog?.version === version && Date.now()-since<500)
                this.driver.effect(clip,this.values.effectsVolume/100);
        } catch { /* Missing/slow audio never delays or blocks the action. */ }
    }
    dispose(): void { this.destroyed = true; this.generation++; this.driver.stop(); this.driver.stopEffects(); }
}
