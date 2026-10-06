/** 资产总览：现金、净资产、资产列表（等级/抵押状态/标准价值）。银行格才能 100% 抵押/赎回（此处仅展示）。 */
import { Node } from 'cc';
import { standardValue } from '../core/Rules';
import { Theme } from '../core/Theme';
import { ghostButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';
import { chip } from '../ui/Widgets';
import { coinText, tierColor, tierName } from './Common';

export class AssetsPopup extends Popup {
    constructor() {
        super('assets', '我的资产', 660, 880, 0, true);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const st = ctx.store;
        const me = st.me();
        const sum = mk(p, 'Sum', 28, 92, w - 56, 120);
        fillRR(gfx(sum), 0, 0, w - 56, 120, 18, Theme.c.ivoryDark);
        text(sum, '现金', 20, 8, 100, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        coinText(sum, 20, 44, me.cash, Theme.font.lg);
        text(sum, '净资产', 250, 8, 120, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        coinText(sum, 250, 44, st.netWorthOf('p1'), Theme.font.lg, Theme.c.greenDark);
        text(sum, '冻结', 460, 8, 100, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        coinText(sum, 460, 44, me.frozen, Theme.font.lg);
        const assets = st.assetsOf('p1');
        text(p, '地产与车站（' + assets.length + '）', 28, 226, 300, 36, Theme.font.sm, Theme.c.ink, { bold: true, align: 'l' });
        const rowH = 88;
        const list = new ScrollList(p, 28, 268, w - 56, h - 268 - 120);
        assets.forEach((a, i) => {
            const row = mk(list.content, 'Asset', 0, i * rowH, w - 56, rowH - 8);
            const g = gfx(row);
            fillRR(g, 0, 0, w - 56, rowH - 8, 16, a.p.mortgaged ? '#E9EDF1' : Theme.c.white);
            fillRR(g, 14, 14, 52, 52, 10, tierColor(a.tile.tier, a.tile.type === 'STATION'));
            text(row, a.tile.name + ' · ' + tierName(a.tile), 82, 4, 300, 38, Theme.font.sm, Theme.c.ink, { bold: true, align: 'l' });
            const station = a.tile.type === 'STATION';
            text(row, station ? '车站' : a.p.level === 0 ? '未升级' : a.p.level + ' 级', 82, 40, 140, 30, Theme.font.xs, Theme.c.inkSoft, { align: 'l' });
            if (a.p.mortgaged) chip(row, 200, 40, '已抵押', Theme.c.gray, Theme.c.white, 16, 26);
            const sv = standardValue(station, a.tile.tier, a.p.upgradeSpent);
            text(row, '标准价值', w - 56 - 220, 4, 100, 30, Theme.font.xs, Theme.c.inkSoft, { align: 'l' });
            coinText(row, w - 56 - 130, 4, sv, Theme.font.sm);
        });
        if (assets.length === 0) text(list.content, '暂无资产', 0, 60, w - 56, 60, Theme.font.md, Theme.c.inkFaint);
        list.setContentHeight(assets.length * rowH);
        ghostButton(p, '关闭', 40, h - 100, w - 80, 76, () => this.close(), Theme.font.lg);
    }
}
