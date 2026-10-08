/**
 * 页面 6：结算（设计稿 03）：奖杯 + 蓝色绶带"本局结算"；排名面板右上角标签（限时结束 · N分钟）；"净资产排名"两侧金色桂叶；
 * 排名 / 玩家 / 净资产表头；前三名奖牌；底部"回原房间再来一局"、说明文字与房间号胶囊。并列按 1、1、3 编号，没有"胜负"列。
 */
import { Theme } from '../core/Theme';
import { primaryButton } from '../ui/Buttons';
import { art } from '../ui/Art';
import { ctx } from '../ui/Ctx';
import { drawClock, drawCoin, drawCopy } from '../ui/Icons';
import { col, fillCircle, fillRR, gfx, line, mk, onTap, text } from '../ui/Kit';
import { Toast } from '../ui/Toast';
import { copyText } from '../net/Wx';
import { Screen } from '../ui/Screen';
import { ScrollList } from '../ui/ScrollList';
import { avatar, roundedPanel } from '../ui/Widgets';

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
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');

        // 奖杯 + 绶带
        art(this.root, 'result_trophy', 245, 64, 230, 150);
        art(this.root, 'result_plaque', 140, 168, 440, 170);
        const title = text(this.root, '本局结算', 140, 204, 440, 90, 54, Theme.c.white, { bold: true });
        title.enableOutline = true;
        title.outlineColor = col('#1E4FA8');
        title.outlineWidth = 4;

        // 排名面板 + 右上角标签
        const card = roundedPanel(this.root, 32, 362, 656, 640, { fill: '#FFF8EA', r: 28 });
        const tab = roundedPanel(this.root, 452, 330, 226, 46, { fill: '#FFFFFF', r: 18, shadow: 2 });
        drawClock(gfx(mk(tab, 'Clock', 10, 7, 32, 32)), 16, 16, 12, Theme.c.navy);
        text(tab, tabText(res.reason, s.settings.timeLimitMinutes, s.settings.endMode), 44, 0, 176, 46, 20, Theme.c.navy, { bold: true, align: 'l' });
        text(card, '净资产排名', 0, 18, 656, 60, 40, '#7A4A12', { bold: true });
        art(card, 'result_laurel', 178, 14, 40, 66);
        const right = art(card, 'result_laurel', 438, 14, 40, 66);
        if (right) {
            // 右侧桂叶水平镜像（以左边为轴翻转，再右移一个宽度）
            right.setScale(-1, 1, 1);
            right.setPosition(right.position.x + 40, right.position.y, 0);
        }
        const head = mk(card, 'Head', 20, 92, 616, 48);
        fillRR(gfx(head), 0, 0, 616, 48, 14, '#F3E7CB');
        text(head, '排名', 10, 0, 90, 48, 22, Theme.c.noteGray, { bold: true });
        text(head, '玩家', 120, 0, 200, 48, 22, Theme.c.noteGray, { bold: true });
        text(head, '净资产', 400, 0, 200, 48, 22, Theme.c.noteGray, { bold: true });
        const list = new ScrollList(card, 0, 146, 656, 484);
        const rowH = 60;
        res.standings.forEach((r, i) => {
            const row = mk(list.content, 'Row' + i, 20, i * rowH, 616, rowH);
            const g = gfx(row);
            if (i > 0) line(g, 12, 0, 604, 0, '#EFE4CC', 2);
            if (r.rank <= 3 && !r.bankrupt) {
                fillCircle(g, 55, rowH / 2, 21, MEDAL[r.rank - 1]);
                fillCircle(g, 55, rowH / 2, 16, '#FFFFFF55');
                text(row, String(r.rank), 34, 0, 42, rowH, 26, Theme.c.navy, { bold: true });
            } else {
                text(row, String(r.rank), 34, 0, 42, rowH, 26, Theme.c.navy, { bold: true });
            }
            avatar(row, 120, 6, 48, r.avatar ?? 0, r.nickname ?? '?', { dim: r.bankrupt });
            text(row, r.nickname ?? '', 182, 0, 190, rowH, 24, Theme.c.navy, { bold: true, align: 'l' });
            drawCoin(gfx(mk(row, 'C', 400, 16, 28, 28)), 14, 14, 13);
            text(row, String(r.netWorth), 438, 0, 170, rowH, 28, Theme.c.navy, { bold: true, align: 'l' });
        });
        list.setContentHeight(res.standings.length * rowH);

        if (st.isWatcher()) {
            // 观战者：留在本页等房间开下一局（开局时自动进入观战），或返回大厅
            primaryButton(this.root, '返回大厅', 40, 1018, 640, 96, () => {
                void st.online!.unwatch();
                ctx.screens.go('lobby');
            }, Theme.font.lg);
            const note = '观战中：留在本页，房间开下一局时会自动进入观战';
            text(this.root, note, 0, 1124, Theme.W, 36, 22, '#00000088', { bold: true });
            text(this.root, note, 0, 1122, Theme.W, 36, 22, Theme.c.white, { bold: true });
            return;
        }
        // 再来一局（用户 2026-10-08）：一键回原房间并自动准备；下方显示已有几人准备
        const meReady = !!s.members.find((m) => m.playerId === st.myId)?.ready;
        primaryButton(this.root, meReady ? '已准备，回房间等待开局' : '再来一局', 40, 1018, 470, 96, () => {
            if (!st.online) for (const m of s.members) m.ready = m.playerId === st.myId;
            if (!meReady) st.setReady(st.myId, true);
            ctx.screens.go('room');
        }, Theme.font.lg);
        const back = mk(this.root, 'BackToRoom', 526, 1018, 154, 96);
        fillRR(gfx(back), 0, 0, 154, 96, 30, '#FFFFFFDD');
        text(back, '只回房间', 0, 0, 154, 96, 24, Theme.c.navy, { bold: true });
        onTap(back, () => ctx.screens.go('room'));
        const readyN = s.members.filter((m) => m.ready).length;
        const hint = st.online && readyN > 0 ? '已有 ' + readyN + '/' + s.members.length + ' 人准备再来一局，房间号和设置保留'
            : '房间号和设置保留，点「再来一局」自动准备';
        text(this.root, hint, 0, 1124, Theme.W, 36, 22, '#00000088', { bold: true });
        text(this.root, hint, 0, 1122, Theme.W, 36, 22, Theme.c.white, { bold: true });
        const room = roundedPanel(this.root, 190, 1178, 340, 56, { fill: '#FFFFFFEE', r: 28, shadow: 0 });
        text(room, '房间号：' + s.roomId, 0, 0, 276, 56, 24, Theme.c.navy, { bold: true });
        drawCopy(gfx(room), 300, 28, 28, Theme.c.navy);
        onTap(room, () => void copyText(s.roomId).then((ok) => Toast.show(ok ? '房间号已复制：' + s.roomId : '复制失败，房间号：' + s.roomId)));
    }
}

/** 面板右上角标签：结束方式。 */
function tabText(reason: string, minutes: number, mode: string): string {
    if (reason === 'ALL_AWAY') return '全员挂机 · 提前结束';
    if (mode === 'BANKRUPTCY') return '破产模式结束';
    return '限时结束 · ' + minutes + '分钟';
}

/** 服务端结束原因 → 中文（未知原因原样显示）。 */
function reasonText(reason: string): string {
    const map: Record<string, string> = {
        TIME_UP: '时间到，按净资产排名', NO_PLAYERS: '没有可继续的玩家',
        ALL_AWAY: '全员挂机或托管，本局提前结束',
    };
    return map[reason] ?? reason;
}
