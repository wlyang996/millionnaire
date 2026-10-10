/** The approved UI33 score board, loaded independently from saved series facts. */
import { BlockInputEvents, Node } from 'cc';
import { SeriesState } from '../core/Models';
import { Theme } from '../core/Theme';
import { renderSeriesScore } from '../screens/SeriesScreen';
import { primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { mk, text } from '../ui/Kit';
import { art } from '../ui/Art';
import { Popup } from '../ui/Popup';

export class SeriesHistoryPopup extends Popup {
    get coversScreen(): boolean { return true; }
    private series: SeriesState | null = null;
    private error = false;
    constructor(private readonly roomId: number, seriesNo: number) {
        super('series-history', '系列积分榜', Theme.W, Theme.H);
        const online = ctx.store.online;
        if (!online) { this.error = true; return; }
        void online.client.seriesDetail(roomId, seriesNo).then(s => { this.series = s; }, () => { this.error = true; })
            .then(() => { if (!this.closed && this.body?.isValid) this.rebuildBody(); });
    }
    mount(layer: Node): void {
        this.root = mk(layer, 'Popup:series-history', 0, 0, Theme.W, Theme.H);
        this.root.addComponent(BlockInputEvents);
        this.panel = this.root;
        this.body = mk(this.root, 'Body', 0, 0, Theme.W, Theme.H);
        this.buildBody(this.body, Theme.W);
    }
    protected buildBody(p: Node, w: number): void {
        if (this.series) {
            renderSeriesScore(p, this.series, { roomId: String(this.roomId), close: () => this.close() });
            return;
        }
        art(p, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        text(p, this.error ? '无法读取系列榜单，请稍后重试' : '正在读取系列榜单…', 32, 500, w - 64, 90, 32, Theme.c.navy, { bold: true });
        primaryButton(p, '返回对局详情', 40, 1140, w - 80, 92, () => this.close());
    }
}
