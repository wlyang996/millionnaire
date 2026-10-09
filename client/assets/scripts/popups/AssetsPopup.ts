/** Approved asset overview. Values and ownership remain driven by the store. */
import { Node } from 'cc';
import { ROBOT_AVATAR } from '../core/Models';
import { standardValue } from '../core/Rules';
import { Theme } from '../core/Theme';
import { art, informationCharacterKey } from '../ui/Art';
import { ctx } from '../ui/Ctx';
import { mk, onTap, text } from '../ui/Kit';
import { ScrollList } from '../ui/ScrollList';
import { avatar, chip } from '../ui/Widgets';
import { InformationPage, informationClose, informationPanel, shopKey } from './InformationPage';
import { TileInfoPopup } from './TileInfoPopup';
import { tierName } from './Common';

export class AssetsPopup extends InformationPage {
    constructor(private readonly playerId: string = ctx.store.myId) {
        super('assets', playerId === ctx.store.myId ? '我的资产' : (ctx.store.player(playerId)?.nickname ?? '玩家') + '的资产');
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const st = ctx.store;
        const me = st.player(this.playerId) ?? st.me();
        if (me.avatar === ROBOT_AVATAR) avatar(p, 380, 244, 200, me.avatar, me.nickname);
        else art(p, informationCharacterKey(me.avatar), 310, 184, 345, 310);
        const sum = informationPanel(p, 'info_summary_panel', 32, 452, w - 64, 146);
        const metrics: [string, string, number][] = [
            ['现金', 'info_cash', me.cash], ['冻结资金', 'info_frozen', me.frozen],
            ['净资产', 'info_net_worth', st.netWorthOf(me.playerId)],
        ];
        metrics.forEach(([label, key, value], i) => {
            const x = 14 + i * 212;
            art(sum, key, x, 42, 52, 62);
            text(sum, label, x + 62, 24, 138, 34, 22, Theme.c.inkSoft, { align: 'l' });
            text(sum, String(value), x + 62, 60, 138, 52, 36, Theme.c.ink, { bold: true, align: 'l' });
        });
        const area = informationPanel(p, 'info_rent_panel', 32, 614, w - 64, 510);
        const assets = st.assetsOf(me.playerId);
        art(area, 'info_properties', 22, 12, 38, 40);
        const landCount = assets.filter(a => a.tile.type === 'PROPERTY').length;
        text(area, '房产 ' + assets.length + ' · 地产 ' + landCount + ' · 车站 ' + (assets.length - landCount),
            72, 12, 540, 46, 26, Theme.c.ink, { bold: true, align: 'l' });
        const list = new ScrollList(area, 18, 72, w - 100, 416);
        const rowH = 182;
        assets.forEach((a, i) => {
            const rw = w - 100;
            const row = informationPanel(list.content, 'info_asset_panel', 0, i * rowH, rw, rowH - 12);
            art(row, shopKey(a.tile.type, a.tile.tier), 10, 18, 135, 134);
            text(row, a.tile.name, 156, 12, 230, 42, 30, Theme.c.ink, { bold: true, align: 'l' });
            const tierBg = a.tile.tier === 'MID' ? '#FFF0AD' : a.tile.tier === 'HIGH' ? '#E3D6FF' : Theme.c.greenSoft;
            chip(row, 156, 57, tierName(a.tile), tierBg, Theme.c.ink, 19, 28);
            const station = a.tile.type === 'STATION';
            text(row, station ? '车站' : (a.p.level ? a.p.level + '级' : '未升级') + ' · 标准', 156, 92, 230, 30, 21, Theme.c.inkSoft, { align: 'l' });
            const value = standardValue(station, a.tile.tier, a.p.upgradeSpent);
            text(row, '房产价值', rw - 178, 22, 154, 32, 22, Theme.c.inkSoft, { align: 'l' });
            art(row, 'info_cash', rw - 178, 83, 32, 36);
            text(row, String(value), rw - 140, 81, 98, 42, 28, Theme.c.ink, { bold: true, align: 'l' });
            if (a.p.mortgaged) {
                art(row, 'info_mortgaged', 154, 126, 23, 26);
                text(row, '已抵押 · 本金 ' + a.p.mortgagePaid, 184, 126, 326, 28, 20, Theme.c.redDark, { align: 'l' });
            } else text(row, '未抵押', 156, 126, 220, 28, 20, Theme.c.greenDark, { align: 'l' });
            onTap(row, () => ctx.popups.open(new TileInfoPopup(a.tile.index)), false);
        });
        if (!assets.length) {
            art(list.content, 'info_empty', 200, 30, 190, 170);
            text(list.content, '暂无资产', 0, 218, w - 100, 42, 30, Theme.c.inkSoft);
        }
        list.setContentHeight(Math.max(300, assets.length * rowH));
        informationClose(p, 44, h - 124, w - 88, () => this.close());
    }
}
