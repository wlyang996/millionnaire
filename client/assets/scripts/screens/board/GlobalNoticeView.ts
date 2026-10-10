/** Screen32: authoritative whole-round reward and city announcement, with live labels. */
import { BlockInputEvents, Label, Node } from 'cc';
import { CityEvent, GameView, GlobalNotice, TitleAward } from '../../core/Models';
import { GLOBAL_RULES } from '../../core/Rules';
import { Theme } from '../../core/Theme';
import { informationCard } from '../../popups/InformationPage';
import { art } from '../../ui/Art';
import { ctx } from '../../ui/Ctx';
import { col, fillRR, gfx, mk, setText, text } from '../../ui/Kit';
import { ScrollList } from '../../ui/ScrollList';
import { avatar } from '../../ui/Widgets';

const INK = '#101A50';
export function cityEffect(city: CityEvent): string {
    const label = city.spec.kind === 'UPGRADE_DISCOUNT' ? '地产升级费' : city.spec.kind === 'STATION_RENT' ? '车站租金' : '地产租金';
    return label + ' ×' + city.spec.multiplierPercent + '%';
}
export function cityBadge(parent: Node, g: GameView, y: number, notice?: GlobalNotice): Node | null {
    const city = notice?.event ?? g.progress?.cityEvent;
    if (!city) return null;
    const round = g.round ?? 0;
    const state = notice?.kind === 'CITY_ENDED' ? '已结束' : round < city.startRound
        ? '第' + city.startRound + '轮生效' : '剩余' + (city.endRound - round) + '轮';
    const height = g.players.length > 4 ? 32 : 46;
    const n = informationCard(parent, 46, y, 628, height, '#FFF5CF', height / 2);
    art(n, city.spec.kind === 'UPGRADE_DISCOUNT' ? 'info_upgrade' : 'info_cash', 10, 2, height - 4, height - 4);
    text(n, city.spec.name + ' · ' + cityEffect(city) + ' · ' + state, 48, 0, 568, height, 22, INK, { bold: true });
    return n;
}
export function titleMetric(a: TitleAward): string {
    if (a.kind === 'RENT_KING') return '实际收租 ' + a.value;
    if (a.kind === 'PROPERTY_TYCOON') return '同时持有房产峰值 ' + a.value;
    if (a.kind === 'LUCKY_STAR') return '幸运收入 ' + a.value;
    return '第' + a.previousRank + '名 → 第' + a.finalRank + '名';
}
export class GlobalNoticeView {
    readonly node: Node;
    private countdown: Label;
    constructor(parent: Node, readonly notice: GlobalNotice, game: GameView) {
        this.node = mk(parent, 'GlobalNotice:' + notice.id, 0, 0, Theme.W, Theme.H);
        this.node.addComponent(BlockInputEvents);
        fillRR(gfx(this.node), 0, 0, Theme.W, Theme.H, 0, Theme.c.skyBottom);
        art(this.node, 'information_background', 0, 0, Theme.W, Theme.H, 'stretch');
        if (notice.kind === 'ROUND_REWARD') this.reward(game); else this.city();
        const bottom = mk(this.node, 'Continue', 156, 1152, 408, 92);
        art(bottom, 'button_flat_gray', 0, 0, 408, 92, 'capsule');
        this.countdown = text(bottom, '', 14, 0, 380, 86, 34, INK, { bold: true });
        this.tick();
    }
    private plaque(caption: string, y: number): void {
        art(this.node, 'title_wood', 32, y, 656, 168, 'stretch');
        const l = text(this.node, caption, 56, y + 25, 608, 105, 54, '#FFF0B3', { bold: true });
        l.enableOutline = true; l.outlineColor = col('#653616'); l.outlineWidth = 4;
    }
    private reward(game: GameView): void {
        const r = GLOBAL_RULES.roundReward;
        art(this.node, 'reward_gift', 108, 58, 504, 332);
        this.plaque(r?.name ?? '收入奖励', 338);
        const sub = informationCard(this.node, 42, 508, 636, 68, '#FFF7E4', 28);
        text(sub, '第' + this.notice.completedRound + '轮完成 · 收入奖励已到账', 8, 0, 620, 68, 29, '#722F19', { bold: true });
        const table = informationCard(this.node, 36, 590, 648, 364, '#FFFAEC', 28);
        text(table, '玩家', 40, 8, 218, 46, 24, '#603E25', { bold: true });
        text(table, '累计收入', 276, 8, 150, 46, 24, '#603E25', { bold: true });
        text(table, '本次奖励', 440, 8, 190, 46, 24, '#603E25', { bold: true });
        const list = new ScrollList(table, 16, 58, 616, 292);
        this.notice.awards.forEach((a, i) => {
            const p = game.players.find(p => p.playerId === a.playerId);
            const row = informationCard(list.content, 0, i * 72, 616, 68, a.playerId === ctx.store.myId ? '#FFF0BC' : '#FFFDF5', 16, false);
            avatar(row, 10, 8, 52, p?.avatar ?? 0, p?.nickname ?? '玩家');
            text(row, p?.nickname ?? '玩家', 76, 0, 168, 68, 27, INK, { bold: true, align: 'l' });
            text(row, String(a.income), 250, 0, 160, 68, 28, INK, { bold: true });
            text(row, '+' + a.reward, 424, 0, 182, 68, 34, '#16833B', { bold: true });
        });
        list.setContentHeight(this.notice.awards.length * 72);
        const own = this.notice.awards.find(a => a.playerId === ctx.store.myId);
        const box = informationCard(this.node, 44, 974, 632, 150, '#D5F2FF', 32);
        if (own) {
            const me = game.players.find(p => p.playerId === own.playerId);
            avatar(box, 30, 16, 92, me?.avatar ?? 0, me?.nickname ?? '我');
            text(box, '你的奖励', 144, 6, 452, 42, 29, INK, { bold: true });
            text(box, '+' + own.reward, 144, 44, 452, 68, 58, '#16833B', { bold: true });
        } else text(box, '本轮奖励已发放', 24, 16, 584, 88, 38, INK, { bold: true });
        text(box, r ? '按累计玩法收入' + r.rewardPercent + '%发放 · 每人最多' + r.perPlayerCap : '', 12, 114, 608, 32, 21, '#245C9A', { bold: true });
    }
    private city(): void {
        const city = this.notice.event!;
        art(this.node, city.spec.artworkKey, 38, 44, 644, 535);
        this.plaque(city.spec.name + '即将开启', 538);
        const panel = informationCard(this.node, 44, 728, 632, 376, '#FFF8E8', 32);
        const rows = [
            ['icon_clock_normal', '第' + city.startRound + '—' + (city.endRound - 1) + '轮生效'],
            ['info_upgrade', cityEffect(city)],
            ['info_properties', '对所有存活玩家生效'],
        ];
        rows.forEach(([key, label], i) => {
            const row = informationCard(panel, 24, 24 + i * 92, 584, 84, '#FFF1D7', 16, false);
            art(row, key, 18, 12, 60, 60);
            text(row, label, 96, 0, 474, 84, 33, INK, { bold: true, align: 'l' });
        });
        text(panel, '持续' + city.spec.durationRounds + '整轮 · 效果不叠加', 24, 316, 584, 44, 25, '#71472F', { bold: true });
    }
    tick(): void {
        const now = ctx.store.online?.now() ?? ctx.clock.now();
        setText(this.countdown, Math.max(0, Math.ceil((this.notice.endsAt - now) / 1000)) + '秒后继续');
    }
    destroy(): void { this.node.removeFromParent(); this.node.destroy(); }
}
