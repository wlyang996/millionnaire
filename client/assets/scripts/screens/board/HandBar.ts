/**
 * 底部手牌栏（v6）：单排不换行；默认视口容纳 5 个完整项，超过 5 个横向滑动（右侧渐隐 + 箭头 + 底部滑动指示）；
 * 同类卡合并并带数量角标；没有的卡不补空槽；右上角总量按真实总张数（上限 6，如 6/6）。
 * 滑动与点击区分：位移超过约 10px 判滑动（HScroll 与 Kit.onTap 同阈值）。
 */
import { Node } from 'cc';
import { CardType, Card, CARD_NAMES } from '../../core/Models';
import { groupCards, MAX_HAND } from '../../core/Rules';
import { Theme } from '../../core/Theme';
import { CardDetailPage } from '../../popups/CardDetailPage';
import { ctx } from '../../ui/Ctx';
import { HScroll } from '../../ui/HScroll';
import { drawCardIcon } from '../../ui/Icons';
import { fillCircle, fillRR, gfx, line, mk, onTap, text } from '../../ui/Kit';
import { roundedPanel } from '../../ui/Widgets';
import { art, CARD_ART } from '../../ui/Art';

export function groupHand(hand: Card[]): { type: CardType; count: number }[] {
    return groupCards(hand.map((c) => c.type)) as { type: CardType; count: number }[];
}

/** 设计稿 09：道具格浅色底 + 图标 + 名称。 */
const CARD_BG: Record<string, string> = {
    ROADBLOCK: '#EDE3FA', RENT_WAIVER: '#DCEBFB', BUILD: '#DFF3D9', DOWNGRADE: '#FFEBD2', FIXED_MOVE: '#FCE4EA', JAIL_RELEASE: '#DDEEFB',
    AUCTION: '#F8EEDC', TRADE: '#DFF3D9', REFUSE_PURCHASE: '#FDE0E0', HOUSE_PROTECTION: '#DFF3D9', QUERY: '#FCE4EA',
    FORCED_PURCHASE: '#FFF3C4', DEMOLISH: '#E7EAEE', CLEAR_LAND: '#E4F5D6',
};

/** 设计稿 09 里的道具图标（有独立图标的用图标，其余用道具卡图）。 */
const CARD_ICON: Record<string, string> = {
    ROADBLOCK: 'icon_roadblock', RENT_WAIVER: 'icon_shield', BUILD: 'icon_house', FIXED_MOVE: 'icon_move',
    JAIL_RELEASE: 'icon_key', AUCTION: 'icon_auction', QUERY: 'icon_query',
};

/**
 * 手牌栏（设计稿 01 / 09）：白色圆角栏；左侧 5 项视口（每项 104×76：浅色底、图标、名称，同类合并带数量角标），
 * 右侧独立栏：总量 n/6、横滑箭头、位置条。
 */
export function drawHandBar(parent: Node, x: number, y: number, w: number, h: number, hand: Card[], canUse: boolean): Node {
    const bar = roundedPanel(parent, x, y, w, h, { r: 22, fill: '#FFFFFFF2' });
    const groups = groupHand(hand);
    const total = hand.length;
    const B = Theme.board;
    const itemW = B.handItemW;
    const gap = B.handGap;
    const ih = h - 16;
    const col0 = 12 + B.handViewW + 6;
    const colW = w - col0 - 8;
    text(bar, total + '/' + MAX_HAND, col0, 6, colW, 26, 20, total > MAX_HAND ? Theme.c.red : Theme.c.navy, { bold: true });
    if (groups.length === 0) {
        text(bar, '暂无道具（事件格可获得，最多持 ' + MAX_HAND + ' 张）', 12, 0, B.handViewW, h, Theme.font.sm, Theme.c.inkSoft);
        return bar;
    }
    const sc = new HScroll(bar, 12, 8, B.handViewW, ih);
    groups.forEach((gp, i) => {
        const card = mk(sc.content, 'Card:' + gp.type, i * (itemW + gap), 0, itemW, ih);
        const g = gfx(card);
        fillRR(g, 0, 0, itemW, ih, 14, CARD_BG[gp.type] ?? Theme.c.ivory);
        const isz = Math.min(itemW - 30, ih - 28);
        if (!art(card, CARD_ICON[gp.type] ?? CARD_ART[gp.type], (itemW - isz) / 2, 4, isz, isz)) {
            drawCardIcon(g, gp.type, itemW / 2, 4 + isz / 2, isz);
        }
        text(card, CARD_NAMES[gp.type], 2, ih - 26, itemW - 4, 24, 19, Theme.c.navy, { bold: true });
        if (gp.count > 1) {
            fillCircle(g, itemW - 13, 13, 12, Theme.c.orange);
            text(card, String(gp.count), itemW - 25, 1, 24, 24, 17, Theme.c.white, { bold: true });
        }
        onTap(card, () => ctx.popups.open(new CardDetailPage(gp.type, canUse)), false);
    });
    const cw = groups.length * itemW + (groups.length - 1) * gap;
    sc.setContentWidth(cw);
    // 右侧栏：箭头（还有内容时 ›，到最右 ‹）+ 位置条
    const overlay = mk(bar, 'HandArrow', col0, 30, colW, 34);
    const track = mk(bar, 'HandTrack', col0 + (colW - 34) / 2, h - 16, 34, 6);
    const paint = () => {
        const g = gfx(overlay);
        g.clear();
        const g2 = gfx(track);
        g2.clear();
        if (sc.maxOffset <= 0) return;
        fillRR(g2, 0, 0, 34, 6, 3, '#00000018');
        const frac = B.handViewW / cw;
        fillRR(g2, (34 - 34 * frac) * (sc.offset / sc.maxOffset), 0, 34 * frac, 6, 3, Theme.c.blue);
        const atEnd = sc.offset >= sc.maxOffset - 1;
        const cx = colW / 2 + (atEnd ? 4 : -4);
        const cy = 17;
        line(g, cx - (atEnd ? -6 : 6), cy - 12, cx + (atEnd ? -6 : 6), cy, '#9AA5B8', 5);
        line(g, cx + (atEnd ? -6 : 6), cy, cx - (atEnd ? -6 : 6), cy + 12, '#9AA5B8', 5);
    };
    sc.onScroll = paint;
    paint();
    return bar;
}
