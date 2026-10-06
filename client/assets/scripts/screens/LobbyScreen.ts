/** 页面 2：大厅（创建房间 / 房号加入 / 返回对局 / 我的战绩）。 */
import { Theme } from '../core/Theme';
import { HistoryPopup } from '../popups/HistoryPopup';
import { JoinRoomPopup } from '../popups/JoinRoomPopup';
import { IconButton, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCoin } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar, chip, roundedPanel } from '../ui/Widgets';

export class LobbyScreen extends Screen {
    readonly id = 'lobby' as const;
    readonly title = '大厅';

    protected build(): void {
        const st = ctx.store;
        this.backdrop('sky');

        // 顶部：头像 + 昵称 + 在线信号 + 设置/音量
        const me = mk(this.root, 'MeBar', 80, 18, 330, 72);
        const g = gfx(me);
        fillRR(g, 0, 3, 330, 68, 34, Theme.c.shadow);
        fillRR(g, 0, 0, 330, 68, 34, Theme.c.ivory);
        avatar(me, 4, 4, 60, st.profile.avatar, st.profile.nickname || '我');
        text(me, st.profile.nickname || '微信用户', 76, 0, 190, 68, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        for (let i = 0; i < 3; i++) fillRR(gfx(mk(me, 'Sig', 280 + i * 11, 40 - i * 8, 7, 12 + i * 8)), 0, 0, 7, 12 + i * 8, 2, Theme.c.green);
        new IconButton(this.root, 424, 22, 64, '♪', () => Toast.show('音量设置（演示）'));

        // 标题木牌
        const sign = mk(this.root, 'Logo', 90, 140, 540, 180);
        const sg = gfx(sign);
        fillRR(sg, 0, 12, 540, 168, 32, '#6B4423');
        fillRR(sg, 0, 0, 540, 168, 32, '#A4723C');
        strokeRR(sg, 0, 0, 540, 168, 32, '#5A3A1A', 5);
        text(sign, '好友桌游', 0, 8, 540, 120, 88, '#FFF3C4', { bold: true });
        text(sign, '2～8 人好友房 · 30 / 50 格地图', 0, 120, 540, 44, Theme.font.sm, '#FFE9A8');

        // 迷你棋盘插画
        const art = mk(this.root, 'Art', 110, 360, 500, 270);
        this.drawMiniBoard(art);

        // 主按钮
        primaryButton(this.root, '创建房间', 48, 668, 624, 112, () => {
            st.patchScenario({ host: true });
            ctx.screens.push('room');
        }, Theme.font.xl);
        secondaryButton(this.root, '房间号加入', 48, 806, 624, 112, () => ctx.popups.open(new JoinRoomPopup()), Theme.font.xl);

        // 返回对局卡
        const inGame = st.session.status === 'PLAYING';
        const card = roundedPanel(this.root, 48, 948, 624, 108);
        if (inGame) {
            fillRR(gfx(mk(card, 'Ic', 18, 18, 72, 72)), 0, 0, 72, 72, 14, Theme.c.greenSoft);
            fillCircle(gfx(mk(card, 'Ic2', 18, 18, 72, 72)), 36, 36, 18, Theme.c.green);
            text(card, '返回对局', 110, 12, 300, 48, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
            text(card, '房间 ' + st.session.roomId + (st.isSpectator() ? ' · 观战中' : ' · 进行中'), 110, 58, 360, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
            chip(card, 500, 36, '回到牌桌', Theme.c.yellow, Theme.c.yellowText, Theme.font.sm);
            onTap(card, () => ctx.screens.go(st.isSpectator() ? 'spectator' : 'board'));
        } else {
            text(card, '当前没有进行中的对局', 0, 0, 624, 108, Theme.font.md, Theme.c.inkSoft);
        }

        // 我的战绩
        const rec = roundedPanel(this.root, 148, 1088, 424, 84, { r: 42 });
        const rg = gfx(mk(rec, 'Cup', 26, 18, 48, 48));
        drawCoin(rg, 24, 24, 20);
        text(rec, '我的战绩', 80, 0, 260, 84, Theme.font.lg, Theme.c.ink, { bold: true });
        text(rec, '›', 360, 0, 40, 84, Theme.font.xl, Theme.c.inkSoft);
        onTap(rec, () => ctx.popups.open(new HistoryPopup()));
        text(this.root, '联机、微信登录与实时语音为演示占位，尚未接入后端', 48, 1200, 624, 36, Theme.font.xs, Theme.c.ink);
    }

    private drawMiniBoard(art: import('cc').Node): void {
        const g = gfx(art);
        fillRR(g, 0, 8, 500, 262, 30, Theme.c.shadow);
        fillRR(g, 0, 0, 500, 262, 30, '#FFFFFFB0');
        fillRR(g, 40, 38, 420, 186, 18, Theme.c.grass);
        const colors = [Theme.c.tierLow, Theme.c.tierMid, Theme.c.tierHigh, Theme.c.yellow, Theme.c.red];
        const t = 38;
        let k = 0;
        const put = (x: number, y: number) => fillRR(g, x, y, t, t, 7, colors[k++ % colors.length]);
        for (let i = 0; i < 12; i++) put(14 + i * 39, 4);
        for (let i = 0; i < 12; i++) put(14 + i * 39, 220);
        for (let i = 1; i < 5; i++) {
            put(2, 4 + i * 43);
            put(460, 4 + i * 43);
        }
        text(art, '好友房间 · 个人竞争', 40, 90, 420, 50, Theme.font.lg, '#2F6B2F', { bold: true });
        text(art, '地产 · 事件 · 道具 · 小游戏', 40, 140, 420, 40, Theme.font.sm, '#2F6B2F');
    }
}
