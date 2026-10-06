/**
 * 底部手牌栏（v6）：单排不换行；默认视口容纳 5 个完整项，超过 5 个横向滑动（右侧渐隐 + 箭头 + 底部滑动指示）；
 * 同类卡合并并带数量角标；没有的卡不补空槽；右上角总量按真实总张数（上限 6，如 6/6）。
 * 滑动与点击区分：位移超过约 10px 判滑动（HScroll 与 Kit.onTap 同阈值）。
 */
import { Node } from 'cc';
import { CardType, Card, CARD_DESC, CARD_NAMES } from '../../core/Models';
import { groupCards, MAX_HAND } from '../../core/Rules';
import { Theme } from '../../core/Theme';
import { ConfirmPopup } from '../../popups/ConfirmPopup';
import { ctx } from '../../ui/Ctx';
import { HScroll } from '../../ui/HScroll';
import { drawCardIcon } from '../../ui/Icons';
import { fillCircle, fillRR, gfx, line, mk, onTap, strokeRR, text } from '../../ui/Kit';
import { Toast } from '../../ui/Toast';
import { roundedPanel } from '../../ui/Widgets';

export function groupHand(hand: Card[]): { type: CardType; count: number }[] {
    return groupCards(hand.map((c) => c.type)) as { type: CardType; count: number }[];
}

const CARD_BG: Record<string, string> = {
    ROADBLOCK: '#FFE3E3', RENT_WAIVER: '#DDEFFD', BUILD: '#DDF6E3', DOWNGRADE: '#FFEBD2', FIXED_MOVE: '#FFE3EC', JAIL_RELEASE: '#FFF3C4',
    AUCTION: '#F3E7D4', TRADE: '#DDF6E3', REFUSE_PURCHASE: '#FDE0E0', HOUSE_PROTECTION: '#DDF6E3', QUERY: '#DDEFFD',
    FORCED_PURCHASE: '#FFF3C4', DEMOLISH: '#E7EAEE', CLEAR_LAND: '#E4F5D6',
};

export function drawHandBar(parent: Node, x: number, y: number, w: number, h: number, hand: Card[], canUse: boolean): Node {
    const bar = roundedPanel(parent, x, y, w, h, { r: 26, fill: '#FFFFFFEE' });
    const groups = groupHand(hand);
    const total = hand.length;
    const B = Theme.board;
    const itemW = B.handItemW;
    const gap = B.handGap;
    const ch = h - 52; // 卡高
    text(bar, total + '/' + MAX_HAND, 12 + B.handViewW, 4, w - 18 - B.handViewW, 26, 18, total > MAX_HAND ? Theme.c.red : Theme.c.inkSoft, { bold: true });
    if (groups.length === 0) {
        text(bar, '暂无道具（事件格可获得，最多持 ' + MAX_HAND + ' 张）', 0, 0, w, h, Theme.font.sm, Theme.c.inkSoft);
        return bar;
    }
    const sc = new HScroll(bar, 12, 30, B.handViewW, ch + 4);
    groups.forEach((gp, i) => {
        const card = mk(sc.content, 'Card:' + gp.type, i * (itemW + gap), 2, itemW, ch);
        const g = gfx(card);
        fillRR(g, 0, 0, itemW, ch, 16, CARD_BG[gp.type] ?? Theme.c.ivory);
        strokeRR(g, 0, 0, itemW, ch, 16, '#00000018', 2);
        drawCardIcon(g, gp.type, itemW / 2, 38, 58);
        text(card, CARD_NAMES[gp.type], 2, ch - 36, itemW - 4, 30, 19, Theme.c.ink, { bold: true });
        if (gp.count > 1) {
            fillCircle(g, itemW - 14, 14, 13, Theme.c.orange);
            text(card, String(gp.count), itemW - 27, 1, 26, 26, 18, Theme.c.white, { bold: true });
        }
        onTap(card, () => ctx.popups.open(new ConfirmPopup({
            title: CARD_NAMES[gp.type],
            message: CARD_DESC[gp.type] + (canUse ? '' : '\n（现在不是你的主动用卡时机）'),
            confirmText: '使用',
            onConfirm: () => (canUse ? Toast.show('已使用「' + CARD_NAMES[gp.type] + '」（演示，未结算）') : Toast.show('只能在自己的回合使用')),
        }, 'card')), false);
    });
    const cw = groups.length * itemW + (groups.length - 1) * gap;
    sc.setContentWidth(cw);
    // 右侧列（设计图 01-board）：总量 n/6 在上，箭头居中（还有内容时），滑动指示在下
    const col0 = 12 + B.handViewW;
    const colW = w - col0 - 6;
    const overlay = mk(bar, 'HandArrow', col0, 30, colW, ch + 4);
    const track = mk(bar, 'HandTrack', col0 + (colW - 34) / 2, h - 18, 34, 6);
    const paint = () => {
        const g = gfx(overlay);
        g.clear();
        const g2 = gfx(track);
        g2.clear();
        if (sc.maxOffset <= 0) return;
        fillRR(g2, 0, 0, 34, 6, 3, '#00000018');
        const frac = B.handViewW / cw;
        fillRR(g2, (34 - 34 * frac) * (sc.offset / sc.maxOffset), 0, 34 * frac, 6, 3, Theme.c.blue);
        // 左右箭头：右侧还有内容画 ›，已到最右画 ‹ 提示可回滑
        const atEnd = sc.offset >= sc.maxOffset - 1;
        const cx = colW / 2 + (atEnd ? 4 : -4);
        const cy = (ch + 4) / 2;
        line(g, cx - (atEnd ? -6 : 6), cy - 14, cx + (atEnd ? -6 : 6), cy, Theme.c.inkSoft, 5);
        line(g, cx + (atEnd ? -6 : 6), cy, cx - (atEnd ? -6 : 6), cy + 14, Theme.c.inkSoft, 5);
    };
    sc.onScroll = paint;
    paint();
    return bar;
}
