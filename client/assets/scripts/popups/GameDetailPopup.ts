/**
 * 战绩详情（用户 2026-10-09）：在"我的战绩"里点一局打开。上面是本局概况（模式、地图、人数、时长、结束原因、初始现金），
 * 中间是全部玩家按名次的结算（净资产、现金、是否破产 / 认输，自己一行高亮），下面是本局的对局记录（按回合，与棋盘页「记录」同样的写法，
 * 只含公开事件，所以别人抽到的道具种类看不到）。数据来自 GET /api/me/games/{roomId}/{gameNo}。
 */
import { Node } from 'cc';
import { boardNames } from '../core/BoardNames';
import { Theme } from '../core/Theme';
import { appendLog, LogLine } from '../net/GameLog';
import { SGameDetail } from '../net/Protocol';
import { defaultAvatar } from '../net/ViewAdapter';
import { ctx } from '../ui/Ctx';
import { drawCoin } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';
import { avatar } from '../ui/Widgets';

const W = 680;
const H = 1180;
const PLAYER_H = 96;
const HEADER_H = 46;
const LOG_H = 62;
/** 详情里最多列出的记录条数（太长的对局只留最后这些，免得节点过多卡顿） */
const DETAIL_LIMIT = 800;

const REASONS: Record<string, string> = {
    TIME_UP: '时间到，按净资产排名',
    LAST_SURVIVOR: '只剩一名玩家',
    ALL_ELIMINATED: '所有玩家都已出局',
    NO_PLAYERS: '玩家都离开了',
};

function pad(n: number): string {
    return (n < 10 ? '0' : '') + n;
}

function dateText(ms: number): string {
    const d = new Date(ms);
    return d.getFullYear() + '/' + pad(d.getMonth() + 1) + '/' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

export class GameDetailPopup extends Popup {
    private detail: SGameDetail | null = null;
    private error = '';

    constructor(private readonly roomId: number, private readonly gameNo: number) {
        super('game-detail', '对局详情', W, H, 0, true);
        this.dimBackground = true;
        const online = ctx.store.online;
        if (!online) {
            this.error = '演示模式没有对局详情';
            return;
        }
        void online.gameDetail(roomId, gameNo).then((r) => {
            if (typeof r === 'string') this.error = r;
            else this.detail = r;
            if (!this.closed && this.body && this.body.isValid) this.rebuildBody();
        });
    }

    protected buildTitle(): void {
        text(this.panel, this.title, 80, 20, this.pw - 160, 64, 38, Theme.c.navy, { bold: true });
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const back = mk(p, 'Back', 20, 16, 60, 60);
        fillCircle(gfx(back), 30, 30, 28, Theme.c.white);
        text(back, '‹', 0, -4, 60, 60, 52, Theme.c.navy, { bold: true });
        onTap(back, () => this.close(), false);
        const d = this.detail;
        if (!d) {
            text(p, this.error ? '加载失败：' + this.error : '加载中…', 0, 420, w, 60, Theme.font.md, Theme.c.noteGray);
            return;
        }
        const list = new ScrollList(p, 24, 96, w - 48, h - 120);
        const lw = list.w;
        const c = list.content;
        let y = 0;

        // 概况
        const timed = d.endMode === 'TIME_LIMIT';
        const size = d.boardId === 'classic-30' ? 30 : 50;
        const minutes = Math.max(1, Math.round((d.endedAt - d.startedAt) / 60000));
        const info = mk(c, 'Info', 0, y, lw, 150);
        fillRR(gfx(info), 0, 0, lw, 150, 18, Theme.c.boxBeige);
        text(info, (timed ? '限时 ' + (d.timeLimitMinutes ?? 0) + ' 分钟' : '破产模式') + ' · ' + size + ' 格 · ' + d.playerCount + ' 人',
            20, 10, lw - 40, 44, 28, Theme.c.navy, { bold: true, align: 'l' });
        text(info, dateText(d.startedAt) + ' 开始 · 用时 ' + minutes + ' 分钟', 20, 56, lw - 40, 36, 22, Theme.c.inkSoft, { align: 'l' });
        text(info, '结束：' + (REASONS[d.endReason] ?? d.endReason) + ' · 初始现金 ' + d.initialCash, 20, 98, lw - 40, 36, 22,
            Theme.c.inkSoft, { align: 'l' });
        y += 166;

        // 结算
        y = this.section(c, '最终排名', y, lw);
        for (const pl of d.players) {
            const n = mk(c, 'Player', 0, y, lw, PLAYER_H - 10);
            const g = gfx(n);
            fillRR(g, 0, 0, lw, PLAYER_H - 10, 16, pl.me ? '#FFF6DA' : Theme.c.white);
            const out = pl.life === 'BANKRUPT' || pl.life === 'SURRENDERED';
            text(n, pl.rank ? '第' + pl.rank + '名' : '—', 10, 0, 92, PLAYER_H - 10, 26, pl.rank === 1 ? Theme.c.payRed : '#2B79C4', { bold: true });
            avatar(n, 104, 10, 66, pl.avatar >= 0 && pl.avatar < 8 ? pl.avatar : defaultAvatar(pl.playerId), pl.nickname, { dim: out });
            text(n, pl.nickname + (pl.me ? '（我）' : ''), 184, 6, 230, 42, 26, Theme.c.navy, { bold: true, align: 'l' });
            text(n, out ? (pl.life === 'SURRENDERED' ? '认输' : '破产') : '现金 ' + (pl.cash ?? 0), 184, 46, 230, 34, 21,
                out ? Theme.c.payRed : Theme.c.noteGray, { align: 'l' });
            drawCoin(gfx(mk(n, 'Coin', lw - 190, (PLAYER_H - 10) / 2 - 15, 30, 30)), 15, 15, 15);
            text(n, String(pl.netWorth ?? 0), lw - 152, 0, 140, PLAYER_H - 10, 28, Theme.c.navy, { bold: true, align: 'l' });
            y += PLAYER_H;
        }
        if (d.players.length < d.playerCount) {
            text(c, '另有 ' + (d.playerCount - d.players.length) + ' 名玩家已注销，不显示', 0, y, lw, 36, 20, Theme.c.noteGray);
            y += 40;
        }

        // 对局记录
        y = this.section(c, '对局记录', y + 10, lw);
        const log = this.lines(d);
        if (!log) {
            text(c, '这局没有保存对局记录', 0, y, lw, 50, Theme.font.sm, Theme.c.noteGray);
            y += 60;
        }
        if (log && log.length >= DETAIL_LIMIT) {
            text(c, '对局较长，只显示最后 ' + DETAIL_LIMIT + ' 条', 0, y, lw, 36, 20, Theme.c.noteGray);
            y += 40;
        }
        for (const l of log ?? []) {
            if (l.header) {
                const n = mk(c, 'LogHeader', 0, y + 6, lw, HEADER_H - 10);
                fillRR(gfx(n), 0, 0, lw, HEADER_H - 10, 12, Theme.c.blueSoft);
                text(n, l.text, 16, 0, lw - 32, HEADER_H - 10, 22, Theme.c.blueDark, { bold: true, align: 'l' });
                y += HEADER_H;
                continue;
            }
            const n = mk(c, 'LogLine', 0, y, lw, LOG_H);
            if (l.mine) fillRR(gfx(n), 0, 4, lw, LOG_H - 8, 10, '#FFF6DA');
            text(n, l.text, 16, 2, lw - 32, LOG_H - 4, 22, l.mine ? Theme.c.ink : Theme.c.inkSoft,
                { bold: !!l.mine, align: 'l', wrap: true, lineHeight: 28 });
            y += LOG_H;
        }
        list.setContentHeight(y + 16);
    }

    private section(c: Node, title: string, y: number, lw: number): number {
        text(c, title, 4, y, lw - 8, 48, 28, Theme.c.navy, { bold: true, align: 'l' });
        return y + 54;
    }

    /** 把本局事件翻成记录（按发生顺序；单局不截断）。 */
    private lines(d: SGameDetail): LogLine[] | null {
        if (!d.events) return null;
        const me = d.players.find((p) => p.me)?.playerId ?? '';
        const names = boardNames(d.boardId === 'classic-30' ? 30 : 50);
        const out: LogLine[] = [];
        appendLog(out, d.events, {
            myId: me,
            name: (id) => (String(id) === me ? '你' : d.players.find((p) => p.playerId === String(id))?.nickname ?? '玩家'),
            tile: (i) => names[Number(i)] ?? '',
        }, DETAIL_LIMIT);
        return out;
    }
}
