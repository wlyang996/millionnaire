package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.EventKind;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 起点三选一（用户 2026-10-08）：停在起点开窗口，选一张得现金（60%，200～1000 步长 100）或道具（40%）。
 * 30 格正式棋盘：p1 依次掷 5、6、5、6、6、2 停在 5 / 11 / 16 / 22 / 28 / 0，p2 掷 1、3、4、2、3 停在 1 / 4 / 8 / 10 / 13，途中只有买地窗口（都放弃）。
 */
class StartPickTest {
    private static final RuleConfig RULES = RuleConfigs.defaultV1();

    private static Table reachStart(List<ScriptedRandom.Step> after) {
        return reachStart(2, after);
    }

    private static Table reachStart(int lastRoll, List<ScriptedRandom.Step> after) {
        Table t = approachStart(lastRoll, after);
        long before = t.cash("p1");
        t.rollOnly();
        assertEquals(0, t.position("p1"));
        assertEquals(LandingStep.START_PICK, t.game().turn().landing().step(), "landing on start opens the pick");
        assertEquals(before + RULES.economy().startReward(), t.cash("p1"), "the start reward is paid as usual");
        return t;
    }

    private static Table approachStart(int lastRoll, List<ScriptedRandom.Step> after) {
        List<ScriptedRandom.Step> all = new ArrayList<>(Table.script(Table.order(90, 10), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 5, 1, 6, 3, 5, 4, 6, 2, 6, 3, lastRoll)));
        all.addAll(after);
        Table t = new Table(RULES, new ScriptedRandom(all), 1).start(2, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 30);
        for (int i = 0; i < 10; i++) {
            String who = t.current();
            t.rollOnly();
            while (who.equals(t.current()) && t.game().turn().landing() != null) {
                t.pass();
            }
        }
        return t;
    }

    @Test
    void eventForwardMoveAlsoResumesAfterTheStartPick() {
        Table t = approachStart(1, Table.script(Table.steps(DrawPoint.EVENT_KIND, 100, 60),
                Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, 0), Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 2),
                Table.steps(DrawPoint.START_PICK_KIND, 100, 10), Table.steps(DrawPoint.START_PICK_CASH, 9, 0)));
        t.rollOnly();
        assertEquals(29, t.position("p1"));
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(0, t.position("p1"));
        assertEquals(LandingStep.START_PICK, t.game().turn().landing().step());
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        t.act(w -> new GameCommand.PickStartCard("p1", w, 0));
        assertEquals(2, t.position("p1"));
        assertEquals(1, t.log.stream().filter(GameEvent.EventDrawn.class::isInstance).count(), "the continuation cannot draw a second event");
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void roadblocksTakePriorityAndAreResolvedAgainOnTheRemainingPath() {
        var board = new com.millionnaire.engine.core.state.BoardState("test", List.of())
                .placed(new com.millionnaire.engine.core.state.Roadblock(1, 1, "p2", 1));
        var plan = MovementRules.segment(1, com.millionnaire.engine.core.state.MoveKind.DICE, 28, 6, 30);
        var first = MovementRules.resolve(plan, board, "p1", 30, true);
        assertEquals(0, first.to());
        assertNull(first.stoppedBy());
        var next = MovementRules.resolve(MovementRules.segment(2, first.kind(), 0,
                MovementRules.remainingAtStart(first), 30), board, "p1", 30, true);
        assertEquals(1, next.to());
        assertNotNull(next.stoppedBy());
        assertEquals(0, MovementRules.remainingAtStart(next));
        var beforeStart = board.placed(new com.millionnaire.engine.core.state.Roadblock(2, 29, "p2", 1));
        assertEquals(29, MovementRules.resolve(plan, beforeStart, "p1", 30, true).to());
        assertEquals(4, MovementRules.resolve(plan, new com.millionnaire.engine.core.state.BoardState("test", List.of()),
                "p1", 30, false).to(), "disabling start pick preserves uninterrupted movement");
        assertEquals(28, MovementRules.resolve(MovementRules.segment(1,
                com.millionnaire.engine.core.state.MoveKind.EVENT_BACKWARD, 1, 3, 30),
                new com.millionnaire.engine.core.state.BoardState("test", List.of()), "p1", 30, true).to());
    }

    @Test
    void passingStartPausesThenContinuesTheUnspentDiceWithoutAnotherRoll() {
        Table t = reachStart(6, Table.script(Table.steps(DrawPoint.START_PICK_KIND, 100, 10),
                Table.steps(DrawPoint.START_PICK_CASH, 9, 3)));
        var pause = t.state;
        assertEquals(pause, t.engine.restore(t.engine.snapshot(pause)));
        assertEquals(pause, t.engine.rebuild(t.log));
        int before = t.log.size();
        long cash = t.cash("p1");
        t.act(w -> new GameCommand.PickStartCard("p1", w, 1));
        assertEquals(4, t.position("p1"), "two steps to start + four remaining steps");
        assertEquals("p1", t.current(), "the final landing is handled in the same turn");
        assertEquals(cash + 500, t.cash("p1"), "the start reward is not paid twice");
        var resumed = t.log.subList(before, t.log.size()).stream().filter(GameEvent.PlayerMoved.class::isInstance)
                .map(GameEvent.PlayerMoved.class::cast).toList();
        assertEquals(1, resumed.size());
        assertEquals(0, resumed.getFirst().from());
        assertEquals(4, resumed.getFirst().steps());
        assertFalse(t.log.subList(before, t.log.size()).stream().anyMatch(GameEvent.DiceRolled.class::isInstance));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void passingStartTimeoutDrawsThenResumesAndRejectsTheOldWindow() {
        Table t = reachStart(6, Table.script(Table.steps(DrawPoint.START_PICK_KIND, 100, 0),
                Table.steps(DrawPoint.START_PICK_CASH, 9, 0)));
        long window = t.window().windowId();
        t.tick(t.window().window().deadline());
        assertEquals(4, t.position("p1"));
        assertNotNull(t.send(t.now, new GameCommand.PickStartCard("p1", window, 0)).rejection());
        assertEquals(1, t.log.stream().filter(GameEvent.StartPickDrawn.class::isInstance).count());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void pickingCashPaysTheDrawnAmount() {
        // 种类 10 < 60 → 现金；金额序号 3 → 200 + 300 = 500
        Table t = reachStart(Table.script(Table.steps(DrawPoint.START_PICK_KIND, 100, 10), Table.steps(DrawPoint.START_PICK_CASH, 9, 3)));
        long before = t.cash("p1");
        t.act(w -> new GameCommand.PickStartCard("p1", w, 2));
        assertEquals(before + 500, t.cash("p1"));
        var e = t.log.stream().filter(GameEvent.StartPickDrawn.class::isInstance).map(GameEvent.StartPickDrawn.class::cast).toList();
        assertEquals(1, e.size());
        assertEquals(2, e.get(0).index());
        assertEquals(EventKind.CASH_REWARD, e.get(0).kind());
        assertEquals("p2", t.current());
        assertEquals(t.now + RULES.timing().startPickPresentationMs(), t.window().window().opensAt());
        assertEquals(RULES.timing().decisionWindowMs(), t.window().window().deadline() - t.window().window().opensAt());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test
    void pickingACardAddsOneToTheHand() {
        // 种类 70 ≥ 60 → 道具；道具按事件得道具的概率抽，0 → 路障
        Table t = reachStart(Table.script(Table.steps(DrawPoint.START_PICK_KIND, 100, 70), Table.steps(DrawPoint.EVENT_CARD, 1000, 0)));
        int hand = t.game().player("p1").orElseThrow().hand().size();
        t.act(w -> new GameCommand.PickStartCard("p1", w, 0));
        assertEquals(hand + 1, t.game().player("p1").orElseThrow().hand().size());
        assertEquals(CardType.ROADBLOCK, t.game().player("p1").orElseThrow().hand().get(hand));
        assertEquals("p2", t.current());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void outOfRangeIndexIsRejectedAndTimeoutPicksForThePlayer() {
        Table t = reachStart(Table.script(Table.steps(DrawPoint.START_PICK_KIND, 100, 0), Table.steps(DrawPoint.START_PICK_CASH, 9, 0)));
        assertNotNull(t.act(w -> new GameCommand.PickStartCard("p1", w, 3)).rejection());
        long before = t.cash("p1");
        t.tick(t.window().window().deadline());
        assertEquals(before + 200, t.cash("p1"));
        assertTrue(t.log.stream().anyMatch(e -> e instanceof GameEvent.StartPickDrawn s && s.auto()));
    }
}
