/**
 * 棋盘上方（玩家条下面）的非阻断横幅（设计稿 28 第三张、29）：
 * - 交易等待：卖家与旁观者看到"糖糖 → 可可 · 青麦路 · 报价 500 · 等待可可答复 12秒"（买家看到的是 04 号稿交易确认弹窗）；
 * - 排队：自己提交的拍卖卡 / 交易卡申请尚未开始时，"拍卖申请已提交 · 将在当前操作结束后开始"；
 * - 结果卡：拍卖成交 / 流拍、交易成交 / 被拒绝 / 超时，显示几秒后消失。
 * 只画在棋盘页根节点上，不拦截棋盘操作；倒计时文字由 tick 每帧刷新。
 */
import { Label, Node } from 'cc';
import { Theme } from '../../core/Theme';
import { art, CARD_ART } from '../../ui/Art';
import { ctx } from '../../ui/Ctx';
import { fillCircle, fillRR, gfx, line, mk, setText, strokeCircle, text } from '../../ui/Kit';
import { avatar } from '../../ui/Widgets';

const X = 20;
const W = 680;

export interface FlowBanners {
    /** 每帧：刷新倒计时；结果卡到时返回 true（需要重绘）。 */
    tick(now: number): boolean;
}

export function drawFlowBanners(root: Node, y0: number): FlowBanners {
    const st = ctx.store;
    const g = st.game;
    let y = y0;
    let countdown: { label: Label; deadline: number; buyer: string } | null = null;
    const name = (id: string) => (id === st.myId ? '你' : st.player(id)?.nickname ?? '玩家');
    const shadowBox = (h: number, fill = '#FFFDF7F2'): Node => {
        const n = mk(root, 'FlowBanner', X, y, W, h);
        const bg = gfx(n);
        fillRR(bg, 0, 4, W, h, 22, '#00000022');
        fillRR(bg, 0, 0, W, h, 22, fill);
        y += h + 10;
        return n;
    };

    // 交易等待（卖家 / 旁观者）
    const tr = g?.trade;
    if (tr && tr.buyer !== st.myId) {
        const n = shadowBox(88);
        const seller = st.player(tr.seller);
        const buyer = st.player(tr.buyer);
        avatar(n, 12, 14, 60, seller?.avatar ?? 0, seller?.nickname ?? '');
        text(n, name(tr.seller), 76, 0, 76, 88, 24, Theme.c.navy, { bold: true, align: 'l' });
        text(n, '→', 146, 0, 30, 88, 28, Theme.c.navy, { bold: true });
        avatar(n, 178, 14, 60, buyer?.avatar ?? 0, buyer?.nickname ?? '');
        text(n, name(tr.buyer), 242, 0, 76, 88, 24, Theme.c.navy, { bold: true, align: 'l' });
        art(n, 'icon_house', 318, 26, 36, 36);
        text(n, st.tile(tr.tile).name, 358, 8, 150, 38, 24, Theme.c.navy, { bold: true, align: 'l' });
        text(n, '报价 ' + tr.price, 358, 44, 150, 36, 22, '#2F86E8', { bold: true, align: 'l' });
        const w = g!.windows.find((x) => x.windowId === tr.windowId);
        const cl = gfx(mk(n, 'Clock', 506, 30, 28, 28));
        fillCircle(cl, 14, 14, 14, Theme.c.payRed);
        line(cl, 14, 14, 14, 6, Theme.c.white, 3);
        line(cl, 14, 14, 20, 14, Theme.c.white, 3);
        const label = text(n, '', 540, 0, W - 548, 88, 22, Theme.c.payRed, { bold: true, align: 'l', wrap: true, lineHeight: 30 });
        if (w) countdown = { label, deadline: w.deadline, buyer: name(tr.buyer) };
        else setText(label, '等待' + name(tr.buyer) + '答复');
    }

    // 排队横幅（自己的申请尚未开始）
    const req = st.myRequest;
    if (req) {
        const n = shadowBox(96);
        art(n, req.kind === 'AUCTION' ? 'icon_auction' : CARD_ART.TRADE, 18, 12, 72, 72);
        text(n, req.kind === 'AUCTION' ? '拍卖申请已提交' : '交易申请已提交', 106, 10, W - 120, 44, 30, Theme.c.navy, { bold: true, align: 'l' });
        text(n, req.kind === 'AUCTION' ? '将在当前操作结束后开始' : '等待安全结算边界', 106, 52, W - 120, 34, 22, Theme.c.noteGray, { align: 'l' });
    }

    // 结果卡
    const r = st.flowResult;
    if (r && r.until > Date.now()) {
        const trade = r.kind.indexOf('TRADE') === 0;
        const ok = r.kind === 'AUCTION_SOLD' || r.kind === 'TRADE_DONE';
        const timeout = r.kind === 'TRADE_TIMEOUT';
        const n = shadowBox(timeout ? 132 : 112);
        const pill = ({ AUCTION_SOLD: '拍卖成交', AUCTION_PASSED: '流拍', TRADE_DONE: '交易成交', TRADE_DECLINED: '交易被拒绝', TRADE_TIMEOUT: '答复超时' })[r.kind];
        const pw = pill.length * 24 + 32;
        fillRR(gfx(mk(n, 'Pill', 16, 10, pw, 34)), 0, 0, pw, 34, 17, ok ? '#FFE9A8' : r.kind === 'AUCTION_PASSED' ? '#FFF1E2' : '#FDE2E2');
        text(n, pill, 16, 10, pw, 34, 20, Theme.c.navy, { bold: true });
        const tile = st.tile(r.tile).name;
        const cy = 50;
        if (r.kind === 'AUCTION_SOLD') {
            const wn = st.player(r.a);
            avatar(n, 24, cy, 52, wn?.avatar ?? 0, wn?.nickname ?? '');
            text(n, name(r.a) + ' 以 ' + r.price + ' 拍下 ' + tile, 92, cy, W - 180, 52, 26, Theme.c.navy, { bold: true, align: 'l' });
        } else if (r.kind === 'AUCTION_PASSED') {
            art(n, 'icon_auction', 24, cy, 52, 52);
            text(n, tile + ' 无人出价 · 本次流拍', 92, cy, W - 120, 52, 26, Theme.c.navy, { bold: true, align: 'l' });
        } else if (r.kind === 'TRADE_DONE') {
            const se = st.player(r.a);
            const bu = st.player(r.b);
            avatar(n, 24, cy, 48, se?.avatar ?? 0, se?.nickname ?? '');
            text(n, '→', 74, cy, 30, 48, 24, Theme.c.navy, { bold: true });
            avatar(n, 104, cy, 48, bu?.avatar ?? 0, bu?.nickname ?? '');
            text(n, name(r.a) + ' 以 ' + r.price + ' 把' + tile + '卖给' + name(r.b) + ' · 交易成交', 164, cy, W - 244, 48, 24, Theme.c.navy,
                { bold: true, align: 'l' });
        } else {
            const ic = gfx(mk(n, 'Icon', 24, cy, 48, 48));
            fillCircle(ic, 24, 24, 24, '#6B7480');
            if (timeout) {
                strokeCircle(ic, 24, 24, 15, Theme.c.white, 3);
                line(ic, 24, 24, 24, 14, Theme.c.white, 3);
                line(ic, 24, 24, 31, 24, Theme.c.white, 3);
            } else {
                line(ic, 16, 16, 32, 32, Theme.c.white, 4);
                line(ic, 32, 16, 16, 32, Theme.c.white, 4);
            }
            const who = name(r.b);
            text(n, timeout ? who + '超时未答复 · 保留交易卡' : who + '拒绝了交易 · 保留交易卡', 88, cy, W - 110, 48, 26, Theme.c.navy,
                { bold: true, align: 'l' });
            if (timeout) text(n, '拒绝或超时仍消耗主动用卡机会', 0, 100, W, 26, 18, Theme.c.noteGray);
        }
        if (ok) art(n, 'icon_check', W - 76, cy - 2, 56, 56);
        void trade;
    }

    return {
        tick(now: number): boolean {
            if (countdown && countdown.label.isValid) {
                const s = Math.max(0, Math.ceil((countdown.deadline - ctx.clock.now()) / 1000));
                setText(countdown.label, '等待' + countdown.buyer + '答复\n' + s + ' 秒');
            }
            return !!r && r.until <= now;
        },
    };
}
