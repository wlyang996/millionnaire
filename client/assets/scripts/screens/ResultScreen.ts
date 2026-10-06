/** 页面 6：结算。净资产排名（并列按 1、1、3 编号），没有"胜负"列；破产标记；可回原房间再来一局。 */
import { ME } from '../core/MockStore';
import { Theme } from '../core/Theme';
import { ghostButton, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCoin } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, text } from '../ui/Kit';
import { Screen } from '../ui/Screen';
import { ScrollList } from '../ui/ScrollList';
import { avatar, chip, roundedPanel } from '../ui/Widgets';

const MEDAL = ['#F2B82E', '#B8C2CC', '#D98A4B'];

export class ResultScreen extends Screen {
    readonly id = 'result' as const;
    readonly title = '结算';

    protected build(): void {
        const st = ctx.store;
        const res = st.buildResult();
        const s = st.session;
        this.backdrop('sky');
        const hd = mk(this.root, 'Header', 150, 24, 360, 60);
        text(hd, '对局结算', 0, 0, 360, 60, Theme.font.lg, Theme.c.ink, { bold: true });

        const mine = res.standings.find((x) => x.playerId === ME);
        const top = roundedPanel(this.root, 24, 100, 672, 150, { r: 28 });
        text(top, res.reason + ' · 按净资产排名', 28, 14, 616, 40, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        text(top, mine ? '你的名次：第 ' + mine.rank + ' 名' : '本局已结束', 28, 52, 616, 70, Theme.font.xl, Theme.c.ink, { bold: true, align: 'l' });
        if (mine) chip(top, 480, 64, '净资产 ' + mine.netWorth, Theme.c.yellow, Theme.c.yellowText, Theme.font.sm);

        // 排名表（无"胜负"列）
        const card = roundedPanel(this.root, 24, 262, 672, 660);
        const cols: [string, number, number][] = [['名次', 12, 90], ['玩家', 110, 230], ['净资产', 350, 150], ['现金', 500, 150]];
        const head = mk(card, 'Head', 0, 10, 672, 50);
        fillRR(gfx(head), 12, 0, 648, 46, 12, Theme.c.ivoryDark);
        for (const [t, x, w] of cols) text(head, t, x + 10, 0, w, 46, Theme.font.sm, Theme.c.inkSoft, { bold: true });
        const list = new ScrollList(card, 0, 68, 672, 580);
        const rowH = 76;
        res.standings.forEach((r, i) => {
            const row = mk(list.content, 'Row' + i, 0, i * rowH, 672, rowH);
            const isMe = r.playerId === ME;
            const g = gfx(row);
            fillRR(g, 12, 4, 648, rowH - 8, 16, isMe ? '#FFF1C9' : '#FFFFFFAA');
            const medal = r.rank <= 3 && !r.bankrupt ? MEDAL[r.rank - 1] : Theme.c.grayDark;
            fillCircle(g, 56, rowH / 2, 24, medal);
            text(row, String(r.rank), 30, 0, 52, rowH, Theme.font.lg, Theme.c.white, { bold: true });
            avatar(row, 110, 8, 60, r.avatar ?? 0, r.nickname ?? '?', { dim: r.bankrupt });
            text(row, (r.nickname ?? '') + (isMe ? '（我）' : ''), 180, r.bankrupt ? 4 : 0, 160, r.bankrupt ? 40 : rowH, Theme.font.sm, Theme.c.ink, { bold: true, align: 'l' });
            if (r.bankrupt) chip(row, 180, 42, '已破产', Theme.c.redSoft, Theme.c.redDark, 16, 26);
            const coin = (x: number) => drawCoin(gfx(mk(row, 'C', x, 22, 32, 32)), 16, 16, 13);
            coin(352);
            text(row, String(r.netWorth), 390, 0, 110, rowH, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
            coin(500);
            text(row, String(r.cash), 538, 0, 110, rowH, Theme.font.md, Theme.c.ink, { align: 'l' });
        });
        list.setContentHeight(res.standings.length * rowH);
        text(this.root, '并列按 1、1、3 编号；破产者排在存活者之后，越晚出局越靠前', 24, 930, 672, 28, Theme.font.xs, Theme.c.ink);

        // 再来一局
        primaryButton(this.root, '回原房间再来一局', 24, 960, 672, 104, () => {
            for (const m of s.members) m.ready = false;
            ctx.screens.go('room');
        }, Theme.font.lg);
        text(this.root, '房间号 ' + s.roomId + ' 与设置保留，全员需重新准备；新局重置资金、卡牌、地产及行动顺序', 40, 1076, 640, 60, Theme.font.xs, Theme.c.ink, { wrap: true, lineHeight: 28 });
        ghostButton(this.root, '返回大厅', 24, 1160, 672, 84, () => ctx.screens.go('lobby'), Theme.font.md);
    }
}
