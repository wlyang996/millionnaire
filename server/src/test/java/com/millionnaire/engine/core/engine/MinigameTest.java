package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.LifeState;
import com.millionnaire.engine.core.state.MinigameState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 虎口拔牙（requirements 第 13 节、已采纳默认值 #15）。 */
class MinigameTest {

    /** 用真实随机推进到"有人刚落在游戏区、小游戏开始"的局面（固定种子中找第一个满足条件的）。 */
    static Table started(int players) {
        for (long seed = 1; seed < 400; seed++) {
            Table t = Table.production(seed).start(players, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 60);
            for (int turns = 0; turns < 40 && t.session().inGame(); turns++) {
                while (t.session().inGame() && t.game().turn().stage() == TurnStage.LANDING) {
                    t.pass();
                }
                if (!t.session().inGame() || t.game().minigame() != null) {
                    break;
                }
                t.rollOnly();
                if (t.session().inGame() && t.game().minigame() != null) {
                    return t;
                }
            }
        }
        throw new AssertionError("no seed reached the game zone");
    }

    private static <T extends Event> List<T> events(List<Event> log, Class<T> type) {
        return log.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static FlowFrame top(Table t) {
        return t.game().flow().top().orElseThrow();
    }

    @Test
    void landingOnTheGameZoneStartsAToothGameForAllAlivePlayersFromTheTrigger() {
        Table t = started(3);
        var g = t.game();
        MinigameState m = g.minigame();
        String trigger = g.turn().currentPlayer();
        assertEquals(trigger, m.trigger());
        assertEquals(TurnStage.AWAITING_FLOW, g.turn().stage());
        assertEquals(LandingStep.MINIGAME, g.turn().landing().step());
        assertEquals(6, m.teeth(), "teeth = participants × 2");
        assertEquals(3, m.participants().size());
        assertEquals(trigger, m.participants().get(0), "the trigger picks first");
        // 之后按行动顺序轮转
        List<String> order = g.players().stream().map(p -> p.playerId()).toList();
        int i = order.indexOf(trigger);
        assertEquals(List.of(order.get(i), order.get((i + 1) % 3), order.get((i + 2) % 3)), m.participants());
        FlowFrame w = top(t);
        assertEquals(FlowKind.MINIGAME, w.kind());
        assertEquals(trigger, w.owner());
        assertEquals(t.config.timing().toothPickMs(), w.window().deadline() - w.window().opensAt(), "a manual pick has 10 s");
        // 危险牙保密：公开事件与视图都不含它
        var started = events(t.log, GameEvent.MinigameStarted.class).getLast();
        assertEquals(List.copyOf(m.participants()), started.participants());
        for (String viewer : order) {
            var view = SessionDomain.INSTANCE.project(t.state, viewer).game().minigame();
            assertEquals(m.picks(), view.picks());
            assertEquals(trigger, view.picker());
            assertEquals(w.windowId(), view.windowId());
        }
        assertTrue(t.log.stream().filter(e -> e instanceof KernelEvent.RandomDrawn d && d.point() == DrawPoint.DANGER_TOOTH)
                .allMatch(e -> e.visibility() == com.millionnaire.engine.core.event.Visibility.SERVER_ONLY));
        t.engine.validate(t.state);
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test
    void manualPicksRunUntilTheDangerToothAndEveryOtherParticipantEarnsTheReward() {
        Table t = started(3);
        MinigameState m = t.game().minigame();
        TreeMap<String, Long> before = new TreeMap<>();
        m.participants().forEach(p -> before.put(p, t.cash(p)));
        long turn = t.game().turn().turnNo();
        String trigger = m.trigger();
        int picks = 0;
        while (t.game() != null && t.game().minigame() != null) {
            MinigameState now = t.game().minigame();
            FlowFrame w = top(t);
            assertEquals(now.picker(), w.owner());
            assertNull(t.send(Math.max(t.now + 10, w.window().opensAt()),
                    new GameCommand.PickTooth(now.picker(), w.windowId(), now.remaining().get(0))).rejection());
            picks++;
        }
        GameEvent.MinigameEnded end = events(t.log, GameEvent.MinigameEnded.class).getLast();
        assertEquals(m.danger(), end.danger());
        assertEquals(m.danger() + 1, picks, "lowest-first picks hit the danger tooth on pick danger+1");
        assertEquals(m.participants().get(m.danger() % 3), end.loser());
        assertEquals(500, end.reward());
        for (String p : m.participants()) {
            long expected = before.get(p) + (p.equals(end.loser()) ? 0 : 500);
            assertEquals(expected, t.cash(p), p + " reward (the loser pays nothing)");
        }
        // 小游戏结束后落点与回合结束，下一位玩家开始投骰
        assertNull(t.game().minigame());
        assertEquals(turn + 1, t.game().turn().turnNo());
        assertNotEquals(trigger, t.current());
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        t.game().ledger().verifyInvariants();
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void invalidPicksAreRejectedWithoutChangingState() {
        Table t = started(2);
        MinigameState m = t.game().minigame();
        FlowFrame w = top(t);
        long at = Math.max(t.now + 10, w.window().opensAt());
        String other = m.participants().get(1);
        assertEquals(RejectionCode.NOT_YOUR_WINDOW, t.send(at, new GameCommand.PickTooth(other, w.windowId(), 0)).rejection());
        assertEquals(RejectionCode.INVALID_ARGUMENT, t.send(at, new GameCommand.PickTooth(m.picker(), w.windowId(), m.teeth())).rejection());
        assertEquals(RejectionCode.INVALID_ARGUMENT, t.send(at, new GameCommand.PickTooth(m.picker(), w.windowId(), -1)).rejection());
        assertEquals(RejectionCode.WINDOW_MISMATCH, t.send(at, new GameCommand.PickTooth(m.picker(), w.windowId() + 1, 0)).rejection());
        assertEquals(m, t.game().minigame());
        int safe = m.danger() == 0 ? 1 : 0;
        t.send(at, new GameCommand.PickTooth(m.picker(), w.windowId(), safe));
        FlowFrame next = top(t);
        assertEquals(other, next.owner(), "the turn passes to the next participant");
        assertEquals(RejectionCode.INVALID_ARGUMENT, t.send(Math.max(t.now + 10, next.window().opensAt()),
                new GameCommand.PickTooth(other, next.windowId(), safe)).rejection(), "a pressed tooth cannot be picked again");
        assertNotNull(t.send(t.now + 10, new GameCommand.RollDice(t.current(), next.windowId())).rejection(),
                "no board action while the minigame runs");
    }

    @Test
    void timeoutAndHostedPlayersAreAutoPickedFromTheRemainingTeeth() {
        Table t = started(2);
        MinigameState m = t.game().minigame();
        String second = m.participants().get(1);
        t.send(t.now + 1, new GameCommand.SetControl(t.game().gameNo(), second, ControlMode.HOSTED));
        FlowFrame w = top(t);
        int before = t.game().minigame().picks().size();
        t.tick(w.window().deadline());                       // 手动玩家 10 秒超时 → 服务端代选
        var picked = events(t.log, GameEvent.ToothPicked.class).getLast();
        assertTrue(picked.auto());
        assertEquals(m.trigger(), picked.playerId());
        if (t.game() != null && t.game().minigame() != null) {
            assertEquals(before + 1, t.game().minigame().picks().size());
            FlowFrame hosted = top(t);
            assertEquals(second, hosted.owner());
            assertEquals(t.config.timing().autoActDelayMs(), hosted.window().deadline() - hosted.window().opensAt(),
                    "a hosted picker is auto-picked after the automatic-action delay");
            t.tick(hosted.window().deadline());
            assertTrue(events(t.log, GameEvent.ToothPicked.class).getLast().auto());
        }
        t.finishMinigame();
        assertTrue(events(t.log, GameEvent.ToothPicked.class).stream().anyMatch(GameEvent.ToothPicked::auto));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void surrenderDuringTheMinigameIsDeferredAndThePlayerIsAutoPickedUntilItEnds() {
        Table t = started(3);
        MinigameState m = t.game().minigame();
        String quitter = m.participants().get(2);
        assertNull(t.send(t.now + 1, new GameCommand.Surrender(quitter, t.game().gameNo())).rejection());
        assertTrue(t.game().pendingSurrenders().contains(quitter));
        assertTrue(t.game().player(quitter).orElseThrow().alive(), "participants stay alive until the minigame ends");
        t.engine.validate(t.state);
        t.finishMinigame();
        assertNull(t.game().minigame());
        assertEquals(LifeState.SURRENDERED, t.game().player(quitter).orElseThrow().life());
        var quitterPicks = events(t.log, GameEvent.ToothPicked.class).stream().filter(p -> p.playerId().equals(quitter)).toList();
        assertTrue(quitterPicks.stream().allMatch(GameEvent.ToothPicked::auto), "a deferred surrenderer is auto-picked");
        assertEquals(0, t.cash(quitter), "the surrender is settled after the rewards");
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void everyBoundaryInsideTheMinigameResumesFromASnapshot() {
        Table t = started(2);
        while (t.game() != null && t.game().minigame() != null) {
            assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
            t.engine.validate(t.state);
            t.pickTooth();
        }
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void theGameZoneDoesNothingWithFewerThanTwoAlivePlayers() {
        Table t = started(2);
        var g = t.game();
        assertTrue(MinigameModule.eligible(t.config, g, g.turn().landing().tile()));
        var lonely = g.withPlayers(g.players().stream().map(p -> p.playerId().equals(g.turn().currentPlayer()) ? p
                : p.eliminated(LifeState.BANKRUPT, new com.millionnaire.engine.core.state.Elimination(1, 1, 0))).toList());
        assertFalse(MinigameModule.eligible(t.config, lonely, g.turn().landing().tile()));
        assertNull(EconomyModule.requiredStep(t.config, lonely, g.turn().currentPlayer(), g.turn().landing().tile()));
    }
}
