/**
 * 横向滑动容器：Mask 裁剪 + 触摸拖动（捕获阶段，内部按钮仍可点；位移>10px 才算滑动）+ 鼠标滚轮。
 * content 内按 x 向右摆放；setContentWidth 后可滑动。onScroll 供滑动指示/渐隐更新。
 */
import { Mask, Node } from 'cc';
import { mk, resize } from './Kit';

interface UIEvt {
    getUIDelta(): { x: number; y: number };
    getScrollY?(): number;
}

export class HScroll {
    readonly viewport: Node;
    readonly content: Node;
    offset = 0;
    contentW = 0;
    onScroll: (() => void) | null = null;
    private dragging = false;
    private moved = 0;

    constructor(parent: Node, x: number, y: number, readonly w: number, readonly h: number) {
        this.viewport = mk(parent, 'HScrollViewport', x, y, w, h);
        const mask = this.viewport.addComponent(Mask);
        mask.type = Mask.Type.GRAPHICS_RECT;
        this.content = mk(this.viewport, 'HScrollContent', 0, 0, w, h);
        const v = this.viewport;
        v.on(Node.EventType.TOUCH_START, () => {
            this.dragging = true;
            this.moved = 0;
        }, this, true);
        v.on(Node.EventType.TOUCH_MOVE, (e: UIEvt) => {
            if (!this.dragging) return;
            const d = e.getUIDelta();
            this.moved += Math.abs(d.x);
            if (this.moved > 10) this.scrollBy(-d.x);
        }, this, true);
        const end = () => {
            this.dragging = false;
        };
        v.on(Node.EventType.TOUCH_END, end, this, true);
        v.on(Node.EventType.TOUCH_CANCEL, end, this, true);
        v.on(Node.EventType.MOUSE_WHEEL, (e: UIEvt) => this.scrollBy(-(e.getScrollY ? e.getScrollY() : 0) * 0.5), this);
    }

    get maxOffset(): number {
        return Math.max(0, this.contentW - this.w);
    }

    setContentWidth(cw: number): void {
        this.contentW = cw;
        resize(this.content, Math.max(cw, this.w), this.h);
        this.scrollBy(0);
    }

    /** d>0 表示内容向左移（看到右侧更多）。 */
    scrollBy(d: number): void {
        this.offset = Math.max(0, Math.min(this.maxOffset, this.offset + d));
        this.content.setPosition(-this.offset, 0, 0);
        this.onScroll?.();
    }

    scrollTo(off: number): void {
        this.offset = 0;
        this.scrollBy(off);
    }
}
