/**
 * 土地拍卖（20 秒，总时长最多 40 秒）。起拍=基准 50%，封顶=一口价=基准 2.5 倍，最低加价=基准 10%。
 * 最后 3 秒内"出价者变化"才恢复到 3 秒（不超过 40 秒上限）；报价需足额可用现金，不靠抵押。
 * 布局按设计稿 05"地产拍卖"：地产卡、当前最高价与出价人、最低加价 | 封顶价、步进器、出价 / 一口价、三栏资金、规则说明。
 */
import { Label, Node } from 'cc';
import { auctionParams, SECONDS, standardValue } from '../core/Rules';
import { Theme } from '../core/Theme';
import { art } from '../ui/Art';
import { Button, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, onTap, setText, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { avatar } from '../ui/Widgets';
import { box, inlineRow, inlineRowLeft, LEVEL_NAMES, noteLines, propertyArtKey, tierName } from './Common';

const W = 640;
const H = 916;

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
        super('auction', '地产拍卖', W, H, SECONDS.auction);
        this.titleIcon = 'icon_auction';
    }

    protected buildBody(p: Node): void {
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
        const X = 36;
        const IW = W - 2 * X;
        this.banner = text(p, '', X, 72, IW, 26, 20, Theme.c.payRed, { bold: true });

        // 地产卡：插画 + 档位·等级、基准价值、起拍价
        const head = box(p, X, 96, IW, 150, Theme.c.white, 20);
        strokeRR(gfx(head), 0, 0, IW, 150, 20, Theme.c.panelLine, 2);
        art(head, propertyArtKey(tile), 8, 10, 190, 130);
        const lv = tile.type === 'PROPERTY' ? ' · ' + LEVEL_NAMES[prop ? prop.level : 0] : '';
        text(head, tile.name + ' · ' + tierName(tile) + lv, 214, 10, IW - 226, 40, 28, Theme.c.navy, { bold: true, align: 'l' });
        const kv = (label: string, v: number, y: number) => {
            text(head, label, 214, y, 140, 40, 24, Theme.c.noteGray, { bold: true, align: 'l' });
            inlineRowLeft(head, 380, y, 40, [{ coin: 34 }, { t: String(v), size: 32 }]);
        };
        kv('基准价值', this.base, 56);
        kv('起拍价', this.start, 100);

        // 当前最高价 + 出价人
        box(p, X, 262, IW, 112, Theme.c.boxBeige, 20);
        text(p, '当前最高价', X + 24, 268, 220, 36, 24, Theme.c.navy, { bold: true, align: 'l' });
        inlineRowLeft(p, X + 22, 304, 64, [{ coin: 50 }, { t: String(this.highBid), size: 56, color: Theme.c.payRed }], 10);
        if (bidder) {
            avatar(p, X + IW - 250, 276, 84, bidder.avatar, bidder.nickname, { ring: '#3E8EF2' });
            text(p, '出价人：', X + IW - 158, 276, 90, 84, 22, Theme.c.noteGray, { bold: true, align: 'l' });
            text(p, bidder.nickname, X + IW - 76, 276, 72, 84, 24, Theme.c.navy, { bold: true, align: 'l' });
        } else {
            text(p, '暂无出价', X + IW - 200, 276, 180, 84, 24, Theme.c.noteGray, { bold: true });
        }

        // 最低加价 | 封顶价
        inlineRow(p, X + IW / 4, 388, 44, [{ t: '最低加价', size: 24, color: Theme.c.noteGray }, { coin: 30 }, { t: String(this.minRaise), size: 28 }]);
        fillRR(gfx(mk(p, 'Sep', W / 2 - 1, 396, 2, 28)), 0, 0, 2, 28, 1, Theme.c.panelLine);
        inlineRow(p, X + (IW * 3) / 4, 388, 44, [{ t: '封顶价', size: 24, color: Theme.c.noteGray }, { coin: 30 }, { t: String(this.cap), size: 28 }]);

        // 步进器：− [金币 出价] +
        const stp = box(p, X, 446, IW, 72, Theme.c.boxGray, 18);
        const step = (label: string, x: number, d: number) => {
            const b = mk(stp, 'Step' + label, x, 6, 84, 60);
            text(b, label, 0, 0, 84, 60, 48, Theme.c.noteGray, { bold: true });
            onTap(b, () => {
                this.myBid = Math.max(this.highBid + this.minRaise, Math.min(this.cap, this.myBid + d * this.minRaise));
                this.rebuildBody();
            });
        };
        step('−', 4, -1);
        step('+', IW - 88, 1);
        const field = box(stp, 92, 6, IW - 184, 60, Theme.c.white, 14);
        inlineRow(field, (IW - 184) / 2, 0, 60, [{ coin: 40 }, { t: String(this.myBid), size: 40 }]);

        // 出价（蓝）/ 一口价（黄）
        const bw = (IW - 18) / 2;
        const bid: Button = secondaryButton(p, '出价', X, 536, bw, 92, () => this.placeBid(this.myBid), 32).withCoin(this.myBid);
        bid.setEnabled(this.myBid <= avail + (this.highBidder === st.myId ? this.frozen : 0), '可用现金不足（报价需足额，不可抵押）');
        const buy: Button = primaryButton(p, '一口价', X + bw + 18, 536, bw, 92, () => this.placeBid(this.cap), 32).withCoin(this.cap);
        buy.setEnabled(this.cap <= me.cash, '可用现金不足（需足额，不可抵押）');

        // 我的现金 | 我的冻结资金 | 可用现金
        fillRR(gfx(mk(p, 'Line', X, 646, IW, 2)), 0, 0, IW, 2, 1, Theme.c.panelLine);
        const cols: [string, number][] = [['我的现金', me.cash], ['我的冻结资金', this.frozen], ['可用现金', avail]];
        const cw = IW / 3;
        cols.forEach(([k, v], i) => {
            text(p, k, X + i * cw, 658, cw, 34, 22, Theme.c.noteGray, { bold: true });
            inlineRow(p, X + i * cw + cw / 2, 694, 44, [{ coin: 32 }, { t: String(v), size: 32 }], 8);
            if (i > 0) fillRR(gfx(mk(p, 'Sep', X + i * cw - 1, 668, 2, 62)), 0, 0, 2, 62, 1, Theme.c.panelLine);
        });

        // 规则说明
        const note = box(p, X, 754, IW, 140, Theme.c.boxGray, 18);
        noteLines(note, IW, ['报价需足额可用现金，不可抵押参拍', '最后 3 秒出价恢复到 3 秒，总时长最多 40 秒', '最高报价冻结资金，被超过立即解冻']);
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

    /** 演示：模拟他人加价（演示面板调用；联机时拍卖由服务端推送）。 */
    demoOtherBid(): void {
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
