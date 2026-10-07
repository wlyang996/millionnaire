/**
 * 欠款·首段「应急抵押」（30 秒）：手动勾选资产（不自动挑选），按应急比例抵押：低价 80% / 中价 70% / 高价 60% / 车站 70%。
 * 筹足即还；首段超时进入第二段（DebtSecondPopup）；第二段到期仍未筹足则破产。
 * segment=2 时继续使用第二段的截止时间（弹窗等待计入第二段 30 秒，整段不超过 60 秒）。
 * 布局按设计稿 05"应急抵押"：红色欠款提示、勾选资产列表、已筹 / 还差、抵押所选资产、规则说明。
 */
import { Node } from 'cc';
import { debtShortfall, SECONDS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { Button, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCheck } from '../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';
import { Toast } from '../ui/Toast';
import { box, inlineRow, mortgageRow, noteLines } from './Common';
import { DebtSecondPopup } from './DebtSecondPopup';

const W = 640;
const H = 890;

/** 联机：服务端债务窗口（债务编号、窗口、截止时刻）。 */
export interface OnlineDebt { debtId: number; windowId: number; deadline: number }

/** 联机：每笔债务开始时已经抵押的资产（之后新抵押的即"本次已抵押"）。 */
const mortgagedBefore = new Map<number, Set<number>>();

function rememberMortgaged(debtId: number): Set<number> {
    let set = mortgagedBefore.get(debtId);
    if (!set) {
        const st = ctx.store;
        set = new Set(st.assetsOf(st.myId).filter((a) => a.p.mortgaged).map((a) => a.tile.index));
        mortgagedBefore.clear(); // 只保留当前这笔
        mortgagedBefore.set(debtId, set);
    }
    return set;
}

/** 本次债务里已经应急抵押的资产（联机按债务开始时的快照判断，演示按本地记录）。 */
export function debtMortgaged(online?: OnlineDebt): number[] {
    const st = ctx.store;
    if (!online) return st.debt.selected;
    const before = rememberMortgaged(online.debtId);
    return st.assetsOf(st.myId).filter((a) => a.p.mortgaged && !before.has(a.tile.index)).map((a) => a.tile.index);
}

export class DebtPopup extends Popup {
    private picked = new Set<number>();
    private busy = false;

    /** @param online 联机时为服务端债务窗口：勾选后逐块发送 EmergencyMortgage，筹足由服务端立即还款并关窗 */
    constructor(
        private readonly amount: number, private readonly creditor: string | null,
        private readonly segment: 1 | 2 = 1, private readonly carryDeadline = 0,
        private readonly online?: OnlineDebt,
    ) {
        super('debt', segment === 1 ? '应急抵押' : '应急抵押（第二段）', W, H, SECONDS.debt1);
        this.titleIcon = 'icon_house';
        const st = ctx.store;
        if (online) {
            rememberMortgaged(online.debtId);
            this.withDeadline(online.deadline);
        } else if (segment === 1) st.debt = { debtor: 'p1', amount, creditor, segment: 1, selected: [] };
    }

    mount(layer: Node): void {
        super.mount(layer);
        if (this.carryDeadline > 0 && this.cd) this.cd.setDeadline(this.carryDeadline, SECONDS.debt1);
    }

    private raisedDone(): number {
        const st = ctx.store;
        return debtMortgaged(this.online).reduce((s, i) => s + (st.prop(i)?.mortgagePaid ?? 0), 0);
    }

    private raisedPick(): number {
        const st = ctx.store;
        let s = 0;
        this.picked.forEach((i) => (s += st.emergencyAmount(i)));
        return s;
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const cash = st.me().cash;
        const raised = this.raisedDone() + this.raisedPick();
        // 联机：抵押所得已计入现金，还差 = 欠款 - 现金 - 本次勾选；演示：现金未含已抵押所得
        const short = this.online ? debtShortfall(this.amount, cash, this.raisedPick()) : debtShortfall(this.amount, cash, raised);
        const X = 24;
        const IW = W - 2 * X;

        // 红色提示：现金不足，需偿还 N；当前现金
        box(p, X, 92, IW, 120, '#FCDCD6', 20);
        inlineRow(p, W / 2, 98, 60, [{ t: '现金不足，需偿还', size: 34, color: Theme.c.payRed }, { coin: 40 }, { t: String(this.amount), size: 38, color: Theme.c.payRed }], 8);
        inlineRow(p, W / 2, 158, 44, [{ t: '当前现金', size: 24, color: Theme.c.noteGray }, { coin: 32 }, { t: String(cash), size: 30 }], 10);

        // 资产列表（勾选框 + 插画 + 档位 / 原价 + 应急比例 / 可得）
        const rowH = 92;
        const done = debtMortgaged(this.online);
        const assets = st.assetsOf(st.myId).filter((a) => !a.p.mortgaged || done.indexOf(a.tile.index) >= 0);
        const list = new ScrollList(p, X, 226, IW, rowH * 4);
        assets.forEach((a, i) => {
            const row = mk(list.content, 'Asset', 0, i * rowH, IW, rowH - 8);
            const isDone = done.indexOf(a.tile.index) >= 0;
            const on = this.picked.has(a.tile.index);
            const g = gfx(row);
            fillRR(g, 0, 0, IW, rowH - 8, 16, isDone ? '#EEF0F3' : Theme.c.white);
            strokeRR(g, 18, (rowH - 8) / 2 - 20, 40, 40, 8, on ? Theme.c.blue : '#B8C2D0', 3);
            if (on || isDone) {
                fillRR(g, 18, (rowH - 8) / 2 - 20, 40, 40, 8, isDone ? Theme.c.grayDark : Theme.c.blue);
                drawCheck(g, 38, (rowH - 8) / 2, 38, Theme.c.white, 4);
            }
            mortgageRow(row, 74, 0, IW - 74, rowH - 8, a.tile, isDone ? '已得' : '可得', st.emergencyAmount(a.tile.index), isDone);
            if (!isDone) onTap(row, () => {
                if (this.picked.has(a.tile.index)) this.picked.delete(a.tile.index);
                else this.picked.add(a.tile.index);
                this.rebuildBody();
            }, false);
        });
        if (assets.length === 0) text(list.content, '没有可抵押的资产', 0, 100, IW, 60, 26, Theme.c.noteGray);
        list.setContentHeight(assets.length * rowH);

        // 已筹 / 还差
        box(p, X, 600, IW, 60, Theme.c.boxBeige, 16);
        inlineRow(p, W / 2, 600, 60, [
            { t: '已筹', size: 26 }, { coin: 34 }, { t: String(raised), size: 30 },
            { t: '  /  还差', size: 26 }, { coin: 34 }, { t: String(short), size: 30, color: short > 0 ? Theme.c.payRed : Theme.c.gainGreen },
        ], 8);

        const btn: Button = secondaryButton(p, short === 0 && this.picked.size ? '抵押并还款' : '抵押所选资产', X, 674, IW, 88, () => this.confirm(), 34);
        btn.setEnabled(this.picked.size > 0 && !this.busy, this.busy ? '正在抵押…' : '请先勾选要抵押的资产（需手动选择）');

        const note = box(p, X, 776, IW, 96, Theme.c.boxGray, 18);
        noteLines(note, IW, [
            '手动勾选，筹足立即还款' + (this.segment === 1 ? '；首段超时进入第二段' : '；第二段到期未筹足即破产'),
            '有欠款时均按应急比例；无欠款在银行可按原价 100% 抵押',
        ], 8);
    }

    private confirm(): void {
        const st = ctx.store;
        if (this.online) {
            void this.confirmOnline();
            return;
        }
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
            this.close();
            st.emit();
        } else {
            this.rebuildBody();
        }
    }

    /** 联机：逐块发送应急抵押；服务端筹足即还款并关闭债务窗口，本弹窗随之关闭。 */
    private async confirmOnline(): Promise<void> {
        const online = ctx.store.online;
        if (!online || !this.online || this.busy) return;
        this.busy = true;
        this.rebuildBody();
        const tiles = Array.from(this.picked);
        for (const tile of tiles) {
            const g = ctx.store.game;
            if (!g.debt || g.debt.windowId !== this.online.windowId) break; // 已还清或窗口已结束
            const r = await online.act('EmergencyMortgage', { windowId: this.online.windowId, tile });
            if (!r.ok) break;
            this.picked.delete(tile);
        }
        this.busy = false;
        if (!this.closed) this.rebuildBody();
    }

    /** 联机：债务还清、破产或窗口换到第二段时关闭（第二段由棋盘页重新打开）。 */
    tick(): void {
        super.tick();
        if (this.closed || !this.online) return;
        const d = ctx.store.game.debt;
        if (!d || d.windowId !== this.online.windowId) this.close();
    }

    protected onExpire(): void {
        if (this.online) {
            // 联机：到期由服务端处理（首段 → 第二段；第二段 → 破产）
            this.close();
            return;
        }
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
