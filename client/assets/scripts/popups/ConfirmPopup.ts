/** 二次确认弹窗（认输、移除成员、返回大厅等）。 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { dangerButton, ghostButton, primaryButton, secondaryButton } from '../ui/Buttons';
import { art, informationCharacterKey } from '../ui/Art';
import { ctx } from '../ui/Ctx';
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

/**
 * 认输二次确认（设计稿 17 中）：面板上方探出本人角色半身像；大标题"确认认输？"、两行说明，
 * 蓝色"继续游戏" / 红色"确认认输"。规则：主动认输须二次确认；现金与资产由系统回收，不奖励他人。
 */
export class SurrenderPopup extends Popup {
    constructor(private readonly onConfirm: () => void) {
        super('surrender', '', 576, 360, 0, false);
        this.dimBackground = true;
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const me = ctx.store.me();
        art(p, informationCharacterKey(me ? me.avatar : 0), (w - 180) / 2, -186, 180, 202);
        text(p, '确认认输？', 0, 34, w, 70, 46, Theme.c.navy, { bold: true });
        text(p, '现金和资产由系统回收，\n之后可观战和聊天', 30, 112, w - 60, 90, Theme.font.md, Theme.c.noteGray, { wrap: true, lineHeight: 40 });
        const bw = (w - 36 * 2 - 24) / 2;
        secondaryButton(p, '继续游戏', 36, h - 124, bw, 92, () => this.close(), Theme.font.lg);
        dangerButton(p, '确认认输', 36 + bw + 24, h - 124, bw, 92, () => {
            this.close();
            this.onConfirm();
        }, Theme.font.lg);
    }
}

export function surrenderConfirm(onConfirm: () => void): Popup {
    return new SurrenderPopup(onConfirm);
}
