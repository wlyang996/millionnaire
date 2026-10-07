/**
 * 弹窗基类（设计稿 04/05/17 的统一外框）：全屏透明遮罩（只吞掉底层触摸，不压暗棋盘）+ 居中的暖白圆角面板；
 * 标题居中深蓝粗体；带倒计时的弹窗在面板右上角显示红色秒表 + "N秒"（所有弹窗位置一致，见 Theme.popupRing）。
 * 倒计时由截止时间驱动（core/Clock.Countdown）。
 */
import { BlockInputEvents, Node } from 'cc';
import { Countdown } from '../core/Clock';
import { Theme, textWidth } from '../core/Theme';
import { art } from './Art';
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
    /** 是否压暗底层（设计稿里的弹窗都不压暗棋盘；个别需要时子类打开）。 */
    protected dimBackground = false;
    /** 标题左侧图标（设计稿 05：拍卖锤 / 房子），空串表示纯文字标题。 */
    protected titleIcon = '';
    private titleNode: Node | null = null;

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
        if (this.dimBackground) fillRR(gfx(maskNode), 0, 0, Theme.W, Theme.H, 0, Theme.c.mask);
        maskNode.addComponent(BlockInputEvents);
        if (this.dismissOnMask) onTap(maskNode, () => this.close(), false);
        const px = (Theme.W - this.pw) / 2;
        // 面板以棋盘中心（y≈640）居中，不压住顶部玩家条
        const py = Math.max(Theme.safeTop, Math.min(Theme.H - this.ph - 20, 640 - this.ph / 2));
        this.panel = mk(this.root, 'PopupPanel', px, py, this.pw, this.ph);
        paintPanel(this.panel, this.pw, this.ph, { fill: Theme.c.panelFill, r: 30, shadow: 8, stroke: Theme.c.panelLine, strokeW: 3 });
        // 吞掉面板上的空白点击（避免触发 mask 关闭）
        this.panel.addComponent(BlockInputEvents);
        this.buildTitle();
        if (this.cd) {
            const r = Theme.popupRing;
            this.ring = new CountdownBadge(this.panel, this.pw - r.size - r.inset, 22, r.size - 10, 40);
            if (this.deadlineAt !== null) this.cd.setDeadline(this.deadlineAt, this.seconds);
            else this.cd.start(this.seconds);
            this.ring.update(this.cd);
        }
        this.body = mk(this.panel, 'Body', 0, 0, this.pw, this.ph);
        this.buildBody(this.body, this.pw, this.ph);
    }

    protected abstract buildBody(panel: Node, w: number, h: number): void;

    protected buildTitle(): void {
        this.titleNode?.destroy();
        const side = Theme.popupRing.size + Theme.popupRing.inset;
        const t = mk(this.panel, 'Title', side, 14, this.pw - side * 2, 64);
        this.titleNode = t;
        if (!this.titleIcon) {
            text(t, this.title, 0, 0, this.pw - side * 2, 64, 38, Theme.c.navy, { bold: true });
            return;
        }
        // 图标 + 标题整体居中
        const icon = 58;
        const gap = 12;
        const tw = textWidth(this.title, 38) + 4;
        const x0 = (this.pw - side * 2 - (icon + gap + tw)) / 2;
        art(t, this.titleIcon, x0, 3, icon, icon);
        text(t, this.title, x0 + icon + gap, 0, tw + 8, 64, 38, Theme.c.navy, { bold: true, align: 'l' });
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

    /** 后台加载的美术图到达：重建主体，把先前的占位换成图（状态都在字段里，重建不丢）。 */
    artArrived(): void {
        if (this.closed || !this.body || !this.body.isValid) return;
        if (this.titleIcon) this.buildTitle();
        this.rebuildBody();
    }

    /** 弹窗内部状态变化时重建主体（标题与倒计时环保持不变）。 */
    protected rebuildBody(): void {
        destroyChildren(this.body);
        this.buildBody(this.body, this.pw, this.ph);
    }
}
