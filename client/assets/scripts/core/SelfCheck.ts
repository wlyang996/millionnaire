/**
 * 无头逻辑自检（纯 TS，不依赖 cc 与 node）：规则数值、棋盘模板、排名、昵称检查、倒计时、演示数据一致性。
 * 两种运行方式：1) 演示菜单"逻辑自检"按钮（引擎内）；2) 命令行：先用 tsc 编译 core/*.ts 再 node 调用 runSelfCheck()（见 README）。
 */
import { DESIGN_30, DESIGN_50 } from './BoardNames';
import { matchClockOpacity, matchClockState, remainingSecFromMs } from './MatchClock';
import { Theme } from './Theme';
import {
    advanceEvent, canClickCard, CARD_ODDS, clickCard, closeResult, EVENT_IDLE, EVENT_KIND_ODDS, eventViewMode, rollEvent, triggerEvent,
} from './EventDraw';
import { axisCell, boardAxis, eventDeckRect } from './BoardLayout';
import { buildBoard, countTypes, gridCell, gridFor, ringLength } from './BoardLayout';
import { defaultGroups } from './SetBonus';
import { Clock, Countdown } from './Clock';
import { MockStore } from './MockStore';
import {
    auctionParams, checkNickname, clampTradePrice, debtShortfall, emergencyMortgage, maxPlayers, netWorth,
    groupCards, rankStandings, rentOf, setRentPercent, standardValue, stationRent, toothCount, tradeRange, TIERS, STATION,
} from './Rules';
import { textWidth } from './Theme';

export interface CheckResult { passed: number; failures: string[] }

export function runSelfCheck(): CheckResult {
    const fails: string[] = [];
    let passed = 0;
    const eq = (name: string, actual: unknown, expected: unknown): void => {
        const a = JSON.stringify(actual);
        const e = JSON.stringify(expected);
        if (a === e) passed++;
        else fails.push(name + '：期望 ' + e + '，实际 ' + a);
    };
    const ok = (name: string, cond: boolean): void => eq(name, cond, true);
    for (const size of [30, 50] as const) {
        const grid = gridFor(size);
        const xs = boardAxis(grid.cols, grid.tile);
        const ys = boardAxis(grid.rows, grid.tile);
        ok('外围加宽不改变棋盘范围 ' + size, xs[grid.cols] === grid.cols * grid.tile && ys[grid.rows] === grid.rows * grid.tile);
        ok('所有格子中心点击仍命中原格 ' + size, buildBoard(size).every(tile => {
            const c = gridCell(tile.index, grid);
            return axisCell(xs, (xs[c.col] + xs[c.col + 1]) / 2) === c.col
                && axisCell(ys, (ys[c.row] + ys[c.row + 1]) / 2) === c.row;
        }));
        eq('棋盘外点击不命中 ' + size, [axisCell(xs, -1), axisCell(xs, xs[grid.cols])], [-1, -1]);
    }

    // ---- 数值（requirements 第 6/7/9/8 节）----
    eq('低价租金', TIERS.LOW.rent, [100, 250, 450, 700]);
    eq('中价租金', TIERS.MID.rent, [200, 500, 900, 1400]);
    eq('高价租金', TIERS.HIGH.rent, [300, 750, 1350, 2100]);
    eq('原价', [TIERS.LOW.price, TIERS.MID.price, TIERS.HIGH.price, STATION.price], [500, 1000, 1500, 1000]);
    eq('升级费', [TIERS.LOW.upgrade, TIERS.MID.upgrade, TIERS.HIGH.upgrade], [300, 600, 900]);
    eq('rentOf 越界夹取', [rentOf('MID', -1), rentOf('MID', 9)], [200, 1400]);
    eq('车站租金', [stationRent(1), stationRent(3)], [200, 600]);
    setRentPercent(140);
    eq('租金倍率 ×1.4（地产 / 车站）', [rentOf('LOW', 1), stationRent(2)], [350, 560]);
    setRentPercent(130);
    eq('租金倍率向下取整到 10', rentOf('LOW', 0), 130);
    setRentPercent(100);
    eq('标准价值', [standardValue(false, 'LOW', 0), standardValue(false, 'LOW', 300), standardValue(false, 'HIGH', 1800), standardValue(true, undefined, 0)], [500, 650, 2400, 1000]);
    eq('应急抵押可得', [emergencyMortgage(false, 'LOW'), emergencyMortgage(false, 'MID'), emergencyMortgage(false, 'HIGH'), emergencyMortgage(true, undefined)], [400, 700, 900, 700]);
    eq('拍卖参数(1000)', auctionParams(1000), { start: 500, cap: 2500, minRaise: 100 });
    eq('交易范围(1000)', tradeRange(1000), { min: 500, max: 2500 });
    eq('交易价夹取', [clampTradePrice(1000, 0), clampTradePrice(1000, 9999)], [500, 2500]);
    eq('欠款差额', [debtShortfall(700, 0, 400), debtShortfall(700, 0, 800), debtShortfall(700, 300, 100)], [300, 0, 300]);
    eq('牙齿数', [toothCount(2), toothCount(8)], [4, 16]);
    eq('最大人数', [maxPlayers(30), maxPlayers(50)], [4, 8]);

    // ---- 棋盘模板（requirements 第 3 节；2026-10-08 事件格拆成抽卡事件 + 固定事件）----
    const c30 = countTypes(buildBoard(30));
    // 同组地产默认分组（与服务端 SetBonus.bySides 一致）：30 格 8 组、50 格 14 组，每组 2～3 块且只含普通地产
    const g30 = defaultGroups(buildBoard(30));
    eq('30格同组', [[1, 3], [5, 6], [27, 28]].map(([a, b]) => g30[a] === g30[b] && g30[a] > 0), [true, true, true]);
    eq('同组数', [new Set(g30.filter((x) => x > 0)).size, new Set(defaultGroups(buildBoard(50)).filter((x) => x > 0)).size], [8, 14]);
    eq('30格数量', [c30.START, c30.PROPERTY, c30.EVENT, c30.FIXED_EVENT, c30.UNLUCKY_EVENT, c30.BANK, c30.JAIL, c30.REST, c30.GAME_ZONE, c30.STATION], [1, 16, 3, 1, 1, 1, 1, 1, 1, 4]);
    eq('30格价位', [c30.LOW, c30.MID, c30.HIGH, c30.AUCTION_LOT], [6, 6, 4, 3]);
    const c50 = countTypes(buildBoard(50));
    eq('50格数量', [c50.START, c50.PROPERTY, c50.EVENT, c50.FIXED_EVENT, c50.UNLUCKY_EVENT, c50.BANK, c50.JAIL, c50.REST, c50.GAME_ZONE, c50.STATION], [1, 28, 5, 2, 2, 2, 1, 1, 2, 6]);
    eq('50格价位', [c50.LOW, c50.MID, c50.HIGH, c50.AUCTION_LOT], [10, 10, 8, 5]);
    for (const size of [30, 50] as const) {
        const g = gridFor(size);
        eq(size + '格环周长', ringLength(g), size);
        const seen = new Set<string>();
        let adjacent = true;
        let prev = gridCell(size - 1, g);
        for (let i = 0; i < size; i++) {
            const c = gridCell(i, g);
            seen.add(c.col + ',' + c.row);
            if (Math.abs(c.col - prev.col) + Math.abs(c.row - prev.row) !== 1) adjacent = false;
            prev = c;
        }
        eq(size + '格格位不重复', seen.size, size);
        ok(size + '格相邻格相接', adjacent);
        const cell0 = gridCell(0, g);
        eq(size + '格起点在左下', cell0, { col: 0, row: g.rows - 1 });
    }

    // ---- v6 地名与设计位置 ----
    for (const size of [30, 50] as const) {
        const design = size === 30 ? DESIGN_30 : DESIGN_50;
        const board = buildBoard(size);
        const g = gridFor(size);
        eq(size + '格列行数与设计一致', [g.cols, g.rows], [design.cols, design.rows]);
        eq(size + '格设计格数', design.tiles.length, size);
        ok(size + '格每格 col/row/type/tier/名称与设计一致', design.tiles.every((d) => {
            const t = board[d.index];
            const c = gridCell(d.index, g);
            return c.col === d.col && c.row === d.row && t.type === d.type && (t.tier ?? null) === d.tier && t.name === d.name;
        }));
        const props = board.filter((t) => t.type === 'PROPERTY');
        eq(size + '格普通地产名字无重复', new Set(props.map((t) => t.name)).size, props.length);
        ok(size + '格全部名称非空且无重复', new Set(board.map((t) => t.name)).size === size && board.every((t) => t.name.length > 0));
        ok(size + '格普通地产名均为 3 字', props.every((t) => Array.from(t.name).length === 3));
        const cnt = (tier: string) => props.filter((t) => t.tier === tier).length;
        eq(size + '格低/中/高数量', [cnt('LOW'), cnt('MID'), cnt('HIGH')], size === 30 ? [6, 6, 4] : [10, 10, 8]);
    }
    const n30 = new Set(buildBoard(30).filter((t) => t.type === 'PROPERTY').map((t) => t.name));
    const t50 = buildBoard(50).filter((t) => t.type === 'PROPERTY');
    ok('30 格地名是 50 格地名的子集且同档', buildBoard(30).filter((t) => t.type === 'PROPERTY').every((t) => t50.some((u) => u.name === t.name && u.tier === t.tier)) && n30.size === 16);

    // ---- v6 本局剩余时间警示阈值 ----
    const st6 = (s: number) => matchClockState(s);
    eq('时间阈值 600/599/180/179/1/0', [st6(600), st6(599), st6(180), st6(179), st6(1), st6(0)], ['normal', 'warning', 'warning', 'urgent', 'urgent', 'zero']);
    eq('剩余毫秒向上取整', [remainingSecFromMs(599001), remainingSecFromMs(600000), remainingSecFromMs(0), remainingSecFromMs(-5)], [600, 600, 0, 0]);
    eq('闪烁透明度 100%↔35%', [matchClockOpacity('urgent', 0), matchClockOpacity('urgent', 600), matchClockOpacity('urgent', 1100), matchClockOpacity('warning', 600), matchClockOpacity('zero', 600)], [1, 0.35, 1, 1, 1]);

    // ---- v6 道具栏与顶部玩家条布局常量 ----
    const bd = Theme.board;
    ok('道具栏 5 项宽度不超过视口', bd.handVisible * bd.handItemW + (bd.handVisible - 1) * bd.handGap <= bd.handViewW && bd.handViewW + 24 <= Theme.W);
    ok('顶部玩家条 4 列 + 间隙不超过 720', 4 * 168 + 3 * 6 + 12 <= Theme.W && bd.playerCols === 4);
    eq('手牌按种类合并保序', groupCards(['A', 'B', 'A', 'C']), [{ type: 'A', count: 2 }, { type: 'B', count: 1 }, { type: 'C', count: 1 }]);
    const stH = new MockStore();
    const gh = groupCards(stH.game.myHand.map((c) => c.type));
    eq('演示手牌首屏 5 项 + 横滑出狱', gh.slice(0, 5).map((x) => x.type).concat(gh.slice(5).map((x) => x.type)), ['RENT_WAIVER', 'BUILD', 'AUCTION', 'FIXED_MOVE', 'ROADBLOCK', 'JAIL_RELEASE']);

    // ---- 事件卡抽卡状态机 ----
    const seq = (vals: number[]) => {
        let i = 0;
        return () => vals[Math.min(i++, vals.length - 1)];
    };
    eq('事件概率总和 100%', Math.round(EVENT_KIND_ODDS.reduce((a, [, p]) => a + p, 0) * 1000), 1000);
    eq('道具概率总和 100', Math.round(CARD_ODDS.reduce((a, [, p]) => a + p, 0) * 10), 1000);
    const kindAt = (r: number) => rollEvent(seq([r, 0, 0])).kind;
    eq('事件类别阈值 0.29/0.30/0.54/0.55/0.79/0.80/0.94/0.95', [0.29, 0.3, 0.54, 0.55, 0.79, 0.8, 0.94, 0.95].map(kindAt),
        ['CASH_REWARD', 'CASH_FINE', 'CASH_FINE', 'CARD', 'CARD', 'MOVE', 'MOVE', 'JAIL']);
    const amounts = new Set<number>();
    for (let k = 0; k < 9; k++) amounts.add(rollEvent(seq([0, (k + 0.5) / 9])).amount);
    eq('奖励/罚款金额 100~500 步长 50（9 档）', Array.from(amounts).sort((a, b) => a - b), [100, 150, 200, 250, 300, 350, 400, 450, 500]);
    eq('位移 1~3 前后各半', [rollEvent(seq([0.8, 0, 0.2])).steps, rollEvent(seq([0.8, 0.99, 0.7])).steps, rollEvent(seq([0.8, 0.5, 0.3])).steps], [1, -3, 2]);
    eq('道具按概率取值', [rollEvent(seq([0.6, 0])).card, rollEvent(seq([0.6, 0.99])).card], ['ROADBLOCK', 'CLEAR_LAND']);
    const tm = { flipMs: 700, autoMs: 15000 };
    let es = EVENT_IDLE;
    eq('初始 IDLE，任何人都是默认视图', [es.phase, eventViewMode(es, 'p1'), eventViewMode(es, 'p2')], ['IDLE', 'DEFAULT', 'DEFAULT']);
    es = triggerEvent(es, 'p1', 1000);
    eq('触发后 WAITING；仅本人 MY_DRAW，他人 OTHER_DRAWING', [es.phase, eventViewMode(es, 'p1'), eventViewMode(es, 'p2'), eventViewMode(es, 'obs')], ['WAITING', 'MY_DRAW', 'OTHER_DRAWING', 'OTHER_DRAWING']);
    ok('仅本人可点击', canClickCard(es, 'p1') && !canClickCard(es, 'p2'));
    ok('触发中再次触发被忽略', triggerEvent(es, 'p2', 1100) === es);
    const c0 = clickCard(es, 'p2', 1200, seq([0]));
    ok('他人点击无效', !c0.accepted && c0.state === es);
    const c1 = clickCard(es, 'p1', 1200, seq([0, 0]));
    ok('本人点击进入 FLIPPING 且已定结果', c1.accepted && c1.state.phase === 'FLIPPING' && c1.state.result !== null);
    const c2 = clickCard(c1.state, 'p1', 1300, seq([0.99]));
    ok('重复点击不重新抽取', !c2.accepted && c2.state === c1.state && c2.state.result === c1.state.result);
    const a0 = advanceEvent(c1.state, 1500, tm, seq([0]));
    ok('翻牌未满时长保持 FLIPPING', a0.state === c1.state && a0.settle === null);
    const a1 = advanceEvent(c1.state, 1900, tm, seq([0]));
    ok('翻牌满时长进入 RESULT 并结算一次', a1.state.phase === 'RESULT' && a1.settle === c1.state.result && a1.state.settled === 1);
    const a2 = advanceEvent(a1.state, 5000, tm, seq([0]));
    ok('RESULT 下重复推进不再结算', a2.state === a1.state && a2.settle === null);
    ok('他人不能确认关闭', closeResult(a1.state, 'p2') === a1.state);
    const closed = closeResult(a1.state, 'p1');
    eq('本人确认后回 IDLE（结算计数保留）', [closed.phase, closed.actor, closed.settled], ['IDLE', null, 1]);
    const to = advanceEvent(es, 1000 + 15000, tm, seq([0, 0]));
    ok('等待超时系统代抽进入 FLIPPING', to.state.phase === 'FLIPPING' && to.settle === null);
    const to2 = advanceEvent(to.state, 1000 + 15000 + 700, tm, seq([0]));
    ok('超时代抽只结算一次', to2.state.phase === 'RESULT' && to2.state.settled === 1 && advanceEvent(to2.state, 99999, tm, seq([0])).settle === null);
    ok('等待未超时不代抽', advanceEvent(es, 1000 + 14999, tm, seq([0])).state === es);
    // 牌堆位置不越过内圈、不压外圈格
    for (const size of [30, 50] as const) {
        const g = gridFor(size);
        const fit = Math.min(Theme.W / (g.cols * g.tile), 740 / (g.rows * g.tile));
        const r = eventDeckRect(g, fit);
        ok(size + '格事件牌堆在内圈范围内', r.x >= g.tile && r.y >= g.tile && r.x + r.w <= (g.cols - 1) * g.tile && r.y + r.h <= (g.rows - 1) * g.tile);
    }

    // ---- 排名：1、1、3，破产者在后 ----
    const r = rankStandings([
        { playerId: 'a', netWorth: 5000, cash: 1000 },
        { playerId: 'b', netWorth: 5000, cash: 1000 },
        { playerId: 'c', netWorth: 3000, cash: 500 },
        { playerId: 'd', netWorth: 3000, cash: 900, bankrupt: true },
        { playerId: 'e', netWorth: 4000, cash: 100 },
    ]);
    eq('并列排名', r.map((x) => x.playerId + x.rank), ['a1', 'b1', 'e3', 'c4', 'd5']);
    const r2 = rankStandings([{ playerId: 'a', netWorth: 100, cash: 50 }, { playerId: 'b', netWorth: 100, cash: 60 }]);
    eq('同净资产比现金', r2.map((x) => x.playerId + x.rank), ['b1', 'a2']);
    eq('净资产公式', netWorth(1000, [
        { isStation: false, tier: 'MID', p: { tileIndex: 1, owner: 'x', level: 1, mortgaged: false, mortgagePaid: 0, upgradeSpent: 600 } },
        { isStation: true, p: { tileIndex: 4, owner: 'x', level: 0, mortgaged: true, mortgagePaid: 700, upgradeSpent: 0 } },
    ]), 1000 + 1300 + (1000 - 700));

    // ---- 昵称 ----
    ok('正常昵称', checkNickname('小林').ok);
    ok('拒绝零宽', !checkNickname('小​林').ok);
    ok('拒绝 RLO', !checkNickname('‮abc').ok);
    ok('拒绝双向隔离', !checkNickname('a⁦b').ok);
    ok('拒绝纯空白', !checkNickname('   ').ok);
    ok('拒绝空', !checkNickname('').ok);
    ok('拒绝 BOM', !checkNickname('﻿小').ok);
    ok('放行 emoji ZWJ 序列', checkNickname('\u{1F468}‍\u{1F469}').ok);
    ok('拒绝裸 ZWJ', !checkNickname('a‍b').ok);
    ok('拒绝过长', !checkNickname('一二三四五六七八九十十一').ok);

    // ---- 倒计时（截止时间驱动、可暂停）----
    let t = 1000;
    const clock = new Clock(() => t);
    const cd = new Countdown(clock);
    cd.start(15);
    t += 14200;
    eq('倒计时剩余秒', cd.remainingSec(), 1);
    clock.pause();
    t += 60000;
    eq('暂停期间冻结', cd.remainingSec(), 1);
    clock.resume();
    t += 900;
    ok('到期', cd.expired());
    ok('到期只触发一次', cd.consumeExpire() && !cd.consumeExpire());
    cd.start(20);
    t += 18000;
    const hard = clock.now() + 20000;
    cd.extendTo(3000, hard);
    eq('拍卖最后3秒延长到3秒', cd.remainingMs(), 3000);
    cd.extendTo(30000, clock.now() + 5000);
    eq('延长不超过总时长上限', cd.remainingMs(), 5000);
    ok('textWidth 估算', textWidth('好友房', 20) === 60);

    // ---- 演示数据一致性：各场景 ----
    for (const size of [30, 50] as const) {
        for (let n = 2; n <= 8; n++) {
            const st = new MockStore();
            st.patchScenario({ boardSize: size, players: n });
            const sc = st.scenario;
            const exp = size === 30 ? Math.min(n, 4) : n;
            eq('场景人数 ' + size + '格/' + n + '人', st.game.players.length, exp);
            ok('30格不超过4人 ' + size + '/' + n, sc.boardSize === 50 || st.game.players.length <= 4);
            const own = st.game.properties.filter((p) => p.owner);
            ok('资产归属玩家存在 ' + size + '/' + n, own.every((p) => !!st.player(p.owner as string)));
            const res = st.buildResult();
            const ranks = res.standings.map((s) => s.rank);
            ok('结算首名为 1 ' + size + '/' + n, ranks[0] === 1);
            ok('结算编号非降序 ' + size + '/' + n, ranks.every((v, i) => i === 0 || v >= ranks[i - 1]));
            if (res.standings.filter((x) => !x.bankrupt).length >= 3) ok('演示结算含 1/1/3 并列 ' + size + '/' + n, ranks[0] === 1 && ranks[1] === 1 && ranks[2] === 3);
            // 并列后下一名应跳号：rank == 位置+1 或与前一名相同
            ok('结算 1/1/3 跳号规则 ' + size + '/' + n, ranks.every((v, i) => i === 0 || v === ranks[i - 1] || v === i + 1));
        }
    }
    const st = new MockStore();
    st.setProfile({ nickname: '头像检查', avatar: 1 });
    eq('资料保存同步房间成员头像', st.session.members[0].avatar, 1);
    eq('资料保存同步对局玩家头像', [st.me().avatar, st.me().nickname], [1, '头像检查']);
    st.patchScenario({ host: true });
    eq('创建房间重建仍保留选择头像', [st.session.members[0].avatar, st.me().avatar], [1, 1]);
    st.patchScenario({ players: 4, boardSize: 30 });
    eq('切换人数地图仍保留选择头像', [st.me().avatar, st.me().nickname], [1, '头像检查']);
    st.patchScenario({ boardSize: 50, players: 8 });
    st.patchScenario({ boardSize: 30 });
    eq('8人切30格收敛为4人', [st.scenario.boardSize, st.game.players.length], [30, 4]);
    const me = st.me();
    me.position = st.game.tiles.length - 2;
    const cash0 = me.cash;
    const mv = st.moveBy('p1', 3);
    eq('越过起点领 1000', [mv.passedStart, st.me().cash - cash0], [true, 1000]);
    ok('欠款能力 = 现金 + 应急抵押所得', st.debtCapacity() > st.me().cash);
    st.patchScenario({ spectator: true });
    ok('观战场景：我已破产且无手牌', st.isSpectator() && st.game.myHand.length === 0);

    return { passed, failures: fails };
}
