/** 页面基类：持有 root 节点（720×1280）；refresh() 默认整页重建，需要保留状态的页面可重写。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { paintBackdrop } from './Backdrop';
import { drawBack } from './Icons';
import { IconButton } from './Buttons';
import { destroyChildren, mk, text } from './Kit';

export type ScreenId = 'profile' | 'lobby' | 'room' | 'board' | 'teeth' | 'result' | 'spectator';

export abstract class Screen {
    root!: Node;
    abstract readonly id: ScreenId;
    abstract readonly title: string;

    /** 由 ScreenManager 在 root 创建后调用 */
    attach(root: Node): void {
        this.root = root;
        this.rebuild();
    }

    rebuild(): void {
        destroyChildren(this.root);
        this.build();
    }

    protected abstract build(): void;

    /** 页面首次显示（过渡开始时）调用 */
    onShow(): void {}

    /** store 变化时调用 */
    refresh(): void {
        this.rebuild();
    }

    /** 每帧 */
    tick(_dt: number): void {}

    onHide(): void {}

    // ---- 常用页面部件 ----
    protected backdrop(kind: 'sky' | 'plain' | 'night' = 'sky'): void {
        paintBackdrop(this.root, kind);
    }

    /** 标准页眉：返回键 + 居中标题。返回键让出左上角的"☰"演示按钮位置。 */
    protected header(title: string, onBack?: () => void): void {
        if (onBack) {
            new IconButton(this.root, 80, 24, 60, '', onBack, Theme.c.ivory, Theme.c.ink, (g, s) => drawBack(g, s / 2, s / 2, s * 0.6, Theme.c.ink));
        }
        const bar = mk(this.root, 'Header', 150, 24, 360, 60);
        text(bar, title, 0, 0, 360, 60, Theme.font.lg, Theme.c.ink, { bold: true });
    }
}
