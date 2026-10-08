/**
 * 规则说明（用户 2026-10-08）：房间页与棋盘页都能打开。数值全部取当前生效的参数（联机为房间绑定的后台配置），
 * 后台改了价格、租金、奖励、道具上限、租金上涨、同组加成后，这里随之变化，不另写一份。
 */
import { Node } from 'cc';
import {
    BAIL_COST, BANKRUPTCY_CAP_MINUTES, MAX_HAND, MINIGAME_REWARD, RENT_RISE, START_BONUS, STATION, TIERS,
} from '../core/Rules';
import { setBonusPercent } from '../core/SetBonus';
import { Theme } from '../core/Theme';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, onTap, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';

const W = 660;
const H = 1080;
const FONT = 23;
const LINE = 33;

/** 规则的各个小节（标题 + 若干段落），按当前参数生成。 */
export function ruleSections(): { title: string; lines: string[] }[] {
    const s = ctx.store.session.settings;
    const timed = s.endMode === 'TIME_LIMIT';
    const tier = (k: 'LOW' | 'MID' | 'HIGH') => {
        const t = TIERS[k];
        return t.name + '地：原价 ' + t.price + '，每次升级 ' + t.upgrade + '，租金 ' + t.rent.join(' / ');
    };
    const rr = RENT_RISE;
    const rise = rr.stepPercent > 0
        ? '破产模式下租金会随轮数上涨：前 ' + rr.freeRounds + ' 轮原价，之后每 ' + rr.everyRounds + ' 轮租金 +' + rr.stepPercent
            + '%，最高 ×' + rr.capPercent / 100 + '。所有人各走一次算一轮，页眉会显示当前轮数和倍率。'
        : '';
    const sets = setBonusPercent() > 100
        ? '格子顶部颜色相同的普通地产是同一组。整组都归你、且都没抵押时，组内每块地租金 ×' + setBonusPercent() / 100 + '。'
        : '';
    return [
        {
            title: '怎样获胜',
            lines: [
                timed ? '本局为限时模式（' + s.timeLimitMinutes + ' 分钟）：时间到后按净资产排名，净资产 = 现金 + 地产与车站的价值。'
                    : '本局为破产模式：其他人都破产或认输，最后留下的人获胜；最长 ' + BANKRUPTCY_CAP_MINUTES + ' 分钟，到时按净资产排名。',
                '只剩一名玩家时立即获胜。',
            ],
        },
        {
            title: '回合',
            lines: [
                '轮到你时点骰子投骰（' + s.rollSeconds + ' 秒内不投会自动投），棋子按点数前进。',
                '经过或停在起点获得 ' + START_BONUS + '。经过其他格子不触发效果，只处理停下的格子。',
                '买地、升级等选择限时，超时视为放弃。',
            ],
        },
        {
            title: '地产与租金',
            lines: [
                '停在无主地产可以买下；停在自己的地产可以升级，每次一级，最多三级。',
                '停在别人没抵押的地产要付租金。',
                tier('LOW'), tier('MID'), tier('HIGH'),
                '车站原价 ' + STATION.price + '，不能升级；租金 = 所有者持有的车站数 × ' + STATION.rentEach + '。',
                sets, rise,
            ].filter((x) => x),
        },
        {
            title: '事件格',
            lines: [
                '问号格：点中央的卡片翻开随机事件，可能奖励、罚款、得道具、移动、入狱等。',
                '幸运格（红）只出好事，不幸格（紫）只出坏事，踩上去自动翻开。',
                '现金不够付罚款时，按欠款处理。',
            ],
        },
        {
            title: '道具',
            lines: [
                '事件格可以获得道具，最多持有 ' + MAX_HAND + ' 张，超出时要弃掉一张。',
                '主动卡只能在自己回合投骰前使用，每回合一次；免租等响应卡在被收费时询问是否使用。',
                '点手牌可以查看每张卡的说明。',
            ],
        },
        {
            title: '监狱',
            lines: [
                '停在监狱或被送进监狱会被关押，之后每回合掷骰，偶数出狱，连续三次失败自动出狱。',
                '也可以支付 ' + BAIL_COST + ' 或使用出狱卡出狱。关押期间照常收租。',
            ],
        },
        {
            title: '银行、抵押与破产',
            lines: [
                '停在银行可以按原价抵押自己的地产或车站，抵押期间不收租、不能升级。',
                '赎回要还抵押得到的钱，在银行以外的地方赎回另收 10% 手续费；在格子详情里也能赎回。',
                '现金不够付钱时，可以选择资产应急抵押筹钱；仍然不够就破产出局。',
            ],
        },
        {
            title: '拍卖与交易',
            lines: [
                '部分地产是拍卖地，到达时可以选择发起拍卖。拍卖卡、交易卡可以卖出自己的地产。',
                '拍卖期间棋盘暂停，出价须有足够现金。',
            ],
        },
        {
            title: '虎口拔牙',
            lines: ['停在游戏区时所有未破产玩家一起玩：轮流拔牙，拔到危险牙的人输，其余人各得 ' + MINIGAME_REWARD + '。'],
        },
        {
            title: '掉线与托管',
            lines: [
                '掉线后系统会自动投骰，但不会帮你买地；回来后可恢复手动。',
                '点自己的头像可以主动托管或认输。',
            ],
        },
    ];
}

export class RulesPopup extends Popup {
    constructor() {
        super('rules', '游戏规则', W, H, 0, true);
    }

    protected buildTitle(): void {
        text(this.panel, this.title, 80, 20, this.pw - 160, 64, 38, Theme.c.ink, { bold: true });
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const close = mk(p, 'Close', w - 76, 20, 56, 56);
        text(close, '❌', 0, 0, 56, 56, 30, Theme.c.red, { bold: true });
        onTap(close, () => this.close(), false);
        const list = new ScrollList(p, 24, 96, w - 48, h - 120);
        const lw = list.w;
        const perLine = Math.floor((lw - 24) / FONT);
        let y = 0;
        for (const sec of ruleSections()) {
            const head = mk(list.content, 'RuleHead', 0, y + 8, lw, 44);
            fillRR(gfx(head), 0, 0, lw, 44, 12, Theme.c.blueSoft);
            text(head, sec.title, 16, 0, lw - 32, 44, 25, Theme.c.blueDark, { bold: true, align: 'l' });
            y += 60;
            for (const line of sec.lines) {
                // 粗估行数：全角字按一个字宽、半角按半个字宽
                const units = Array.from(line).reduce((a, ch) => a + (ch.charCodeAt(0) > 255 ? 1 : 0.55), 0);
                const rows = Math.max(1, Math.ceil(units / perLine));
                const hh = rows * LINE + 6;
                text(list.content, '· ' + line, 8, y, lw - 16, hh, FONT, Theme.c.ink, { align: 'l', wrap: true, lineHeight: LINE });
                y += hh + 4;
            }
            y += 6;
        }
        list.setContentHeight(y + 16);
    }
}
