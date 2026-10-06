/**
 * 输入框封装。
 * - 网页（H5）：不用 cc.EditBox。它的 DOM 文本框按引擎的摄像机换算位置，而本项目的 Canvas/Camera 是运行时创建的，
 *   在等比缩放（SHOW_ALL）的宽屏上会整体偏到别处（文字"跑到上面去"）。这里改为自管一个 <input>，
 *   放进画布所在的容器里绝对定位（iOS 弹键盘平移页面时跟着画布一起走），打开期间每帧按 Root（720×1280 设计区，
 *   左上锚点）在页面上的实际缩放与居中位置重新对齐；字号不小于 16px（iOS 小于 16px 会在聚焦时放大整页）。
 * - 微信小游戏等无 DOM 环境：仍用 cc.EditBox（调起系统键盘，不涉及 DOM 定位）。
 */
import { EditBox, Label, Layers, Node, UITransform, Vec3 } from 'cc';
import { Theme } from '../core/Theme';
import { ctx } from './Ctx';
import { col, fillRR, gfx, mk, onTap, setText, strokeRR, text } from './Kit';

type Rect = { left: number; top: number; width: number; height: number };
type El = { getBoundingClientRect(): Rect; parentElement: { appendChild(e: unknown): void } | null };
type Dom = {
    document: {
        createElement(tag: string): HTMLInputElement;
        body: { appendChild(e: unknown): void };
        getElementById(id: string): El | null;
        querySelector(sel: string): El | null;
    };
    requestAnimationFrame(fn: () => void): number;
    cancelAnimationFrame(id: number): void;
    scrollTo(x: number, y: number): void;
};

function domEnv(): Dom | null {
    const g = globalThis as unknown as { document?: unknown; wx?: unknown };
    return g.document && !g.wx ? (g as unknown as Dom) : null;
}

export class EditField {
    /** 网页上正在输入的输入框个数（>0 时不做美术到达引起的整页重绘，避免打断输入）。 */
    static editing = 0;
    readonly node: Node;
    private readonly box: EditBox | null = null;
    private readonly bg: Node;
    private readonly w: number;
    private readonly h: number;
    private current = '';
    private label: Label | null = null;
    private placeholderText = '';
    private input: HTMLInputElement | null = null;
    private frame = 0;
    /** 回车（网页）/ 键盘"完成"（小游戏）时调用，聊天用来直接发送。 */
    onEnter: (() => void) | null = null;

    constructor(
        parent: Node, x: number, y: number, w: number, h: number, placeholder: string,
        private readonly maxLength: number, private readonly onChange: (s: string) => void,
    ) {
        this.w = w;
        this.h = h;
        this.bg = mk(parent, 'EditBg', x, y, w, h);
        this.paint(false);
        this.placeholderText = placeholder;
        if (domEnv()) {
            // 网页：画布上只画文字，点击时在同一位置叠一个真正的 <input>
            this.label = text(this.bg, '', 14, 0, w - 28, h, Theme.font.md, Theme.c.inkFaint, { align: 'l' });
            this.render();
            onTap(this.bg, () => this.openDom(), false);
            this.node = this.bg;
            return;
        }
        const n = new Node('EditBox');
        n.layer = Layers.Enum.UI_2D;
        const ut = n.addComponent(UITransform);
        ut.setContentSize(w, h);
        n.setPosition(new Vec3(x + w / 2, -(y + h / 2), 0));
        parent.addChild(n);
        const box = n.addComponent(EditBox);
        // addComponent 时引擎已自动创建 TEXT_LABEL / PLACEHOLDER_LABEL，直接设置样式即可（自建会留下一个多余的 "label"）
        this.styleLabel(box.textLabel, Theme.font.md, Theme.c.ink, w, h);
        this.styleLabel(box.placeholderLabel, Theme.font.md, Theme.c.inkFaint, w, h);
        box.placeholder = placeholder;
        box.maxLength = maxLength;
        box.inputMode = EditBox.InputMode.SINGLE_LINE;
        n.on('text-changed', (eb: EditBox) => onChange(eb.string));
        n.on('editing-did-began', () => this.paint(true));
        n.on('editing-did-ended', (eb: EditBox) => {
            this.paint(false);
            onChange(eb.string);
        });
        n.on('editing-return', () => this.onEnter?.());
        this.box = box;
        this.node = n;
    }

    get value(): string {
        return this.box ? this.box.string : this.current;
    }

    set value(s: string) {
        if (this.box) this.box.string = s;
        else {
            this.current = s;
            if (this.input) this.input.value = s;
            this.render();
        }
    }

    /** 收起网页输入框（所在弹窗关闭时调用，避免 <input> 留在页面上）。 */
    blur(): void {
        this.closeDom();
    }

    // ------------------------------------------------------------ 网页输入
    private openDom(): void {
        const env = domEnv();
        if (!env || this.input) return;
        const el = env.document.createElement('input');
        el.type = 'text';
        el.value = this.current;
        el.maxLength = this.maxLength;
        el.placeholder = this.placeholderText;
        el.setAttribute('enterkeyhint', this.onEnter ? 'send' : 'done');
        el.setAttribute('autocomplete', 'off');
        Object.assign(el.style, {
            position: 'absolute', zIndex: '1000', margin: '0', padding: '0 12px', boxSizing: 'border-box',
            border: '3px solid ' + Theme.c.blue, borderRadius: '10px', outline: 'none', background: '#FFFFFF',
            color: Theme.c.ink, fontFamily: 'sans-serif',
            // 页面样式给 body/div 设了 user-select: none，iOS 上会让输入框无法输入
            userSelect: 'text', webkitUserSelect: 'text',
        });
        el.addEventListener('input', () => {
            this.current = el.value;
            this.render();
            this.onChange(this.current);
        });
        el.addEventListener('keydown', (e: KeyboardEvent) => {
            if (e.key !== 'Enter') return;
            this.current = el.value;
            if (this.onEnter) this.onEnter();
            else el.blur();
        });
        el.addEventListener('blur', () => this.closeDom());
        // 放进画布所在的容器：iOS 弹出键盘平移页面时，输入框与画布一起移动
        const canvas = env.document.getElementById('GameCanvas') ?? env.document.querySelector('canvas');
        (canvas?.parentElement ?? env.document.body).appendChild(el);
        this.input = el;
        EditField.editing++;
        this.placeInput();
        // 打开期间每帧对齐（键盘弹出、画布重新布局、横竖屏切换都能跟上）
        const follow = () => {
            if (this.input !== el) return;
            this.placeInput();
            this.frame = env.requestAnimationFrame(follow);
        };
        this.frame = env.requestAnimationFrame(follow);
        this.paint(true);
        el.focus();
    }

    private closeDom(): void {
        const el = this.input;
        if (!el) return;
        this.input = null;
        EditField.editing = Math.max(0, EditField.editing - 1);
        const env = domEnv();
        if (env) {
            env.cancelAnimationFrame(this.frame);
            env.scrollTo(0, 0); // iOS 收起键盘后页面可能停在平移后的位置
        }
        this.current = el.value;
        el.remove();
        if (!this.bg.isValid) return;
        this.paint(false);
        this.render();
        this.onChange(this.current);
    }

    /** 设计坐标（Root 左上为原点）→ 容器内坐标：按 SHOW_ALL 的等比缩放与居中换算，再减去定位容器在页面上的位置。 */
    private placeInput(): void {
        const env = domEnv();
        const el = this.input;
        if (!env || !el) return;
        if (!this.bg.isValid) return void this.closeDom();
        const canvas = env.document.getElementById('GameCanvas') ?? env.document.querySelector('canvas');
        if (!canvas) return;
        const r = canvas.getBoundingClientRect();
        const host = (el.offsetParent as unknown as El | null)?.getBoundingClientRect() ?? { left: 0, top: 0, width: 0, height: 0 };
        const s = Math.min(r.width / Theme.W, r.height / Theme.H);
        const ox = r.left - host.left + (r.width - Theme.W * s) / 2;
        const oy = r.top - host.top + (r.height - Theme.H * s) / 2;
        const p = this.bg.worldPosition;
        const root = ctx.root.worldPosition;
        const dx = p.x - root.x;
        const dy = root.y - p.y;
        const style = {
            left: Math.round(ox + dx * s) + 'px', top: Math.round(oy + dy * s) + 'px',
            width: Math.round(this.w * s) + 'px', height: Math.round(this.h * s) + 'px',
            fontSize: Math.max(16, Math.round(Theme.font.md * s)) + 'px',
        };
        if (el.style.left !== style.left || el.style.top !== style.top || el.style.width !== style.width
            || el.style.height !== style.height || el.style.fontSize !== style.fontSize) Object.assign(el.style, style);
    }

    private render(): void {
        if (!this.label) return;
        const empty = !this.current;
        setText(this.label, empty ? this.placeholderText : this.current, empty ? Theme.c.inkFaint : Theme.c.ink);
    }

    // ------------------------------------------------------------ 共用
    /** 引擎按"左上锚点"摆放这两个 Label（位置 = 左上角），但创建时锚点是居中，需改成 (0,1) 并固定框大小。 */
    private styleLabel(l: Label | null, size: number, color: string, w: number, h: number): void {
        if (!l) return;
        const ut = l.node.getComponent(UITransform);
        if (ut) {
            ut.setAnchorPoint(0, 1);
            ut.setContentSize(w - 4, h);
        }
        l.overflow = Label.Overflow.CLAMP;
        l.fontSize = size;
        l.lineHeight = size + 8;
        l.color = col(color);
        l.horizontalAlign = Label.HorizontalAlign.LEFT;
        l.verticalAlign = Label.VerticalAlign.CENTER;
    }

    private paint(focus: boolean): void {
        const g = gfx(this.bg);
        g.clear();
        fillRR(g, 0, 0, this.w, this.h, 18, Theme.c.white);
        strokeRR(g, 0, 0, this.w, this.h, 18, focus ? Theme.c.blue : Theme.c.ivoryLine, focus ? 4 : 3);
    }
}
