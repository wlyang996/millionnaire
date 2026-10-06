/**
 * 升级（15 秒），按设计稿 04"升级地产"：升级后的房屋插画、"档位 · 当前等级 → 下一级"胶囊、升级费用、租金对比（绿色新租金）、
 * 一级 / 二级 / 三级示意（目标等级黄色圆底高亮），底部"放弃 / 升级 (金币) 费用"。不弹成功 / 超时提示。
 */
import { Node } from 'cc';
import { MAX_LEVEL, rentOf, SECONDS, TIERS } from '../core/Rules';
import { Theme } from '../core/Theme';
import { primaryButton, softButton } from '../ui/Buttons';
import { art } from '../ui/Art';
import { ctx } from '../ui/Ctx';
import { fillCircle, fillPoly, gfx, mk, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { box, inlineRow, LEVEL_NAMES, pillLabel, tierName } from './Common';

const W = 480;
const H = 666;

export class UpgradePopup extends Popup {
    /** @param windowId 联机时为服务端的落点决策窗口；演示时不传 */
    constructor(private readonly tileIndex: number, private readonly windowId?: number) {
        super('upgrade', '升级地产', W, H, SECONDS.upgrade);
    }

    protected buildBody(p: Node): void {
        const st = ctx.store;
        const tile = st.tile(this.tileIndex);
        const tier = tile.tier ?? 'LOW';
        const prop = st.prop(this.tileIndex);
        const level = prop ? prop.level : 0;
        const next = Math.min(MAX_LEVEL, level + 1);
        const cost = TIERS[tier].upgrade;
        const full = level >= MAX_LEVEL;
        const cash = st.me().cash;
        art(p, 'house_lv' + next, (W - 315) / 2, 64, 315, 178);
        pillLabel(p, tierName(tile) + ' · ' + LEVEL_NAMES[level] + (full ? '' : ' → ' + LEVEL_NAMES[next]), W / 2, 247, 47);
        box(p, 24, 306, W - 48, 50, Theme.c.boxBeige, 16);
        inlineRow(p, W / 2, 306, 50, [{ t: '升级费用', size: 24 }, { coin: 36 }, { t: String(cost), size: 44 }], 14);
        box(p, 24, 365, W - 48, 58, Theme.c.boxGray, 16);
        inlineRow(p, W / 2, 365, 58, [
            { t: '租金', size: 24 }, { coin: 32 }, { t: String(rentOf(tier, level)), size: 40 },
            { arrow: 46 },
            { coin: 32 }, { t: String(rentOf(tier, next)), size: 40, color: Theme.c.gainGreen },
        ], 12);
        // 一级 / 二级 / 三级：目标等级黄色圆底高亮，其余灰显
        for (let i = 1; i <= 3; i++) {
            const cx = W / 2 + (i - 2) * 130;
            const target = i === next;
            if (target) fillCircle(gfx(mk(p, 'Ring', cx - 44, 426, 88, 88)), 44, 44, 44, '#FFE08A');
            art(p, 'house_lv' + i, cx - 38, 438, 76, 64, 'contain', !target);
            text(p, LEVEL_NAMES[i], cx - 60, 508, 120, 28, 22, target ? Theme.c.navy : Theme.c.noteGray, { bold: target });
            if (i < 3) fillPoly(gfx(mk(p, 'Chevron', cx + 58, 462, 14, 18)), [[0, 0], [14, 9], [0, 18]], '#C5CCD8');
        }
        text(p, '最多三级 · 每次升一级', 0, 538, W, 26, 20, Theme.c.noteGray);
        softButton(p, '放弃', 9, 570, 174, 74, () => this.skip(), 30);
        const b = primaryButton(p, full ? '已满级' : '升级', 199, 570, W - 199 - 15, 74, () => this.upgrade(cost, next), 30);
        if (!full) b.withCoin(cost);
        b.setEnabled(!full && cash >= cost);
    }

    private upgrade(cost: number, next: number): void {
        const st = ctx.store;
        this.close();
        if (st.online && this.windowId !== undefined) {
            void st.online.act('UpgradeProperty', { windowId: this.windowId });
            return;
        }
        if (!st.spend(cost)) return;
        const prop = st.prop(this.tileIndex);
        if (prop) {
            prop.level = next;
            prop.upgradeSpent += cost;
        }
        st.emit();
    }

    private skip(): void {
        const st = ctx.store;
        if (st.online && this.windowId !== undefined) void st.online.act('SkipUpgrade', { windowId: this.windowId });
        this.close();
    }

    protected onExpire(): void {
        this.close();
    }
}
