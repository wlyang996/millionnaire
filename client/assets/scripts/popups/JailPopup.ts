/**
 * 监狱操作（设计稿 15 右）：棋盘压暗，监狱近景（石拱门、铁栏、招牌写"监狱"）+ 右上 ×；
 * 羊皮纸"已关押 / 已失败 N 次"；黄色"掷出狱骰"、蓝色"支付 500 出狱"，持有出狱卡时显示绿色"使用出狱卡 (×N)"；
 * 底部说明"偶数释放 · 连续 3 次失败自动释放 / 出狱后另投一次移动骰"。
 * 掷骰交给棋盘页（关闭本页后播骰子动画，联机发 RollDice）；支付保释联机发 PayBail。
 * 出狱卡的主动使用服务端尚未提供命令，按钮置灰。关闭后仍可直接点棋盘中央的骰子。
 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { art } from '../ui/Art';
import { Button, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillCircle, fillRR, gfx, line, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { box, noteLines } from './Common';

const W = 680;
const H = 1100;
const BAIL = 500;
const SCENE_W = 540;
const SCENE_H = Math.round(SCENE_W * 1207 / 1303);

export class JailPopup extends Popup {
    /**
     * @param windowId 联机时为服务端的回合窗口（JAIL_DECISION 阶段）
     * @param onRoll 掷出狱骰：由棋盘页播放骰子并发送命令
     */
    constructor(private readonly windowId: number | undefined, private readonly onRoll: () => void) {
        super('jail', '', W, H, 0, false);
        this.dimBackground = true;
    }

    protected buildBody(p: Node, w: number): void {
        gfx(this.panel).clear(); // 设计稿没有面板：监狱场景直接压在暗场上
        const st = ctx.store;
        const me = st.me();
        const sx = (w - SCENE_W) / 2;
        const sy = 96;
        if (!art(p, 'scene_jail_closeup', sx, sy, SCENE_W, SCENE_H)) {
            fillRR(gfx(mk(p, 'Scene', sx, sy, SCENE_W, SCENE_H)), 0, 0, SCENE_W, SCENE_H, 30, '#8C8F96');
        }
        // 招牌（素材上的空白木牌，约在宽 29%–75%、高 6%–20%）
        text(p, '监狱', sx + SCENE_W * 0.29, sy + SCENE_H * 0.055, SCENE_W * 0.46, SCENE_H * 0.15, 52, '#5A3412', { bold: true });

        const x = mk(p, 'Close', w - 96, 120, 64, 64);
        const xg = gfx(x);
        fillCircle(xg, 32, 32, 30, Theme.c.white);
        line(xg, 21, 21, 43, 43, Theme.c.navy, 5);
        line(xg, 43, 21, 21, 43, Theme.c.navy, 5);
        onTap(x, () => this.close(), false);

        // 羊皮纸：已关押 / 已失败 N 次
        const tag = mk(p, 'Status', (w - 380) / 2, sy + SCENE_H - 40, 380, 110);
        const tg = gfx(tag);
        fillRR(tg, 0, 6, 380, 104, 16, '#6B4423');
        fillRR(tg, 0, 0, 380, 104, 16, '#F6E7C4');
        strokeRR(tg, 6, 6, 368, 92, 12, '#C9A46A', 2);
        text(tag, '已关押', 0, 6, 380, 52, 38, Theme.c.payRed, { bold: true });
        text(tag, '已失败 ' + (me ? me.jailFailures : 0) + ' 次', 0, 54, 380, 40, 24, Theme.c.payRed, { bold: true });

        const by = sy + SCENE_H + 84;
        const roll = primaryButton(p, '掷出狱骰', 60, by, w - 120, 96, () => {
            this.close();
            this.onRoll();
        }, 38);
        art(roll.node, 'dice_5', 70, 14, 62, 62);
        const pay: Button = secondaryButton(p, '支付 ' + BAIL + ' 出狱', 60, by + 108, w - 120, 80, () => this.payBail(), 32);
        art(pay.node, 'icon_coin', 150, 14, 46, 46);
        pay.setEnabled((me ? me.cash : 0) >= BAIL, '现金不足 ' + BAIL);
        const cards = st.game.myHand.filter((c) => c.type === 'JAIL_RELEASE').length;
        let ny = by + 200;
        if (cards > 0) {
            const use = new Button(p, '使用出狱卡（×' + cards + '）', 60, ny, w - 120, 80, 'success', () => undefined, 32);
            use.setEnabled(false, '出狱卡的使用即将开放');
            ny += 96;
        }
        const note = box(p, 40, Math.max(ny + 8, H - 112), w - 80, 96, Theme.c.boxBeige, 18);
        noteLines(note, w - 80, ['偶数释放 · 连续 3 次失败自动释放', '出狱后另投一次移动骰'], 8, true);
    }

    private payBail(): void {
        const st = ctx.store;
        this.close();
        if (st.online && this.windowId !== undefined) {
            void st.online.act('PayBail', { windowId: this.windowId });
            return;
        }
        const me = st.me();
        if (!st.spend(BAIL)) return;
        me.inJail = false;
        st.emit();
    }

    /** 联机：窗口不再是出狱判定（已掷骰 / 已付费 / 超时）时关闭。 */
    tick(): void {
        super.tick();
        const online = ctx.store.online;
        if (this.closed || !online || this.windowId === undefined) return;
        const w = online.myWindow('TURN');
        if (!w || w.windowId !== this.windowId || ctx.store.game.stage !== 'JAIL_DECISION') this.close();
    }
}
