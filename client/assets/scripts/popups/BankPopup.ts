/**
 * 银行（设计稿 15 左、中）：标题"银行"（银行图标）+ 右上 ×；"抵押 / 赎回"两个页签。
 * - 抵押：列出我未抵押的地产/车站（插画、地名与勾选、档位与等级、原价、可得），单选；"本次可得"红字，黄色"确认抵押"。
 *   银行按原价 100% 抵押，保留等级与所有权，抵押期间不收租。
 * - 赎回：列出我已抵押的资产（"已抵押"红签、已抵押本金），下方手续费（银行 0）、应付金额、可用现金，黄色"确认赎回"。
 * 联机时每次确认发送 BankMortgage / Redeem（服务端在同一个银行窗口内可多次办理），× 发送 FinishBank；
 * 窗口被服务端结束（超时）后自动关闭。演示模式直接改本地数据。
 */
import { Node } from 'cc';
import { BoardTile, PropertyState } from '../core/Models';
import { landPrice, TIERS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { art } from '../ui/Art';
import { Button, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCheck } from '../ui/Icons';
import { fillCircle, fillRR, gfx, line, mk, onTap, strokeCircle, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';
import { box, inlineRow, inlineRowLeft, noteLines, propertyArtKey } from './Common';

const W = 640;
const H = 880;
const ROW_H = 112;

type Tab = 'mortgage' | 'redeem';

export class BankPopup extends Popup {
    private tab: Tab = 'mortgage';
    private sel = -1;
    private busy = false;

    /** @param windowId 联机时为服务端的银行（落点）窗口；演示时不传 */
    constructor(private readonly windowId?: number) {
        super('bank', '银行', W, H, 0, false);
        this.titleIcon = 'icon_bank';
    }

    private assets(): { tile: BoardTile; p: PropertyState }[] {
        const st = ctx.store;
        return st.assetsOf(st.myId).filter((a) => (this.tab === 'mortgage' ? !a.p.mortgaged : a.p.mortgaged));
    }

    protected buildBody(p: Node, w: number): void {
        const st = ctx.store;
        // 右上 ×：结束银行办理
        const x = mk(p, 'Close', w - 76, 18, 56, 56);
        const xg = gfx(x);
        fillCircle(xg, 28, 28, 26, Theme.c.white);
        strokeCircle(xg, 28, 28, 26, Theme.c.panelLine, 2);
        line(xg, 19, 19, 37, 37, Theme.c.navy, 4);
        line(xg, 37, 19, 19, 37, Theme.c.navy, 4);
        onTap(x, () => this.finish(), false);

        // 页签
        const tabs: [Tab, string][] = [['mortgage', '抵押'], ['redeem', '赎回']];
        tabs.forEach(([id, label], i) => {
            const on = this.tab === id;
            const tn = mk(p, 'Tab:' + id, 24 + i * ((w - 48) / 2), 92, (w - 48) / 2, 70);
            const tg = gfx(tn);
            fillRR(tg, 0, 0, (w - 48) / 2, 70, 18, on ? '#2F86E8' : '#E6E9EE');
            text(tn, label, 0, 0, (w - 48) / 2, 70, 32, on ? Theme.c.white : Theme.c.noteGray, { bold: true });
            onTap(tn, () => {
                if (this.tab === id) return;
                this.tab = id;
                this.sel = -1;
                this.rebuildBody();
            }, false);
        });
        const mortgage = this.tab === 'mortgage';
        text(p, mortgage ? '选择要抵押的地块，银行将按原价支付。' : '选择要赎回的地块，支付本金与手续费后即可赎回。',
            24, 170, w - 48, 44, Theme.font.sm, Theme.c.noteGray);

        const list = this.assets();
        if (this.sel >= list.length) this.sel = -1;
        const listH = mortgage ? 3 * ROW_H : 2 * ROW_H;
        const sl = new ScrollList(p, 24, 222, w - 48, listH);
        list.forEach((a, i) => this.row(sl.content, a, i, w - 48, mortgage));
        sl.setContentHeight(list.length * ROW_H);
        if (list.length === 0) text(p, mortgage ? '没有可抵押的资产' : '没有已抵押的资产', 24, 222, w - 48, 100, Theme.font.md, Theme.c.noteGray);

        const chosen = this.sel >= 0 ? list[this.sel] : undefined;
        const value = chosen ? this.amountOf(chosen) : 0;
        let btn: Button;
        if (mortgage) {
            const sy = 222 + listH + 14;
            box(p, 24, sy, w - 48, 200, Theme.c.white, 20);
            inlineRow(p, w / 2, sy + 14, 64, [{ t: '本次可得', size: 28 }, { coin: 40 }, { t: String(value), size: 44, color: Theme.c.payRed }], 12);
            btn = primaryButton(p, '确认抵押', 90, sy + 92, w - 180, 92, () => this.confirm(), 36);
            const note = box(p, 24, H - 92, w - 48, 72, Theme.c.boxGray, 18);
            noteLines(note, w - 48, ['银行按原价 100% · 保留等级与所有权 · 抵押期间不收租'], 16);
        } else {
            const sy = 222 + listH + 14;
            const sum = box(p, 24, sy, w - 48, 214, Theme.c.white, 20);
            strokeRR(gfx(sum), 0, 0, w - 48, 214, 20, Theme.c.panelLine, 2);
            const kv = (label: string, v: number, y: number, color: string, size: number) => {
                text(sum, label, 24, y, 200, 56, 26, Theme.c.navy, { align: 'l' });
                const vw = String(v).length * size * 0.58 + 48;
                inlineRowLeft(sum, w - 48 - 24 - vw, y, 56, [{ coin: 36 }, { t: String(v), size, color }], 10);
            };
            kv('手续费', 0, 10, Theme.c.navy, 30);
            kv('应付金额', value, 72, Theme.c.payRed, 38);
            fillRR(gfx(mk(sum, 'Line', 20, 140, w - 88, 2)), 0, 0, w - 88, 2, 1, Theme.c.panelLine);
            kv('可用现金', st.me().cash, 150, Theme.c.navy, 32);
            btn = primaryButton(p, '确认赎回', 90, sy + 232, w - 180, 92, () => this.confirm(), 36);
            const note = box(p, 24, H - 92, w - 48, 72, Theme.c.boxGray, 18);
            noteLines(note, w - 48, ['银行免费赎回 · 其他位置手续费 10%'], 16);
        }
        const enough = mortgage || value <= st.me().cash;
        btn.setEnabled(!!chosen && enough && !this.busy, !chosen ? '请先选择地块' : '可用现金不足');
    }

    /** 抵押可得 = 原价（100%）；赎回应付 = 抵押本金（银行免手续费）。 */
    private amountOf(a: { tile: BoardTile; p: PropertyState }): number {
        return this.tab === 'mortgage' ? landPrice(a.tile.type === 'STATION', a.tile.tier) : a.p.mortgagePaid;
    }

    private row(parent: Node, a: { tile: BoardTile; p: PropertyState }, i: number, w: number, mortgage: boolean): void {
        const on = this.sel === i;
        const n = mk(parent, 'Asset' + i, 0, i * ROW_H, w, ROW_H - 10);
        const g = gfx(n);
        const rh = ROW_H - 10;
        fillRR(g, 0, 0, w, rh, 18, on ? '#EAF4FF' : Theme.c.white);
        strokeRR(g, 1, 1, w - 2, rh - 2, 18, on ? '#3E8EF2' : Theme.c.panelLine, on ? 4 : 2);
        art(n, propertyArtKey(a.tile), 10, 8, 104, rh - 16);
        text(n, a.tile.name, 124, 10, 130, 44, 28, Theme.c.navy, { bold: true, align: 'l' });
        // 勾选圈
        const cg = gfx(mk(n, 'Check', 250, 14, 36, 36));
        if (on) {
            fillCircle(cg, 18, 18, 18, '#2F86E8');
            drawCheck(cg, 18, 18, 28, Theme.c.white, 4);
        } else strokeCircle(cg, 18, 18, 16, '#B8C2D0', 3);
        const station = a.tile.type === 'STATION';
        if (mortgage) {
            const tier = station ? '车站' : TIERS[a.tile.tier ?? 'LOW'].name + ' ' + a.p.level + '级';
            text(n, tier, 124, 54, 160, 36, 22, Theme.c.noteGray, { align: 'l' });
            const price = landPrice(station, a.tile.tier);
            fillRR(gfx(mk(n, 'Sep', 300, 14, 2, rh - 28)), 0, 0, 2, rh - 28, 1, Theme.c.panelLine);
            text(n, '原价', 316, 8, 100, 34, 22, Theme.c.noteGray, { align: 'l' });
            inlineRowLeft(n, 316, 44, 44, [{ coin: 30 }, { t: String(price), size: 28 }], 8);
            fillRR(gfx(mk(n, 'Sep2', 446, 14, 2, rh - 28)), 0, 0, 2, rh - 28, 1, Theme.c.panelLine);
            text(n, '可得', 462, 8, 100, 34, 22, Theme.c.noteGray, { align: 'l' });
            inlineRowLeft(n, 462, 44, 44, [{ coin: 30 }, { t: String(price), size: 28 }], 8);
        } else {
            const tag = mk(n, 'Tag', 124, 56, 96, 32);
            fillRR(gfx(tag), 0, 0, 96, 32, 10, '#FDE2E4');
            text(tag, '已抵押', 0, 0, 96, 32, 20, Theme.c.payRed, { bold: true });
            fillRR(gfx(mk(n, 'Sep', 316, 14, 2, rh - 28)), 0, 0, 2, rh - 28, 1, Theme.c.panelLine);
            text(n, '已抵押本金', 336, 8, 200, 34, 22, Theme.c.navy, { align: 'l' });
            inlineRowLeft(n, 336, 44, 44, [{ coin: 32 }, { t: String(a.p.mortgagePaid), size: 30 }], 8);
        }
        onTap(n, () => {
            this.sel = on ? -1 : i;
            this.rebuildBody();
        }, false);
    }

    private confirm(): void {
        const st = ctx.store;
        const a = this.sel >= 0 ? this.assets()[this.sel] : undefined;
        if (!a || this.busy) return;
        if (st.online && this.windowId !== undefined) {
            this.busy = true;
            this.rebuildBody();
            const cmd = this.tab === 'mortgage' ? 'BankMortgage' : 'Redeem';
            void st.online.act(cmd, { windowId: this.windowId, tile: a.tile.index }).then(() => {
                // 结果随推送更新到 store（UPDATE 先于 RESULT 到达）；这里按新数据重画
                this.busy = false;
                this.sel = -1;
                if (!this.closed) this.rebuildBody();
            });
            return;
        }
        const amount = this.amountOf(a);
        if (this.tab === 'mortgage') {
            a.p.mortgaged = true;
            a.p.mortgagePaid = amount;
            st.me().cash += amount;
        } else {
            if (!st.spend(amount)) return;
            a.p.mortgaged = false;
            a.p.mortgagePaid = 0;
        }
        this.sel = -1;
        st.emit();
        this.rebuildBody();
    }

    private finish(): void {
        const online = ctx.store.online;
        this.close();
        if (online && this.windowId !== undefined) void online.act('FinishBank', { windowId: this.windowId });
    }

    /** 联机：银行窗口被服务端结束（超时或已办完）时自动关闭。 */
    tick(): void {
        super.tick();
        const online = ctx.store.online;
        if (this.closed || !online || this.windowId === undefined) return;
        const w = online.myWindow();
        if (!w || w.windowId !== this.windowId) this.close();
    }
}
