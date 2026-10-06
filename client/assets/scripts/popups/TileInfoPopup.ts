/** 点击棋盘格子的详情：名称与棋盘上的三字地名一致（core/BoardNames）、档位、价格/租金、归属与等级。 */
import { Node } from 'cc';
import { landPrice, STATION } from '../core/Rules';
import { TILE_TYPE_NAME } from '../core/BoardLayout';
import { Theme } from '../core/Theme';
import { ghostButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { chip } from '../ui/Widgets';
import { infoRow, rentTable, tierName, tileHero, tileSubtitle } from './Common';

export class TileInfoPopup extends Popup {
    constructor(private readonly tileIndex: number) {
        super('tile', ctx.store.tile(tileIndex).name, 640, 760, 0, true);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const st = ctx.store;
        const tile = st.tile(this.tileIndex);
        const prop = st.prop(this.tileIndex);
        const isProp = tile.type === 'PROPERTY';
        const station = tile.type === 'STATION';
        chip(p, 28, 84, '第 ' + (this.tileIndex + 1) + ' 格 · ' + (isProp || station ? tierName(tile) : TILE_TYPE_NAME[tile.type]), Theme.c.ivoryDark, Theme.c.inkSoft, Theme.font.xs);
        if (isProp || station) {
            tileHero(p, tile, 40, 128, w - 80, 190, tileSubtitle(tile, prop));
            infoRow(p, 40, 334, w - 80, '原价', landPrice(station, tile.tier), Theme.c.ivoryDark);
            if (isProp) rentTable(p, 40, 408, w - 80, tile.tier ?? 'LOW', prop ? [prop.level] : [0]);
            else text(p, '租金 = 所有者持有未抵押车站数 × ' + STATION.rentEach, 40, 408, w - 80, 40, Theme.font.sm, Theme.c.inkSoft);
            const owner = prop && prop.owner ? st.player(prop.owner) : undefined;
            text(p, owner ? '所有者：' + owner.nickname + (prop && prop.mortgaged ? '（已抵押）' : '') : '无主，可购买', 40, 524, w - 80, 40, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
            if (tile.auctionLot) text(p, '指定拍卖地产：到达者可原价买、发起拍卖或放弃', 40, 570, w - 80, 36, Theme.font.xs, Theme.c.inkSoft, { align: 'l' });
        } else {
            text(p, tile.name + '：' + TILE_TYPE_NAME[tile.type] + '格', 40, 200, w - 80, 60, Theme.font.lg, Theme.c.ink, { bold: true });
        }
        ghostButton(p, '关闭', 40, h - 104, w - 80, 76, () => this.close(), Theme.font.lg);
    }
}
