/** 租金响应（10 秒）：仅持有免租卡时出现；可提前选"不使用"；超时不使用。响应先于付款与现金不足判定。 */
import { Node } from 'cc';
import { SECONDS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { secondaryButton, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCardIcon } from '../ui/Icons';
import { fillRR, gfx, mk, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { avatar } from '../ui/Widgets';
import { coinText, tileSubtitle } from './Common';
import { beginDebt } from './DebtPopup';

export class RentPopup extends Popup {
    constructor(private readonly tileIndex: number, private readonly ownerName: string, private readonly amount: number) {
        super('rent', '支付租金', 640, 760, SECONDS.rent);
    }

    protected buildBody(p: Node, w: number): void {
        const st = ctx.store;
        const tile = st.tile(this.tileIndex);
        const owner = st.game.players.find((x) => x.nickname === this.ownerName);
        avatar(p, (w - 100) / 2, 96, 100, owner ? owner.avatar : 1, this.ownerName);
        text(p, this.ownerName, 0, 200, w, 34, Theme.font.md, Theme.c.ink, { bold: true });
        text(p, this.ownerName + '的' + tileSubtitle(tile, st.prop(this.tileIndex)), 20, 234, w - 40, 34, Theme.font.sm, Theme.c.inkSoft);
        const box = mk(p, 'Due', 40, 284, w - 80, 120);
        fillRR(gfx(box), 0, 0, w - 80, 120, 20, Theme.c.ivoryDark);
        text(box, '应付租金', 0, 8, w - 80, 36, Theme.font.sm, Theme.c.inkSoft, { bold: true });
        const tw = 150;
        coinText(box, (w - 80 - tw) / 2, 44, this.amount, Theme.font.xxl - 8, Theme.c.redDark);
        // 免租卡
        const card = mk(p, 'Waiver', 40, 420, w - 80, 120);
        const g = gfx(card);
        fillRR(g, 0, 6, w - 80, 114, 24, Theme.c.blueDark);
        fillRR(g, 0, 0, w - 80, 114, 24, Theme.c.blue);
        drawCardIcon(g, 'RENT_WAIVER', 90, 57, 84);
        text(card, '免租卡', 150, 14, 300, 56, Theme.font.xl, Theme.c.white, { bold: true, align: 'l' });
        text(card, '免除本次租金', 150, 66, 300, 34, Theme.font.sm, '#E8F4FF', { align: 'l' });
        primaryButton(p, '使用免租卡', 40, 556, w - 80, 88, () => {
            Toast.show('已使用免租卡，本次租金免除');
            this.close();
        }, Theme.font.lg);
        secondaryButton(p, '不使用', 40, 656, w - 80, 76, () => this.pay(), Theme.font.md);
        text(p, '超时按"不使用"处理', 40, 732, w - 80, 26, Theme.font.xs, Theme.c.inkFaint);
    }

    private pay(): void {
        const st = ctx.store;
        this.close();
        if (st.me().cash >= this.amount) {
            st.spend(this.amount);
            Toast.show('已支付租金 ' + this.amount);
        } else {
            beginDebt(this.amount, null);
        }
    }

    protected onExpire(): void {
        this.pay();
    }
}
