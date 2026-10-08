/** Screen18: property identity, prices and four flat rent rows with live values. */
import { Node } from 'cc';
import { inflateRent, landPrice, TIERS } from '../core/Rules';
import { art } from '../ui/Art';
import { ctx } from '../ui/Ctx';
import { gfx, mk, text } from '../ui/Kit';
import { drawHouse } from '../ui/Icons';
import { avatar, chip } from '../ui/Widgets';
import { InformationPage, informationCard, redeemOrClose, shopKey } from './InformationPage';
import { buildSpecialLand, SpecialLandState } from './SpecialLandView';

const INK = '#101A50';

export class TileInfoPopup extends InformationPage {
    protected get headingY(): number { return ctx.store.tile(this.tileIndex).type === 'JAIL' ? 74 : 112; }
    protected get headingSize(): number { return ctx.store.tile(this.tileIndex).type === 'JAIL' ? 66 : 44; }
    protected get headingOutline(): number { return ctx.store.tile(this.tileIndex).type === 'JAIL' ? 0 : 3; }
    private specialState: SpecialLandState = { bankMode: 'mortgage', selected: null };
    constructor(private readonly tileIndex: number) {
        super('tile', ({ BANK: '银行 · 抵押与赎回', JAIL: '监狱', REST: '休息区', GAME_ZONE: '游戏中心' } as Record<string, string>)[ctx.store.tile(tileIndex).type] ?? '格子详情');
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const st = ctx.store, tile = st.tile(this.tileIndex), prop = st.prop(this.tileIndex);
        if (tile.type !== 'PROPERTY') {
            buildSpecialLand(p, this.tileIndex, this.specialState, () => this.rebuildBody(), () => this.close());
            return;
        }
        const owner = prop?.owner ? st.player(prop.owner) : undefined;
        // Supplied shop artwork differs from the street close-up in screen18.
        art(p, shopKey(tile.type, tile.tier), 18, 238, w - 36, 326);
        const ownerBox = informationCard(p, 94, 222, 222, 66, '#FFFEF6', 32);
        text(ownerBox, owner?.nickname ?? '无主', 66, 8, 148, 48, 32, INK, { bold: true, align: 'l' });
        if (owner) avatar(p, 40, 190, 112, owner.avatar, owner.nickname);

        const info = informationCard(p, 32, 552, w - 64, 152, '#FFFEF8', 32);
        art(info, shopKey(tile.type, tile.tier), 22, 18, 134, 116);
        text(info, tile.name, 180, 16, 442, 64, 46, INK, { bold: true, align: 'l' });
        const level = prop?.level ?? 0, mortgaged = !!prop?.mortgaged;
        text(info, level ? level + '级 ·' : '未升级 ·', 180, 84, 144, 44, 30, INK, { bold: true, align: 'l' });
        chip(info, 338, 86, mortgaged ? '已抵押' : '未抵押', mortgaged ? '#FFE0E0' : '#B3F2B7',
            mortgaged ? '#B73337' : '#125D2B', 25, 40);

        const price = informationCard(p, 32, 714, 322, 122, '#FFFEF8', 30);
        const upgrade = informationCard(p, 366, 714, 322, 122, '#FFFEF8', 30);
        [price, upgrade].forEach((card, i) => {
            text(card, i ? '升级费' : '原价', 30, 8, 260, 36, 25, INK, { align: 'l' });
            art(card, 'info_cash', 28, 54, 52, 52);
            text(card, String(i ? TIERS[tile.tier ?? 'LOW'].upgrade : landPrice(false, tile.tier)),
                96, 44, 204, 66, 44, INK, { bold: true, align: 'l' });
        });

        const rentPanel = informationCard(p, 32, 846, w - 64, 290, '#FFF8E3', 30);
        drawHouse(gfx(mk(rentPanel, 'RentHome', 26, 14, 44, 44)), 22, 22, 40, '#D84128', '#FFF1CF');
        text(rentPanel, '租金表', 84, 10, 300, 50, 30, INK, { bold: true, align: 'l' });
        TIERS[tile.tier ?? 'LOW'].rent.map((r) => inflateRent(r)).forEach((rent, i) => {
            const current = i === level;
            const row = informationCard(rentPanel, 26, 66 + i * 52, w - 116, 48,
                current ? '#EFF8E9' : '#FFFCF4', 16, false);
            if (current) text(row, '当前', 16, 2, 74, 44, 20, '#277240', { bold: true });
            text(row, i ? i + '级' : '未升级', 98, 2, 180, 44, 26, INK, { bold: true });
            art(row, 'info_cash', 330, 6, 36, 36);
            text(row, String(rent), 388, 0, 178, 48, 30, INK, { bold: true, align: 'l' });
        });
        if (mortgaged) text(p, '抵押本金 ' + prop!.mortgagePaid + ' · 抵押期间不收租 · 不在银行赎回另收 10% 手续费', 50, 1136, w - 100, 24, 19, '#B73337', { align: 'l' });
        redeemOrClose(p, this.tileIndex, 44, h - 124, w - 88, () => this.close());
    }
}
