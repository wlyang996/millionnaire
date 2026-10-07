/**
 * 棋盘页底部（设计稿 01）：手牌栏（y 1102–1192，观战时换成聊天条）+ 深蓝页脚（y 1198–1280：头像、"昵称 · 我"、金币现金、
 * 红字认输、描边"我的资产 ›"、语音、聊天）。
 */
import { Theme } from '../../core/Theme';
import { AssetsPopup } from '../../popups/AssetsPopup';
import { ChatPopup } from '../../popups/ChatPopup';
import { surrenderConfirm } from '../../popups/ConfirmPopup';
import { IconButton, primaryButton, secondaryButton } from '../../ui/Buttons';
import { ctx } from '../../ui/Ctx';
import { drawChat, drawCoin, drawMic } from '../../ui/Icons';
import { fillRR, gfx, mk, onTap, strokeRR, text } from '../../ui/Kit';
import { Toast } from '../../ui/Toast';
import { avatar, roundedPanel } from '../../ui/Widgets';
import { Node } from 'cc';
import { drawHandBar } from './HandBar';
import { CashChange, CashChangeNode, cashDelta } from './PlayerBar';

export function drawBottom(root: Node, spectator: boolean, change?: CashChange, changeNodes: CashChangeNode[] = []): void {
    const st = ctx.store;
    const me = st.me();
    if (spectator) {
        const b = roundedPanel(root, 12, 1052, 696, 140, { r: 26, fill: '#1F4E8CD9' });
        const chat = st.game.chat.slice(-2);
        if (!chat.length) text(b, '已进入观战，和好友聊聊吧', 0, 0, 696, 140, Theme.font.md, Theme.c.white);
        chat.forEach((c, i) => {
            const player = st.game.players.find((p) => p.nickname === c.from);
            avatar(b, 14, 10 + i * 64, 48, player?.avatar ?? 0, c.from);
            text(b, c.from + '：', 74, 10 + i * 64, 108, 48, Theme.font.sm, Theme.c.white, { align: 'l' });
            const bubble = roundedPanel(b, 184, 10 + i * 64, 486, 48, { r: 20, fill: '#FFFFFFEE', shadow: 0 });
            text(bubble, c.text, 12, 0, 462, 48, Theme.font.sm, Theme.c.ink, { align: 'l' });
        });
        const footer = roundedPanel(root, 20, 1198, 680, 82, { r: 30, fill: '#1F4E8CE6', shadow: 0 });
        new IconButton(footer, 10, 8, 66, '', () => Toast.show('语音尚未开放'), '#FFFFFF22', Theme.c.white,
            (g, s) => drawMic(g, s / 2, s / 2, s * 0.6, Theme.c.white));
        const input = roundedPanel(footer, 88, 8, 420, 66, { r: 33, fill: '#FFFFFF22', shadow: 0 });
        text(input, '点击输入聊天内容…', 18, 0, 320, 66, Theme.font.sm, Theme.c.white, { align: 'l' });
        onTap(input, () => ctx.popups.open(new ChatPopup()));
        secondaryButton(footer, '返回大厅', 520, 6, 150, 70, () => ctx.screens.go('lobby'), Theme.font.md);
        return;
    } else {
        drawHandBar(root, 40, 1102, 660, 90, st.game.myHand, st.isMyTurn());
    }
    const row = mk(root, 'Bottom', 20, 1198, 680, 82);
    fillRR(gfx(row), 0, 4, 680, 82, 30, Theme.c.shadow);
    fillRR(gfx(row), 0, 0, 680, 82, 30, '#1F4E8CE6');
    const ownAvatar = avatar(row, 10, 6, 70, me.avatar, me.nickname, { ring: Theme.c.white });
    onTap(ownAvatar, () => ctx.popups.open(new AssetsPopup(me.playerId)));
    text(row, me.nickname + ' · 我', 98, 8, 150, 28, 20, Theme.c.white, { align: 'l' });
    if (!spectator) {
        drawCoin(gfx(mk(row, 'Coin', 98, 42, 30, 30)), 15, 15, 14);
        text(row, String(me.cash), 134, 36, 112, 42, 30, Theme.c.white, { bold: true, align: 'l' });
        if (change) cashDelta(row, 130, 22, change, changeNodes, 20); // 浮在现金正上方，向上飘出底栏
        const assets = mk(row, 'Btn:assets', 310, 15, 156, 52);
        fillRR(gfx(assets), 0, 0, 156, 52, 26, '#FFFFFF1A');
        strokeRR(gfx(assets), 0, 0, 156, 52, 26, '#FFFFFF99', 2);
        text(assets, '我的资产 ›', 0, 0, 156, 52, 22, Theme.c.white, { bold: true });
        onTap(assets, () => ctx.popups.open(new AssetsPopup()));
    }
    const mic = spectator ? 360 : 508;
    new IconButton(row, mic, 8, 66, '', () => Toast.show('语音尚未开放'), '#FFFFFF22', Theme.c.white,
        (g, s) => drawMic(g, s / 2, s / 2, s * 0.6, Theme.c.white));
    new IconButton(row, mic + 97, 8, 66, '', () => ctx.popups.open(new ChatPopup()), '#FFFFFF22', Theme.c.white,
        (g, s) => drawChat(g, s / 2, s / 2, s * 0.62, Theme.c.white));
    if (spectator) {
        primaryButton(row, '返回大厅', 520, 6, 150, 70, () => ctx.screens.go('lobby'), Theme.font.md);
    } else {
        const sur = mk(row, 'Btn:surrender', 244, 21, 56, 40);
        text(sur, '认输', 0, 0, 56, 40, 24, '#FF5A55', { bold: true });
        onTap(sur, () => ctx.popups.open(surrenderConfirm(() => {
            st.surrender();
            ctx.screens.go('spectator');
        })));
    }
}
