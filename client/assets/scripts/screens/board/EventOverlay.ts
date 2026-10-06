/**
 * 中央事件抽卡覆盖层：所有观察者同步观看；仅触发者可点击卡背。
 * 使用已有五类事件卡面；结果展示后由 BoardScreen 自动收起，没有确认/投骰按钮。
 * 动画由时间戳驱动：等待时轻微呼吸/发光，翻牌时长 Theme.anim.eventFlipMs。
 */
import { Node } from 'cc';
import { CARD_NAMES } from '../../core/Models';
import { EventDrawState, eventResultText } from '../../core/EventDraw';
import { Theme } from '../../core/Theme';
import { col, fillCircle, gfx, line, mk, onTap, text } from '../../ui/Kit';
import { CARD_BACK, centerNode, drawCardBack } from './EventDeck';
import { art } from '../../ui/Art';

const CX = Theme.W / 2;
const CARD_W = 330;
const CARD_H = 360;
const CARD_CY = 650;

export class EventOverlay {
    readonly root: Node;
    private card: Node | null = null;
    private glow: Node | null = null;
    private back: Node | null = null;
    private face: Node | null = null;
    private state: EventDrawState | null = null;

    constructor(parent: Node) {
        this.root = mk(parent, 'EventOverlay', 0, 0, Theme.W, Theme.H);
    }

    /** 状态变化时重建；interactive 仅允许触发者本人点击。 */
    build(state: EventDrawState, onCard: () => void, interactive: boolean, actorName: string, onClose?: () => void): void {
        this.state = state;
        // 标题（金色，两侧装饰线）
        text(this.root, '触发事件', CX - 118, 347, 240, 54, 40, '#3A2A0A', { bold: true });
        text(this.root, '触发事件', CX - 120, 344, 240, 54, 40, CARD_BACK.gold, { bold: true });
        const dec = gfx(mk(this.root, 'TitleDeco', 0, 344, Theme.W, 54));
        line(dec, CX - 190, 28, CX - 130, 28, CARD_BACK.gold, 3);
        line(dec, CX + 130, 28, CX + 190, 28, CARD_BACK.gold, 3);
        if (state.phase !== 'RESULT') {
            art(this.root, 'info_asset_panel', CX - 220, 392, 440, 492, 'panel');
            text(this.root, interactive ? '点击卡背抽取' : actorName + ' 正在抽卡', CX - 200, 410, 400, 52, 32, Theme.c.ink, { bold: true });
            text(this.root, '奖励 · 罚款 · 道具 · 位移 · 入狱', CX - 208, 832, 416, 36, 22, Theme.c.ink, { bold: true });
        }
        // 光晕 + 卡片
        this.glow = centerNode(this.root, 'CardGlow', CX, CARD_CY, CARD_W + 40, CARD_H + 40);
        this.card = centerNode(this.root, 'EventCard', CX, CARD_CY, CARD_W, CARD_H);
        this.back = centerNode(this.card, 'Back', 0, 0, CARD_W, CARD_H);
        // centerNode 以父节点左上为参照；卡片内部子节点用中心为原点，需要回到 (0,0)
        this.back.setPosition(0, 0, 0);
        if (!art(this.back, 'event_card_fan', -CARD_W / 2, -CARD_H / 2, CARD_W, CARD_H))
            drawCardBack(this.back, CARD_W, CARD_H);
        this.face = centerNode(this.card, 'Face', 0, 0, CARD_W, CARD_H);
        this.face.setPosition(0, 0, 0);
        this.drawFace(this.face, state);
        this.face.active = state.phase === 'RESULT';
        this.back.active = state.phase !== 'RESULT';
        if (state.phase === 'WAITING' && interactive) onTap(this.card, onCard, false);
        if (state.phase === 'RESULT' && onClose) {
            // 结果默认展示几秒后自动收起；也可以点卡片或右上角 × 立即关闭
            onTap(this.card, onClose, false);
            const x = mk(this.root, 'CloseResult', CX + CARD_W / 2 - 30, CARD_CY - CARD_H / 2 - 30, 60, 60);
            const xg = gfx(x);
            fillCircle(xg, 30, 30, 26, '#2D3B4AE6');
            line(xg, 20, 20, 40, 40, Theme.c.white, 5);
            line(xg, 40, 20, 20, 40, Theme.c.white, 5);
            onTap(x, onClose);
            text(this.root, '点击卡片关闭', CX - 150, CARD_CY + CARD_H / 2 + 14, 300, 40, 24, Theme.c.ink, { bold: true });
        }
        // 结果无需操作；等待时显示本人点击提示或他人抽卡提示。
        this.tick(Date.now());
    }

    /** 已有事件卡面加动态结果文字。 */
    private drawFace(n: Node, state: EventDrawState): void {
        const g = gfx(n);
        g.clear();
        g.fillColor = col(CARD_BACK.goldDark);
        g.roundRect(-CARD_W / 2, -CARD_H / 2, CARD_W, CARD_H, 18);
        g.fill();
        g.fillColor = col('#FFF8E6');
        g.roundRect(-CARD_W / 2 + 5, -CARD_H / 2 + 5, CARD_W - 10, CARD_H - 10, 14);
        g.fill();
        const r = state.result;
        if (!r) return;
        const t = eventResultText(r);
        const key = { CASH_REWARD: 'event_reward', CASH_FINE: 'event_fine', CARD: 'event_tool', MOVE: 'event_move', JAIL: 'event_jail' }[r.kind];
        if (art(n, key, -CARD_W / 2, -CARD_H / 2, CARD_W, CARD_H, 'stretch')) g.clear();
        text(n, t.title, -CARD_W / 2 + 10, -CARD_H / 2 + 14, CARD_W - 20, 32, 22, Theme.c.white, { bold: true });
        const detail = r.kind === 'CARD' && r.card ? CARD_NAMES[r.card] : t.detail;
        text(n, detail, -CARD_W / 2 + 12, CARD_H / 2 - 58, CARD_W - 24, 44, 24,
            r.kind === 'CASH_FINE' || r.kind === 'JAIL' ? Theme.c.redDark : Theme.c.greenDark, { bold: true });
    }

    /** 每帧：等待时呼吸/发光；翻牌时水平翻转（先背面缩到 0，再正面展开）。 */
    tick(now: number): void {
        const s = this.state;
        if (!s || !this.card || !this.glow || !this.back || !this.face) return;
        if (s.phase === 'WAITING') {
            const k = Math.sin(((now - s.since) / Theme.anim.eventBreathMs) * Math.PI * 2);
            const sc = 1 + 0.03 * k;
            this.card.setScale(sc, sc, 1);
            this.paintGlow(0.35 + 0.25 * (k + 1) / 2);
        } else if (s.phase === 'FLIPPING' && !s.result) {
            // 已点卡、等待服务端结果：卡背轻快抖动，不翻到空白正面
            const k = Math.sin(((now - s.since) / 260) * Math.PI * 2);
            this.card.setScale(1 + 0.05 * k, 1 - 0.03 * k, 1);
            this.back.active = true;
            this.face.active = false;
            this.paintGlow(0.7);
        } else if (s.phase === 'FLIPPING') {
            const k = Math.min(1, (now - s.since) / Theme.anim.eventFlipMs);
            this.card.setScale(Math.max(0.02, Math.abs(Math.cos(Math.PI * k))), 1 + 0.04 * Math.sin(Math.PI * k), 1);
            this.back.active = k < 0.5;
            this.face.active = k >= 0.5;
            this.paintGlow(0.6);
        } else {
            this.card.setScale(1, 1, 1);
            this.paintGlow(0.3);
        }
    }

    private lastGlow = -1;
    private paintGlow(a: number): void {
        if (!this.glow || Math.abs(a - this.lastGlow) < 0.02) return;
        this.lastGlow = a;
        const g = gfx(this.glow);
        g.clear();
        for (let i = 0; i < 3; i++) {
            g.fillColor = col('#FFD646', a * (0.35 - i * 0.1));
            const e = 6 + i * 8;
            g.roundRect(-CARD_W / 2 - e, -CARD_H / 2 - e, CARD_W + 2 * e, CARD_H + 2 * e, 22 + e);
            g.fill();
        }
    }
}
