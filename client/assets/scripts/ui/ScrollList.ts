/**
 * 纵向滚动列表：Mask 裁剪 + 触摸拖动（捕获阶段，因此内部按钮仍能点击，位移>10px 视为拖动）+ 鼠标滚轮。
 * 用法：const l = new ScrollList(parent,x,y,w,h); 往 l.content 里按 y 向下摆放子节点，再 l.setContentHeight(totalH)。
 */
import { Mask, Node } from 'cc';
import { destroyChildren, mk, resize } from './Kit';

interface UIEvt {
    getUILocation(): { x: number; y: number };
    getUIDelta(): { x: number; y: number };
    getScrollY?(): number;
}

export class ScrollList {
    readonly viewport: Node;
    readonly content: Node;
    private contentH = 0;
    private offset = 0;
    private dragging = false;
    private moved = 0;

    constructor(parent: Node, x: number, y: number, readonly w: number, readonly h: number) {
        this.viewport = mk(parent, 'ScrollViewport', x, y, w, h);
        const mask = this.viewport.addComponent(Mask);
        mask.type = Mask.Type.GRAPHICS_RECT;
        this.content = mk(this.viewport, 'ScrollContent', 0, 0, w, h);
        const v = this.viewport;
        v.on(Node.EventType.TOUCH_START, () => {
            this.dragging = true;
            this.moved = 0;
        }, this, true);
        v.on(Node.EventType.TOUCH_MOVE, (e: UIEvt) => {
            if (!this.dragging) return;
            const d = e.getUIDelta();
            this.moved += Math.abs(d.y);
            if (this.moved > 10) this.scrollBy(d.y);
        }, this, true);
        const end = () => {
            this.dragging = false;
        };
        v.on(Node.EventType.TOUCH_END, end, this, true);
        v.on(Node.EventType.TOUCH_CANCEL, end, this, true);
        v.on(Node.EventType.MOUSE_WHEEL, (e: UIEvt) => {
            const sy = e.getScrollY ? e.getScrollY() : 0;
            this.scrollBy(-sy * 0.5);
        }, this);
    }

    setContentHeight(h: number): void {
        this.contentH = h;
        resize(this.content, this.w, Math.max(h, this.h));
        this.scrollBy(0);
    }

    /** d 为 UI 坐标系(y 向上)下的手指位移：向上拖 d>0，内容跟手上移，偏移增大。 */
    private scrollBy(d: number): void {
        const maxOff = Math.max(0, this.contentH - this.h);
        this.offset = Math.max(0, Math.min(maxOff, this.offset + d));
        this.content.setPosition(0, this.offset, 0);
    }

    scrollToTop(): void {
        this.offset = 0;
        this.content.setPosition(0, 0, 0);
    }

    clear(): void {
        destroyChildren(this.content);
        this.scrollToTop();
    }
}
