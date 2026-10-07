/**
 * 租金响应（10 秒），按设计稿 04"支付租金"：地主头像（金色圈）、"某某的档位 · 等级"、应付租金（红色大字）、
 * 免租卡票券 + "免除本次租金"，竖排"使用免租卡 / 不使用"，底部"超时不使用"。
 * 仅持有免租卡时出现；响应先于付款与现金不足判定。
 * 联机：传入服务端的落点窗口，"使用免租卡 / 不使用"发送 RespondCard；超时由服务端按"不使用"处理（随后缴租或进入欠款）。
 */
import { Node } from 'cc';
import { SECONDS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { art } from '../ui/Art';
import { primaryButton, softButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { avatar } from '../ui/Widgets';
import { box, inlineRow, LEVEL_NAMES, tierName } from './Common';
import { beginDebt } from './DebtPopup';

const W = 480;
const H = 700;

export class RentPopup extends Popup {
    private sent = false;

    /** @param windowId 联机时为服务端的免租响应窗口（落点 RESPONSE 步骤）；演示时不传 */
    constructor(private readonly tileIndex: number, private readonly ownerName: string, private readonly amount: number,
                private readonly windowId?: number) {
        super('rent', '支付租金', W, H, SECONDS.rent);
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const tile = st.tile(this.tileIndex);
        const prop = st.prop(this.tileIndex);
        const owner = st.game.players.find((x) => x.nickname === this.ownerName);
        avatar(p, (W - 124) / 2, 66, 124, owner ? owner.avatar : 1, this.ownerName, { ring: '#F5B82E' });
        text(p, this.ownerName, 0, 194, W, 30, 24, Theme.c.navy, { bold: true });
        const level = tile.type === 'PROPERTY' ? ' · ' + LEVEL_NAMES[prop ? prop.level : 0] : '';
        text(p, this.ownerName + '的' + tierName(tile) + level, 0, 222, W, 28, 22, Theme.c.navy, { bold: true });
        box(p, 24, 252, W - 48, 104, Theme.c.boxBeige, 18);
        text(p, '应付租金', 0, 258, W, 30, 24, Theme.c.navy, { bold: true });
        inlineRow(p, W / 2, 288, 62, [{ coin: 46 }, { t: String(this.amount), size: 60, color: Theme.c.payRed }], 12);
        this.ticket(p, (W - 241) / 2, 366, 241, 104);
        text(p, '免除本次租金', 0, 478, W, 30, 22, Theme.c.navy, { bold: true });
        primaryButton(p, '使用免租卡', 20, 516, W - 40, 66, () => this.answer(true), 30);
        softButton(p, '不使用', 20, 590, W - 40, 66, () => this.answer(false), 30);
        text(p, '超时不使用', 0, 662, W, 26, 20, Theme.c.noteGray);
    }

    /** 免租卡票券：蓝色圆角卡、白色内框、盾牌图标与"免租卡"，略微倾斜。 */
    private ticket(p: Node, x: number, y: number, w: number, h: number): void {
        const holder = mk(p, 'Ticket', x + w / 2, y + h / 2, 0, 0);
        holder.setRotationFromEuler(0, 0, 4);
        const n = mk(holder, 'Card', -w / 2, -h / 2, w, h);
        const g = gfx(n);
        fillRR(g, 0, 6, w, h - 6, 16, '#1F5FC9');
        fillRR(g, 0, 0, w, h - 6, 16, '#3E8EF2');
        strokeRR(g, 8, 8, w - 16, h - 22, 10, '#FFFFFFAA', 3);
        art(n, 'icon_shield', 18, (h - 6) / 2 - 34, 68, 68);
        text(n, '免租卡', 92, 0, w - 104, h - 6, 40, Theme.c.white, { bold: true });
    }

    private answer(use: boolean): void {
        const st = ctx.store;
        if (st.online && this.windowId !== undefined) {
            if (this.sent) return;
            this.sent = true;
            this.close();
            void st.online.act('RespondCard', { windowId: this.windowId, use });
            return;
        }
        if (use) {
            // 演示：用掉一张免租卡
            const i = st.game.myHand.findIndex((c) => c.type === 'RENT_WAIVER');
            if (i >= 0) st.game.myHand.splice(i, 1);
            this.close();
            st.emit();
            return;
        }
        this.pay();
    }

    private pay(): void {
        const st = ctx.store;
        this.close();
        if (st.me().cash >= this.amount) st.spend(this.amount);
        else beginDebt(this.amount, null);
    }

    protected onExpire(): void {
        if (ctx.store.online && this.windowId !== undefined) {
            this.close(); // 服务端按"不使用"处理
            return;
        }
        this.pay();
    }

    /** 联机：免租窗口结束（已回应 / 超时）后关闭。 */
    tick(): void {
        super.tick();
        const online = ctx.store.online;
        if (this.closed || !online || this.windowId === undefined) return;
        const w = online.myWindow('TURN');
        if (!w || w.windowId !== this.windowId) this.close();
    }
}
