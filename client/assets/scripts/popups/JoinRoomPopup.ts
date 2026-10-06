/** 房号加入：6 位数字键盘输入（演示：000000 表示不存在，888888 表示已满，其余成功进入房间页）。 */
import { Label, Node } from 'cc';
import { Theme } from '../core/Theme';
import { primaryButton, ghostButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, setText, strokeRR, text } from '../ui/Kit';
import { Keypad } from '../ui/Keypad';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';

export class JoinRoomPopup extends Popup {
    private code = '';
    private cells: Label[] = [];
    private err!: Label;

    constructor() {
        super('join', '输入房间号', 620, 860, 0, true);
    }

    protected buildBody(p: Node, w: number): void {
        text(p, '向房主要 6 位房间号，输入后加入', 28, 84, w - 56, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        const cw = 76;
        const gap = 12;
        const total = cw * 6 + gap * 5;
        const x0 = (w - total) / 2;
        this.cells = [];
        for (let i = 0; i < 6; i++) {
            const c = mk(p, 'Cell', x0 + i * (cw + gap), 140, cw, 96);
            const g = gfx(c);
            fillRR(g, 0, 0, cw, 96, 16, Theme.c.white);
            strokeRR(g, 0, 0, cw, 96, 16, Theme.c.ivoryLine, 3);
            this.cells.push(text(c, '', 0, 0, cw, 96, Theme.font.xl, Theme.c.ink, { bold: true }));
        }
        this.err = text(p, '', 28, 248, w - 56, 36, Theme.font.sm, Theme.c.red);
        new Keypad(p, 40, 296, w - 80, 96, (k) => this.key(k));
        const y = 296 + 96 * 4 + 14 * 3 + 36;
        ghostButton(p, '取消', 40, y, 240, 92, () => this.close(), Theme.font.lg);
        primaryButton(p, '加入', 300, y, w - 340, 92, () => this.join(), Theme.font.lg);
    }

    private key(k: string): void {
        if (k === '⌫') this.code = this.code.slice(0, -1);
        else if (k === '清空') this.code = '';
        else if (this.code.length < 6) this.code += k;
        this.cells.forEach((l, i) => setText(l, this.code.charAt(i)));
        setText(this.err, '');
    }

    private join(): void {
        if (this.code.length < 6) {
            setText(this.err, '请输入完整的 6 位房间号');
            return;
        }
        if (this.code === '000000') return void setText(this.err, '房间不存在或已关闭');
        if (this.code === '888888') return void setText(this.err, '房间已满');
        this.close();
        Toast.show('已加入房间 ' + this.code);
        ctx.store.patchScenario({ host: false });
        ctx.screens.push('room');
    }
}
