/** 二次确认弹窗（认输、移除成员、返回大厅等）。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { dangerButton, ghostButton, primaryButton } from '../ui/Buttons';
import { text } from '../ui/Kit';
import { Popup } from '../ui/Popup';

export interface ConfirmOpts {
    title: string;
    message: string;
    confirmText?: string;
    cancelText?: string;
    danger?: boolean;
    onConfirm: () => void;
    onCancel?: () => void;
}

export class ConfirmPopup extends Popup {
    constructor(private readonly o: ConfirmOpts, id = 'confirm') {
        super(id, o.title, 600, 420, 0, false);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        text(p, this.o.message, 36, 100, w - 72, 160, Theme.font.md, Theme.c.ink, { wrap: true, align: 'l', valign: 't', lineHeight: 42 });
        const bw = (w - 36 * 2 - 20) / 2;
        ghostButton(p, this.o.cancelText ?? '取消', 36, h - 130, bw, 92, () => {
            this.close();
            this.o.onCancel?.();
        }, Theme.font.lg);
        const go = () => {
            this.close();
            this.o.onConfirm();
        };
        const label = this.o.confirmText ?? '确定';
        if (this.o.danger) dangerButton(p, label, 36 + bw + 20, h - 130, bw, 92, go, Theme.font.lg);
        else primaryButton(p, label, 36 + bw + 20, h - 130, bw, 92, go, Theme.font.lg);
    }
}

/** 认输二次确认（规则：主动认输须二次确认；无欠款则现金和资产系统回收，不奖励他人）。 */
export function surrenderConfirm(onConfirm: () => void): ConfirmPopup {
    return new ConfirmPopup({
        title: '确认认输？',
        message: '认输后你的现金和全部资产由系统回收，不会奖励其他玩家。之后只能观战、聊天和语音。',
        confirmText: '确认认输',
        cancelText: '继续游戏',
        danger: true,
        onConfirm,
    }, 'surrender');
}
