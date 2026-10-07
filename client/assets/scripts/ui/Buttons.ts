/** 通用按钮：PrimaryButton(黄) / SecondaryButton(蓝) / 危险 / 幽灵(象牙) / IconButton。 */
import { Graphics, Label, Node } from 'cc';
import { Theme, textWidth } from '../core/Theme';
import { art } from './Art';
import { drawCoin } from './Icons';
import { fillCircle, fillRR, gfx, mk, onTap, setText, strokeCircle, strokeRR, text, TextOpts } from './Kit';
import { Toast } from './Toast';

export type BtnStyle = 'primary' | 'secondary' | 'danger' | 'ghost' | 'success' | 'soft' | 'ghostRed' | 'disabled';

interface Skin { face: string; edge: string; text: string; stroke?: string }

function skinOf(s: BtnStyle): Skin {
    const c = Theme.c;
    switch (s) {
        case 'primary': return { face: c.yellow, edge: c.yellowDark, text: c.yellowText };
        case 'secondary': return { face: c.blue, edge: c.blueDark, text: c.white };
        case 'danger': return { face: c.red, edge: c.redDark, text: c.white };
        case 'success': return { face: c.green, edge: c.greenDark, text: c.white };
        case 'ghost': return { face: c.ivory, edge: c.ivoryLine, text: c.ink, stroke: c.ivoryLine };
        // 设计稿 05 的"确认破产"：浅色底、红字
        case 'ghostRed': return { face: c.ivory, edge: c.ivoryLine, text: c.payRed, stroke: c.ivoryLine };
        // 设计稿 04 的"放弃 / 不使用 / 拒绝"：浅蓝底、深蓝字（没有对应位图，按稿面用图形绘制）
        case 'soft': return { face: c.softBlue, edge: c.softBlueEdge, text: c.navy };
        default: return { face: c.gray, edge: c.grayDark, text: c.white };
    }
}

export class Button {
    readonly node: Node;
    private readonly label: Label;
    private readonly w: number;
    private readonly h: number;
    private style: BtnStyle;
    private enabledFlag = true;
    private disabledHint = '';
    private readonly edge = 6;
    private skin: Node | null = null;
    /** 设计稿里"购买 (金币) 1000"这类内容：文字 + 金币 + 金额，整体居中 */
    private coinAmount: number | null = null;
    private coinRow: Node | null = null;
    private readonly fontSize: number;

    constructor(
        parent: Node, caption: string, x: number, y: number, w: number, h: number,
        style: BtnStyle, private onClick: () => void, fontSize: number = Theme.font.lg,
    ) {
        this.w = w;
        this.h = h;
        this.style = style;
        this.fontSize = fontSize;
        this.node = mk(parent, 'Btn:' + caption, x, y, w, h);
        const o: TextOpts = { bold: true };
        this.label = text(this.node, caption, 8, 0, w - 16, h - this.edge, fontSize, skinOf(style).text, o);
        this.paint();
        onTap(this.node, () => {
            if (!this.enabledFlag) {
                if (this.disabledHint) Toast.show(this.disabledHint);
                return;
            }
            this.onClick();
        });
    }

    private paint(): void {
        const g = gfx(this.node);
        g.clear();
        const k = skinOf(this.enabledFlag ? this.style : 'disabled');
        this.skin?.removeFromParent();
        this.skin?.destroy();
        const keys: Record<BtnStyle, string> = { primary: 'button_flat_yellow', secondary: 'button_flat_blue',
            success: 'button_flat_green', ghost: 'button_flat_ivory', ghostRed: 'button_flat_ivory', danger: 'button_flat_red', soft: '', disabled: 'button_flat_gray' };
        const key = keys[this.enabledFlag ? this.style : 'disabled'];
        if (!key) {
            // 浅蓝按钮：底边 + 面 + 上半高光 + 描边，圆头与位图按钮一致
            const r = (this.h - 4) / 2;
            fillRR(g, 0, 4, this.w, this.h - 4, r, k.edge);
            fillRR(g, 0, 0, this.w, this.h - 4, r, k.face);
            fillRR(g, 6, 4, this.w - 12, (this.h - 4) * 0.42, r * 0.8, Theme.c.softBlueHi);
            strokeRR(g, 1, 1, this.w - 2, this.h - 6, r, k.edge, 2);
            this.skin = null;
            this.paintContent(k.text);
            return;
        }
        this.skin = art(this.node, key, 0, 0, this.w, this.h, 'capsule');
        if (this.skin) {
            this.skin.setSiblingIndex(0);
            this.paintContent(k.text);
            return;
        }
        // 皮肤属首屏必需图（启动时加载）；万一没有，不另画替代皮肤。
        this.paintContent(k.text);
    }

    /** 带金额的按钮内容（设计稿 04："购买 (金币) 1000"、"升级 (金币) 600"、"同意 (金币) 1200"）。 */
    withCoin(amount: number): this {
        this.coinAmount = amount;
        this.paint();
        return this;
    }

    private paintContent(color: string): void {
        this.coinRow?.destroy();
        this.coinRow = null;
        if (this.coinAmount === null) {
            this.label.node.active = true;
            setText(this.label, this.label.string, color);
            return;
        }
        // 文字 + 金币 + 金额整体居中
        this.label.node.active = false;
        const fs = this.fontSize;
        const caption = this.label.string;
        const amount = String(this.coinAmount);
        const d = Math.round(fs * 1.1);
        const capW = textWidth(caption, fs);
        const amtW = textWidth(amount, fs);
        const gap = Math.round(fs * 0.3);
        const total = capW + gap + d + gap * 0.6 + amtW;
        const hh = this.h - this.edge;
        const row = mk(this.node, 'CoinRow', (this.w - total) / 2, 0, total, hh);
        text(row, caption, 0, 0, capW + 4, hh, fs, color, { bold: true, align: 'l' });
        const c = gfx(mk(row, 'Coin', capW + gap, (hh - d) / 2, d, d));
        drawCoin(c, d / 2, d / 2, d / 2);
        text(row, amount, capW + gap + d + gap * 0.6, 0, amtW + 8, hh, fs, color, { bold: true, align: 'l' });
        this.coinRow = row;
    }

    setText(s: string): void {
        setText(this.label, s);
    }

    setStyle(s: BtnStyle): void {
        this.style = s;
        this.paint();
    }

    /** 禁用；hint 为点击禁用按钮时的 Toast 提示（说明为什么不能点）。 */
    setEnabled(on: boolean, hint = ''): void {
        this.enabledFlag = on;
        this.disabledHint = hint;
        this.paint();
    }

    get enabled(): boolean {
        return this.enabledFlag;
    }

    setOnClick(fn: () => void): void {
        this.onClick = fn;
    }
}

export function primaryButton(parent: Node, caption: string, x: number, y: number, w: number, h: number, fn: () => void, size?: number): Button {
    return new Button(parent, caption, x, y, w, h, 'primary', fn, size);
}

export function secondaryButton(parent: Node, caption: string, x: number, y: number, w: number, h: number, fn: () => void, size?: number): Button {
    return new Button(parent, caption, x, y, w, h, 'secondary', fn, size);
}

/** 浅蓝次要按钮（设计稿 04 的"放弃 / 不使用 / 拒绝"）。 */
export function softButton(parent: Node, caption: string, x: number, y: number, w: number, h: number, fn: () => void, size?: number): Button {
    return new Button(parent, caption, x, y, w, h, 'soft', fn, size);
}

export function ghostButton(parent: Node, caption: string, x: number, y: number, w: number, h: number, fn: () => void, size?: number): Button {
    return new Button(parent, caption, x, y, w, h, 'ghost', fn, size);
}

/** 浅色底红字按钮（设计稿 05 的"确认破产"）。 */
export function ghostRedButton(parent: Node, caption: string, x: number, y: number, w: number, h: number, fn: () => void, size?: number): Button {
    return new Button(parent, caption, x, y, w, h, 'ghostRed', fn, size);
}

export function dangerButton(parent: Node, caption: string, x: number, y: number, w: number, h: number, fn: () => void, size?: number): Button {
    return new Button(parent, caption, x, y, w, h, 'danger', fn, size);
}

/** 圆形图标按钮；glyph 是单个文字符号（如 ☰ ‹ ✕），也可传 draw 回调自绘图标。 */
export class IconButton {
    readonly node: Node;
    private readonly g: Graphics;
    constructor(
        parent: Node, x: number, y: number, size: number, glyph: string, fn: () => void,
        private readonly bg: string = Theme.c.ivory, fg: string = Theme.c.ink,
        private readonly draw?: (g: Graphics, size: number) => void,
    ) {
        this.node = mk(parent, 'Icon:' + glyph, x, y, size, size);
        this.g = gfx(this.node);
        this.repaint(size);
        if (glyph) text(this.node, glyph, 0, 0, size, size, Math.round(size * 0.5), fg, { bold: true });
        onTap(this.node, fn);
    }

    private repaint(size: number): void {
        this.g.clear();
        fillCircle(this.g, size / 2, size / 2 + 3, size / 2, Theme.c.shadow);
        fillCircle(this.g, size / 2, size / 2, size / 2, this.bg);
        strokeCircle(this.g, size / 2, size / 2, size / 2 - 1, Theme.c.ivoryLine, 2);
        if (this.draw) this.draw(this.g, size);
    }
}
