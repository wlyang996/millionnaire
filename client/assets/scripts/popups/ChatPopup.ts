/** 聊天：最近消息 + 固定短语/表情（演示；真实接入时发送走服务端聊天通道）。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { avatar } from '../ui/Widgets';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, onTap, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';

const PHRASES = ['好～', '稳住！', '哈哈', '加油', '手下留情', '借我点钱', '你太强了', '再来一局'];

export class ChatPopup extends Popup {
    constructor() {
        super('chat', '聊天', 640, 720, 0, true);
    }

    protected buildTitle(): void {
        text(this.panel, this.title, 80, 20, this.pw - 160, 64, 38, Theme.c.ink, { bold: true });
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const st = ctx.store;
        const close = mk(p, 'Close', w - 76, 20, 56, 56);
        text(close, '❌', 0, 0, 56, 56, 30, Theme.c.red, { bold: true });
        onTap(close, () => this.close(), false);
        const lines = st.game.chat.slice(-4);
        const box = mk(p, 'Lines', 28, 96, w - 56, 290);
        fillRR(gfx(box), 0, 0, w - 56, 290, 18, '#FFFFFFAA');
        const colors = ['#FFE9EB', Theme.c.blueSoft, '#FFF3D5', Theme.c.greenSoft];
        lines.forEach((l, i) => {
            const player = st.game.players.find(player => player.nickname === l.from);
            avatar(box, 14, 12 + i * 68, 52, player?.avatar ?? 0, l.from);
            text(box, l.from + '：', 78, 12 + i * 68, 126, 52, 23, Theme.c.ink, { bold: true, align: 'l' });
            const bubble = mk(box, 'Message', 208, 14 + i * 68, w - 284, 48);
            fillRR(gfx(bubble), 0, 0, w - 284, 48, 16, colors[i]);
            text(bubble, l.text, 12, 0, w - 308, 48, 23, Theme.c.ink, { bold: true, align: 'l' });
        });
        if (lines.length === 0) text(box, '还没有消息', 0, 0, w - 56, 290, Theme.font.sm, Theme.c.inkFaint);
        text(p, '快捷短语（破产观战时同样可用）', 28, 402, w - 56, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        PHRASES.forEach((s, i) => {
            const bw = (w - 56 - 12) / 2;
            const b = mk(p, 'Phrase', 28 + (i % 2) * (bw + 12), 446 + Math.floor(i / 2) * 68, bw, 56);
            fillRR(gfx(b), 0, 0, bw, 56, 28, Theme.c.blueSoft);
            text(b, s, 0, 0, bw, 56, Theme.font.sm, Theme.c.blueDark, { bold: true });
            onTap(b, () => {
                st.game.chat.push({ from: st.me().nickname, text: s });
                this.rebuildBody();
                st.emit();
            });
        });
    }
}
