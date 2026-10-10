/**
 * 服务端视图 → 客户端模型（core/Models）。纯 TS，不依赖 cc。
 * - 棋盘格取服务端模板（/api/boards），格子名字沿用 BoardNames（与美术设计稿逐格一致，服务端布局也已对齐）；
 * - 服务端没有的展示字段：头像按 playerId 稳定取 0..7；lastDice 取最近一次 DiceRolled；聊天暂为空；
 * - 服务端的落点与债务原样带到 GameView.landing / debt，供弹窗判断该弹什么。
 */
import { buildBoard } from '../core/BoardLayout';
import { boardNames } from '../core/BoardNames';
import {
    ROBOT_AVATAR, BoardTile, Card, CardType, GameResult, GameView, Member, PlayerView, PropertyState, RoomSettings, SessionView,
} from '../core/Models';
import { boardSizeOf, luckyPool, TIERS } from '../core/Rules';
import { BoardTemplate, SEvent, SGame, SView } from './Protocol';

/** playerId → 头像编号 0..7（同一玩家在所有人屏幕上一致）。 */
export function defaultAvatar(playerId: string): number {
    let h = 0;
    for (let i = 0; i < playerId.length; i++) h = (h * 31 + playerId.charCodeAt(i)) >>> 0;
    return h % 8;
}

export function tilesFor(boardId: string, boards: BoardTemplate[] | null): BoardTile[] {
    const size = boardSizeOf(boardId);
    const tpl = boards?.find((b) => b.id === boardId);
    if (!tpl) return buildBoard(size);
    const names = boardNames(size);
    return tpl.tiles.map((t): BoardTile => {
        const tile: BoardTile = { index: t.index, type: t.type, name: names[t.index] ?? String(t.index) };
        if (t.type === 'FIXED_EVENT' || t.type === 'UNLUCKY_EVENT') tile.lucky = luckyPool(t.type === 'UNLUCKY_EVENT');
        if (t.type === 'PROPERTY' && t.tier) {
            tile.tier = t.tier;
            tile.auctionLot = t.auctionDesignated;
        }
        return tile;
    });
}

/** 从本步事件里找最近一次掷骰点数；没有则沿用上一次。 */
export function lastDiceFrom(events: SEvent[], previous: number): number {
    for (let i = events.length - 1; i >= 0; i--) {
        const e = events[i];
        if ((e.kind === 'DiceRolled' || e.kind === 'JailRolled') && e.data && typeof e.data.value === 'number') return e.data.value;
    }
    return previous;
}

export function adaptSession(v: SView, boards: BoardTemplate[] | null, lastDice: number,
    chosen: Record<string, number> = {}): SessionView {
    // 玩家所选头像优先（服务端随推送下发），没选的按玩家 ID 取默认头像
    const avatarOf = (id: string): number => {
        const a = chosen[id];
        return typeof a === 'number' && a >= 0 && a <= ROBOT_AVATAR ? a : defaultAvatar(id);
    };
    const members: Member[] = v.members.map((m) => ({
        playerId: m.playerId, nickname: m.nickname, ready: m.ready, avatar: avatarOf(m.playerId),
    }));
    const nick = (id: string) => v.members.find((m) => m.playerId === id)?.nickname ?? '玩家' + id.slice(-4);
    const settings: RoomSettings = {
        boardId: v.settings.boardId === 'classic-30' ? 'classic-30' : 'classic-50',
        initialCash: v.settings.initialCash,
        endMode: v.settings.endMode,
        timeLimitMinutes: v.settings.timeLimitMinutes,
        rollSeconds: v.settings.rollSeconds,
        initialCards: v.settings.initialCards ?? 0,
        fastMode: v.settings.fastMode ?? false,
    };
    const lastResult: GameResult | null = v.lastResult && {
        titles: v.lastResult.titles ?? [],
        metrics: v.lastResult.metrics ?? {},
        gameNo: v.lastResult.gameNo,
        reason: v.lastResult.reason,
        standings: v.lastResult.standings.map((s) => ({
            ...s, nickname: nick(s.playerId), avatar: avatarOf(s.playerId),
        })),
    };
    return {
        roomId: v.roomId,
        status: v.game ? 'PLAYING' : 'LOBBY',
        hostId: v.hostId ?? '',
        members,
        settings,
        game: v.game ? adaptGame(v.game, boards, lastDice, nick, avatarOf) : null,
        gamesPlayed: v.gamesPlayed,
        lastResult,
    };
}

function adaptGame(g: SGame, boards: BoardTemplate[] | null, lastDice: number, nick: (id: string) => string,
    avatarOf: (id: string) => number): GameView {
    const tiles = tilesFor(g.board.boardId, boards);
    const players: PlayerView[] = g.players.map((p) => ({
        playerId: p.playerId, nickname: nick(p.playerId), avatar: avatarOf(p.playerId), position: p.position,
        cash: p.cash, handCount: p.handCount, life: p.life, inJail: p.inJail, jailFailures: p.jailFailures,
        control: p.control, conn: p.conn, frozen: p.frozen,
    }));
    const properties: PropertyState[] = g.board.ownables.map((o) => {
        const tier = tiles[o.tile]?.tier;
        return {
            tileIndex: o.tile, owner: o.owner, level: o.level, mortgaged: o.mortgaged, mortgagePaid: o.principal,
            // 服务端视图不含累计升级费：按档位标准升级费 × 等级计（建造卡免费升级的计价口径待确认，open-decisions #2）
            upgradeSpent: tier ? TIERS[tier].upgrade * o.level : 0,
        };
    });
    const myHand: Card[] = g.myHand.map((type, i) => ({ id: type + '#' + i, type: type as CardType }));
    return {
        gameNo: g.gameNo,
        progress: g.progress ?? null,
        phase: 'PLAYING',
        players,
        orderDraws: g.orderDraws.map((d) => ({ playerId: d.playerId, value: d.draws[d.draws.length - 1] ?? 0 })),
        boardId: g.board.boardId,
        turnNo: g.turnNo,
        round: g.round ?? 0,
        rentPercent: g.rentPercent ?? 100,
        currentPlayer: g.currentPlayer ?? '',
        stage: (g.stage as GameView['stage']) ?? 'NONE',
        globalEndsAt: g.globalEndsAt,
        windows: g.windows.map((w) => ({ ...w, kind: w.kind as GameView['windows'][number]['kind'] })),
        myHand,
        tiles,
        properties,
        lastDice,
        chat: [],
        landing: g.landing,
        debt: g.debt,
        minigame: g.minigame ?? null,
        cards: g.cards ? {
            chanceUsed: g.cards.chanceUsed,
            response: g.cards.response ? {
                ...g.cards.response, attack: g.cards.response.attack as CardType, response: g.cards.response.response as CardType,
            } : null,
        } : null,
        roadblocks: (g.board.roadblocks ?? []).map((r) => r.tile),
        auction: g.auction ?? null,
        trade: g.trade ?? null,
    };
}
