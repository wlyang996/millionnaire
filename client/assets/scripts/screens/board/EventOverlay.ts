/**
 * 事件抽卡覆盖层（仅触发事件的玩家本人的界面）。依据 design/screens/10-event-card-preview.png：
 * 棋盘中央出现问号卡背（上方"触发事件"、下方"点击卡片翻开"），骰子/回合药丸被临时遮挡，投骰子按钮变暗；点击卡片直接翻开，没有独立的"抽取事件"按钮。
 * 翻牌后显示中性结果面板（事件类型 + 结果数值/文字）——事件卡"卡面"设计图没有画，卡面图案待用户补设计，这里不做任何图案发挥。
 * 动画由时间戳驱动：等待时轻微呼吸/发光，翻牌时长 Theme.anim.eventFlipMs。
 */
import { Node } from 'cc';
import { CARD_NAMES } from '../../core/Models';
import { EventDrawState, eventResultText } from '../../core/EventDraw';
import { Theme } from '../../core/Theme';
import { Button, primaryButton } from '../../ui/Buttons';
import { col, gfx, line, mk, onTap, text } from '../../ui/Kit';
import { CARD_BACK, centerNode, drawCardBack } from './EventDeck';

const CX = Theme.W / 2;
const CARD_W = 180;
const CARD_H = 250;
const CARD_CY = 587;

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

    /** 按当前状态重建（状态变化时由 BoardScreen 调用）。onCard：点击卡片；onConfirm：确认结果。 */
    build(state: EventDrawState, onCard: () => void, onConfirm: () => void): void {
        this.state = state;
        // 标题（金色，两侧装饰线）
        text(this.root, '触发事件', CX - 118, 407, 240, 54, 40, '#3A2A0A', { bold: true }); // 深色描边感，保证在草地上可读
        text(this.root, '触发事件', CX - 120, 404, 240, 54, 40, CARD_BACK.gold, { bold: true });
        const dec = gfx(mk(this.root, 'TitleDeco', 0, 404, Theme.W, 54));
        line(dec, CX - 190, 28, CX - 130, 28, CARD_BACK.gold, 3);
        line(dec, CX + 130, 28, CX + 190, 28, CARD_BACK.gold, 3);
        // 光晕 + 卡片
        this.glow = centerNode(this.root, 'CardGlow', CX, CARD_CY, CARD_W + 40, CARD_H + 40);
        this.card = centerNode(this.root, 'EventCard', CX, CARD_CY, CARD_W, CARD_H);
        this.back = centerNode(this.card, 'Back', 0, 0, CARD_W, CARD_H);
        // centerNode 以父节点左上为参照；卡片内部子节点用中心为原点，需要回到 (0,0)
        this.back.setPosition(0, 0, 0);
        drawCardBack(this.back, CARD_W, CARD_H);
        this.face = centerNode(this.card, 'Face', 0, 0, CARD_W, CARD_H);
        this.face.setPosition(0, 0, 0);
        this.drawFace(this.face, state);
        this.face.active = state.phase === 'RESULT';
        this.back.active = state.phase !== 'RESULT';
        if (state.phase === 'WAITING') onTap(this.card, onCard, false);
        // 下方：等待时提示文字；结果时"确定"；投骰子按钮变暗（被事件遮挡）
        if (state.phase === 'RESULT') {
            primaryButton(this.root, '确定', CX - 100, 736, 200, 70, onConfirm, Theme.font.lg);
        } else {
            text(this.root, state.phase === 'WAITING' ? '点击卡片翻开' : '翻牌中…', CX - 150, 716, 300, 34, Theme.font.md, Theme.c.white, { bold: true });
            const dim: Button = primaryButton(this.root, '投骰子', CX - 150, 764, 300, 84, () => undefined, Theme.font.xl);
            dim.setEnabled(false);
        }
        this.tick(Date.now());
    }

    /** 中性结果面板（卡面待用户补设计）：事件类型 + 结果文字。 */
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
        text(n, t.title, -CARD_W / 2 + 8, -CARD_H / 2 + 30, CARD_W - 16, 44, 30, Theme.c.ink, { bold: true });
        const detail = r.kind === 'CARD' && r.card ? CARD_NAMES[r.card] : t.detail;
        text(n, detail, -CARD_W / 2 + 8, -34, CARD_W - 16, 70, r.kind === 'CASH_REWARD' || r.kind === 'CASH_FINE' ? 40 : 30, r.kind === 'CASH_FINE' || r.kind === 'JAIL' ? Theme.c.redDark : Theme.c.greenDark, { bold: true });
        if (r.kind === 'CARD') text(n, '已加入手牌', -CARD_W / 2 + 8, 50, CARD_W - 16, 30, Theme.font.sm, Theme.c.inkSoft);
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
