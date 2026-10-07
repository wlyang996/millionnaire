/** Full-page card details follow screen 16 with supplied independent artwork. */
import { BlockInputEvents, Node } from 'cc';
import { CARD_DESC, CARD_NAMES, CardType } from '../core/Models';
import { Theme } from '../core/Theme';
import { art, CARD_ART } from '../ui/Art';
import { Button } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, onTap, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { cardUsable, openCardUse } from './CardUse';

interface RuleRow { icon: string; caption: string }
const RESPONSE_CARDS: CardType[] = ['RENT_WAIVER', 'REFUSE_PURCHASE', 'HOUSE_PROTECTION'];

function rules(type: CardType): RuleRow[] {
    if (type === 'FIXED_MOVE') return [
        { icon: 'dice_1', caption: '仅正常投骰前使用' },
        { icon: 'icon_move', caption: '选前进 1～6 格，替代投骰' },
        { icon: 'icon_roadblock', caption: '忽略沿途路障' },
        { icon: 'icon_house', caption: '落起点可领奖，经过不领奖' },
        { icon: 'card_face_blank', caption: '消耗本回合主动用卡机会' },
    ];
    if (type === 'HOUSE_PROTECTION') return [
        { icon: 'icon_shield', caption: '抵挡一次降级、拆楼或清地' },
        { icon: 'icon_clock_normal', caption: '响应时使用，\n不占主动用卡机会' },
    ];
    return [
        { icon: CARD_ART[type], caption: CARD_DESC[type] },
        { icon: 'icon_clock_normal', caption: RESPONSE_CARDS.includes(type)
            ? '对应响应窗口内使用，\n不占主动用卡机会'
            : type === 'AUCTION' || type === 'TRADE' ? '可非自己回合申请，\n在安全结算边界处理'
            : type === 'JAIL_RELEASE' ? '自己的回合被关押时使用'
                : '自己的回合，投骰前或落点结算后使用' },
        ...(RESPONSE_CARDS.includes(type) ? [] : [{ icon: 'card_face_blank', caption: '消耗本回合主动用卡机会' }]),
    ];
}

export class CardDetailPage extends Popup {
    get coversScreen(): boolean { return true; }
    constructor(private readonly type: CardType, private readonly canUse: boolean) {
        super('card', '道具卡详情', Theme.W, Theme.H);
    }

    mount(layer: Node): void {
        this.root = mk(layer, 'CardDetailPage', 0, 0, Theme.W, Theme.H);
        this.root.addComponent(BlockInputEvents);
        fillRR(gfx(this.root), 0, 0, Theme.W, Theme.H, 0, Theme.c.skyBottom);
        // Reuse supplied PNGs until the dedicated screen-16 assets arrive; never draw replacement art.
        if (!art(this.root, 'card_detail_background', 0, 0, Theme.W, Theme.H, 'stretch'))
            art(this.root, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        this.panel = this.root;
        this.body = mk(this.root, 'Body', 0, 0, Theme.W, Theme.H);
        this.buildBody(this.body, Theme.W, Theme.H);
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const back = mk(p, 'Back', 26, Theme.safeTop + 8, 76, 76);
        art(back, 'card_detail_back', 0, 0, 76, 76);
        art(back, 'icon_back', 20, 20, 36, 36);
        onTap(back, () => this.close());
        const title = mk(p, 'Title', 170, Theme.safeTop + 8, 380, 76);
        art(title, 'card_detail_title', 0, 0, 380, 76, 'capsule');
        text(title, this.title, 12, 0, 356, 72, 38, Theme.c.ink, { bold: true });

        if (!art(p, 'detail_' + CARD_ART[this.type], 135, 205, 450, 490)) {
            art(p, CARD_ART[this.type], 135, 205, 450, 490);
        }
        // 大卡素材底部留有空白名牌（约在卡高的 83%～97%）：卡片按高 490 等比放在 y 205，名牌即 y 612～680
        text(p, CARD_NAMES[this.type], 165, 612, 390, 66, 44, Theme.c.navy, { bold: true });

        const rows = rules(this.type);
        const y = rows.length === 2 ? 780 : 710;
        const boxH = rows.length === 2 ? 310 : 380;
        // Replaceable image slot for the cream rules panel and its separators.
        if (!art(p, 'card_detail_rules_' + rows.length, 24, y, w - 48, boxH, 'stretch'))
            art(p, 'info_asset_panel', 24, y, w - 48, boxH, 'panel');
        const rowH = (boxH - 48) / rows.length;
        rows.forEach((row, i) => {
            art(p, row.icon, 52, y + 24 + i * rowH + (rowH - 54) / 2, 54, 54);
            text(p, row.caption, 132, y + 24 + i * rowH, w - 186, rowH, 28, Theme.c.ink,
                { bold: true, align: 'l', wrap: true, lineHeight: 36 });
        });
        const response = RESPONSE_CARDS.includes(this.type);
        const actionY = h - Theme.safeBottom - 122;
        new Button(p, '关闭', response ? 142 : 28, actionY, response ? w - 284 : 258, 100,
            'disabled', () => this.close(), 36);
        if (!response) {
            // 使用：关掉详情，按卡种打开确认面板（路障 / 定点移动 / 地产类）或查询选人页（设计稿 13 / 14）
            // 拍卖卡可非自己回合申请
            const usable = this.canUse || this.type === 'AUCTION' || this.type === 'TRADE' ? cardUsable(this.type) : { ok: false, reason: '只能在自己的回合使用' };
            const action = new Button(p, this.type === 'FIXED_MOVE' ? '选择落点' : '使用', 300, actionY, w - 328, 100,
                'primary', () => {
                    this.close();
                    openCardUse(this.type);
                }, 36);
            action.setEnabled(usable.ok, usable.reason);
        }
    }
}
