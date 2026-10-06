/** 升级（15 秒）：展示升级费、升级前后租金对比，最多三级、每次一级。 */
import { Node } from 'cc';
import { MAX_LEVEL, rentOf, SECONDS, TIERS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { ghostButton, primaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillPoly, gfx, mk, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
import { art } from '../ui/Art';
import { coinText, infoRow, rentTable, tileHero, tileSubtitle } from './Common';

export class UpgradePopup extends Popup {
    constructor(private readonly tileIndex: number) {
        super('upgrade', '升级地产', 640, 800, SECONDS.upgrade);
    }

    protected buildBody(p: Node, w: number): void {
        const st = ctx.store;
        const tile = st.tile(this.tileIndex);
        const tier = tile.tier ?? 'LOW';
        const prop = st.prop(this.tileIndex);
        const level = prop ? prop.level : 0;
        const next = Math.min(MAX_LEVEL, level + 1);
        const cost = TIERS[tier].upgrade;
        const full = level >= MAX_LEVEL;
        const cash = st.me().cash;
        tileHero(p, tile, 40, 92, w - 80, 210, tileSubtitle(tile, prop) + (full ? '' : ' → ' + next + '级'));
        infoRow(p, 40, 316, w - 80, '升级费用', cost, Theme.c.ivoryDark);
        // 租金对比
        const cmp = mk(p, 'Cmp', 40, 390, w - 80, 64);
        text(cmp, '租金', 20, 0, 80, 64, Theme.font.md, Theme.c.ink, { bold: true, align: 'l' });
        coinText(cmp, 120, 12, rentOf(tier, level), Theme.font.lg);
        fillPoly(gfx(cmp), [[290, 22], [322, 32], [290, 42]], Theme.c.green);
        coinText(cmp, 340, 12, rentOf(tier, next), Theme.font.lg, Theme.c.greenDark);
        for (let i = 1; i <= 3; i++) {
            const x = 40 + (i - 1) * (w - 80) / 3;
            art(p, 'house_lv' + i, x + 34, 458, (w - 80) / 3 - 68, 80, 'contain', i !== next);
            text(p, i + '级', x, 536, (w - 80) / 3, 30, Theme.font.sm, i === next ? Theme.c.blueDark : Theme.c.inkSoft, { bold: i === next });
        }
        text(p, '最多三级 · 每次升一级 · 抵押的地产不能升级', 40, 570, w - 80, 30, Theme.font.xs, Theme.c.inkFaint);
        const b = primaryButton(p, full ? '已满级' : '升级 ' + cost, 40 + (w - 100) * 0.38 + 20, 620, (w - 100) * 0.62, 92, () => {
            if (!st.spend(cost)) return Toast.show('现金不足');
            if (prop) {
                prop.level = next;
                prop.upgradeSpent += cost;
            }
            Toast.show('升级成功：' + next + ' 级');
            this.close();
            st.emit();
        }, Theme.font.lg);
        b.setEnabled(!full && cash >= cost, full ? '已是最高等级' : '现金不足');
        ghostButton(p, '放弃', 40, 620, (w - 100) * 0.38, 92, () => this.close(), Theme.font.md);
        text(p, '现金 ' + cash + (full ? '' : '，升级后剩余 ' + (cash - cost)), 40, 724, w - 80, 36, Theme.font.sm, Theme.c.inkSoft);
    }

    protected onExpire(): void {
        Toast.show('操作超时，视为放弃');
        this.close();
    }
}
