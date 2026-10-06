/** 聊天：最近消息 + 固定短语/表情（演示；真实接入时发送走服务端聊天通道）。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { ghostButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, onTap, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';

const PHRASES = ['好～', '稳住！', '哈哈', '加油', '手下留情', '借我点钱', '你太强了', '再来一局'];

export class ChatPopup extends Popup {
    constructor() {
        super('chat', '聊天', 640, 780, 0, true);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const st = ctx.store;
        const lines = st.game.chat.slice(-6);
        const box = mk(p, 'Lines', 28, 96, w - 56, 290);
        fillRR(gfx(box), 0, 0, w - 56, 290, 18, '#FFFFFFAA');
        lines.forEach((l, i) => {
            text(box, l.from + '：' + l.text, 16, 10 + i * 44, w - 88, 40, Theme.font.sm, Theme.c.ink, { align: 'l' });
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
        ghostButton(p, '关闭', 40, h - 96, w - 80, 76, () => this.close(), Theme.font.lg);
    }
}
