/** 通用控件：RoundedPanel、Avatar、Chip、CountdownRing/Label、Segmented 选项组。 */
import { Graphics, Label, Node } from 'cc';
import { Countdown } from '../core/Clock';
import { Theme, textWidth } from '../core/Theme';
import {
    col, fillCircle, fillRR, gfx, line, mk, onTap, paintPanel, PanelOpts, setText, strokeCircle, strokeRR, text,
} from './Kit';
import { Toast } from './Toast';
import { art, characterKey } from './Art';

/** 圆角面板（象牙色卡片）。 */
export function roundedPanel(parent: Node, x: number, y: number, w: number, h: number, o: PanelOpts = {}): Node {
    const n = mk(parent, 'Panel', x, y, w, h);
    paintPanel(n, w, h, { shadow: 6, ...o });
    return n;
}

export interface AvatarOpts {
    ring?: string;
    dim?: boolean;
    showName?: boolean;
}

/** 设计稿的八个内置头像；破产/认输使用灰显。 */
export function avatar(parent: Node, x: number, y: number, size: number, idx: number, name: string, o: AvatarOpts = {}): Node {
    const n = mk(parent, 'Avatar:' + name, x, y, size, size);
    const g = gfx(n);
    if (art(n, characterKey(idx), 0, 0, size, size, 'contain', !!o.dim)) {
        if (o.ring) strokeCircle(g, size / 2, size / 2, size / 2 - 2, o.ring, 4);
        return n;
    }
    const base = Theme.avatarColors[((idx % 8) + 8) % 8];
    fillCircle(g, size / 2, size / 2, size / 2, o.dim ? Theme.c.gray : base);
    fillCircle(g, size / 2, size * 0.36, size * 0.2, '#FFFFFF55'); // 头
    fillRR(g, size * 0.22, size * 0.58, size * 0.56, size * 0.3, size * 0.15, '#FFFFFF33'); // 肩
    strokeCircle(g, size / 2, size / 2, size / 2 - 1.5, o.ring ?? Theme.c.white, o.ring ? 4 : 3);
    const ch = Array.from(name)[0] ?? '?';
    text(n, ch, 0, 0, size, size, Math.round(size * 0.46), Theme.c.white, { bold: true });
    return n;
}

/** 小标签（Chip）：自适应宽度，返回节点与宽度。 */
export function chip(
    parent: Node, x: number, y: number, str: string, bg: string, fg: string,
    size: number = Theme.font.xs, h = 0,
): { node: Node; w: number; h: number } {
    const hh = h || size + 14;
    const w = textWidth(str, size) + 22;
    const n = mk(parent, 'Chip:' + str, x, y, w, hh);
    fillRR(gfx(n), 0, 0, w, hh, hh / 2, bg);
    text(n, str, 0, 0, w, hh, size, fg, { bold: true });
    return { node: n, w, h: hh };
}

/** 倒计时环：位置/尺寸由调用方决定；弹窗里统一放在面板右上角（见 Popup）。 */
export class CountdownRing {
    readonly node: Node;
    private readonly ringNode: Node;
    private readonly label: Label;
    private lastKey = '';
    constructor(parent: Node, x: number, y: number, private readonly size: number) {
        this.node = mk(parent, 'CountdownRing', x, y, size, size);
        this.ringNode = mk(this.node, 'Arc', 0, 0, size, size);
        this.label = text(this.node, '', 0, 0, size, size, Math.round(size * 0.42), Theme.c.ink, { bold: true });
    }

    update(cd: Countdown): void {
        const sec = cd.remainingSec();
        const f = cd.fraction();
        const key = sec + ':' + Math.round(f * 90);
        if (key === this.lastKey) return;
        this.lastKey = key;
        const urgent = sec <= 3;
        setText(this.label, String(sec), urgent ? Theme.c.red : Theme.c.ink);
        const g = gfx(this.ringNode);
        g.clear();
        const s = this.size;
        const r = s / 2 - 6;
        fillCircle(g, s / 2, s / 2, s / 2, Theme.c.white);
        strokeCircle(g, s / 2, s / 2, r, Theme.c.ivoryLine, 6);
        drawArc(g, s / 2, s / 2, r, f, urgent ? Theme.c.red : Theme.c.blue, 6);
    }
}

/**
 * 倒计时徽标：时钟图标 + "N秒"（橙红粗体）。依据 design/screens/04、05 弹窗右上角 "⏱15秒" 与 07 虎口拔牙 "轮到小林 ⏱10秒"。
 * 与 CountdownRing 同样的 update(cd) 接口；最后 3 秒文字变深红。
 */
export class CountdownBadge {
    readonly node: Node;
    private readonly label: Label;
    private lastSec = -1;
    constructor(parent: Node, x: number, y: number, w = 140, h = 48) {
        this.node = mk(parent, 'CountdownRing', x, y, w, h);
        const icon = gfx(mk(this.node, 'ClockIcon', 0, 0, h, h));
        strokeCircle(icon, h / 2, h / 2, h * 0.36, Theme.c.orange, 4);
        line(icon, h / 2, h / 2, h / 2, h * 0.28, Theme.c.orange, 4);
        line(icon, h / 2, h / 2, h * 0.68, h / 2, Theme.c.orange, 4);
        fillCircle(icon, h / 2, h * 0.12, 3, Theme.c.orange);
        this.label = text(this.node, '', h + 2, 0, w - h - 2, h, Theme.font.lg, Theme.c.red, { bold: true, align: 'l' });
    }

    update(cd: Countdown): void {
        const sec = cd.remainingSec();
        if (sec === this.lastSec) return;
        this.lastSec = sec;
        setText(this.label, sec + '秒', sec <= 3 ? Theme.c.redDark : Theme.c.red);
    }
}

/** 从 12 点方向顺时针画占比 frac 的弧（用折线，避免 arc 方向语义歧义）。 */
export function drawArc(g: Graphics, cx: number, cy: number, r: number, frac: number, color: string, lw: number): void {
    if (frac <= 0) return;
    const n = Math.max(2, Math.ceil(48 * Math.min(1, frac)));
    g.strokeColor = col(color);
    g.lineWidth = lw;
    g.lineCap = Graphics.LineCap.ROUND;
    for (let i = 0; i <= n; i++) {
        const a = Math.PI / 2 - (i / n) * frac * Math.PI * 2;
        const px = cx + r * Math.cos(a);
        const py = -(cy - r * Math.sin(a)) ; // 局部 y 向下：cy - r*sin
        if (i === 0) g.moveTo(px, py);
        else g.lineTo(px, py);
    }
    g.stroke();
}

/** "剩余 24秒" 样式的文字倒计时。 */
export class CountdownLabel {
    readonly label: Label;
    constructor(parent: Node, x: number, y: number, w: number, h: number, size: number, private prefix = '剩余 ') {
        this.label = text(parent, '', x, y, w, h, size, Theme.c.ink, { bold: true });
    }

    update(cd: Countdown): void {
        const s = cd.remainingSec();
        setText(this.label, this.prefix + s + '秒', s <= 3 ? Theme.c.red : Theme.c.ink);
    }
}

export interface SegOption {
    label: string;
    value: string | number;
    disabled?: boolean;
    /** 禁用时点击的提示 */
    note?: string;
}

/** 选项组（Segmented）：选中=蓝描边+对勾；可禁用单项；可整体锁定（非房主）。 */
export class Segmented {
    readonly node: Node;
    private items: { opt: SegOption; node: Node }[] = [];
    private selected: string | number;
    locked = false;
    lockHint = '仅房主可修改';

    constructor(
        parent: Node, x: number, y: number, w: number, h: number, private options: SegOption[],
        selected: string | number, private onChange: (v: string | number) => void, gap = 12, size: number = Theme.font.md,
    ) {
        this.node = mk(parent, 'Segmented', x, y, w, h);
        this.selected = selected;
        const iw = (w - gap * (options.length - 1)) / options.length;
        options.forEach((opt, i) => {
            const n = mk(this.node, 'Seg:' + opt.label, i * (iw + gap), 0, iw, h);
            text(n, opt.label, 28, 0, iw - 30, h, size, Theme.c.ink, { bold: true });
            this.items.push({ opt, node: n });
            onTap(n, () => this.tap(opt));
        });
        this.itemW = iw;
        this.itemH = h;
        this.repaint();
    }
    private itemW = 0;
    private itemH = 0;

    private tap(opt: SegOption): void {
        if (opt.disabled) {
            if (opt.note) Toast.show(opt.note);
            return;
        }
        if (this.locked) {
            Toast.show(this.lockHint);
            return;
        }
        if (this.selected === opt.value) return;
        this.selected = opt.value;
        this.repaint();
        this.onChange(opt.value);
    }

    setSelected(v: string | number): void {
        this.selected = v;
        this.repaint();
    }

    setDisabled(value: string | number, disabled: boolean, note = ''): void {
        const it = this.items.find((x) => x.opt.value === value);
        if (it) {
            it.opt.disabled = disabled;
            it.opt.note = note;
        }
        this.repaint();
    }

    private repaint(): void {
        const w = this.itemW;
        const h = this.itemH;
        for (const { opt, node } of this.items) {
            const g = gfx(node);
            g.clear();
            const sel = this.selected === opt.value && !opt.disabled;
            fillRR(g, 0, 0, w, h, h / 2, opt.disabled ? '#E9EDF1' : sel ? Theme.c.blueSoft : Theme.c.white);
            strokeRR(g, 0, 0, w, h, h / 2, sel ? Theme.c.blue : Theme.c.ivoryLine, sel ? 3 : 2);
            if (sel) {
                fillCircle(g, 22, h / 2, 11, Theme.c.blue);
                line(g, 17, h / 2, 21, h / 2 + 4, Theme.c.white, 3);
                line(g, 21, h / 2 + 4, 28, h / 2 - 4, Theme.c.white, 3);
            }
            const lb = node.children[0].getComponent(Label) as Label;
            lb.color = col(opt.disabled ? Theme.c.inkFaint : Theme.c.ink);
        }
    }
}
