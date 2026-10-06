/** 欠款·第二段（30 秒）：继续抵押 / 确认破产。首段 30 秒 + 第二段 30 秒，总计不超过 60 秒；不选默认继续。 */
import { Node } from 'cc';
import { debtShortfall, SECONDS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { dangerButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillCircle, fillRR, gfx, mk, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { coinText, tierName } from './Common';
import { DebtPopup } from './DebtPopup';

export class DebtSecondPopup extends Popup {
    constructor(private readonly amount: number, private readonly creditor: string | null) {
        super('debt2', '仍有欠款', 640, 860, SECONDS.debt1);
    }

    protected buildBody(p: Node, w: number): void {
        const st = ctx.store;
        text(p, '是否继续抵押？', 28, 92, w - 56, 48, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
        const box = mk(p, 'Info', 28, 150, w - 56, 150);
        fillRR(gfx(box), 0, 0, w - 56, 150, 18, Theme.c.ivoryDark);
        const lines = ['首段 30 秒 + 第二段 30 秒，总计不超过 60 秒', '弹窗等待计入第二段；不选择默认继续', '第二段到期仍未筹足则破产'];
        lines.forEach((l, i) => {
            fillCircle(gfx(box), 26, 28 + i * 44, 8, Theme.c.orange);
            text(box, l, 46, 8 + i * 44, w - 120, 40, Theme.font.xs, Theme.c.ink, { align: 'l' });
        });
        secondaryButton(p, '继续抵押', 28, 318, w - 56, 92, () => this.goOn(), Theme.font.lg);
        dangerButton(p, '确认破产', 28, 424, w - 56, 80, () => this.bankrupt(), Theme.font.md);
        text(p, '已抵押资产（已选择）', 28, 520, w - 56, 36, Theme.font.sm, Theme.c.ink, { bold: true, align: 'l' });
        const done = st.debt.selected;
        const list = mk(p, 'Done', 28, 560, w - 56, 150);
        fillRR(gfx(list), 0, 0, w - 56, 150, 16, Theme.c.white);
        let raised = 0;
        done.slice(0, 3).forEach((i, k) => {
            const t = st.tile(i);
            const paid = st.prop(i)?.mortgagePaid ?? 0;
            raised += paid;
            text(list, t.name + ' · 原价 ' + st.unownedLandPrice(i), 20, 8 + k * 44, 360, 40, Theme.font.xs, Theme.c.ink, { align: 'l' });
            coinText(list, w - 56 - 150, 8 + k * 44 + 4, paid, Theme.font.sm, Theme.c.greenDark);
        });
        if (done.length === 0) text(list, '尚未抵押任何资产', 0, 0, w - 56, 150, Theme.font.sm, Theme.c.inkFaint);
        const sum = mk(p, 'Sum', 28, 724, w - 56, 64);
        fillRR(gfx(sum), 0, 0, w - 56, 64, 14, '#FFF1C9');
        text(sum, '已筹', 40, 0, 70, 64, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        coinText(sum, 110, 10, raised, Theme.font.lg);
        text(sum, '/ 还差', 300, 0, 100, 64, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        coinText(sum, 410, 10, debtShortfall(this.amount, st.me().cash, raised), Theme.font.lg, Theme.c.redDark);
    }

    private goOn(): void {
        const deadline = this.cd ? this.cd.getDeadline() : 0;
        this.close();
        ctx.popups.open(new DebtPopup(this.amount, this.creditor, 2, deadline));
    }

    private bankrupt(): void {
        this.close();
        ctx.store.surrender('BANKRUPT');
        Toast.show('已确认破产，进入观战');
        ctx.screens.go('spectator');
    }

    /** 不选则默认继续 */
    protected onExpire(): void {
        this.goOn();
    }
}
