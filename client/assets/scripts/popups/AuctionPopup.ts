/**
 * 土地拍卖（20 秒，总时长最多 40 秒）。起拍=基准 50%，封顶=一口价=基准 2.5 倍，最低加价=基准 10%。
 * 最后 3 秒内"出价者变化"才恢复到 3 秒（不超过 40 秒上限）；报价需足额可用现金，不靠抵押。
 */
import { Label, Node } from 'cc';
import { auctionParams, SECONDS, standardValue } from '../core/Rules';
import { Theme } from '../core/Theme';
import { Button, ghostButton, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawCardIcon } from '../ui/Icons';
import { fillRR, gfx, mk, onTap, setText, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { avatar } from '../ui/Widgets';
import { coinText, tileSubtitle, tierColor } from './Common';

export class AuctionPopup extends Popup {
    private base = 0;
    private start = 0;
    private cap = 0;
    private minRaise = 0;
    private highBid = 0;
    private highBidder: string | null = null;
    private myBid = 0;
    private frozen = 0;
    private hardCap = 0;
    private banner: Label | null = null;

    constructor(private readonly tileIndex: number, private readonly byInitiator = false) {
        super('auction', '地产拍卖', 640, 860, SECONDS.auction);
    }

    protected buildBody(p: Node, w: number): void {
        const st = ctx.store;
        const tile = st.tile(this.tileIndex);
        const prop = st.prop(this.tileIndex);
        if (!this.hardCap) {
            this.base = standardValue(tile.type === 'STATION', tile.tier, prop ? prop.upgradeSpent : 0);
            const a = auctionParams(this.base);
            this.start = a.start;
            this.cap = a.cap;
            this.minRaise = a.minRaise;
            this.highBid = a.start + a.minRaise;
            this.highBidder = 'p3';
            this.hardCap = ctx.clock.now() + SECONDS.auctionMax * 1000;
        }
        const me = st.me();
        if (this.myBid <= this.highBid) this.myBid = Math.min(this.cap, this.highBid + this.minRaise);
        const bidder = this.highBidder ? st.player(this.highBidder) : undefined;
        const avail = me.cash - this.frozen;

        // 头部：地产 + 基准/起拍
        const head = mk(p, 'Head', 28, 88, w - 56, 120);
        const g = gfx(head);
        fillRR(g, 0, 0, 120, 120, 20, '#DFF3D2');
        fillRR(g, 0, 0, 120, 16, 8, tierColor(tile.tier));
        drawCardIcon(g, 'BUILD', 60, 66, 76);
        text(head, tileSubtitle(tile, prop), 136, 0, w - 200, 38, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        text(head, '基准价值', 136, 42, 130, 32, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        coinText(head, 280, 40, this.base, Theme.font.md);
        text(head, '起拍价', 136, 80, 130, 32, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        coinText(head, 280, 78, this.start, Theme.font.md);
        this.banner = text(p, '', 28, 58, w - 150, 30, Theme.font.sm, Theme.c.red, { bold: true, align: 'l' });

        // 当前最高价
        const hi = mk(p, 'High', 28, 220, w - 56, 100);
        fillRR(gfx(hi), 0, 0, w - 56, 100, 18, '#FFF1C9');
        text(hi, '当前最高价', 18, 6, 200, 32, Theme.font.sm, Theme.c.inkSoft, { bold: true, align: 'l' });
        coinText(hi, 18, 38, this.highBid, Theme.font.xl);
        if (bidder) {
            avatar(hi, w - 56 - 240, 14, 72, bidder.avatar, bidder.nickname);
            text(hi, '出价人：' + bidder.nickname, w - 56 - 160, 14, 150, 72, Theme.font.sm, Theme.c.ink, { align: 'l' });
        }
        // 最低加价/封顶
        text(p, '最低加价', 40, 332, 120, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        coinText(p, 160, 332, this.minRaise, Theme.font.sm);
        text(p, '封顶价（一口价）', 300, 332, 200, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        coinText(p, 500, 332, this.cap, Theme.font.sm);

        // 步进器
        const stp = mk(p, 'Stepper', 28, 380, w - 56, 70);
        fillRR(gfx(stp), 0, 0, w - 56, 70, 18, '#FFFFFFCC');
        const step = (label: string, x: number, d: number) => {
            const b = mk(stp, 'Step' + label, x, 6, 78, 58);
            fillRR(gfx(b), 0, 0, 78, 58, 14, Theme.c.ivoryDark);
            text(b, label, 0, 0, 78, 58, Theme.font.xl, Theme.c.ink, { bold: true });
            onTap(b, () => {
                this.myBid = Math.max(this.highBid + this.minRaise, Math.min(this.cap, this.myBid + d * this.minRaise));
                this.rebuildBody();
            });
        };
        step('−', 6, -1);
        step('+', w - 56 - 84, 1);
        coinText(stp, (w - 56) / 2 - 56, 10, this.myBid, Theme.font.xl);

        // 出价/一口价
        const bw = (w - 56 - 20) / 2;
        const bid: Button = secondaryButton(p, '出价 ' + this.myBid, 28, 466, bw, 84, () => this.placeBid(this.myBid), Theme.font.lg);
        bid.setEnabled(this.myBid <= me.cash, '可用现金不足（报价需足额，不可抵押）');
        const buy: Button = primaryButton(p, '一口价 ' + this.cap, 28 + bw + 20, 466, bw, 84, () => this.placeBid(this.cap), Theme.font.lg);
        buy.setEnabled(this.cap <= me.cash, '可用现金不足（需足额，不可抵押）');

        // 我的资金
        const trio = mk(p, 'Trio', 28, 566, w - 56, 84);
        const cols: [string, number][] = [['我的现金', me.cash], ['我的冻结资金', this.frozen], ['可用现金', avail]];
        cols.forEach(([k, v], i) => {
            const cw = (w - 56) / 3;
            text(trio, k, i * cw, 0, cw, 32, Theme.font.xs, Theme.c.inkSoft, { bold: true });
            coinText(trio, i * cw + (cw - 110) / 2, 36, v, Theme.font.md);
        });

        // 规则提示
        const note = mk(p, 'Note', 28, 662, w - 56, 120);
        fillRR(gfx(note), 0, 0, w - 56, 120, 16, '#FFF1C9');
        text(note, '报价需足额可用现金，不可抵押参拍\n最后 3 秒内他人出价才延至 3 秒，总时长最多 40 秒\n最高出价者可自行加价，冻结资金按差额增加', 16, 8, w - 90, 104, Theme.font.xs, Theme.c.inkSoft, { wrap: true, align: 'l', valign: 't', lineHeight: 32 });
        ghostButton(p, '不参与', 28, 792, 150, 52, () => this.close(), Theme.font.sm);
        ghostButton(p, '演示：他人出价', w - 28 - 230, 792, 230, 52, () => this.otherBids(), Theme.font.sm);
        void this.byInitiator;
    }

    protected onTick(): void {
        if (!this.cd || !this.banner) return;
        const ms = this.cd.remainingMs();
        setText(this.banner, ms > 0 && ms <= 3000 ? '最后 3 秒：他人出价将恢复到 3 秒' : '');
    }

    private raise(by: string, amount: number): void {
        const changed = this.highBidder !== by;
        this.highBid = amount;
        this.highBidder = by;
        if (changed && this.cd && this.cd.remainingMs() <= 3000) this.cd.extendTo(3000, this.hardCap);
        this.rebuildBody();
    }

    private placeBid(amount: number): void {
        const st = ctx.store;
        if (amount > st.me().cash) return Toast.show('可用现金不足');
        if (amount >= this.cap) {
            st.spend(this.cap);
            const prop = st.prop(this.tileIndex);
            if (prop) prop.owner = 'p1';
            this.close();
            st.emit();
            return;
        }
        this.frozen = amount;
        this.raise('p1', amount);
    }

    private otherBids(): void {
        const next = Math.min(this.cap, this.highBid + this.minRaise);
        if (next >= this.cap) return Toast.show('已到封顶价');
        this.myBid = 0;
        this.frozen = this.highBidder === 'p1' ? 0 : this.frozen;
        this.raise(this.highBidder === 'p3' ? 'p2' : 'p3', next);
    }

    protected onExpire(): void {
        const st = ctx.store;
        if (this.highBidder === 'p1') {
            st.spend(this.highBid);
            const prop = st.prop(this.tileIndex);
            if (prop) prop.owner = 'p1';
        }
        this.close();
        st.emit();
    }
}
