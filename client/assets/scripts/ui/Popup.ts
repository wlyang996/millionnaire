/**
 * 弹窗基类：全屏遮罩（吞掉底层触摸）+ 居中面板；带倒计时的弹窗在面板右上角固定位置显示倒计时环
 * （所有弹窗位置一致，见 Theme.popupRing）。倒计时由截止时间驱动（core/Clock.Countdown）。
 */
import { BlockInputEvents, Node } from 'cc';
import { Countdown } from '../core/Clock';
import { Theme } from '../core/Theme';
import { destroyChildren, fillRR, gfx, mk, onTap, paintPanel, text } from './Kit';
import { ctx } from './Ctx';
import { CountdownBadge } from './Widgets';

export abstract class Popup {
    root!: Node;
    panel!: Node;
    protected body!: Node;
    readonly cd: Countdown | null;
    private ring: CountdownBadge | null = null;
    closed = false;
    /** Full-page information views replace the board visually while preserving its state. */
    get coversScreen(): boolean { return false; }

    /**
     * @param popupId 目录 id（演示面板用）
     * @param title 标题（显示在面板左上）
     * @param pw 面板宽 @param ph 面板高
     * @param seconds 倒计时秒数，0 表示无倒计时
     */
    constructor(
        readonly popupId: string, protected title: string, protected pw: number, protected ph: number,
        protected seconds = 0, protected dismissOnMask = false,
    ) {
        this.cd = seconds > 0 ? new Countdown(ctx.clock) : null;
    }

    /** 联机：按服务端窗口的截止时刻倒计时（打开前调用；时间基准为 ctx.clock，联机时即服务器时间）。 */
    private deadlineAt: number | null = null;

    withDeadline(deadlineMs: number): this {
        this.deadlineAt = deadlineMs;
        return this;
    }

    /** 由 PopupManager 调用：创建节点树并开始计时。 */
    mount(layer: Node): void {
        this.root = mk(layer, 'Popup:' + this.popupId, 0, 0, Theme.W, Theme.H);
        const maskNode = mk(this.root, 'Mask', 0, 0, Theme.W, Theme.H);
        fillRR(gfx(maskNode), 0, 0, Theme.W, Theme.H, 0, Theme.c.mask);
        maskNode.addComponent(BlockInputEvents);
        if (this.dismissOnMask) onTap(maskNode, () => this.close(), false);
        const px = (Theme.W - this.pw) / 2;
        // 保留顶部玩家条可见：面板在 y=270 以下的区域居中
        const py = Math.max(Theme.safeTop, 270 + (890 - this.ph) / 2);
        this.panel = mk(this.root, 'PopupPanel', px, py, this.pw, this.ph);
        paintPanel(this.panel, this.pw, this.ph, { fill: Theme.c.ivory, r: Theme.radius.lg, shadow: 8, stroke: Theme.c.ivoryLine, strokeW: 3 });
        // 吞掉面板上的空白点击（避免触发 mask 关闭）
        this.panel.addComponent(BlockInputEvents);
        this.buildTitle();
        if (this.cd) {
            const r = Theme.popupRing;
            this.ring = new CountdownBadge(this.panel, this.pw - r.size - r.inset, r.inset + 6, r.size - 10, 48);
            if (this.deadlineAt !== null) this.cd.setDeadline(this.deadlineAt, this.seconds);
            else this.cd.start(this.seconds);
            this.ring.update(this.cd);
        }
        this.body = mk(this.panel, 'Body', 0, 0, this.pw, this.ph);
        this.buildBody(this.body, this.pw, this.ph);
    }

    protected abstract buildBody(panel: Node, w: number, h: number): void;

    protected buildTitle(): void {
        text(this.panel, this.title, 28, 20, this.pw - 28 - Theme.popupRing.size - 40, 64, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
    }

    /** 每帧（PopupManager 调用） */
    tick(): void {
        if (!this.cd || this.closed) return;
        this.ring?.update(this.cd);
        this.onTick();
        if (this.cd.consumeExpire()) this.onExpire();
    }

    protected onTick(): void {}

    /** 超时默认行为：提示并关闭。子类可重写（如欠款首段超时进入第二段）。 */
    protected onExpire(): void {
        this.close();
    }

    /** 重置倒计时（演示面板"重置"） */
    restart(): void {
        this.cd?.start(this.seconds);
    }

    close(): void {
        if (this.closed) return;
        this.closed = true;
        ctx.popups.remove(this);
    }

    /** 弹窗内部状态变化时重建主体（标题与倒计时环保持不变）。 */
    protected rebuildBody(): void {
        destroyChildren(this.body);
        this.buildBody(this.body, this.pw, this.ph);
    }
}
