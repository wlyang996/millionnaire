/**
 * 欠款·第二段（30 秒）：继续抵押 / 确认破产。首段 30 秒 + 第二段 30 秒，总计不超过 60 秒；不选默认继续。
 * 布局按设计稿 05"仍有欠款"：副标题、三条规则、继续抵押（蓝）/ 确认破产（浅底红字）、已抵押资产、已筹 / 还差。
 */
import { Node } from 'cc';
import { debtShortfall, SECONDS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { ghostRedButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { strokeRR, gfx, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { box, inlineRow, mortgageRow, noteLines } from './Common';
import { DebtPopup, debtMortgaged, OnlineDebt } from './DebtPopup';

const W = 600;
const ROW_H = 96;
const LIST_Y = 570;

/** 已抵押资产最多列 2 行，面板高度随行数变化。 */
function rowsShown(online?: OnlineDebt): number {
    return Math.max(1, Math.min(2, debtMortgaged(online).length));
}

export class DebtSecondPopup extends Popup {
    /** @param online 联机时为服务端第二段债务窗口：继续 → ContinueDebt，破产 → DeclareBankruptcy */
    constructor(private readonly amount: number, private readonly creditor: string | null, private readonly online?: OnlineDebt) {
        super('debt2', '仍有欠款', W, LIST_Y + rowsShown(online) * ROW_H + 96, SECONDS.debt1);
        this.titleIcon = 'icon_house';
        if (online) this.withDeadline(online.deadline);
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const X = 28;
        const IW = W - 2 * X;
        text(p, '是否继续抵押？', 0, 84, W, 48, 30, Theme.c.navy, { bold: true });
        const info = box(p, X, 146, IW, 140, Theme.c.boxGray, 18);
        noteLines(info, IW, ['首段 30 秒 + 第二段 30 秒，总计不超过 60 秒', '弹窗等待计入第二段；不选择默认继续', '第二段到期仍未筹足则破产'], 10, true);
        secondaryButton(p, '继续抵押', X, 306, IW, 92, () => this.goOn(), 36);
        ghostRedButton(p, '确认破产', X, 414, IW, 88, () => this.bankrupt(), 36);

        text(p, '已抵押资产', X, 526, 150, 40, 26, Theme.c.navy, { bold: true, align: 'l' });
        text(p, '（已选择）', X + 140, 528, 200, 40, 22, Theme.c.noteGray, { align: 'l' });
        const done = debtMortgaged(this.online);
        const rows = rowsShown(this.online);
        const list = box(p, X, LIST_Y, IW, rows * ROW_H, Theme.c.white, 18);
        strokeRR(gfx(list), 0, 0, IW, rows * ROW_H, 18, Theme.c.panelLine, 2);
        let raised = 0;
        done.forEach((i) => (raised += st.prop(i)?.mortgagePaid ?? 0));
        done.slice(0, rows).forEach((i, k) => {
            mortgageRow(list, 16, k * ROW_H + 4, IW - 32, ROW_H - 8, st.tile(i), '已得', st.prop(i)?.mortgagePaid ?? 0);
        });
        if (done.length === 0) text(list, '尚未抵押任何资产', 0, 0, IW, ROW_H, 24, Theme.c.noteGray);
        if (done.length > rows) text(list, '等 ' + done.length + ' 处', IW - 120, 4, 104, 30, 20, Theme.c.noteGray, { align: 'r' });

        const sy = LIST_Y + rows * ROW_H + 12;
        const short = this.online ? Math.max(0, this.amount - st.me().cash) : debtShortfall(this.amount, st.me().cash, raised);
        box(p, X, sy, IW, 60, Theme.c.boxBeige, 16);
        inlineRow(p, W / 2, sy, 60, [
            { t: '已筹', size: 26 }, { coin: 34 }, { t: String(raised), size: 30 },
            { t: '  /  还差', size: 26 }, { coin: 34 }, { t: String(short), size: 30, color: Theme.c.payRed },
        ], 8);
    }

    private goOn(): void {
        const deadline = this.cd ? this.cd.getDeadline() : 0;
        this.close();
        const online = ctx.store.online;
        if (online && this.online) void online.act('ContinueDebt', { windowId: this.online.windowId });
        ctx.popups.open(new DebtPopup(this.amount, this.creditor, 2, deadline, this.online));
    }

    private bankrupt(): void {
        this.close();
        const online = ctx.store.online;
        if (online && this.online) {
            void online.act('DeclareBankruptcy', { windowId: this.online.windowId });
            return;
        }
        ctx.store.surrender('BANKRUPT');
        ctx.screens.go('spectator');
    }

    /** 联机：债务还清、破产或窗口结束时关闭。 */
    tick(): void {
        super.tick();
        if (this.closed || !this.online) return;
        const d = ctx.store.game.debt;
        if (!d || d.windowId !== this.online.windowId) this.close();
    }

    /** 不选则默认继续（联机：第二段到期由服务端判破产） */
    protected onExpire(): void {
        if (this.online) {
            this.close();
            return;
        }
        this.goOn();
    }
}
