/**
 * 攻击卡的响应窗（10 秒，O4：只在持有对应响应卡时弹出）。设计稿 26（拒绝购买 / 房屋保护-降级 / 房屋保护-清地）与 30（房屋保护-拆楼）：
 * 棋盘底部面板（与 14 号稿主动卡面板同位置）：左上攻击者头像"某某 发起攻击"、右上红色倒计时；标题"被强制购房 / 被降级 / 被拆楼 / 被清地"；
 * 左侧攻击卡插画，右侧地块变化（等级 / 租金 / 出价）；下方防御卡说明条；"不使用 / 使用房屋保护（拒绝购买）"；底注"超时不使用 · 不占主动用卡机会"。
 */
import { Node } from 'cc';
import { CARD_NAMES, ResponseInfo } from '../core/Models';
import { rentOf, standardValue, TIERS } from '../core/Rules';
import { textWidth, Theme } from '../core/Theme';
import { art, CARD_ART } from '../ui/Art';
import { primaryButton, softButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, place, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { avatar } from '../ui/Widgets';
import { inlineRow } from './Common';
import { forcedPrice } from './CardUse';

const W = 692;
const H = 470;

const TITLES: Record<string, string> = {
    FORCED_PURCHASE: '被强制购房', DOWNGRADE: '被降级', DEMOLISH: '被拆楼', CLEAR_LAND: '被清地',
};

export class CardResponsePopup extends Popup {
    private sent = false;

    constructor(private readonly r: ResponseInfo) {
        super('card-response', '', W, H, 10);
    }

    mount(layer: Node): void {
        super.mount(layer);
        place(this.panel, (Theme.W - W) / 2, 1192 - H);
    }

    protected buildTitle(): void {
        // 标题在主体里（攻击者一行 + 图标标题）
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const r = this.r;
        const attacker = st.player(r.attacker);
        const tile = st.tile(r.tile);
        const prop = st.prop(r.tile);
        const level = prop ? prop.level : 0;
        const tier = tile.tier ?? 'LOW';
        // 攻击者
        avatar(p, 24, 16, 56, attacker ? attacker.avatar : 1, attacker ? attacker.nickname : '', { ring: '#F5B82E' });
        text(p, (attacker ? attacker.nickname : '对手') + ' 发起攻击', 92, 16, 360, 56, 26, Theme.c.navy, { bold: true, align: 'l' });
        // 标题
        const title = TITLES[r.attack] ?? CARD_NAMES[r.attack];
        const tw = textWidth(title, 40);
        const tx = (W - 66 - tw) / 2;
        art(p, 'icon_house', tx, 84, 54, 54);
        text(p, title, tx + 66, 80, tw + 10, 64, 40, Theme.c.navy, { bold: true, align: 'l' });
        // 左：攻击卡插画；右：变化
        art(p, CARD_ART[r.attack], 24, 150, 170, 170);
        const ix = 214;
        const iw = W - ix - 24;
        const line = (y: number, label: string, from: string, to: string | null, color = Theme.c.payRed) => {
            const parts = [{ t: label, size: 28 }, { t: from, size: 32, color: '#2F86E8' }];
            if (to !== null) parts.push({ t: '→', size: 28 }, { t: to, size: 32, color });
            inlineRow(p, ix + iw / 2, y, 52, parts, 12);
        };
        switch (r.attack) {
            case 'FORCED_PURCHASE': {
                const station = tile.type === 'STATION';
                const std = standardValue(station, tile.tier, station ? 0 : TIERS[tier].upgrade * level);
                line(156, tile.name, station ? '车站' : level + '级', null);
                text(p, '标准价值', ix + 20, 212, 200, 48, 26, Theme.c.navy, { align: 'l' });
                text(p, String(std), ix + iw - 220, 212, 200, 48, 32, '#2F86E8', { bold: true, align: 'r' });
                text(p, '对方出价', ix + 20, 262, 200, 48, 26, Theme.c.navy, { align: 'l' });
                text(p, String(forcedPrice(r.tile)), ix + iw - 220, 262, 200, 48, 36, Theme.c.payRed, { bold: true, align: 'r' });
                break;
            }
            case 'DOWNGRADE':
                line(170, tile.name, level + '级', Math.max(0, level - 1) + '级');
                line(236, '租金', String(rentOf(tier, level)), String(rentOf(tier, Math.max(0, level - 1))));
                break;
            case 'DEMOLISH':
                line(156, tile.name, level + '级', '0级');
                line(212, '租金', String(rentOf(tier, level)), String(rentOf(tier, 0)));
                text(p, '所有权仍归你', ix, 266, iw, 44, 26, Theme.c.greenDark, { bold: true });
                break;
            case 'CLEAR_LAND':
                line(170, tile.name, level + '级', '无主');
                text(p, '清除等级与所有权', ix, 236, iw, 52, 30, Theme.c.payRed, { bold: true });
                break;
            default:
                break;
        }
        // 防御卡说明条
        const d = mk(p, 'Defense', 24, 326, W - 48, 50);
        fillRR(gfx(d), 0, 0, W - 48, 50, 14, '#FFF1E2');
        art(d, CARD_ART[r.response], 10, 3, 44, 44);
        text(d, CARD_NAMES[r.response], 64, 0, 140, 50, 26, Theme.c.payRed, { bold: true, align: 'l' });
        text(d, '使用后本次' + (CARD_NAMES[r.attack] ?? '') + '无效', 204, 0, W - 48 - 214, 50, 22, Theme.c.navy, { align: 'l' });
        softButton(p, '不使用', 24, 386, 220, 64, () => this.answer(false), 28);
        primaryButton(p, '使用' + CARD_NAMES[r.response], 264, 386, W - 288, 64, () => this.answer(true), 30);
        text(p, '超时不使用 · 不占主动用卡机会', 0, 448, W, 22, 18, Theme.c.noteGray);
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
