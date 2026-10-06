/**
 * 骰子视图与翻滚动画（分镜：准备→抛起→翻滚→落地→回弹→停稳）。总时长取 Theme.anim.diceMs（建议 1.2 秒，待体验调整）。
 * 点数由调用方给定（对局结果），动画只是表现：翻滚中面点数快速变化，落地后固定为最终点数。
 * 用 Date.now() 时间戳驱动（不用帧计数）。body 节点锚点居中，便于旋转/缩放。
 */
import { Graphics, Node, UITransform } from 'cc';
import { Theme } from '../core/Theme';
import { drawPips } from './Icons';
import { col, fillCircle, gfx, mk } from './Kit';

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
        if (!a) return;
        const now = Date.now();
        const t = Math.min(1, (now - a.start) / Theme.anim.diceMs);
        let y = 0;
        let sx = 1;
        let sy = 1;
        let ang = 0;
        let halo: 'none' | 'impact' | 'glow' = 'none';
        let hk = 0;
        const AIR0 = 0.1;
        const AIR1 = 0.62;
        if (t < AIR0) {
            // 准备：压扁蓄力
            const k = t / AIR0;
            sy = 1 - 0.14 * k;
            sx = 1 + 0.08 * k;
        } else if (t < AIR1) {
            // 抛起 + 翻滚：抛物线，旋转 540°
            const k = (t - AIR0) / (AIR1 - AIR0);
            y = 120 * Math.sin(Math.PI * k);
            ang = -540 * k;
            sx = 1 + 0.06 * Math.sin(Math.PI * k);
            sy = sx;
            if (now >= a.flipAt) {
                a.flipAt = now + 70;
                a.shown = 1 + Math.floor(Math.random() * 6);
                this.drawBody(a.shown);
            }
        } else if (t < 0.72) {
            // 落地：压扁 + 冲击线，点数定为最终值
            const k = (t - AIR1) / (0.72 - AIR1);
            if (a.shown !== a.value) {
                a.shown = a.value;
                this.drawBody(a.value);
            }
            sy = 0.8 + 0.2 * k;
            sx = 1.16 - 0.16 * k;
            halo = 'impact';
            hk = k;
        } else if (t < 0.88) {
            // 回弹
            const k = (t - 0.72) / 0.16;
            y = 22 * Math.sin(Math.PI * k);
            ang = 10 * Math.sin(Math.PI * k);
        } else {
            // 停稳：发光
            const k = (t - 0.88) / 0.12;
            const pulse = 1 + 0.06 * Math.sin(Math.PI * k);
            sx = pulse;
            sy = pulse;
            halo = 'glow';
            hk = k;
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
