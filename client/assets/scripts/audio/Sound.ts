import { assetManager, AudioClip, AudioSource, game, Game, input, Input, Node, sys } from 'cc';
import { AudioCatalog, AudioDriver, AudioEffect, AudioPreferences, AudioScene, AudioState } from '../core/AudioState';
import { requestJson } from '../net/Http';
import { onlineEnabled, serverUrl } from '../net/Config';

const KEY = 'millionnaire-audio-preferences-v1';
class CocosAudioDriver implements AudioDriver<AudioClip> {
    private music: AudioSource;
    private effects: AudioSource[];
    private nextEffect = 0;
    private cache = new Map<string, AudioClip>();
    private requests = new Map<string, Promise<AudioClip>>();
    private disposed = false;
    readonly node: Node;
    constructor(parent: Node) {
        this.node = new Node('Sound'); parent.addChild(this.node);
        this.music = this.source('Music'); this.effects = [this.source('Effect1'),this.source('Effect2'),this.source('Effect3')];
        if (!onlineEnabled()) this.music.node.on(AudioSource.EventType.STARTED, () => {
            console.debug('[sound] music started',JSON.stringify({playing:this.music.playing,seconds:this.music.duration,loop:this.music.loop}));
        });
    }
    private source(name: string): AudioSource { const n = new Node(name); this.node.addChild(n); const source = n.addComponent(AudioSource); source.playOnAwake = false; return source; }
    load(url: string): Promise<AudioClip> {
        const hit = this.cache.get(url); if (hit) return Promise.resolve(hit);
        const pending = this.requests.get(url); if (pending) return pending;
        const promise = new Promise<AudioClip>((resolve,reject) => {
            assetManager.loadRemote<AudioClip>(url, { ext: '.wav', maxRetryCount: 0 }, (error,clip) => {
                this.requests.delete(url);
                if (error || !clip || this.disposed) {
                    if (!onlineEnabled()) console.debug('[sound] load failed',url);
                    reject(error ?? new Error('audio unavailable')); return;
                }
                if (!onlineEnabled()) console.debug('[sound] loaded',url);
                clip.addRef(); this.cache.set(url,clip);
                if (this.cache.size > 16) for (const [key,old] of this.cache) {
                    if (old !== clip && old !== this.music.clip && !this.effects.some(e=>e.clip===old)) { this.cache.delete(key); old.decRef(); break; }
                }
                resolve(clip);
            });
        });
        this.requests.set(url,promise); return promise;
    }
    play(clip: AudioClip, loop: boolean): void { this.music.stop(); this.music.clip = clip; this.music.loop = loop; this.music.play(); }
    resume(): void { this.music.play(); }
    pause(): void { this.music.pause(); }
    stop(): void { this.music.stop(); }
    volume(value: number): void { this.music.volume = value; }
    effect(clip: AudioClip, volume: number): void { const s = this.effects[this.nextEffect++ % this.effects.length]; s.stop(); s.clip=clip;s.loop=false;s.volume=volume;s.play(); }
    stopEffects(): void { for (const source of this.effects) source.stop(); }
    dispose(): void { this.disposed=true; this.stop();this.stopEffects();this.node.destroy();for(const clip of this.cache.values())clip.decRef();this.cache.clear(); }
}

class SoundManager {
    state: AudioState<AudioClip> | null = null;
    private driver: CocosAudioDriver | null = null;
    private fetching = false;
    private lastFetch = 0;
    private readonly unlock = () => this.state?.gesture();
    private readonly hide = () => this.state?.setVisible(false);
    private readonly show = () => { this.state?.setVisible(true); void this.refresh(); };
    private readonly visibility = () => this.state?.setVisible(!document.hidden);
    private listeners = new Set<() => void>();
    init(parent: Node): void {
        this.dispose(); this.lastFetch = 0;
        this.driver = new CocosAudioDriver(parent);
        let preferences: unknown = {}; try { preferences = JSON.parse(sys.localStorage.getItem(KEY) ?? '{}'); } catch { /* corrupt local data falls back to server defaults */ }
        this.state = new AudioState(this.driver,preferences,p=>{ try { sys.localStorage.setItem(KEY,JSON.stringify(p)); } catch { /* storage denial does not block playback */ } });
        input.on(Input.EventType.TOUCH_START,this.unlock);input.on(Input.EventType.MOUSE_DOWN,this.unlock);
        game.on(Game.EVENT_HIDE,this.hide);game.on(Game.EVENT_SHOW,this.show);
        if (typeof document !== 'undefined') {document.addEventListener('visibilitychange',this.visibility);document.addEventListener('pointerdown',this.unlock);this.visibility();}
        void this.refresh();
    }
    async refresh(): Promise<void> {
        if (!this.state || this.fetching || Date.now()-this.lastFetch<3000) return;
        this.fetching = true; this.lastFetch = Date.now(); const state = this.state;
        try { const base = serverUrl();const r=await requestJson<AudioCatalog>('GET',base+'/api/audio');if(r.status===200&&r.body&&this.state===state){state.configure(r.body,base);this.notify();} }
        catch { /* keep the last valid catalog on transient failures; first failure stays silent */ }
        finally { this.fetching=false; }
    }
    scene(id: string): void {
        const mapping: Record<string,AudioScene> = { lobby:'lobby',room:'room',series:'room',board:'board',spectator:'board',teeth:'game',result:'result' };
        this.state?.setScene(mapping[id] ?? null);
        if (!onlineEnabled()) console.debug('[sound] scene',id,JSON.stringify(this.state?.diagnostics));
        void this.refresh();
    }
    update(dt: number): void { this.state?.update(dt); }
    effect(id: AudioEffect, identity?: string): void { void this.state?.effect(id,identity); }
    setPreferences(p: AudioPreferences, notify=true): void { this.state?.setPreferences(p);if(notify)this.notify(); }
    reset(): void { this.state?.reset();this.notify(); }
    watch(listener: () => void): void { this.listeners.add(listener); }
    unwatch(listener: () => void): void { this.listeners.delete(listener); }
    private notify(): void { for(const listener of this.listeners)listener(); }
    dispose(): void {
        input.off(Input.EventType.TOUCH_START,this.unlock);input.off(Input.EventType.MOUSE_DOWN,this.unlock);
        game.off(Game.EVENT_HIDE,this.hide);game.off(Game.EVENT_SHOW,this.show);
        if(typeof document !== 'undefined'){document.removeEventListener('visibilitychange',this.visibility);document.removeEventListener('pointerdown',this.unlock);}
        this.state?.dispose();this.driver?.dispose();this.state=null;this.driver=null;this.listeners.clear();
    }
}
export const Sound = new SoundManager();
