/**
 * 演示/调试菜单：左上角常驻"☰"，打开后可跳转任一页面/弹窗、切换演示场景、暂停/重置倒计时、运行布局自检。
 * 评审者不用玩流程就能看到所有界面。
 */
import { BlockInputEvents, Node } from 'cc';
import { ConnPreset } from '../core/MockStore';
import { runSelfCheck } from '../core/SelfCheck';
import { Theme } from '../core/Theme';
import { POPUP_CATALOG } from '../popups/Catalog';
import { Button, ghostButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, onTap, setText, text } from '../ui/Kit';
import { ScreenId } from '../ui/Screen';
import { ScrollList } from '../ui/ScrollList';
import { Toast } from '../ui/Toast';
import { Segmented } from '../ui/Widgets';
import { BoardScreen } from '../screens/BoardScreen';
import { checkLayout, formatIssues } from './LayoutCheck';

const SHEET_W = 600;

const PAGES: [ScreenId, string][] = [
    ['profile', '1 登录资料'], ['lobby', '2 大厅'], ['room', '3 好友房间'], ['board', '4 对局棋盘'],
    ['teeth', '5 虎口拔牙'], ['result', '6 结算'], ['spectator', '破产观战'],
];

export class DemoPanel {
    private sheet: Node | null = null;
    private readonly layer: Node;
    private capsule: Node | null = null;

    /** @param menu 是否显示左上角"☰"（联机正式游戏不显示，H5 加 ?demo=1 的演示模式才显示） */
    constructor(parent: Node, menu = true) {
        this.layer = mk(parent, 'DemoLayer', 0, 0, Theme.W, Theme.H);
        // 微信小游戏里胶囊由微信绘制；网页预览才画占位（设计稿位置 x 540–700，y 12–62）
        if (!(globalThis as unknown as { wx?: unknown }).wx) this.drawCapsule(parent);
        if (!menu) return;
        const btn = mk(this.layer, 'DemoBtn', 10, 30, 56, 56);
        const g = gfx(btn);
        fillRR(g, 0, 3, 56, 56, 28, Theme.c.shadow);
        fillRR(g, 0, 0, 56, 56, 28, '#2D3B4AE6');
        text(btn, '☰', 0, 0, 56, 56, 30, Theme.c.white, { bold: true });
        onTap(btn, () => this.toggle());
    }

    /** 微信胶囊占位（仅预览用，真机由微信绘制）。 */
    private drawCapsule(parent: Node): void {
        const c = Theme.capsule;
        this.capsule = mk(parent, '~Capsule', c.x, c.y, c.w, c.h);
        const g = gfx(this.capsule);
        const half = c.w / 2;
        fillRR(g, 0, 0, c.w, c.h, c.h / 2, '#FFFFFFAA');
        fillRR(g, half - 1, 12, 2, c.h - 24, 1, '#00000033');
        text(this.capsule, '···', 0, 0, half, c.h, 28, Theme.c.ink, { bold: true });
        text(this.capsule, '◎', half, 0, half, c.h, 28, Theme.c.ink, { bold: true });
    }

    /** 把 ☰ 菜单层移到最上（弹窗之上、Toast 之下）。 */
    bringToFront(): void {
        const p = this.layer.parent;
        if (p) this.layer.setSiblingIndex(p.children.length - 1);
    }

    toggle(): void {
        if (this.sheet) this.close();
        else this.open();
    }

    close(): void {
        if (this.sheet) {
            this.sheet.removeFromParent();
            this.sheet.destroy();
            this.sheet = null;
        }
    }

    private open(): void {
        const sheet = mk(this.layer, 'DemoSheet', 0, 0, Theme.W, Theme.H);
        this.sheet = sheet;
        const mask = mk(sheet, 'Mask', 0, 0, Theme.W, Theme.H);
        fillRR(gfx(mask), 0, 0, Theme.W, Theme.H, 0, '#00000066');
        mask.addComponent(BlockInputEvents);
        onTap(mask, () => this.close(), false);
        const panel = mk(sheet, 'Panel', 0, 0, SHEET_W, Theme.H);
        fillRR(gfx(panel), 0, 0, SHEET_W, Theme.H, 0, '#FFF8E6F7');
        panel.addComponent(BlockInputEvents);
        text(panel, '演示菜单', 80, 24, 300, 56, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
        ghostButton(panel, '关闭', SHEET_W - 130, 24, 110, 56, () => this.close(), Theme.font.sm);
        const list = new ScrollList(panel, 0, 96, SHEET_W, Theme.H - 96);
        const y = this.fill(list.content);
        list.setContentHeight(y + 40);
    }

    private heading(p: Node, y: number, s: string): number {
        text(p, s, 24, y, SHEET_W - 48, 44, Theme.font.md, Theme.c.blueDark, { bold: true, align: 'l' });
        return y + 48;
    }

    private grid(p: Node, y: number, items: [string, () => void][], cols = 3): number {
        const gap = 10;
        const w = (SHEET_W - 48 - gap * (cols - 1)) / cols;
        items.forEach(([label, fn], i) => {
            secondaryButton(p, label, 24 + (i % cols) * (w + gap), y + Math.floor(i / cols) * 62, w, 54, fn, 20);
        });
        return y + Math.ceil(items.length / cols) * 62 + 8;
    }

    private seg(p: Node, y: number, label: string, opts: { label: string; value: string | number }[], sel: string | number, fn: (v: string | number) => void): number {
        text(p, label, 24, y, SHEET_W - 48, 32, Theme.font.sm, Theme.c.inkSoft, { bold: true, align: 'l' });
        new Segmented(p, 24, y + 34, SHEET_W - 48, 52, opts, sel, fn, 8, 20);
        return y + 98;
    }

    private fill(p: Node): number {
        const st = ctx.store;
        const sc = st.scenario;
        let y = 8;
        y = this.heading(p, y, '页面（点击直接跳转）');
        y = this.grid(p, y, PAGES.map(([id, label]): [string, () => void] => [label, () => {
            this.close();
            ctx.popups.closeAll();
            if (id === 'spectator' && !st.isSpectator()) st.patchScenario({ spectator: true });
            if (id !== 'spectator' && id !== 'result' && id !== 'profile' && id !== 'lobby' && st.isSpectator()) st.patchScenario({ spectator: false });
            ctx.screens.go(id);
        }]), 3);
        y = this.heading(p, y, '弹窗（叠在当前页面上）');
        y = this.grid(p, y, POPUP_CATALOG.map((e): [string, () => void] => [e.label, () => {
            this.close();
            if (ctx.screens.currentId !== 'board' && ctx.screens.currentId !== 'spectator' && e.id !== 'join' && e.id !== 'history') ctx.screens.go('board');
            ctx.popups.closeAll();
            ctx.popups.open(e.make());
        }]), 3);
        y = this.grid(p, y, [
            ['Toast', () => { this.close(); Toast.show('这是一条通用提示 Toast'); }],
            ['关闭全部弹窗', () => { ctx.popups.closeAll(); this.close(); }],
        ], 3);

        y = this.heading(p, y + 6, '演示场景');
        y = this.seg(p, y, '人数', [2, 3, 4, 5, 6, 7, 8].map((n) => ({ label: String(n), value: n })), sc.players, (v) => st.patchScenario({ players: v as number }));
        y = this.seg(p, y, '地图（30 格最多 4 人）', [{ label: '30 格', value: 30 }, { label: '50 格', value: 50 }], sc.boardSize, (v) => st.patchScenario({ boardSize: v as 30 | 50 }));
        y = this.seg(p, y, '当前回合', [{ label: '我', value: 'me' }, { label: '他人', value: 'other' }], sc.turn, (v) => st.patchScenario({ turn: v as 'me' | 'other' }));
        y = this.seg(p, y, '我的身份', [{ label: '正常', value: 0 }, { label: '破产观战', value: 1 }], sc.spectator ? 1 : 0, (v) => st.patchScenario({ spectator: v === 1 }));
        y = this.seg(p, y, '连接状态', [{ label: '全部正常', value: 'normal' }, { label: '混合(断线/托管/暂离)', value: 'mixed' }, { label: '我疑似断线', value: 'mySuspect' }], sc.conn, (v) => st.patchScenario({ conn: v as ConnPreset }));
        y = this.seg(p, y, '房主', [{ label: '我是房主', value: 1 }, { label: '我不是', value: 0 }], sc.host ? 1 : 0, (v) => st.patchScenario({ host: v === 1 }));
        y = this.seg(p, y, '我的手牌张数', [0, 3, 6, 7].map((n) => ({ label: String(n), value: n })), sc.handCount, (v) => st.patchScenario({ handCount: v as number }));
        y = this.seg(p, y, '对手回合自动演进 / 我方超时自动投骰', [{ label: '关（默认）', value: 0 }, { label: '开', value: 1 }], st.autoplay ? 1 : 0, (v) => { st.autoplay = v === 1; });

        y = this.heading(p, y + 6, '倒计时（截止时间驱动）');
        const pause: Button = secondaryButton(p, ctx.clock.paused ? '继续倒计时' : '暂停倒计时', 24, y, 270, 60, () => {
            if (ctx.clock.paused) ctx.clock.resume();
            else ctx.clock.pause();
            pause.setText(ctx.clock.paused ? '继续倒计时' : '暂停倒计时');
        }, 24);
        secondaryButton(p, '重置当前弹窗', 306, y, 270, 60, () => {
            ctx.popups.restartTop();
            Toast.show(ctx.popups.top ? '已重置当前弹窗倒计时' : '当前没有弹窗');
        }, 24);
        y += 76;

        y = this.heading(p, y, '本局剩余时间（顶部时钟警示）');
        const pin = (label: string, sec: number | null): [string, () => void] => [label, () => {
            st.matchOverrideSec = sec;
            Toast.show(sec === null ? '本局时间恢复实时' : '本局剩余时间固定为 ' + label);
        }];
        y = this.grid(p, y, [pin('12:00', 720), pin('09:59', 599), pin('03:00', 180), pin('02:59', 179), pin('00:30', 30), pin('00:00', 0), pin('恢复实时', null)], 4);
        y = this.heading(p, y, '动画（需在对局棋盘页）');
        y = this.grid(p, y, [
            ['骰子翻滚', () => { this.close(); const b = ctx.screens.current; if (b instanceof BoardScreen) b.demoRoll(); else Toast.show('请先进入对局棋盘页'); }],
            ['逐格跳 4 步', () => { this.close(); const b = ctx.screens.current; if (b instanceof BoardScreen) b.demoHop(4); else Toast.show('请先进入对局棋盘页'); }],
            ['事件格抽卡(我)', () => { this.close(); const b = ctx.screens.current; if (b instanceof BoardScreen) b.demoEventMe(); else Toast.show('请先进入对局棋盘页'); }],
            ['他人抽卡', () => { this.close(); const b = ctx.screens.current; if (b instanceof BoardScreen) b.demoEventOther(); else Toast.show('请先进入对局棋盘页'); }],
            ['逐格跳 1 步', () => { this.close(); const b = ctx.screens.current; if (b instanceof BoardScreen) b.demoHop(1); else Toast.show('请先进入对局棋盘页'); }],
            ['资金 +300（演示）', () => {
                this.close();
                if (!(ctx.screens.current instanceof BoardScreen)) return Toast.show('请先进入对局棋盘页');
                st.me().cash += 300;
                st.emit();
            }],
            ['资金 -400（演示）', () => {
                this.close();
                if (!(ctx.screens.current instanceof BoardScreen)) return Toast.show('请先进入对局棋盘页');
                if (!st.spend(400)) Toast.show('演示现金不足');
            }],
        ], 3);
        y = this.heading(p, y, '工具');
        const out = text(p, '', 24, y + 138, SHEET_W - 48, 200, Theme.font.xs, Theme.c.ink, { wrap: true, align: 'l', valign: 't', lineHeight: 26 });
        const out2 = text(p, '', 24, y + 340, SHEET_W - 48, 60, Theme.font.xs, Theme.c.ink, { wrap: true, align: 'l', valign: 't', lineHeight: 26 });
        ghostButton(p, '布局自检（越界/压胶囊）', 24, y, 360, 60, () => {
            // 自检时先收起菜单，避免菜单自身节点被检查
            const issues = checkLayout();
            const msg = formatIssues(issues);
            setText(out, msg);
            console.warn('[LayoutCheck] ' + msg + '\n' + issues.map((i) => i.kind + ' ' + i.path + ' ' + i.detail).join('\n'));
        }, 22);
        ghostButton(p, '逻辑自检（规则/棋盘/排名）', 24, y + 68, 360, 60, () => {
            const r = runSelfCheck();
            const msg = '逻辑自检：通过 ' + r.passed + ' 项，失败 ' + r.failures.length + ' 项' + (r.failures.length ? '\n' + r.failures.slice(0, 6).join('\n') : '');
            setText(out2, msg);
            console.warn('[SelfCheck] ' + msg);
        }, 22);
        ghostButton(p, '显示/隐藏胶囊占位', 396, y, 180, 60, () => {
            if (this.capsule) this.capsule.active = !this.capsule.active;
        }, 18);
        return y + 420;
    }
}
