/** 页面 6：结算。净资产排名（并列按 1、1、3 编号），没有"胜负"列；破产标记；可回原房间再来一局。 */
import { Theme } from '../core/Theme';
import { ghostButton, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCoin, drawCopy, drawTrophy } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, text } from '../ui/Kit';
import { Toast } from '../ui/Toast';
import { Screen } from '../ui/Screen';
import { ScrollList } from '../ui/ScrollList';
import { avatar, chip, roundedPanel } from '../ui/Widgets';

const MEDAL = ['#F2B82E', '#B8C2CC', '#D98A4B'];

export class ResultScreen extends Screen {
    readonly id = 'result' as const;
    readonly title = '结算';

    protected build(): void {
        const st = ctx.store;
        const res = st.online ? st.session.lastResult : st.buildResult();
        if (!res) {
            text(this.root, '暂无结算结果', 0, 560, Theme.W, 80, Theme.font.lg, Theme.c.inkSoft);
            return;
        }
        const s = st.session;
        this.backdrop('sky');
        const hd = mk(this.root, 'Header', 150, 24, 360, 60);
        text(hd, '本局结算', 0, 0, 360, 60, Theme.font.lg, Theme.c.ink, { bold: true });

        const top = mk(this.root, 'SettlementBanner', 120, 100, 480, 150);
        drawTrophy(gfx(top), 240, 50, 112);
        fillRR(gfx(top), 0, 72, 480, 76, 24, Theme.c.blueDark);
        fillRR(gfx(top), 4, 68, 472, 76, 24, Theme.c.blue);
        text(top, '本局结算', 0, 66, 480, 80, Theme.font.xl, '#FFF3C4', { bold: true });

        // 排名表（无"胜负"列）
        const card = roundedPanel(this.root, 24, 262, 672, 660);
        text(card, '净资产排名', 0, 8, 672, 42, Theme.font.lg, Theme.c.yellowText, { bold: true });
        const cols: [string, number, number][] = [['排名', 12, 90], ['玩家', 110, 280], ['净资产', 400, 240]];
        const head = mk(card, 'Head', 0, 58, 672, 50);
        fillRR(gfx(head), 12, 0, 648, 46, 12, Theme.c.ivoryDark);
        for (const [t, x, w] of cols) text(head, t, x + 10, 0, w, 46, Theme.font.sm, Theme.c.inkSoft, { bold: true });
        const list = new ScrollList(card, 0, 114, 672, 536);
        const rowH = 66;
        res.standings.forEach((r, i) => {
            const row = mk(list.content, 'Row' + i, 0, i * rowH, 672, rowH);
            const isMe = r.playerId === st.myId;
            const g = gfx(row);
            fillRR(g, 12, 4, 648, rowH - 8, 16, isMe ? '#FFF1C9' : '#FFFFFFAA');
            const medal = r.rank <= 3 && !r.bankrupt ? MEDAL[r.rank - 1] : Theme.c.grayDark;
            fillCircle(g, 56, rowH / 2, 24, medal);
            text(row, String(r.rank), 30, 0, 52, rowH, Theme.font.lg, Theme.c.white, { bold: true });
            avatar(row, 110, 4, 56, r.avatar ?? 0, r.nickname ?? '?', { dim: r.bankrupt });
            text(row, (r.nickname ?? '') + (isMe ? '（我）' : ''), 180, r.bankrupt ? 4 : 0, 160, r.bankrupt ? 40 : rowH, Theme.font.sm, Theme.c.ink, { bold: true, align: 'l' });
            if (r.bankrupt) chip(row, 180, 42, '已破产', Theme.c.redSoft, Theme.c.redDark, 16, 26);
            const coin = (x: number) => drawCoin(gfx(mk(row, 'C', x, 22, 32, 32)), 16, 16, 13);
            coin(420);
            text(row, String(r.netWorth), 458, 0, 180, rowH, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
        });
        list.setContentHeight(res.standings.length * rowH);
        text(this.root, res.reason + ' · 并列按 1、1、3 编号', 24, 926, 672, 30, Theme.font.xs, Theme.c.ink);

        // 再来一局
        primaryButton(this.root, '回原房间再来一局', 24, 960, 672, 104, () => {
            for (const m of s.members) m.ready = false;
            ctx.screens.go('room');
        }, Theme.font.lg);
        const room = roundedPanel(this.root, 160, 1084, 400, 54, { shadow: 0 });
        text(room, '房间号 ' + s.roomId, 0, 0, 324, 54, Theme.font.md, Theme.c.ink, { bold: true });
        drawCopy(gfx(room), 356, 27, 30, Theme.c.ink);
        onTap(room, () => Toast.show('房间号：' + s.roomId + '（演示）'));
        ghostButton(this.root, '返回大厅', 24, 1160, 672, 84, () => ctx.screens.go('lobby'), Theme.font.md);
    }
}
