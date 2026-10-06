/**
 * 骰子视图与翻滚动画（分镜：准备→抛起→翻滚→落地→回弹→停稳）。总时长取 Theme.anim.diceMs（建议 1.2 秒，待体验调整）。
 * 点数由调用方给定（对局结果），动画只是表现：翻滚中面点数快速变化，落地后固定为最终点数。
 * 用 Date.now() 时间戳驱动（不用帧计数）。body 节点锚点居中，便于旋转/缩放。
 */
import { Graphics, Node, UITransform } from 'cc';
import { Theme } from '../core/Theme';
import { drawPips } from './Icons';
import { col, fillCircle, gfx, mk } from './Kit';
import { art } from './Art';

const S = 108; // 容器边长
const HALF = 46; // 骰子半边长

export class DiceView {
    readonly node: Node;
    private readonly body: Node;
    private readonly shadow: Node;
    private readonly halo: Node;
    private value = 1;
    private anim: { start: number; value: number; flipAt: number; shown: number; onDone: (() => void) | null } | null = null;
    private last = 0;
    private face: Node | null = null;
    private shown = 0;
    private faces = new Map<string, Node>();
    private faceKey = '';
    private ready = false;
    private readySince = 0;

    constructor(parent: Node, x: number, y: number) {
        this.node = mk(parent, 'Dice', x, y, S, S);
        this.halo = this.centered(mk(this.node, 'Halo', S / 2, S / 2, S, S));
        this.shadow = this.centered(mk(this.node, 'DiceShadow', S / 2, S / 2 + 56, S, 30));
        this.body = this.centered(mk(this.node, 'DiceBody', S / 2, S / 2, S, S));
        this.drawBody(1);
        this.drawShadow(1);
    }

    private centered(n: Node): Node {
        (n.getComponent(UITransform) as UITransform).setAnchorPoint(0.5, 0.5);
        return n;
    }

    get playing(): boolean {
        return this.anim !== null;
    }

    /** 本人可投骰时循环放大/缩小；翻滚开始立即停止提示。 */
    setReady(on: boolean): void {
        if (this.ready === on) return;
        this.ready = on;
        this.readySince = Date.now();
        if (!this.anim) this.body.setScale(1, 1, 1);
    }

    setValue(v: number): void {
        this.value = v;
        this.drawBody(v);
    }

    /** 开始一次翻滚，结束时停在 value 并回调。 */
    play(value: number, onDone?: () => void): void {
        this.anim = { start: Date.now(), value, flipAt: 0, shown: this.value, onDone: onDone ?? null };
        this.last = 0;
    }

    private drawBody(v: number): void {
        this.shown = v;
        if (this.drawFace('dice_' + v)) return;
        const g = gfx(this.body);
        g.clear();
        g.fillColor = col('#E4E0D4');
        g.roundRect(-HALF, -HALF - 4, 2 * HALF, 2 * HALF, 20);
        g.fill();
        g.fillColor = col(Theme.c.white);
        g.roundRect(-HALF, -HALF, 2 * HALF, 2 * HALF, 20);
        g.fill();
        g.fillColor = col('#F2F2F2');
        g.roundRect(-HALF + 5, -HALF + 5, 2 * HALF - 10, 2 * HALF - 10, 16);
        g.fill();
        drawPips(g, -HALF, -HALF, 2 * HALF, v, Theme.c.ink);
    }

    private drawFace(key: string): boolean {
        if (this.faceKey === key) return !!this.face;
        let face = this.faces.get(key);
        if (!face) {
            const loaded = art(this.body, key, -S / 2, -S / 2, S, S);
            if (!loaded) return false;
            face = loaded;
            this.faces.set(key, face);
        }
        if (this.face) this.face.active = false;
        this.face = face;
        this.face.active = true;
        this.faceKey = key;
        gfx(this.body).clear();
        return true;
    }

    private drawShadow(scale: number): void {
        const g = gfx(this.shadow);
        g.clear();
        g.fillColor = col('#00000030');
        g.ellipse(0, 0, 40 * scale, 9 * scale);
        g.fill();
    }

    private drawHalo(kind: 'none' | 'impact' | 'glow', k: number): void {
        const g = gfx(this.halo);
        g.clear();
        if (kind === 'impact') {
            g.strokeColor = col('#FFD96688');
            g.lineWidth = 5;
            for (let i = 0; i < 8; i++) {
                const a = (i / 8) * Math.PI * 2;
                const r0 = 52 + 4 * k;
                const r1 = r0 + 16;
                g.moveTo(Math.cos(a) * r0, Math.sin(a) * r0);
                g.lineTo(Math.cos(a) * r1, Math.sin(a) * r1);
            }
            g.stroke();
        } else if (kind === 'glow') {
            const a = Math.round(120 * (1 - k * 0.5));
            fillCircle(g as Graphics, 0, 0, 72 + 8 * k, '#FFE27A' + a.toString(16).padStart(2, '0'));
            fillCircle(g as Graphics, 0, 0, 58, '#FFF3B8' + Math.round(a * 0.8).toString(16).padStart(2, '0'));
        }
    }

    /** 每帧调用。 */
    update(): void {
        const a = this.anim;
        if (!a) {
            const k = this.ready ? (1 - Math.cos((Date.now() - this.readySince) / Theme.anim.diceReadyMs * Math.PI * 2)) / 2 : 0;
            const scale = 1 + 0.14 * k;
            this.body.setScale(scale, scale, 1);
            return;
        }
        const now = Date.now();
        const t = Math.min(1, (now - a.start) / Theme.anim.diceMs);
        let y = 0, sx = 1, sy = 1, ang = 0, hk = 0;
        let halo: 'none' | 'impact' | 'glow' = 'none';
        const AIR0 = 0.1, AIR1 = 0.62;
        if (t < AIR0) {
            const k = t / AIR0;
            sy = 1 - 0.14 * k; sx = 1 + 0.08 * k;
        } else if (t < AIR1) {
            const k = (t - AIR0) / (AIR1 - AIR0);
            y = 120 * Math.sin(Math.PI * k); ang = -540 * k;
            sx = sy = 1 + 0.06 * Math.sin(Math.PI * k);
            if (now >= a.flipAt) {
                a.flipAt = now + 70; a.shown = 1 + Math.floor(Math.random() * 6);
                this.drawBody(a.shown);
            }
        } else if (t < 0.72) {
            const k = (t - AIR1) / (0.72 - AIR1);
            if (a.shown !== a.value) { a.shown = a.value; this.drawBody(a.value); }
            sy = 0.8 + 0.2 * k; sx = 1.16 - 0.16 * k; halo = 'impact'; hk = k;
        } else if (t < 0.88) {
            const k = (t - 0.72) / 0.16;
            y = 22 * Math.sin(Math.PI * k); ang = 10 * Math.sin(Math.PI * k);
        } else {
            const k = (t - 0.88) / 0.12;
            sx = sy = 1 + 0.06 * Math.sin(Math.PI * k); halo = 'glow'; hk = k;
        }
        this.body.setPosition(S / 2, -S / 2 + y, 0);
        this.body.setScale(sx, sy, 1);
        this.body.angle = ang;
        const sh = Math.max(0.5, 1 - y / 220);
        if (Math.abs(sh - this.last) > 0.01) {
            this.last = sh;
            this.drawShadow(sh);
        }
        this.drawHalo(halo, hk);
        if (t >= 1) {
            this.anim = null;
            this.value = a.value;
            this.drawBody(a.value);
            this.drawHalo('none', 0);
            this.body.setScale(1, 1, 1);
            this.body.angle = 0;
            this.body.setPosition(S / 2, -S / 2, 0);
            this.drawShadow(1);
            a.onDone?.();
        }
    }
}
