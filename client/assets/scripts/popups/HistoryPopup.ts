/**
 * 我的战绩（设计稿 17 右）：标题"我的战绩"，本人角色 + 木牌"最近 20 局 · 仅本人"；
 * 表头 模式 / 人数 / 时长 / 排名 / 净资产，每局一张白色圆角卡：模式图标与名称（下面一行结束日期时间）、人数、时长、
 * 排名（第 1 名红色、其余蓝色）、金币 + 最终净资产。只看自己。
 * 联机时打开即向服务端读取（数据库里的真实战绩）；演示模式用本地演示数据（没有日期）。
 */
import { Node } from 'cc';
import { HistoryEntry } from '../core/Models';
import { Theme } from '../core/Theme';
import { art, informationCharacterKey } from '../ui/Art';
import { drawCoin } from '../ui/Icons';
import { fillCircle, fillRR, gfx, line, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';
import { ctx } from '../ui/Ctx';

const W = 680;
const H = 1180;
const ROW_H = 112;
/** 列：模式（含图标）/ 人数 / 时长 / 排名 / 净资产（x、宽） */
const COLS: [string, number, number][] = [['模式', 24, 170], ['人数', 194, 80], ['时长', 274, 96], ['排名', 370, 110], ['净资产', 480, 150]];

function pad(n: number): string {
    return (n < 10 ? '0' : '') + n;
}

function dateText(ms: number): string {
    const d = new Date(ms);
    return d.getFullYear() + '/' + pad(d.getMonth() + 1) + '/' + pad(d.getDate()) + '  ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

export class HistoryPopup extends Popup {
    private state: 'loading' | 'ready' | 'failed' = 'ready';

    constructor() {
        super('history', '我的战绩', W, H, 0, true);
        this.dimBackground = true; // 设计稿 17 是整页：压暗底层，不露出棋盘 / 大厅
        const online = ctx.store.online;
        if (online) {
            this.state = 'loading';
            void online.loadHistory().then((ok) => {
                this.state = ok ? 'ready' : 'failed';
                if (!this.closed && this.body && this.body.isValid) this.rebuildBody();
            });
        }
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const close = mk(p, 'Close', 20, 16, 60, 60);
        fillCircle(gfx(close), 30, 30, 28, Theme.c.white);
        text(close, '‹', 0, -4, 60, 60, 52, Theme.c.navy, { bold: true });
        onTap(close, () => this.close(), false);

        // 本人角色 + 木牌
        // 大厅里打开时还没有对局：只在对局中取对局里的头像，否则用资料里的头像
        const st = ctx.store;
        const me = st.online && st.session.game ? st.player(st.myId) : undefined;
        art(p, informationCharacterKey(me ? me.avatar : st.profile.avatar), 24, 76, 150, 170);
        const sign = mk(p, 'Sign', 170, 132, w - 200, 86);
        const sg = gfx(sign);
        fillRR(sg, 0, 6, w - 200, 80, 18, '#7A4E25');
        fillRR(sg, 0, 0, w - 200, 80, 18, '#C98F4E');
        strokeRR(sg, 4, 4, w - 208, 72, 14, '#E8B977', 3);
        text(sign, '最近 20 局 · 仅本人', 0, 0, w - 200, 80, 36, '#4A2A0E', { bold: true });

        const head = mk(p, 'Head', 20, 252, w - 40, 60);
        fillRR(gfx(head), 0, 0, w - 40, 60, 16, Theme.c.boxBeige);
        for (const [t, x, cw] of COLS) text(head, t, x - 20, 0, cw, 60, Theme.font.sm, Theme.c.noteGray, { bold: true });

        const list = new ScrollList(p, 0, 324, w, h - 324 - 24);
        const hist = this.state === 'ready' ? ctx.store.history : [];
        hist.forEach((r, i) => this.row(list.content, r, i * ROW_H, w));
        list.setContentHeight(hist.length * ROW_H + 8);
        const empty = this.state === 'loading' ? '加载中…' : this.state === 'failed' ? '战绩加载失败，请稍后再试'
            : hist.length === 0 ? '还没有战绩，打完一局就会出现在这里' : '';
        if (empty) text(p, empty, 0, 420, w, 60, Theme.font.md, Theme.c.noteGray);
    }

    private row(parent: Node, r: HistoryEntry, y: number, w: number): void {
        const n = mk(parent, 'Row', 20, y + 6, w - 40, ROW_H - 12);
        const g = gfx(n);
        const rh = ROW_H - 12;
        fillRR(g, 0, 3, w - 40, rh, 18, '#00000014');
        fillRR(g, 0, 0, w - 40, rh, 18, Theme.c.white);
        const timed = r.mode.indexOf('限时') >= 0;
        const top = r.endedAt ? 12 : 0;
        const midH = r.endedAt ? 52 : rh;
        // 模式图标：限时为闹钟，破产为深色圆底"破"
        if (!timed || !art(n, 'icon_clock_normal', 14, top + 8, 36, 36)) {
            const ig = gfx(mk(n, 'ModeIcon', 14, top + 8, 36, 36));
            fillCircle(ig, 18, 18, 18, timed ? Theme.c.blue : '#3A4250');
            text(ig.node, timed ? '时' : '破', 0, 0, 36, 36, 20, Theme.c.white, { bold: true });
        }
        text(n, timed ? '限时' : '破产', 56, top, 110, midH, 28, Theme.c.navy, { bold: true, align: 'l' });
        if (r.endedAt) text(n, dateText(r.endedAt), 14, 60, 260, 32, 20, Theme.c.noteGray, { align: 'l' });
        text(n, r.players + '人', COLS[1][1] - 20, top, COLS[1][2], midH, 26, Theme.c.navy);
        text(n, r.minutes + '分', COLS[2][1] - 20, top, COLS[2][2], midH, 26, Theme.c.navy);
        const rankColor = r.rank === 1 ? Theme.c.payRed : '#2B79C4';
        text(n, r.rank > 0 ? '第' + r.rank + '名' : '—', COLS[3][1] - 20, top, COLS[3][2], midH, 28, rankColor, { bold: true });
        const cx = COLS[4][1] - 20 + 6;
        drawCoin(gfx(mk(n, 'Coin', cx, top + (midH - 30) / 2, 30, 30)), 15, 15, 15);
        text(n, String(r.finalAssets), cx + 36, top, COLS[4][2] - 50, midH, 28, Theme.c.navy, { bold: true, align: 'l' });
        const ch = gfx(mk(n, 'Chevron', w - 40 - 34, rh / 2 - 12, 20, 24));
        line(ch, 4, 2, 14, 12, '#9AA6B6', 4);
        line(ch, 14, 12, 4, 22, '#9AA6B6', 4);
    }
}
