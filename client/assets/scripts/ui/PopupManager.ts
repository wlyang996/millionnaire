/** 弹窗栈：后开的在上；每帧驱动栈顶及其下弹窗的倒计时（演示里只关心可见的，实际流程由服务端窗口决定）。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { mk } from './Kit';
import { Popup } from './Popup';
import { ctx } from './Ctx';

export class PopupManager {
    readonly layer: Node;
    private stack: Popup[] = [];

    constructor(parent: Node) {
        this.layer = mk(parent, 'PopupLayer', 0, 0, Theme.W, Theme.H);
    }

    get top(): Popup | null {
        return this.stack.length ? this.stack[this.stack.length - 1] : null;
    }

    get count(): number {
        return this.stack.length;
    }

    open(p: Popup): Popup {
        p.mount(this.layer);
        this.stack.push(p);
        this.syncVisibility();
        return p;
    }

    /** 同一 popupId 只保留一个：先关掉旧的再开新的。 */
    openExclusive(p: Popup): Popup {
        for (const o of this.stack.slice()) if (o.popupId === p.popupId) o.close();
        return this.open(p);
    }

    remove(p: Popup): void {
        const i = this.stack.indexOf(p);
        if (i >= 0) this.stack.splice(i, 1);
        if (p.root && p.root.isValid) {
            p.root.removeFromParent();
            p.root.destroy();
        }
        this.syncVisibility();
    }

    private syncVisibility(): void {
        let fullPage = -1;
        this.stack.forEach((popup, i) => { if (popup.coversScreen) fullPage = i; });
        if (ctx.screens) ctx.screens.layer.active = fullPage < 0;
        this.stack.forEach((popup, i) => {
            if (popup.root?.isValid) popup.root.active = fullPage < 0 || i >= fullPage;
        });
    }

    closeTop(): void {
        this.top?.close();
    }

    /** 关闭指定类别的弹窗（如转入托管后撤下待决策弹窗）。 */
    closeIds(ids: string[]): void {
        for (const p of this.stack.slice()) if (ids.indexOf(p.popupId) >= 0) p.close();
    }

    closeAll(): void {
        for (const p of this.stack.slice()) p.close();
    }

    restartTop(): void {
        this.top?.restart();
    }

    update(): void {
        for (const p of this.stack.slice()) p.tick();
    }
}
