/**
 * 绘制/节点原语。约定：所有节点锚点为左上角 (0,1)，布局坐标 y 向下、原点为父节点左上角。
 * 这样 mk(parent, name, x, y, w, h) 的 (x,y) 就是设计稿上的"左上角像素坐标"。
 * Graphics 与 Label、Mask 不能放在同一节点：文字、遮罩总用独立子节点。
 */
import { Color, Graphics, Label, Layers, Node, UIOpacity, UITransform } from 'cc';
import { Theme } from '../core/Theme';

const colorCache = new Map<string, Color>();

/** '#RRGGBB' / '#RRGGBBAA' -> Color（带缓存；alphaMul 额外乘透明度，不缓存）。 */
export function col(hex: string, alphaMul = 1): Color {
    let c = colorCache.get(hex);
    if (!c) {
        const h = hex.charAt(0) === '#' ? hex.slice(1) : hex;
        const r = parseInt(h.slice(0, 2), 16);
        const g = parseInt(h.slice(2, 4), 16);
        const b = parseInt(h.slice(4, 6), 16);
        const a = h.length >= 8 ? parseInt(h.slice(6, 8), 16) : 255;
        c = new Color(r, g, b, a);
        colorCache.set(hex, c);
    }
    return alphaMul === 1 ? c : new Color(c.r, c.g, c.b, Math.round(c.a * alphaMul));
}

/** 创建节点：(x,y) 为左上角在父节点中的位置（y 向下）。 */
export function mk(parent: Node | null, name: string, x: number, y: number, w: number, h: number): Node {
    const n = new Node(name);
    n.layer = Layers.Enum.UI_2D;
    const ut = n.addComponent(UITransform);
    ut.setAnchorPoint(0, 1);
    ut.setContentSize(w, h);
    n.setPosition(x, -y, 0);
    if (parent) parent.addChild(n);
    return n;
}

export function place(n: Node, x: number, y: number): void {
    n.setPosition(x, -y, 0);
}

export function sizeOf(n: Node): { w: number; h: number } {
    const s = (n.getComponent(UITransform) as UITransform).contentSize;
    return { w: s.width, h: s.height };
}

export function resize(n: Node, w: number, h: number): void {
    (n.getComponent(UITransform) as UITransform).setContentSize(w, h);
}

export function gfx(n: Node): Graphics {
    return n.getComponent(Graphics) ?? n.addComponent(Graphics);
}

export function setOpacity(n: Node, v: number): void {
    const o = n.getComponent(UIOpacity) ?? n.addComponent(UIOpacity);
    o.opacity = Math.max(0, Math.min(255, v));
}

// ---- Graphics 绘制（局部坐标：左上角原点，y 向下） ----
export function rr(g: Graphics, x: number, y: number, w: number, h: number, r: number): void {
    g.roundRect(x, -y - h, w, h, Math.min(r, w / 2, h / 2));
}

export function fillRR(g: Graphics, x: number, y: number, w: number, h: number, r: number, color: string): void {
    g.fillColor = col(color);
    rr(g, x, y, w, h, r);
    g.fill();
}

export function strokeRR(g: Graphics, x: number, y: number, w: number, h: number, r: number, color: string, lw: number): void {
    g.strokeColor = col(color);
    g.lineWidth = lw;
    rr(g, x, y, w, h, r);
    g.stroke();
}

export function fillCircle(g: Graphics, cx: number, cy: number, r: number, color: string): void {
    g.fillColor = col(color);
    g.circle(cx, -cy, r);
    g.fill();
}

export function strokeCircle(g: Graphics, cx: number, cy: number, r: number, color: string, lw: number): void {
    g.strokeColor = col(color);
    g.lineWidth = lw;
    g.circle(cx, -cy, r);
    g.stroke();
}

export function line(g: Graphics, x1: number, y1: number, x2: number, y2: number, color: string, lw: number): void {
    g.strokeColor = col(color);
    g.lineWidth = lw;
    g.lineCap = Graphics.LineCap.ROUND;
    g.moveTo(x1, -y1);
    g.lineTo(x2, -y2);
    g.stroke();
}

export function fillPoly(g: Graphics, pts: number[][], color: string): void {
    g.fillColor = col(color);
    pts.forEach((p, i) => (i === 0 ? g.moveTo(p[0], -p[1]) : g.lineTo(p[0], -p[1])));
    g.close();
    g.fill();
}

export interface PanelOpts {
    fill?: string;
    stroke?: string;
    strokeW?: number;
    r?: number;
    /** 底部"厚度"阴影高度，0 为无 */
    shadow?: number;
    shadowColor?: string;
}

/** 在节点上绘制圆角面板（先清空）。 */
export function paintPanel(n: Node, w: number, h: number, o: PanelOpts = {}): void {
    const g = gfx(n);
    g.clear();
    const r = o.r ?? Theme.radius.md;
    const sh = o.shadow ?? 0;
    if (sh > 0) fillRR(g, 0, sh, w, h, r, o.shadowColor ?? Theme.c.shadow);
    fillRR(g, 0, 0, w, h, r, o.fill ?? Theme.c.ivory);
    if (o.stroke) strokeRR(g, 0, 0, w, h, r, o.stroke, o.strokeW ?? 2);
}

export interface TextOpts {
    bold?: boolean;
    align?: 'l' | 'c' | 'r';
    valign?: 't' | 'm' | 'b';
    /** 自动换行（固定宽高内裁剪）；默认单行并在超宽时缩小 */
    wrap?: boolean;
    lineHeight?: number;
}

/** 创建文字节点（自带 w×h 的文本框）。 */
export function text(
    parent: Node, str: string, x: number, y: number, w: number, h: number,
    size: number, color: string = Theme.c.ink, o: TextOpts = {},
): Label {
    const n = mk(parent, 'T:' + (str.length > 6 ? str.slice(0, 6) : str), x, y, w, h);
    const l = n.addComponent(Label);
    l.string = str;
    l.fontSize = size;
    l.lineHeight = o.lineHeight ?? Math.round(size * 1.25);
    l.color = col(color);
    l.isBold = !!o.bold;
    l.horizontalAlign = o.align === 'l' ? Label.HorizontalAlign.LEFT : o.align === 'r' ? Label.HorizontalAlign.RIGHT : Label.HorizontalAlign.CENTER;
    l.verticalAlign = o.valign === 't' ? Label.VerticalAlign.TOP : o.valign === 'b' ? Label.VerticalAlign.BOTTOM : Label.VerticalAlign.CENTER;
    if (o.wrap) {
        l.enableWrapText = true;
        l.overflow = Label.Overflow.CLAMP;
    } else {
        l.enableWrapText = false;
        l.overflow = Label.Overflow.SHRINK;
    }
    return l;
}

export function setText(l: Label, s: string, color?: string): void {
    if (l.string !== s) l.string = s;
    if (color) l.color = col(color);
}

/** 点击：位移小于 16px 才算点击；按下时变淡反馈（节点锚点在左上，缩放会偏移）。返回取消函数无需，节点销毁即可。 */
export function onTap(n: Node, fn: () => void, feedback = true): void {
    let sx = 0;
    let sy = 0;
    let down = false;
    n.on(Node.EventType.TOUCH_START, (e: { getUILocation(): { x: number; y: number } }) => {
        const p = e.getUILocation();
        sx = p.x;
        sy = p.y;
        down = true;
        if (feedback) setOpacity(n, 200);
    });
    n.on(Node.EventType.TOUCH_CANCEL, () => {
        down = false;
        if (feedback) setOpacity(n, 255);
    });
    n.on(Node.EventType.TOUCH_END, (e: { getUILocation(): { x: number; y: number } }) => {
        if (feedback) setOpacity(n, 255);
        if (!down) return;
        down = false;
        const p = e.getUILocation();
        if (Math.abs(p.x - sx) < 10 && Math.abs(p.y - sy) < 10) fn();
    });
}

export function destroyChildren(n: Node): void {
    const kids = n.children.slice();
    for (const k of kids) {
        k.removeFromParent();
        k.destroy();
    }
}

export function fmtMoney(v: number): string {
    return String(Math.round(v));
}
