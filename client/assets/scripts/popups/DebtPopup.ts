/**
 * 欠款·首段「应急抵押」（30 秒）：手动勾选资产（不自动挑选），按应急比例抵押：低价 80% / 中价 70% / 高价 60% / 车站 70%。
 * 筹足即还；首段超时进入第二段（DebtSecondPopup）；第二段到期仍未筹足则破产。
 * segment=2 时继续使用第二段的截止时间（弹窗等待计入第二段 30 秒，整段不超过 60 秒）。
 */
import { Node } from 'cc';
import { debtShortfall, emergencyMortgage, emergencyRatioLabel, SECONDS, landPrice } from '../core/Rules';
import { Theme } from '../core/Theme';
import { Button, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCheck } from '../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';
import { Toast } from '../ui/Toast';
import { coinText, tierColor, tierName } from './Common';
import { DebtSecondPopup } from './DebtSecondPopup';

export class DebtPopup extends Popup {
    private picked = new Set<number>();

    constructor(
        private readonly amount: number, private readonly creditor: string | null,
        private readonly segment: 1 | 2 = 1, private readonly carryDeadline = 0,
    ) {
        super('debt', segment === 1 ? '应急抵押' : '应急抵押（第二段）', 660, 860, SECONDS.debt1);
        const st = ctx.store;
        if (segment === 1) st.debt = { debtor: 'p1', amount, creditor, segment: 1, selected: [] };
    }

    mount(layer: Node): void {
        super.mount(layer);
        if (this.carryDeadline > 0 && this.cd) this.cd.setDeadline(this.carryDeadline, SECONDS.debt1);
    }

    private raisedDone(): number {
        const st = ctx.store;
        return st.debt.selected.reduce((s, i) => s + (st.prop(i)?.mortgagePaid ?? 0), 0);
    }

    private raisedPick(): number {
        const st = ctx.store;
        let s = 0;
        this.picked.forEach((i) => (s += st.emergencyAmount(i)));
        return s;
    }

    protected buildBody(p: Node, w: number): void {
        const st = ctx.store;
        const cash = st.me().cash;
        const raised = this.raisedDone() + this.raisedPick();
        const short = debtShortfall(this.amount, cash, raised);
        const cred = this.creditor ? st.player(this.creditor)?.nickname ?? '对手' : '银行';
        const ban = mk(p, 'Banner', 28, 88, w - 56, 110);
        fillRR(gfx(ban), 0, 0, w - 56, 110, 18, Theme.c.redSoft);
        text(ban, '现金不足，需偿还 ' + this.amount + '（' + cred + '）', 0, 6, w - 56, 54, Theme.font.lg, Theme.c.redDark, { bold: true });
        text(ban, '当前现金 ' + cash, 0, 62, w - 56, 36, Theme.font.md, Theme.c.ink, { bold: true });

        // 资产列表
        const rowH = 96;
        const done = st.debt.selected;
        const assets = st.assetsOf('p1').filter((a) => !a.p.mortgaged || done.indexOf(a.tile.index) >= 0);
        const list = new ScrollList(p, 28, 212, w - 56, 316);
        assets.forEach((a, i) => {
            const row = mk(list.content, 'Asset', 0, i * rowH, w - 56, rowH - 8);
            const isDone = done.indexOf(a.tile.index) >= 0;
            const on = this.picked.has(a.tile.index);
            const g = gfx(row);
            fillRR(g, 0, 0, w - 56, rowH - 8, 16, isDone ? '#E9EDF1' : Theme.c.white);
            fillRR(g, 74, 14, 62, 62, 12, tierColor(a.tile.tier, a.tile.type === 'STATION'));
            strokeRR(g, 18, 26, 38, 38, 8, on ? Theme.c.blue : Theme.c.grayDark, 3);
            if (on || isDone) {
                fillRR(g, 18, 26, 38, 38, 8, isDone ? Theme.c.grayDark : Theme.c.blue);
                drawCheck(g, 37, 45, 38, Theme.c.white, 4);
            }
            const station = a.tile.type === 'STATION';
            text(row, a.tile.name + (a.p.level ? ' · ' + a.p.level + '级' : ''), 150, 6, 300, 38, Theme.font.sm, Theme.c.ink, { bold: true, align: 'l' });
            text(row, '原价 ' + landPrice(station, a.tile.tier) + ' · 应急 ' + emergencyRatioLabel(station, a.tile.tier), 150, 44, 300, 32, Theme.font.xs, Theme.c.inkSoft, { align: 'l' });
            coinText(row, w - 56 - 150, 28, '+' + emergencyMortgage(station, a.tile.tier), Theme.font.md, isDone ? Theme.c.inkFaint : Theme.c.greenDark);
            if (isDone) text(row, '已抵押', 380, 6, 100, 32, Theme.font.xs, Theme.c.inkSoft, { bold: true });
            else onTap(row, () => {
                if (this.picked.has(a.tile.index)) this.picked.delete(a.tile.index);
                else this.picked.add(a.tile.index);
                this.rebuildBody();
            }, false);
        });
        if (assets.length === 0) text(list.content, '没有可抵押的资产', 0, 100, w - 56, 60, Theme.font.md, Theme.c.inkSoft);
        list.setContentHeight(assets.length * rowH);

        const sum = mk(p, 'Sum', 28, 540, w - 56, 64);
        fillRR(gfx(sum), 0, 0, w - 56, 64, 14, '#FFF1C9');
        text(sum, '已筹', 40, 0, 70, 64, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        coinText(sum, 110, 10, raised, Theme.font.lg);
        text(sum, '/ 还差', 300, 0, 100, 64, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        coinText(sum, 410, 10, short, Theme.font.lg, short > 0 ? Theme.c.redDark : Theme.c.greenDark);

        const btn: Button = primaryButton(p, short === 0 && this.picked.size ? '抵押并还款' : '抵押所选资产', 28, 622, w - 56, 92, () => this.confirm(), Theme.font.lg);
        btn.setEnabled(this.picked.size > 0, '请先勾选要抵押的资产（需手动选择）');
        const n = mk(p, 'Note', 28, 728, w - 56, 110);
        fillRR(gfx(n), 0, 0, w - 56, 110, 14, Theme.c.ivoryDark);
        text(n, '手动勾选，筹足立即还款。有欠款时按应急比例抵押；无欠款时在银行可按原价 100% 抵押。\n' + (this.segment === 1 ? '首段 30 秒，超时进入第二段。' : '第二段到期仍未筹足将破产。'), 14, 6, w - 84, 100, Theme.font.xs, Theme.c.inkSoft, { wrap: true, align: 'l', valign: 't', lineHeight: 30 });
    }

    private confirm(): void {
        const st = ctx.store;
        this.picked.forEach((i) => {
            const prop = st.prop(i);
            if (prop) {
                prop.mortgaged = true;
                prop.mortgagePaid = st.emergencyAmount(i);
            }
            st.debt.selected.push(i);
        });
        this.picked.clear();
        const me = st.me();
        const total = this.raisedDone();
        if (debtShortfall(this.amount, me.cash, total) === 0) {
            me.cash = me.cash + total - this.amount;
            Toast.show('欠款已还清');
            this.close();
            st.emit();
        } else {
            Toast.show('已抵押，仍差 ' + debtShortfall(this.amount, me.cash, total));
            this.rebuildBody();
        }
    }

    protected onExpire(): void {
        if (this.segment === 1) {
            this.close();
            ctx.popups.open(new DebtSecondPopup(this.amount, this.creditor));
        } else {
            Toast.show('第二段到期仍未筹足，宣告破产');
            this.close();
            ctx.store.surrender('BANKRUPT');
            ctx.screens.go('spectator');
        }
    }
}

/** 发起欠款流程：抵押完全部可抵押资产仍不足以偿债则立即破产（不开 30+30 秒窗口），否则打开应急抵押首段。 */
export function beginDebt(amount: number, creditor: string | null): void {
    const st = ctx.store;
    if (st.debtCapacity() < amount) {
        Toast.show('抵押全部资产也无法偿还 ' + amount + '，直接破产');
        st.surrender('BANKRUPT');
        ctx.screens.go('spectator');
        return;
    }
    ctx.popups.open(new DebtPopup(amount, creditor));
}
