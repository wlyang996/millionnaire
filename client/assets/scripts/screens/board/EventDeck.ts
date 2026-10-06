/**
 * 事件卡牌堆绘制（代码绘制占位，素材另行制作后统一替换）。
 * 依据 design/screens/11-event-idle-preview.png：棋盘内圈左上角三张蓝金问号卡背小幅扇形展开 + 低矮底座与"事件卡"标签；
 * design/screens/10-event-card-preview.png：抽卡状态下左上角缩成单张小卡 + "事件卡"标签。
 * 卡背配色：青蓝底、双层金边、奶油色大问号（取自设计图目测，色值待设计确认）。
 */
import { Node, UITransform } from 'cc';
import { col, fillRR, gfx, mk, text } from '../../ui/Kit';
import { art } from '../../ui/Art';

export const CARD_BACK = { blue: '#1E7FC4', blueDark: '#155E96', blueLight: '#3AA0E0', gold: '#F2C24B', goldDark: '#C9962A', cream: '#FFF1C2' };

/** 创建锚点居中的节点（旋转/缩放以中心为轴）。 */
export function centerNode(parent: Node, name: string, cx: number, cy: number, w: number, h: number): Node {
    const n = mk(parent, name, cx, cy, w, h);
    (n.getComponent(UITransform) as UITransform).setAnchorPoint(0.5, 0.5);
    return n;
}

/** 在居中节点上画问号卡背（原点=卡片中心）。 */
export function drawCardBack(n: Node, w: number, h: number, withMark = true): void {
    const g = gfx(n);
    g.clear();
    if (art(n, 'event_card_back', -w / 2, -h / 2, w, h, 'stretch')) return;
    const r = w * 0.1;
    g.fillColor = col('#00000033');
    g.roundRect(-w / 2 + 2, -h / 2 - 5, w, h, r);
    g.fill();
    g.fillColor = col(CARD_BACK.goldDark);
    g.roundRect(-w / 2, -h / 2, w, h, r);
    g.fill();
    g.fillColor = col(CARD_BACK.gold);
    g.roundRect(-w / 2 + w * 0.025, -h / 2 + w * 0.025, w * 0.95, h - w * 0.05, r * 0.85);
    g.fill();
    g.fillColor = col(CARD_BACK.blueDark);
    g.roundRect(-w / 2 + w * 0.06, -h / 2 + w * 0.06, w * 0.88, h - w * 0.12, r * 0.7);
    g.fill();
    g.fillColor = col(CARD_BACK.blue);
    g.roundRect(-w / 2 + w * 0.075, -h / 2 + w * 0.075, w * 0.85, h - w * 0.15, r * 0.6);
    g.fill();
    g.strokeColor = col(CARD_BACK.gold);
    g.lineWidth = Math.max(1.5, w * 0.012);
    g.roundRect(-w / 2 + w * 0.11, -h / 2 + w * 0.11, w * 0.78, h - w * 0.22, r * 0.5);
    g.stroke();
    if (withMark) {
        const l = text(n, '?', -w / 2, -h / 2, w, h, Math.round(w * 0.62), CARD_BACK.cream, { bold: true });
        l.lineHeight = Math.round(w * 0.7);
    }
}

/**
 * 事件牌堆。(x,y,w,h) 为世界坐标（左上角，y 向下）里的占位框；mode='fan' 为默认三张扇形，'single' 为抽卡状态的单张小卡。
 */
/** @param aspect 父节点显示时的纵横拉伸比（棋盘世界纵向有拉伸），美术图按它抵消、保持原比例 */
export function drawEventDeck(parent: Node, x: number, y: number, w: number, h: number, mode: 'fan' | 'single', aspect = 1): Node {
    const root = mk(parent, 'EventDeck', x, y, w, h);
    if (mode === 'fan' && art(root, 'event_card_fan', 0, 0, w, h - 20, 'contain', false, { aspect })) {
        // 设计稿 01：扇形牌堆下方的木牌"事件卡"
        const pw = 104;
        const ph = 30;
        const plaque = gfx(mk(root, 'Plaque', (w - pw) / 2, h - ph, pw, ph));
        fillRR(plaque, 0, 0, pw, ph, 10, '#8B5A2B');
        fillRR(plaque, 2, 2, pw - 4, ph - 5, 8, '#E9C792');
        text(root, '事件卡', (w - pw) / 2, h - ph, pw, ph - 2, 20, '#5A3410', { bold: true });
        return root;
    }
    const labelH = Math.min(h * 0.2, 28);
    const cardH = h - labelH * 0.7;
    const cw = Math.min(w * 0.46, cardH / 1.42);
    const ch = cw * 1.42;
    const cx = w / 2;
    const cy = ch / 2 + 2;
    if (mode === 'fan') {
        const side = cw * 0.55;
        const left = centerNode(root, 'DeckL', cx - side, cy + ch * 0.05, cw, ch);
        drawCardBack(left, cw, ch);
        left.angle = 14;
        const right = centerNode(root, 'DeckR', cx + side, cy + ch * 0.05, cw, ch);
        drawCardBack(right, cw, ch);
        right.angle = -14;
        const mid = centerNode(root, 'DeckM', cx, cy, cw, ch);
        drawCardBack(mid, cw, ch);
    } else {
        const small = cw * 0.62;
        const sh = small * 1.42;
        const c = centerNode(root, 'DeckOne', small / 2 + 4, sh / 2 + 2, small, sh);
        drawCardBack(c, small, sh);
    }
    // 低矮木底座 + "事件卡"标签
    const bw = mode === 'fan' ? w * 0.56 : cw * 0.8;
    const bx = mode === 'fan' ? (w - bw) / 2 : 4;
    const by = mode === 'fan' ? ch + 2 - labelH * 0.3 : cw * 0.62 * 1.42 - 4;
    const base = mk(root, 'DeckBase', bx, by, bw, labelH);
    fillRR(gfx(base), 0, 2, bw, labelH, labelH / 2, '#00000033');
    fillRR(gfx(base), 0, 0, bw, labelH, labelH / 2, '#A4723C');
    fillRR(gfx(base), 2, 2, bw - 4, labelH - 4, labelH / 2 - 2, '#C28E52');
    text(base, '事件卡', 0, 0, bw, labelH, Math.max(11, Math.round(labelH * 0.55)), '#FFF3C4', { bold: true });
    return root;
}
