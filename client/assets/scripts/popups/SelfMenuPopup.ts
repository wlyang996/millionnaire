/**
 * 点击底栏自己头像弹出的小菜单（用户 2026-10-07 要求：头像不再打开详情，详情只在顶部玩家条查看；认输移到这里）。
 * 四项：对局记录、游戏规则（用户 2026-10-09 从棋盘右侧移到这里）、主动托管（系统代为投骰，现金够就买 / 升级；全屏"托管中"遮罩里点"取消托管"恢复）、认输（二次确认）。
 * 菜单贴在头像上方，点菜单外任意处关闭。
 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { ctx } from '../ui/Ctx';
import { fillPoly, fillRR, gfx, mk, onTap, place, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { surrenderConfirm } from './ConfirmPopup';
import { GameLogPopup } from './GameLogPopup';
import { RulesPopup } from './RulesPopup';

const W = 300;
const ROW_H = 84;
const H = ROW_H * 4 + 16;

export class SelfMenuPopup extends Popup {
    constructor() {
        super('self-menu', '', W, H, 0, true);
    }

    mount(layer: Node): void {
        super.mount(layer);
        // 贴在底栏头像（x 30–100，y 1204）正上方，小三角指向头像
        place(this.panel, 20, 1198 - H - 18);
    }

    protected buildTitle(): void {
        // 菜单没有标题
    }

    protected buildBody(p: Node): void {
        fillPoly(gfx(mk(p, 'Tail', 0, 0, W, H + 16)), [[44, H - 1], [76, H - 1], [60, H + 14]], Theme.c.panelFill);
        const rows: [string, string, string, () => void][] = [
            ['对局记录', '每一步买地、租金、事件', Theme.c.navy, () => this.open(new GameLogPopup())],
            ['游戏规则', '价格、租金与玩法说明', Theme.c.navy, () => this.open(new RulesPopup())],
            ['主动托管', '系统代为投骰、买地和升级', Theme.c.navy, () => this.host()],
            ['认输', '现金与资产由系统回收', Theme.c.payRed, () => this.surrender()],
        ];
        rows.forEach(([label, note, color, fn], i) => {
            if (i > 0) fillRR(gfx(mk(p, 'Sep' + i, 20, 8 + ROW_H * i, W - 40, 2)), 0, 0, W - 40, 2, 1, Theme.c.panelLine);
            this.item(p, i, label, note, color, fn);
        });
    }

    private open(popup: Popup): void {
        this.close();
        ctx.popups.open(popup);
    }

    private item(p: Node, i: number, label: string, note: string, color: string, fn: () => void): void {
        const n = mk(p, 'Item:' + label, 8, 8 + i * ROW_H, W - 16, ROW_H);
        text(n, label, 20, 8, W - 56, 40, 30, color, { bold: true, align: 'l' });
        text(n, note, 20, 46, W - 56, 28, 18, Theme.c.noteGray, { align: 'l' });
        text(n, '›', W - 60, 0, 32, ROW_H, 34, Theme.c.noteGray);
        onTap(n, fn, false);
    }

    private host(): void {
        const st = ctx.store;
        this.close();
        if (st.online) {
            void st.online.setControl('HOSTED');
            return;
        }
        st.me().control = 'HOSTED';
        st.emit();
    }

    private surrender(): void {
        const st = ctx.store;
        this.close();
        ctx.popups.open(surrenderConfirm(() => {
            st.surrender();
            ctx.screens.go('spectator');
        }));
    }
}
