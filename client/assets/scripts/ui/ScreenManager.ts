/** 页面栈 / 切换 / 淡入过渡。一次只显示一个页面；go 替换当前页并清栈，push 压栈，back 回上一页。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { mk, setOpacity } from './Kit';
import { Screen, ScreenId } from './Screen';

type Factory = () => Screen;

export class ScreenManager {
    private factories = new Map<ScreenId, Factory>();
    private stack: Screen[] = [];
    private fading: { screen: Screen; old: Screen | null; t: number } | null = null;
    readonly layer: Node;

    constructor(parent: Node) {
        this.layer = mk(parent, 'ScreenLayer', 0, 0, Theme.W, Theme.H);
    }

    register(id: ScreenId, f: Factory): void {
        this.factories.set(id, f);
    }

    get current(): Screen | null {
        return this.stack.length ? this.stack[this.stack.length - 1] : null;
    }

    get currentId(): ScreenId | null {
        return this.current ? this.current.id : null;
    }

    /** 切换到某页面并清空栈。 */
    go(id: ScreenId): void {
        this.show(id, false);
    }

    push(id: ScreenId): void {
        this.show(id, true);
    }

    /** 回上一页；栈里没有上一页时回到 fallback（演示面板直接跳转的页面没有栈）。 */
    back(fallback: ScreenId = 'lobby'): void {
        if (this.stack.length < 2) {
            this.go(fallback);
            return;
        }
        const old = this.stack.pop() as Screen;
        const prev = this.stack[this.stack.length - 1];
        this.finishFade();
        old.onHide();
        this.destroyScreen(old);
        const root = mk(this.layer, 'Screen:' + prev.id, 0, 0, Theme.W, Theme.H);
        prev.attach(root);
        prev.onShow();
        this.startFade(prev, null);
    }

    private show(id: ScreenId, push: boolean): void {
        const f = this.factories.get(id);
        if (!f) return;
        this.finishFade();
        const old = this.current;
        if (old) old.onHide();
        const s = f();
        if (!push) {
            while (this.stack.length) this.destroyScreen(this.stack.pop() as Screen);
        } else if (old) {
            // 压栈时旧页面保留数据但销毁节点，返回时重建
            this.destroyScreen(old);
        }
        this.stack.push(s);
        const root = mk(this.layer, 'Screen:' + id, 0, 0, Theme.W, Theme.H);
        s.attach(root);
        s.onShow();
        this.startFade(s, null);
    }

    private destroyScreen(s: Screen): void {
        if (s.root && s.root.isValid) {
            s.root.removeFromParent();
            s.root.destroy();
        }
    }

    private startFade(s: Screen, old: Screen | null): void {
        setOpacity(s.root, 0);
        this.fading = { screen: s, old, t: 0 };
    }

    private finishFade(): void {
        if (this.fading) {
            setOpacity(this.fading.screen.root, 255);
            this.fading = null;
        }
    }

    refresh(): void {
        this.current?.refresh();
    }

    update(dt: number): void {
        if (this.fading) {
            this.fading.t += dt;
            const k = Math.min(1, this.fading.t / 0.2);
            setOpacity(this.fading.screen.root, Math.round(255 * k));
            if (k >= 1) this.fading = null;
        }
        this.current?.tick(dt);
    }
}
