/**
 * 中央事件抽卡覆盖层（设计稿 10 / 12）：棋盘内圈压暗，金色"触发事件"，中央一张发光卡背，下方"点击卡片翻开"
 * （其他观看者显示"等待某某翻开"，不可点）；翻牌后显示卡面：蓝色题头写类别，中间插画，下方结果与说明。
 * 没有提示面板、没有类别说明行。结果停留约 3 秒后自动收起（无关闭按钮，点卡片可提前收起）。
 * 动画由时间戳驱动：等待时轻微呼吸/发光，翻牌时长 Theme.anim.eventFlipMs。
 */
import { Node } from 'cc';
import { CARD_NAMES } from '../../core/Models';
import { EventDrawState, eventStory } from '../../core/EventDraw';
import { Theme } from '../../core/Theme';
import { col, fillCircle, fillRR, gfx, line, mk, onTap, text } from '../../ui/Kit';
import { inlineRow, Seg } from '../../popups/Common';
import { CARD_BACK, centerNode, drawCardBack } from './EventDeck';
import { art } from '../../ui/Art';

const CX = Theme.W / 2;
/** 设计稿 10：卡片 161×237，中心 (360, 620)；标题中心 y≈467；提示中心 y≈766 */
const CARD_W = 161;
const CARD_H = 237;
const CARD_CY = 620;

/** 设计稿 12 的卡面文字：类别、结果（红色数字）、说明。 */
const FACE: Record<string, { title: string; note: string }> = {
    CASH_REWARD: { title: '奖励', note: '系统奖励' },
    CASH_FINE: { title: '罚款', note: '金额不足按欠款流程处理' },
    CARD: { title: '道具', note: '手牌满时进入弃牌' },
    MOVE: { title: '位移', note: '按落点规则结算·不连抽事件' },
    JAIL: { title: '入狱', note: '本回合结束' },
    BUILD: { title: '加盖', note: '自己的房产随机一处，满级则无' },
    DOWNGRADE: { title: '降级', note: '自己的房产随机一处，无房则无' },
    TO_STATION: { title: '车站', note: '前进到车站·经过起点领奖励' },
    TO_START: { title: '起点', note: '前进到起点·领起点奖励' },
};

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

    /**
     * 状态变化时重建；interactive 仅允许触发者本人点击。
     * @param dim 棋盘内圈（屏幕坐标），设计稿 10 中这一块压暗
     */
    build(state: EventDrawState, onCard: () => void, interactive: boolean, actorName: string, onClose?: () => void,
        dim?: { x: number; y: number; w: number; h: number }): void {
        this.state = state;
        if (dim) fillRR(gfx(mk(this.root, 'Dim', dim.x, dim.y, dim.w, dim.h)), 0, 0, dim.w, dim.h, 12, '#0A142873');
        // 金色标题，两侧装饰线
        text(this.root, '触发事件', CX - 118, 443, 240, 54, 42, '#3A2A0A', { bold: true });
        text(this.root, '触发事件', CX - 120, 440, 240, 54, 42, CARD_BACK.gold, { bold: true });
        const dec = gfx(mk(this.root, 'TitleDeco', 0, 440, Theme.W, 54));
        line(dec, CX - 196, 28, CX - 132, 28, CARD_BACK.gold, 3);
        line(dec, CX + 132, 28, CX + 196, 28, CARD_BACK.gold, 3);
        fillCircle(dec, CX - 200, 28, 5, CARD_BACK.gold);
        fillCircle(dec, CX + 200, 28, 5, CARD_BACK.gold);
        // 光晕 + 卡片
        this.glow = centerNode(this.root, 'CardGlow', CX, CARD_CY, CARD_W + 40, CARD_H + 40);
        this.card = centerNode(this.root, 'EventCard', CX, CARD_CY, CARD_W, CARD_H);
        this.back = centerNode(this.card, 'Back', 0, 0, CARD_W, CARD_H);
        // centerNode 以父节点左上为参照；卡片内部子节点用中心为原点，需要回到 (0,0)
        this.back.setPosition(0, 0, 0);
        art(this.back, 'event_card_highlight', -CARD_W / 2 - 14, -CARD_H / 2 - 14, CARD_W + 28, CARD_H + 28, 'stretch');
        if (!art(this.back, 'event_card_back', -CARD_W / 2, -CARD_H / 2, CARD_W, CARD_H, 'stretch'))
            drawCardBack(this.back, CARD_W, CARD_H);
        this.face = centerNode(this.card, 'Face', 0, 0, CARD_W, CARD_H);
        this.face.setPosition(0, 0, 0);
        this.drawFace(this.face, state);
        this.face.active = state.phase === 'RESULT';
        this.back.active = state.phase !== 'RESULT';
        const captionY = CARD_CY + CARD_H / 2 + 14;
        if (state.phase === 'WAITING') {
            if (interactive) onTap(this.card, onCard, false);
            this.caption(interactive ? '点击卡片翻开' : '等待' + actorName + '翻开', captionY);
        }
        // 结果停留几秒后自动收起（用户：抽完应自动关闭）；不显示 × 和"点击关闭"，点卡片可提前收起
        if (state.phase === 'RESULT' && onClose) onTap(this.card, onClose, false);
        this.tick(Date.now());
    }

    private caption(s: string, y: number): void {
        text(this.root, s, CX - 179, y + 2, 360, 36, 26, '#00000099', { bold: true });
        text(this.root, s, CX - 180, y, 360, 36, 26, Theme.c.white, { bold: true });
    }

    /** 卡面（设计稿 12）：卡面图的蓝色题头写类别，下方乳白条写结果（数字红色），底部一行说明。 */
    private drawFace(n: Node, state: EventDrawState): void {
        const g = gfx(n);
        g.clear();
        g.fillColor = col(CARD_BACK.goldDark);
        g.roundRect(-CARD_W / 2, -CARD_H / 2, CARD_W, CARD_H, 14);
        g.fill();
        g.fillColor = col('#FFF8E6');
        g.roundRect(-CARD_W / 2 + 4, -CARD_H / 2 + 4, CARD_W - 8, CARD_H - 8, 12);
        g.fill();
        const r = state.result;
        if (!r) return;
        const key = {
            CASH_REWARD: 'event_reward', CASH_FINE: 'event_fine', CARD: 'event_tool', MOVE: 'event_move', JAIL: 'event_jail',
            BUILD: 'event_tool', DOWNGRADE: 'event_fine', TO_STATION: 'event_move', TO_START: 'event_move',
        }[r.kind];
        if (art(n, key, -CARD_W / 2, -CARD_H / 2, CARD_W, CARD_H, 'stretch')) g.clear();
        const f = FACE[r.kind];
        text(n, f.title, -CARD_W / 2, -CARD_H / 2 + 8, CARD_W, 34, 26, Theme.c.white, { bold: true });
        const red = Theme.c.payRed;
        const navy = Theme.c.navy;
        const segs: Seg[] = r.kind === 'CASH_REWARD' ? [{ t: '+' + r.amount, size: 26, color: red }, { t: '金币', size: 18, color: navy }]
            : r.kind === 'CASH_FINE' ? [{ t: '-' + r.amount, size: 26, color: red }, { t: '金币', size: 18, color: navy }]
                : r.kind === 'CARD' ? [{ t: '获得' + (r.card ? CARD_NAMES[r.card] : '道具') + '×1', size: 18, color: navy }]
                    : r.kind === 'MOVE' ? [{ t: r.steps > 0 ? '前进' : '后退', size: 20, color: navy }, { t: String(Math.abs(r.steps)), size: 24, color: red }, { t: '格', size: 20, color: navy }]
                        : r.kind === 'JAIL' ? [{ t: '前往监狱', size: 20, color: navy }]
                            : [{ t: ({ BUILD: '免费加盖一级', DOWNGRADE: '房屋降一级', TO_STATION: '前往车站', TO_START: '回到起点' } as Record<string, string>)[r.kind] ?? '', size: 18, color: navy }];
        const story = eventStory(r);
        if (story) {
            // 奖励 / 罚款：缘由（两行以内）+ 金额，例如"随地吐痰，被罚款 -200"
            text(n, story, -CARD_W / 2 + 8, CARD_H / 2 - 78, CARD_W - 16, 40, 13, navy, { bold: true, wrap: true, lineHeight: 18 });
            inlineRow(n, 0, CARD_H / 2 - 38, 32, segs, 2);
            return;
        }
        inlineRow(n, 0, CARD_H / 2 - 66, 34, segs, 2);
        text(n, f.note, -CARD_W / 2 + 6, CARD_H / 2 - 30, CARD_W - 12, 22, 12, Theme.c.noteGray, { bold: true });
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
