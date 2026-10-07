/**
 * 攻击卡的响应窗（10 秒，O4：只在持有对应响应卡时弹出）。版式沿用设计稿 04 的"租金响应"：
 * 攻击者头像（金色圈）、"某某对你的 X 使用了降级"、效果说明（等级变化 / 清地 / 强购价）、响应卡票券、
 * 竖排"使用房屋保护 / 拒绝购买"与"不使用"，底部"超时不使用"。设计稿未单独画这张，按租金响应的版式补齐（已在汇总中说明）。
 */
import { Node } from 'cc';
import { CARD_NAMES, ResponseInfo } from '../core/Models';
import { Theme } from '../core/Theme';
import { art } from '../ui/Art';
import { primaryButton, softButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { avatar } from '../ui/Widgets';
import { box } from './Common';
import { forcedPrice } from './CardUse';

const W = 520;
const H = 700;

export class CardResponsePopup extends Popup {
    private sent = false;

    constructor(private readonly r: ResponseInfo) {
        super('card-response', CARD_NAMES[r.response], W, H, 10);
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const attacker = st.player(this.r.attacker);
        const tile = st.tile(this.r.tile);
        const prop = st.prop(this.r.tile);
        const level = prop ? prop.level : 0;
        avatar(p, (W - 124) / 2, 70, 124, attacker ? attacker.avatar : 1, attacker ? attacker.nickname : '', { ring: '#F5B82E' });
        text(p, (attacker ? attacker.nickname : '对手') + ' 对你的' + tile.name, 0, 198, W, 32, 26, Theme.c.navy, { bold: true });
        text(p, '使用了' + CARD_NAMES[this.r.attack], 0, 230, W, 32, 26, Theme.c.payRed, { bold: true });
        box(p, 24, 270, W - 48, 96, Theme.c.boxBeige, 18);
        const effect = this.r.attack === 'DOWNGRADE' ? tile.name + ' ' + level + '级 → ' + (level - 1) + '级'
            : this.r.attack === 'DEMOLISH' ? tile.name + ' ' + level + '级 → 0级（保留所有权）'
                : this.r.attack === 'CLEAR_LAND' ? tile.name + ' 将被清除等级与所有权'
                    : '以 ' + forcedPrice(this.r.tile) + ' 强制买下 ' + tile.name;
        text(p, effect, 24, 270, W - 48, 96, 28, Theme.c.navy, { bold: true, wrap: true });
        this.ticket(p, (W - 260) / 2, 384, 260, 104);
        primaryButton(p, '使用' + CARD_NAMES[this.r.response], 24, 516, W - 48, 70, () => this.answer(true), 30);
        softButton(p, '不使用', 24, 596, W - 48, 66, () => this.answer(false), 30);
        text(p, '超时不使用', 0, 668, W, 26, 20, Theme.c.noteGray);
    }

    /** 响应卡票券：蓝色圆角卡、白色内框、盾牌图标与卡名，略微倾斜。 */
    private ticket(p: Node, x: number, y: number, w: number, h: number): void {
        const holder = mk(p, 'Ticket', x + w / 2, y + h / 2, 0, 0);
        holder.setRotationFromEuler(0, 0, 4);
        const n = mk(holder, 'Card', -w / 2, -h / 2, w, h);
        const g = gfx(n);
        fillRR(g, 0, 6, w, h - 6, 16, '#1F5FC9');
        fillRR(g, 0, 0, w, h - 6, 16, '#3E8EF2');
        strokeRR(g, 8, 8, w - 16, h - 22, 10, '#FFFFFFAA', 3);
        art(n, 'icon_shield', 16, (h - 6) / 2 - 34, 68, 68);
        text(n, CARD_NAMES[this.r.response], 88, 0, w - 100, h - 6, 34, Theme.c.white, { bold: true });
    }

    private answer(use: boolean): void {
        if (this.sent) return;
        this.sent = true;
        this.close();
        void ctx.store.online?.act('RespondCard', { windowId: this.r.windowId, use });
    }

    protected onExpire(): void {
        // 服务端在截止时按"不使用"处理
        this.close();
    }

    /** 响应窗被服务端结束（已回应 / 超时）后关闭。 */
    tick(): void {
        super.tick();
        if (this.closed) return;
        const resp = ctx.store.game?.cards?.response;
        if (!resp || resp.windowId !== this.r.windowId) this.close();
    }
}
