/** 棋盘页底部：手牌栏（观战时换成提示条）+ 深蓝资产条（头像、昵称·我、现金、我的资产、语音、聊天；设计图 01-board）。 */
import { Theme } from '../../core/Theme';
import { AssetsPopup } from '../../popups/AssetsPopup';
import { ChatPopup } from '../../popups/ChatPopup';
import { surrenderConfirm } from '../../popups/ConfirmPopup';
import { IconButton, primaryButton } from '../../ui/Buttons';
import { ctx } from '../../ui/Ctx';
import { drawChat, drawCoin, drawMic } from '../../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../../ui/Kit';
import { Toast } from '../../ui/Toast';
import { avatar, roundedPanel } from '../../ui/Widgets';
import { Node } from 'cc';
import { drawHandBar } from './HandBar';

export function drawBottom(root: Node, spectator: boolean): void {
    const st = ctx.store;
    const me = st.me();
    if (spectator) {
        const b = roundedPanel(root, 12, 1020, 696, 140, { r: 26, fill: '#1F4E8CD9' });
        const chat = st.game.chat.slice(-2);
        if (!chat.length) text(b, '已进入观战，和好友聊聊吧', 0, 0, 696, 140, Theme.font.md, Theme.c.white);
        chat.forEach((c, i) => {
            const player = st.game.players.find((p) => p.nickname === c.from);
            avatar(b, 14, 10 + i * 64, 48, player?.avatar ?? 0, c.from);
            text(b, c.from + '：', 74, 10 + i * 64, 108, 48, Theme.font.sm, Theme.c.white, { align: 'l' });
            const bubble = roundedPanel(b, 184, 10 + i * 64, 486, 48, { r: 20, fill: '#FFFFFFEE', shadow: 0 });
            text(bubble, c.text, 12, 0, 462, 48, Theme.font.sm, Theme.c.ink, { align: 'l' });
        });
        const footer = roundedPanel(root, 12, 1170, 696, 96, { r: 30, fill: '#1F4E8CE6', shadow: 0 });
        new IconButton(footer, 12, 14, 68, '', () => Toast.show('麦克风已开启（演示）'), '#FFFFFF22', Theme.c.white,
            (g, s) => drawMic(g, s / 2, s / 2, s * 0.6, Theme.c.white));
        const input = roundedPanel(footer, 92, 14, 422, 68, { r: 34, fill: '#FFFFFF22', shadow: 0 });
        text(input, '点击输入聊天内容…', 18, 0, 320, 68, Theme.font.sm, Theme.c.white, { align: 'l' });
        onTap(input, () => ctx.popups.open(new ChatPopup()));
        primaryButton(footer, '返回大厅', 530, 10, 154, 76, () => ctx.screens.go('lobby'), Theme.font.md);
        return;
    } else {
        drawHandBar(root, 12, 1020, 696, 140, st.game.myHand, st.isMyTurn());
    }
    const row = mk(root, 'Bottom', 12, 1170, 696, 96);
    fillRR(gfx(row), 0, 4, 696, 92, 30, Theme.c.shadow);
    fillRR(gfx(row), 0, 0, 696, 92, 30, '#1F4E8CE6');
    const ownAvatar = avatar(row, 12, 12, 66, me.avatar, me.nickname, { ring: Theme.c.white });
    onTap(ownAvatar, () => ctx.popups.open(new AssetsPopup(me.playerId)));
    text(row, me.nickname + ' · 我', 88, 8, 170, 30, Theme.font.sm, Theme.c.white, { align: 'l' });
    if (!spectator) {
        drawCoin(gfx(mk(row, 'Coin', 88, 44, 36, 36)), 18, 18, 15);
        text(row, String(me.cash), 130, 40, 122, 44, Theme.font.xl, Theme.c.white, { bold: true, align: 'l' });
        const assets = mk(row, 'Btn:assets', 356, 16, 164, 60);
        fillRR(gfx(assets), 0, 0, 164, 60, 30, '#FFFFFF22');
        strokeRR(gfx(assets), 0, 0, 164, 60, 30, '#FFFFFF88', 2);
        text(assets, '我的资产', 0, 0, 164, 60, Theme.font.sm, Theme.c.white, { bold: true });
        onTap(assets, () => ctx.popups.open(new AssetsPopup()));
    }
    const mic = spectator ? 360 : 530;
    new IconButton(row, mic, 14, 68, '', () => Toast.show('麦克风已开启（演示，未接入语音服务）'), '#FFFFFF22', Theme.c.white,
        (g, s) => drawMic(g, s / 2, s / 2, s * 0.6, Theme.c.white));
    new IconButton(row, mic + 80, 14, 68, '', () => ctx.popups.open(new ChatPopup()), '#FFFFFF22', Theme.c.white,
        (g, s) => drawChat(g, s / 2, s / 2, s * 0.62, Theme.c.white));
    if (spectator) {
        primaryButton(row, '返回大厅', 530, 10, 154, 76, () => ctx.screens.go('lobby'), Theme.font.md);
    } else {
        const sur = mk(row, 'Btn:surrender', 264, 24, 80, 44);
        text(sur, '认输', 0, 0, 80, 44, Theme.font.sm, '#FF6B66', { bold: true });
        onTap(sur, () => ctx.popups.open(surrenderConfirm(() => {
            st.surrender();
            Toast.show('已认输，进入观战');
            ctx.screens.go('spectator');
        })));
    }
}
