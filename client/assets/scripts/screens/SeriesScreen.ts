/** Approved UI33: series setup, between-game points and final joint champions. */
import { Node } from 'cc';
import { GLOBAL_RULES } from '../core/Rules';
import { Theme } from '../core/Theme';
import { ROBOT_AVATAR, SeriesState } from '../core/Models';
import { seriesRows } from '../core/Series';
import { art, informationCharacterKey } from '../ui/Art';
import { primaryButton, IconButton } from '../ui/Buttons';
import { drawBack } from '../ui/Icons';
import { avatar, roundedPanel, Segmented } from '../ui/Widgets';
import { ctx } from '../ui/Ctx';
import { fillCircle, gfx, line, mk, onTap, text } from '../ui/Kit';
import { ScrollList } from '../ui/ScrollList';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { ConfirmPopup } from '../popups/ConfirmPopup';

function background(root: Node): void { art(root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch'); }
function plaque(root: Node, title: string, trophy: boolean): void {
    if (trophy) art(root, 'result_trophy', 248, 126, 224, 172);
    art(root, 'result_plaque', 92, trophy ? 248 : 140, 536, 150);
    text(root, title, 98, trophy ? 282 : 174, 524, 80, 52, '#FFF7CA', { bold: true });
}
function roomTag(root: Node, roomId = ctx.store.session.roomId): void {
    const p = roundedPanel(root, 210, 34, 360, 58, { r: 29, fill: '#FFFFFFCE', shadow: 0 });
    text(p, '房间号 ' + roomId, 0, 0, 360, 58, 30, Theme.c.navy, { bold: true });
}

export class SeriesScreen extends Screen {
    private scoreMode = false;
    readonly id = 'series' as const;
    readonly title = '好友系列赛';
    protected build(): void {
        const st = ctx.store, s = st.session, z = s.series;
        background(this.root);
        this.header('', () => ctx.screens.go('room'));
        roomTag(this.root);
        if (z && (z.roster.length > 0 || this.scoreMode)) { this.scoreMode = true; renderSeriesScore(this.root, z); return; }
        plaque(this.root, '好友系列赛', false);
        art(this.root, 'lobby_friends', 36, 270, 648, 306);
        const seats = roundedPanel(this.root, 24, 576, 672, 252, { fill: '#FFFEF5', r: 26 });
        text(seats, '参赛玩家（' + s.members.length + '人）', 22, 6, 628, 40, 30, Theme.c.navy, { bold: true, align: 'l' });
        const list = new ScrollList(seats, 8, 50, 656, 192);
        s.members.forEach((m, i) => {
            const x = i % 4 * 162, y = Math.floor(i / 4) * 180;
            const c = roundedPanel(list.content, x + 8, y + 4, 142, 110, { fill: Theme.avatarColors[m.avatar % 8] + '55', r: 20, shadow: 0 });
            if (m.avatar === ROBOT_AVATAR || !art(c, informationCharacterKey(m.avatar), 10, 0, 122, 108)) avatar(c, 21, 7, 96, m.avatar, m.nickname);
            text(list.content, m.nickname, x, y + 116, 162, 32, 25, Theme.c.navy, { bold: true });
            const ready = roundedPanel(list.content, x + 16, y + 151, 130, 28, { fill: m.ready ? '#CEFACD' : '#E7EAF0', r: 14, shadow: 0 });
            text(ready, m.ready ? '✓ 已准备' : '◷ 未准备', 0, 0, 130, 28, 22, m.ready ? '#148437' : Theme.c.noteGray, { bold: true });
        });
        list.setContentHeight(Math.ceil(s.members.length / 4) * 180);
        const r = z?.status === 'SETUP' ? z.rules : GLOBAL_RULES.series;
        const panel = roundedPanel(this.root, 24, 840, 672, 272, { fill: '#FFF9EB', r: 26 });
        text(panel, '系列规则设置', 24, 2, 624, 42, 30, Theme.c.navy, { bold: true, align: 'l' });
        if (!r?.enabled) {
            text(panel, '本房间未开放系列赛，请在后台发布启用版本后新建房间', 24, 52, 624, 124, 27, Theme.c.inkSoft);
            primaryButton(this.root, '返回房间', 40, 1130, 640, 96, () => ctx.screens.go('room')); return;
        }
        const isHost = s.hostId === st.myId;
        text(panel, '系列局数', 24, 50, 124, 44, 28, Theme.c.navy, { bold: true, align: 'l' });
        const selected = z?.status === 'SETUP' ? z.totalGames : 0;
        const options = new Segmented(panel, 160, 50, 488, 44, r.gameOptions.map(n => ({ label: n + '局', value: n })), selected,
            n => { if (st.online) void st.online.configureSeries(Number(n)); else Toast.show('系列赛计分需要联机，预览不结算'); }, 8, 26);
        options.locked = !isHost; options.lockHint = '仅房主可选择局数';
        text(panel, '名次积分', 24, 102, 124, 44, 28, Theme.c.navy, { bold: true, align: 'l' });
        const scoreList = new ScrollList(panel, 160, 98, 488, 58);
        for (let i = 0; i < 8; i++) {
            const c = roundedPanel(scoreList.content, i % 4 * 122, Math.floor(i / 4) * 58, 112, 54, { fill: ['#FFE58A', '#CCE4F8', '#F7D3B8', '#E8E9ED'][i % 4], r: 14, shadow: 0 });
            text(c, '第' + (i + 1) + '名', 0, 0, 112, 23, 20, Theme.c.navy, { bold: true });
            text(c, String(r.rankPoints[i]), 0, 23, 112, 30, 30, Theme.c.navy, { bold: true });
        }
        scoreList.setContentHeight(116);
        text(panel, '首局开赛后成员固定', 24, 164, 624, 36, 27, Theme.c.navy, { bold: true, align: 'l' });
        text(panel, '每局资产与手牌重置 · 系列积分保留', 24, 199, 624, 34, 24, Theme.c.inkSoft, { align: 'l' });
        text(panel, r.tieMode === 'SHARED_RANK' ? '单局并列同分 · 累计同分并列冠军' : '单局平分占用名次积分 · 累计同分并列冠军', 24, 233, 624, 32, 22, Theme.c.inkSoft, { align: 'l' });
        const me = s.members.find(m => m.playerId === st.myId);
        const button = primaryButton(this.root, !me?.ready ? '准备' : isHost ? '开始系列赛' : '已准备，等待开局', 40, 1130, 640, 96, () => {
            if (!me?.ready) st.setReady(st.myId, true); else if (isHost) void st.online?.start();
        }, 42);
        button.setEnabled(!!selected && (!me?.ready || isHost && st.allReady() && s.members.length >= r.minPlayers), !selected ? '房主先选择系列局数' : '达到最少人数并全员准备后开赛');
        text(this.root, '房主选择，所有玩家开赛前可查看规则', 24, 1230, 672, 32, 23, Theme.c.navy);
    }
}

/** Also renders in the normal result route, so reconnect and duplicate pushes do not select a wrong screen. */
export function renderSeriesScore(root: Node, z: SeriesState, history?: { roomId: string; close: () => void }): void {
    const st = ctx.store, final = z.status === 'FINISHED' || z.status === 'ENDED', rows = seriesRows(z);
    background(root);
    const back = () => { if (history) history.close(); else if (st.isWatcher()) { void st.online?.unwatch(); ctx.screens.go('lobby'); } else ctx.screens.go('room'); };
    new IconButton(root, 80, 24, 60, '', back, Theme.c.ivory, Theme.c.navy,
        (g, s) => drawBack(g, s / 2, s / 2, s * 0.6, Theme.c.navy));
    roomTag(root, history?.roomId);
    plaque(root, final ? z.status === 'ENDED' ? '系列赛已结束' : '系列冠军' : '系列积分榜', true);
    let cardY = 484;
    if (final && rows.length && z.games.length) {
        const winners = rows.filter(r => r.rank === 1);
        if (winners.length === 1) {
            const p = winners[0];
            art(root, 'result_laurel', 174, 400, 72, 224);
            const right = art(root, 'result_laurel', 474, 400, 72, 224);
            if (right) { right.setScale(-1, 1, 1); right.setPosition(right.position.x + 72, right.position.y, 0); }
            if (p.avatar === ROBOT_AVATAR || !art(root, informationCharacterKey(p.avatar), 224, 400, 272, 206)) avatar(root, 268, 410, 180, p.avatar, p.nickname);
        } else winners.forEach((p, i) => {
            const size = winners.length <= 4 ? 132 : 78;
            const count = Math.min(4, winners.length - Math.floor(i / 4) * 4);
            avatar(root, (720 - ((count - 1) * 156 + size)) / 2 + i % 4 * 156,
                420 + Math.floor(i / 4) * 90, size, p.avatar, p.nickname);
        });
        const p = roundedPanel(root, 104, 610, 512, 62, { fill: '#FFF5C8', r: 31 });
        text(p, (winners.length > 1 ? winners.length + '位并列冠军' : winners[0].nickname) + ' · ' + winners[0].points + '分', 0, 0, 512, 62, 36, Theme.c.navy, { bold: true });
        cardY = 686;
    } else {
        const tag = roundedPanel(root, 170, 408, 380, 62, { fill: '#D4EAFF', r: 31 });
        text(tag, '第' + z.games.length + '/' + z.totalGames + '局已完成', 0, 0, 380, 62, 34, Theme.c.navy, { bold: true });
    }
    const h = (final ? 1120 : 1056) - cardY;
    const card = roundedPanel(root, 24, cardY, 672, h, { fill: '#FFF9ED', r: 28 });
    const heads = final ? [['排名', 10, 90], ['玩家', 146, 250], ['总积分', 460, 170]] : [['排名', 10, 90], ['玩家', 146, 220], ['累计积分', 374, 140], ['本局增加', 520, 136]];
    heads.forEach(([label, x, w]) => text(card, String(label), Number(x), 8, Number(w), 44, 25, Theme.c.navy, { bold: true }));
    const list = new ScrollList(card, 10, 58, 652, h - 72);
    rows.forEach((p, i) => {
        const row = mk(list.content, 'SeriesRow:' + p.playerId, 8, i * 88, 636, 88), g = gfx(row);
        if (i) line(g, 12, 0, 622, 0, '#EBDDCA', 2);
        fillCircle(g, 48, 43, 28, p.rank <= 3 ? ['#F8C94D', '#CBD3DE', '#E19B63'][p.rank - 1] : '#DDE1E9');
        text(row, String(p.rank), 16, 0, 64, 86, 38, Theme.c.navy, { bold: true });
        avatar(row, 100, 9, 68, p.avatar, p.nickname);
        text(row, p.nickname, 184, 0, final ? 244 : 170, 58, 30, Theme.c.navy, { bold: true, align: 'l' });
        if (p.withdrawn) text(row, '已退出 · 保留积分', 184, 54, 224, 28, 19, Theme.c.inkSoft, { align: 'l' });
        text(row, String(p.points), final ? 462 : 360, 0, final ? 160 : 134, 86, 38, Theme.c.navy, { bold: true });
        if (!final) {
            const add = roundedPanel(row, 512, 20, 112, 48, { fill: '#D6ECFF', r: 18, shadow: 0 });
            text(add, p.added === null ? '—' : '+' + p.added, 0, 0, 112, 48, 32, '#155BD6', { bold: true });
        }
    });
    list.setContentHeight(rows.length * 88);
    if (!final) {
        const hint = roundedPanel(root, 24, 1066, 672, 62, { fill: '#D5EDFF', r: 22 });
        text(hint, '累计积分相同即并列，不额外比较净资产', 16, 0, 640, 62, 26, Theme.c.navy, { bold: true });
    }
    primaryButton(root, history ? '返回对局详情' : st.isWatcher() ? '返回大厅' : final ? '返回房间' : '回房准备第' + (z.games.length + 1) + '局', 40, 1140, 640, 92, () => {
        if (history) return history.close();
        if (st.isWatcher()) { void st.online?.unwatch(); ctx.screens.go('lobby'); } else ctx.screens.go('room');
    }, 40);
    text(root, history ? '已保存的系列榜单 · 只读' : st.isWatcher() ? '留在本页，下一局开始时自动进入观战' : final ? '累计积分决定系列名次，称号不额外发钱' : '下一局重置资产，系列积分保留', 16, 1236, 688, 32, 23, Theme.c.navy);
    if (!history && !final && st.session.hostId === st.myId) {
        const end = text(root, '结束系列', 514, 94, 170, 38, 22, Theme.c.navy, { bold: true });
        onTap(end.node, () => ctx.popups.open(new ConfirmPopup({ title: '结束系列赛', message: '保留已完成的' + z.games.length + '局积分并结束本系列？', confirmText: '结束系列', onConfirm: () => void st.online?.endSeries(z.seriesNo) }, 'end-series')));
    }
}
