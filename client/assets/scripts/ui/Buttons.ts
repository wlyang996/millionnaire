/** 通用按钮：PrimaryButton(黄) / SecondaryButton(蓝) / 危险 / 幽灵(象牙) / IconButton。 */
import { Graphics, Label, Node } from 'cc';
import { Theme } from '../core/Theme';
import { art } from './Art';
import { fillCircle, gfx, mk, onTap, setText, strokeCircle, text, TextOpts } from './Kit';
import { Toast } from './Toast';

export type BtnStyle = 'primary' | 'secondary' | 'danger' | 'ghost' | 'success' | 'disabled';

interface Skin { face: string; edge: string; text: string; stroke?: string }

function skinOf(s: BtnStyle): Skin {
    const c = Theme.c;
    switch (s) {
        case 'primary': return { face: c.yellow, edge: c.yellowDark, text: c.yellowText };
        case 'secondary': return { face: c.blue, edge: c.blueDark, text: c.white };
        case 'danger': return { face: c.red, edge: c.redDark, text: c.white };
        case 'success': return { face: c.green, edge: c.greenDark, text: c.white };
        case 'ghost': return { face: c.ivory, edge: c.ivoryLine, text: c.ink, stroke: c.ivoryLine };
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

    constructor(
        parent: Node, caption: string, x: number, y: number, w: number, h: number,
        style: BtnStyle, private onClick: () => void, fontSize: number = Theme.font.lg,
    ) {
        this.w = w;
        this.h = h;
        this.style = style;
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
            success: 'button_flat_green', ghost: 'button_flat_ivory', danger: 'button_flat_red', disabled: 'button_flat_gray' };
        this.skin = art(this.node, keys[this.enabledFlag ? this.style : 'disabled'], 0, 0, this.w, this.h, 'capsule');
        if (this.skin) {
            this.skin.setSiblingIndex(0);
            setText(this.label, this.label.string, k.text);
            return;
        }
        // Missing artwork is reported by preloadArt; do not invent a replacement skin.
        setText(this.label, this.label.string, k.text);
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

export function ghostButton(parent: Node, caption: string, x: number, y: number, w: number, h: number, fn: () => void, size?: number): Button {
    return new Button(parent, caption, x, y, w, h, 'ghost', fn, size);
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
