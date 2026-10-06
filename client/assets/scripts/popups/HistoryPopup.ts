/** 我的战绩：最近 20 局（模式、人数、时长、排名、最终资产），只看自己。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { ghostButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';

export class HistoryPopup extends Popup {
    constructor() {
        super('history', '我的战绩 · 最近 20 局', 640, 1040, 0, true);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const cols: [string, number, number][] = [['模式', 24, 150], ['人数', 174, 80], ['时长', 254, 110], ['排名', 364, 90], ['最终资产', 454, 170]];
        const head = mk(p, 'Head', 0, 96, w, 56);
        fillRR(gfx(head), 24, 0, w - 48, 52, 14, Theme.c.ivoryDark);
        for (const [t, x, cw] of cols) text(head, t, x, 0, cw, 52, Theme.font.sm, Theme.c.inkSoft, { bold: true });
        const list = new ScrollList(p, 0, 160, w, h - 160 - 120);
        const hist = ctx.store.history;
        hist.forEach((r, i) => {
            const row = mk(list.content, 'Row', 0, i * 64, w, 64);
            if (i % 2 === 0) fillRR(gfx(row), 24, 4, w - 48, 56, 12, '#FFFFFF99');
            const rankColor = r.rank === 1 ? Theme.c.yellowDark : Theme.c.ink;
            const vals = [r.mode, r.players + '人', r.minutes + '分', '第' + r.rank + '名', String(r.finalAssets)];
            cols.forEach(([, x, cw], k) => text(row, vals[k], x, 0, cw, 64, Theme.font.sm, k === 3 ? rankColor : Theme.c.ink, { bold: k === 3 }));
        });
        list.setContentHeight(hist.length * 64);
        ghostButton(p, '关闭', 40, h - 104, w - 80, 80, () => this.close(), Theme.font.lg);
    }
}
