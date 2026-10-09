package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.EventKind;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 事件拆分（用户 2026-10-08）：正式配置的 30 格棋盘为 2 E、9 F（随地吐痰 -200）、17 E、24 F（搭乘快车：去随机车站）、29 E；
 * 抽卡事件新增加盖、降级、去车站、回起点。权重区间：奖励 [0,22) 罚款 [22,40) 道具 [40,60) 位移 [60,72) 入狱 [72,77)
 * 加盖 [77,85) 降级 [85,91) 去车站 [91,96) 回起点 [96,100)。p1 抽 90 先行动，开局每人 2 张路障。
 */
class EventsV2Test {
    private static final RuleConfig RULES = RuleConfigs.defaultV1();

    @SafeVarargs
    private static Table table(List<ScriptedRandom.Step>... rest) {
        List<ScriptedRandom.Step> head = Table.script(Table.order(90, 10), Table.deal(2));
        List<ScriptedRandom.Step> all = new java.util.ArrayList<>(head);
        for (List<ScriptedRandom.Step> r : rest) {
            all.addAll(r);
        }
        return new Table(RULES, new ScriptedRandom(all), 1).start(2);
    }

    /** 当前玩家投骰，并把落点上的窗口都放弃（买地 / 升级），直到换人。 */
    private static void turn(Table t) {
        String who = t.current();
        t.rollOnly();
        while (t.session().inGame() && who.equals(t.current()) && t.game().turn().landing() != null) {
            t.pass();
        }
    }

    private static void consistent(Table t) {
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test
    void productionBoardsSplitEventTilesIntoDrawnAndFixed() {
        var b30 = RULES.board(RuleConfigs.BOARD_30).orElseThrow();
        assertEquals(3, b30.count(TileType.EVENT));
        assertEquals(1, b30.count(TileType.FIXED_EVENT));
        assertEquals(1, b30.count(TileType.UNLUCKY_EVENT));
        assertEquals(TileType.FIXED_EVENT, b30.tiles().get(9).type());
        assertEquals(TileType.UNLUCKY_EVENT, b30.tiles().get(24).type());
        assertEquals(RuleConfigs.LUCKY, b30.pool(TileType.FIXED_EVENT));
        assertEquals(RuleConfigs.UNLUCKY, b30.pool(TileType.UNLUCKY_EVENT));
        var b50 = RULES.board(RuleConfigs.BOARD_50).orElseThrow();
        assertEquals(5, b50.count(TileType.EVENT));
        assertEquals(2, b50.count(TileType.FIXED_EVENT));
        assertEquals(2, b50.count(TileType.UNLUCKY_EVENT));
        assertEquals(RuleConfigs.UNLUCKY, b50.pool(TileType.UNLUCKY_EVENT));
        assertTrue(RuleConfigs.LUCKY.stream().noneMatch(f -> f.kind() == EventKind.CASH_FINE), "lucky tiles only do good things");
        assertTrue(RuleConfigs.UNLUCKY.stream().noneMatch(f -> f.kind() == EventKind.CASH_REWARD), "unlucky tiles only do bad things");
    }

    @Test
    void aLuckyRewardPaysOnArrivalWithoutAWindow() {
        // p1 3 → 3（放弃），p2 1 → 1（放弃），p1 6 → 9 幸运格，奖池抽到 [0,25)"好人好事" +300
        var t = table(Table.dice(DrawPoint.MOVE_DIE, 3, 1, 6), Table.steps(DrawPoint.LUCKY_EVENT, 100, 10));
        turn(t);
        turn(t);
        long before = t.cash("p1");
        t.rollOnly();
        assertEquals(9, t.position("p1"));
        assertEquals(before + 300, t.cash("p1"));
        var fixed = t.log.stream().filter(GameEvent.FixedEventTriggered.class::isInstance).map(GameEvent.FixedEventTriggered.class::cast).toList();
        assertEquals(1, fixed.size());
        assertEquals(EventKind.CASH_REWARD, fixed.get(0).kind());
        assertEquals(0, fixed.get(0).pick());
        assertEquals("p2", t.current(), "no window: the reward settles and the turn ends");
        consistent(t);
    }

    @Test
    void aLuckyTileDrawsFromThePoolEachTime() {
        // 同一个幸运格（9 号）两次落点抽到不同结果：p1 先抽到"回到起点"[75,100)，p2 后抽到"免费加盖"[25,50)（无地，无事发生）
        // 回到起点后还有起点三选一：抽到现金 200
        var t = table(Table.dice(DrawPoint.MOVE_DIE, 3, 3, 6), Table.steps(DrawPoint.LUCKY_EVENT, 100, 80),
                Table.steps(DrawPoint.START_PICK_KIND, 100, 0), Table.steps(DrawPoint.START_PICK_CASH, 9, 0),
                Table.dice(DrawPoint.MOVE_DIE, 6), Table.steps(DrawPoint.LUCKY_EVENT, 100, 30));
        turn(t);
        turn(t);
        long before = t.cash("p1");
        t.rollOnly();
        assertEquals(0, t.position("p1"), "back to start");
        assertEquals(before + RULES.economy().startReward(), t.cash("p1"));
        t.pass(); // 起点三选一
        assertEquals(before + RULES.economy().startReward() + 200, t.cash("p1"));
        t.rollOnly();
        assertEquals(9, t.position("p2"));
        var fixed = t.log.stream().filter(GameEvent.FixedEventTriggered.class::isInstance).map(GameEvent.FixedEventTriggered.class::cast).toList();
        assertEquals(List.of(EventKind.TO_START, EventKind.BUILD), fixed.stream().map(GameEvent.FixedEventTriggered::kind).toList());
        assertEquals(List.of(3, 1), fixed.stream().map(GameEvent.FixedEventTriggered::pick).toList());
        consistent(t);
    }

    @Test
    void aLuckyExpressMovesForwardToARandomStation() {
        // p1 3 → 3，p2 1 → 1，p1 6 → 9 幸运格抽到"搭乘快车"[50,75)，再抽到第 2 个车站（11 号）
        var t = table(Table.dice(DrawPoint.MOVE_DIE, 3, 1, 6), Table.steps(DrawPoint.LUCKY_EVENT, 100, 60),
                Table.steps(DrawPoint.EVENT_STATION, 4, 1));
        turn(t);
        turn(t);
        t.rollOnly();
        assertEquals(11, t.position("p1"));
        assertEquals(LandingStep.BUY, t.game().turn().landing().step(), "the station landing settles normally");
        consistent(t);
    }

    /** p1 6/6/6/6 → 6、12、18、24（不幸格）；p2 1/2/1 → 1、3、4。 */
    private static Table toUnlucky(int roll, List<ScriptedRandom.Step> after) {
        return table(Table.dice(DrawPoint.MOVE_DIE, 6, 1, 6, 2, 6, 1, 6), Table.steps(DrawPoint.LUCKY_EVENT, 100, roll), after);
    }

    private static void walkToUnlucky(Table t) {
        for (int i = 0; i < 6; i++) {
            turn(t);
        }
    }

    @Test
    void anUnluckyFineChargesOnArrival() {
        var t = toUnlucky(10, List.of());
        walkToUnlucky(t);
        long before = t.cash("p1");
        t.rollOnly();
        assertEquals(24, t.position("p1"));
        assertEquals(before - 200, t.cash("p1"));
        var fixed = t.log.stream().filter(GameEvent.FixedEventTriggered.class::isInstance).map(GameEvent.FixedEventTriggered.class::cast).toList();
        assertEquals(List.of(EventKind.CASH_FINE), fixed.stream().map(GameEvent.FixedEventTriggered::kind).toList());
        assertEquals("p2", t.current());
        consistent(t);
    }

    @Test
    void anUnluckyDetourAlwaysMovesBack() {
        // 抽到"迷路倒退"[50,75)：不抽方向，只抽格数（1 + 1 = 2），从 24 退到 22（休息区）
        var t = toUnlucky(60, Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 1));
        walkToUnlucky(t);
        t.rollOnly();
        assertEquals(22, t.position("p1"));
        consistent(t);
    }

    @Test
    void anUnluckyArrestSendsToJail() {
        var t = toUnlucky(90, List.of());
        walkToUnlucky(t);
        t.rollOnly();
        assertEquals(7, t.position("p1"));
        assertTrue(t.game().player("p1").orElseThrow().inJail(), "p1 is in jail");
        consistent(t);
    }

    @Test
    void drawnBuildUpgradesAnOwnedPropertyForFree() {
        // p1 1 → 1 买下；p2 1 → 1 缴租；p1 1 → 2 抽卡：加盖（唯一候选 1 号）
        var t = table(Table.dice(DrawPoint.MOVE_DIE, 1, 1, 1), Table.steps(DrawPoint.EVENT_KIND, 100, 80),
                Table.steps(DrawPoint.EVENT_TARGET, 1, 0));
        t.rollOnly();
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        turn(t);
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(1, t.game().board().ownable(1).orElseThrow().level());
        var change = t.log.stream().filter(GameEvent.EventPropertyChanged.class::isInstance).map(GameEvent.EventPropertyChanged.class::cast).toList();
        assertEquals(List.of(new GameEvent.EventPropertyChanged("p1", change.get(0).landingId(), change.get(0).cursor(), 1, 1)), change);
        consistent(t);
    }

    @Test
    void drawnDowngradeWithoutLevelsDoesNothing() {
        var t = table(Table.dice(DrawPoint.MOVE_DIE, 2), Table.steps(DrawPoint.EVENT_KIND, 100, 88));
        t.rollOnly();
        long cash = t.cash("p1");
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        var change = t.log.stream().filter(GameEvent.EventPropertyChanged.class::isInstance).map(GameEvent.EventPropertyChanged.class::cast).toList();
        assertEquals(1, change.size());
        assertEquals(-1, change.get(0).tile());
        assertEquals(cash, t.cash("p1"));
        assertEquals("p2", t.current());
        consistent(t);
    }

    @Test
    void drawnReturnToStartWalksForwardAndCollectsTheStartReward() {
        var t = table(Table.dice(DrawPoint.MOVE_DIE, 2), Table.steps(DrawPoint.EVENT_KIND, 100, 97));
        t.rollOnly();
        long cash = t.cash("p1");
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(0, t.position("p1"));
        assertEquals(cash + RULES.economy().startReward(), t.cash("p1"));
        consistent(t);
    }

    @Test
    void drawnStationTripUsesTheStationDraw() {
        // 2 号抽卡：去车站，抽到第 2 个车站（11 号），前进 9 格
        var t = table(Table.dice(DrawPoint.MOVE_DIE, 2), Table.steps(DrawPoint.EVENT_KIND, 100, 93),
                Table.steps(DrawPoint.EVENT_STATION, 4, 1));
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(11, t.position("p1"));
        consistent(t);
    }
}
