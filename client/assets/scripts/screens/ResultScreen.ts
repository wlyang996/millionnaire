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
import { titleMetric } from './board/GlobalNoticeView';
import { renderSeriesScore } from './SeriesScreen';
import { previewName } from '../net/Config';
import { GameResult, ROBOT_AVATAR } from '../core/Models';
import { teamColor, teamName } from '../core/Teams';
import { informationCard } from '../popups/InformationPage';
import { informationCharacterKey } from '../ui/Art';

const MEDAL = ['#F2B82E', '#B8C2CC', '#D98A4B'];

export class ResultScreen extends Screen {
    private showingSeries = /^series-(points|final)/.test(previewName() ?? '');
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
        const series = s.series;
        const seriesResult = !!series && !!series.games.length && series.games[series.games.length - 1].gameNo === res.gameNo;
        if (seriesResult && this.showingSeries) {
            renderSeriesScore(this.root, series);
            return;
        }
        this.backdrop('sky');
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        if(res.teamStandings?.length){renderTeamResult(this.root,res);return;}

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
        const rowH = res.titles?.length ? 48 : 60;
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
            avatar(row, 120, 4, rowH - 8, r.avatar ?? 0, r.nickname ?? '?', { dim: r.bankrupt });
            text(row, r.nickname ?? '', 182, 0, 190, rowH, 24, Theme.c.navy, { bold: true, align: 'l' });
            drawCoin(gfx(mk(row, 'C', 400, 16, 28, 28)), 14, 14, 13);
            text(row, String(r.netWorth), 438, 0, 170, rowH, 28, Theme.c.navy, { bold: true, align: 'l' });
        });
        let contentH = res.standings.length * rowH;
        if (res.titles?.length) {
            text(list.content, '本局趣味称号', 16, contentH + 6, 624, 42, 30, '#7A4A12', { bold: true });
            contentH += 56;
            const keys: Record<string, string> = { RENT_KING: 'result_trophy', PROPERTY_TYCOON: 'icon_house', LUCKY_STAR: 'event_reward', COMEBACK: 'info_net_worth' };
            res.titles.forEach((a, i) => {
                const x = i % 2 === 0 ? 16 : 336;
                const n = roundedPanel(list.content, x, contentH + Math.floor(i / 2) * 116, 304, 110, { fill: '#FFF7E3', r: 20 });
                art(n, keys[a.kind] ?? 'result_trophy', 14, 6, 42, 42);
                text(n, a.name, 66, 2, 222, 44, 28, '#6F2D16', { bold: true, align: 'l' });
                const p = res.standings.find(p => p.playerId === a.playerId);
                avatar(n, 14, 54, 46, p?.avatar ?? 0, p?.nickname ?? '玩家');
                text(n, p?.nickname ?? '玩家', 76, 46, 214, 34, 24, Theme.c.navy, { bold: true, align: 'l' });
                text(n, titleMetric(a), 76, 78, 214, 30, 19, Theme.c.navy, { bold: true, align: 'l' });
            });
            contentH += Math.ceil(res.titles.length / 2) * 116;
        }
        list.setContentHeight(contentH);

        if (seriesResult) {
            primaryButton(this.root, '查看系列积分', 40, 1018, 640, 96, () => { this.showingSeries = true; this.rebuild(); }, Theme.font.lg);
            text(this.root, '本局净资产排名计入系列积分，下一局资产重置', 20, 1120, 680, 60, 25, Theme.c.navy, { bold: true });
            return;
        }

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

/** Screen35: team placement first, personal net worth below, independent titles retained. */
function renderTeamResult(root: import('cc').Node,res: GameResult): void {
    const rows=res.teamStandings!, winners=rows.filter(r=>r.rank===1),draw=winners.length>1;
    const winner=winners[0], people=draw ? winners.flatMap(r=>res.standings.filter(p=>r.members.includes(p.playerId)).slice(0,1))
        : res.standings.filter(p=>winner.members.includes(p.playerId));
    people.slice(0,2).forEach((p,i)=>{
        if(p.avatar===ROBOT_AVATAR)avatar(root,i?410:84,182,210,p.avatar,p.nickname ?? '机器人');
        else art(root,informationCharacterKey(p.avatar ?? 0),i?390:64,168,250,268);
    });
    art(root,'result_trophy',258,132,204,182);
    const plaque=informationCard(root,72,368,576,70,teamColor(winner.teamId));
    text(plaque,draw?'两队并列冠军':teamName(winner.teamId)+'获胜',0,0,576,70,48,'#FFFFFF',{bold:true});
    const panel=informationCard(root,32,454,656,660);
    const list=new ScrollList(panel,16,8,624,642); let y=0;
    text(list.content,'队伍排名 · 净资产',0,y,624,54,36,Theme.c.navy,{bold:true});y+=62;
    rows.forEach(r=>{
        const n=informationCard(list.content,0,y,624,78,'#FFFEF9',22,false);
        text(n,'第'+r.rank+'名',12,0,134,78,32,Theme.c.navy,{bold:true});
        text(n,teamName(r.teamId),150,0,176,78,34,teamColor(r.teamId),{bold:true});
        art(n,'info_cash',358,22,38,42); text(n,String(r.netWorth),406,0,200,78,40,Theme.c.navy,{bold:true,align:'l'});y+=86;
    });
    text(list.content,'成员个人净资产',0,y,624,50,32,Theme.c.navy,{bold:true});y+=58;
    for(const team of rows)for(const pid of team.members){
        const p=res.standings.find(r=>r.playerId===pid);if(!p)continue;
        avatar(list.content,12,y,52,p.avatar ?? 0,p.nickname ?? '玩家',{dim:!team.survivors.includes(pid)});
        text(list.content,p.nickname ?? '玩家',78,y,224,54,25,Theme.c.navy,{bold:true,align:'l'});
        text(list.content,teamName(team.teamId),302,y,114,54,23,teamColor(team.teamId),{bold:true});
        text(list.content,String(p.netWorth),434,y,178,54,31,Theme.c.navy,{bold:true,align:'l'});y+=60;
    }
    for(const award of res.titles ?? []){
        const p=res.standings.find(r=>r.playerId===award.playerId);
        text(list.content,award.name+' · '+(p?.nickname ?? '玩家'),12,y,600,44,24,Theme.c.navy,{bold:true,align:'l'});y+=48;
    }
    text(list.content,'资金和土地各自独立，按队伍净资产排名',0,y,624,44,23,Theme.c.navy);y+=50;
    list.setContentHeight(y);
    primaryButton(root,'返回房间',40,1146,640,94,()=>ctx.screens.go('room'),42);
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
