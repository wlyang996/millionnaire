/** 页面 2：大厅（创建房间 / 房号加入 / 返回对局 / 我的战绩）。 */
import { UITransform } from 'cc';
import { Theme } from '../core/Theme';
import { HistoryPopup } from '../popups/HistoryPopup';
import { JoinRoomPopup } from '../popups/JoinRoomPopup';
import { IconButton, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawTrophy } from '../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../ui/Kit';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar, roundedPanel } from '../ui/Widgets';
import { art } from '../ui/Art';

export class LobbyScreen extends Screen {
    readonly id = 'lobby' as const;
    readonly title = '大厅';

    protected build(): void {
        const st = ctx.store;
        this.backdrop('sky');
        art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');

        // 顶部：头像 + 昵称 + 在线信号 + 设置/音量
        const me = mk(this.root, 'MeBar', 80, 18, 330, 72);
        const g = gfx(me);
        fillRR(g, 0, 3, 330, 68, 34, Theme.c.shadow);
        fillRR(g, 0, 0, 330, 68, 34, Theme.c.ivory);
        avatar(me, 4, 4, 60, st.profile.avatar, st.profile.nickname || '我');
        text(me, st.profile.nickname || '微信用户', 76, 0, 190, 68, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        for (let i = 0; i < 3; i++) fillRR(gfx(mk(me, 'Sig', 280 + i * 11, 40 - i * 8, 7, 12 + i * 8)), 0, 0, 7, 12 + i * 8, 2, Theme.c.green);
        new IconButton(this.root, 424, 22, 64, '♪', () => Toast.show('音量设置（演示）'));
        const settings = new IconButton(this.root, 626, 102, 64, '', () => Toast.show('房间内可调整地图、资金与回合设置'));
        art(settings.node, 'icon_settings', 10, 10, 44, 44);

        // 标题木牌
        const sign = mk(this.root, 'Logo', 90, 140, 540, 180);
        const sg = gfx(sign);
        fillRR(sg, 0, 12, 540, 168, 32, '#6B4423');
        fillRR(sg, 0, 0, 540, 168, 32, '#A4723C');
        strokeRR(sg, 0, 0, 540, 168, 32, '#5A3A1A', 5);
        if (art(sign, 'title_wood', 0, 0, 540, 168, 'stretch')) sg.clear();
        text(sign, '好友桌游', 0, 8, 540, 120, 88, '#FFF3C4', { bold: true });
        text(sign, '2～8 人好友房 · 30 / 50 格地图', 0, 120, 540, 44, Theme.font.sm, '#FFE9A8');
        // The approved screen-08 character group must be supplied as separate artwork.
        art(this.root, 'lobby_characters', 48, 334, 624, 316);

        // 主按钮
        const create = primaryButton(this.root, '创建房间', 48, 668, 624, 112, () => {
            st.patchScenario({ host: true });
            ctx.screens.push('room');
        }, Theme.font.xl);
        art(create.node, 'icon_house', 34, 24, 64, 64);
        text(create.node, '›', 544, 0, 50, 106, Theme.font.xl, Theme.c.yellowText);
        const join = secondaryButton(this.root, '房间号加入', 48, 806, 624, 112, () => ctx.popups.open(new JoinRoomPopup()), Theme.font.xl);
        text(join.node, '123', 24, 0, 84, 106, Theme.font.md, Theme.c.white, { bold: true });
        text(join.node, '›', 544, 0, 50, 106, Theme.font.xl, Theme.c.white);

        // 返回对局卡
        const inGame = st.session.status === 'PLAYING';
        const card = roundedPanel(this.root, 48, 948, 624, 108);
        if (inGame) {
            fillRR(gfx(mk(card, 'Ic', 18, 18, 72, 72)), 0, 0, 72, 72, 14, Theme.c.greenSoft);
            this.drawTown(mk(card, 'MiniTown', 18, 18, 72, 72));
            text(card, '返回对局', 110, 12, 300, 48, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
            text(card, '房间 ' + st.session.roomId + (st.isSpectator() ? ' · 观战中' : ' · 进行中'), 110, 58, 360, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
            text(card, '›', 554, 20, 50, 68, Theme.font.xl, Theme.c.inkSoft);
            onTap(card, () => ctx.screens.go(st.isSpectator() ? 'spectator' : 'board'));
        } else {
            text(card, '当前没有进行中的对局', 0, 0, 624, 108, Theme.font.md, Theme.c.inkSoft);
        }

        // 我的战绩
        const rec = roundedPanel(this.root, 148, 1088, 424, 84, { r: 42 });
        const rg = gfx(mk(rec, 'Cup', 26, 18, 48, 48));
        drawTrophy(rg, 24, 24, 44);
        text(rec, '我的战绩', 80, 0, 260, 84, Theme.font.lg, Theme.c.ink, { bold: true });
        text(rec, '›', 360, 0, 40, 84, Theme.font.xl, Theme.c.inkSoft);
        onTap(rec, () => ctx.popups.open(new HistoryPopup()));
    }

    private drawTown(parent: import('cc').Node): boolean {
        const size = parent.getComponent(UITransform)?.contentSize;
        return !!art(parent, 'board_town', 0, 0, size?.width ?? 500, size?.height ?? 270, 'stretch');
    }

}
