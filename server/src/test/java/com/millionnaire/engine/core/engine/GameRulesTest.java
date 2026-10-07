package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.testkit.TestBoards;
import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.OrderDraw;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.Standing;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M1 对局规则的确定性场景（脚本随机源）：定序、起点、监狱、超时、全局到时、拒绝、自动动作、覆盖流程、认领。 */
class GameRulesTest {
    private static final long DICE_ANIM = 1500;
    private static final long STEP_ANIM = 250;
    private static final long ROLL_MS = 15_000;

    private static long count(List<Event> events, Class<?> type) {
        return events.stream().filter(type::isInstance).count();
    }

    // ------------------------------------------------------------ 定序

    @Test
    void tiesAreRedrawnOnlyWithinTheirSubgroupUntilSeparated() {
        // 首抽：p1/p2/p3 同为 50，p4 = 90 → p4 已分开；组 [p1,p2,p3] 重抽 30/30/70 → p3 分开；子组 [p1,p2] 再抽 20/10
        ScriptedRandom r = ScriptedRandom.withEventCards(script(order(50, 50, 50, 90), order(30, 30, 70), order(20, 10), Table.deal(4)));
        Table t = new Table(r, 1).start(4);
        GameState g = t.game();
        assertEquals(List.of("p4", "p3", "p1", "p2"), g.players().stream().map(PlayerState::playerId).toList());
        assertEquals(List.of(new OrderDraw("p1", List.of(50, 30, 20)), new OrderDraw("p2", List.of(50, 30, 10)),
                new OrderDraw("p3", List.of(50, 70)), new OrderDraw("p4", List.of(90))), g.orderDraws());
        assertEquals(9, count(t.log, GameEvent.OrderNumberDrawn.class));
        r.assertExhausted(t.state);
        assertEquals("p4", t.current(), "the highest number moves first");
        assertEquals(1, g.turn().turnNo());
    }

    // ------------------------------------------------------------ 起点奖励

    @Test
    void startRewardOncePerTurnForLandingOrPassingAndNeverAtGameStart() {
        // 第 5 次落在起点；第 11 次从 28 越过起点（避开 23 号游戏区，不触发虎口拔牙）
        List<Integer> p1 = List.of(6, 6, 6, 6, 6, 4, 6, 6, 6, 6, 6);
        List<Integer> p2 = List.of(1, 1, 1, 1, 1, 1, 1, 2, 1, 1);
        List<ScriptedRandom.Step> moves = new ArrayList<>();
        for (int i = 0; i < p1.size(); i++) {
            moves.addAll(dice(DrawPoint.MOVE_DIE, p1.get(i)));
            if (i < p2.size()) {
                moves.addAll(dice(DrawPoint.MOVE_DIE, p2.get(i)));
            }
        }
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), moves)), 1).start(2);
        assertEquals(3000, t.cash("p1"), "no reward at game start");
        for (int i = 0; i < 9; i++) {
            t.roll();
        }
        assertEquals(0, t.position("p1"));
        assertEquals(4000, t.cash("p1"), "landing on start pays once");
        for (int i = 0; i < 12; i++) {
            t.roll();
        }
        assertEquals(4, t.position("p1"));
        assertEquals(5000, t.cash("p1"), "passing start pays once");
        assertEquals(3000, t.cash("p2"));
        assertEquals(2, count(t.log, GameEvent.StartRewardPaid.class));
        Ledger l = t.game().ledger();
        l.verifyInvariants();
        assertEquals(-2000, l.systemNet());
        assertEquals(2, l.journal().size());
    }

    @Test
    void aSecondStartRewardInTheSameTurnIsRejectedOnRebuild() {
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 6, 1, 6, 1, 6, 1, 6, 1, 6))), 1)
                .start(2);
        for (int i = 0; i < 9; i++) {
            t.roll();
        }
        List<Event> doubled = new ArrayList<>();
        for (Event e : t.log) {
            doubled.add(e);
            if (e instanceof GameEvent.StartRewardPaid) {
                doubled.add(e);
            }
        }
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(doubled));
    }

    // ------------------------------------------------------------ 监狱

    @Test
    void thirdJailFailureReleasesAndMovesTheSameTurnWithTheRemainingRollTime() {
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2),
                dice(DrawPoint.MOVE_DIE, 2, 1, 6, 1), dice(DrawPoint.JAIL_DIE, 1), dice(DrawPoint.MOVE_DIE, 1),
                dice(DrawPoint.JAIL_DIE, 3), dice(DrawPoint.MOVE_DIE, 1), dice(DrawPoint.JAIL_DIE, 5),
                dice(DrawPoint.MOVE_DIE, 4))), 1).start(2);
        t.roll();
        t.roll();
        t.roll();                                                         // p1：2 → 8，停在监狱
        assertTrue(t.game().player("p1").orElseThrow().inJail());
        assertEquals(1, count(t.log, GameEvent.PlayerJailed.class));
        t.roll();                                                         // p2
        assertEquals(TurnStage.JAIL_DECISION, t.game().turn().stage());
        t.roll();                                                         // 判定 1：失败 1
        t.roll();                                                         // p2
        t.roll();                                                         // 判定 3：失败 2
        assertEquals(2, t.game().player("p1").orElseThrow().jailFailures());
        t.roll();                                                         // p2
        FlowFrame jailWindow = t.window();
        long at = jailWindow.window().opensAt() + 4000;
        t.send(at, new GameCommand.RollDice("p1", jailWindow.windowId()));  // 判定 5：第三次失败 → 当回合释放
        assertFalse(t.game().player("p1").orElseThrow().inJail());
        assertEquals("p1", t.current(), "moves in the same turn");
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        FlowFrame preRoll = t.window();
        assertEquals(at + DICE_ANIM, preRoll.window().opensAt(), "the judgment animation does not consume roll time");
        assertEquals(jailWindow.window().deadline() - at, preRoll.window().deadline() - preRoll.window().opensAt(),
                "the remaining roll time carries over");
        assertTrue(t.log.stream().anyMatch(e -> e instanceof GameEvent.JailReleased r
                && r.reason() == GameEvent.ReleaseReason.THIRD_FAILURE));
        t.roll();
        assertEquals(12, t.position("p1"), "the judgment die is not used as the move");
    }

    @Test
    void releaseWithZeroRemainingTimeMovesImmediatelyWithoutAZeroLengthWindow() {
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 1, 6, 1),
                dice(DrawPoint.JAIL_DIE, 2), dice(DrawPoint.MOVE_DIE, 2))), 1).start(2);
        for (int i = 0; i < 4; i++) {
            t.roll();
        }
        assertEquals(TurnStage.JAIL_DECISION, t.game().turn().stage());
        long deadline = t.window().window().deadline();
        StepResult r = t.tick(deadline);                                  // 超时：自动判定，偶数释放，剩余 0 → 立即自动移动
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.JailRolled j && j.auto()));
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.DiceRolled d && d.auto() && d.value() == 2));
        assertFalse(r.events().stream().anyMatch(e -> e instanceof GameEvent.WindowOpened o && o.frame().owner().equals("p1")
                        && o.frame().resumeTag().equals(TurnStage.PRE_ROLL.name())),
                "no zero-length post-release roll window was opened");
        assertEquals(10, t.position("p1"), "M3b: movement lands on an event draw window");
        t.pass();
        assertEquals("p2", t.current());
    }

    @Test
    void bailReleasesWithTheRemainingTimeAndNeedsEnoughCash() {
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 1, 6, 1, 5))), 1).start(2);
        for (int i = 0; i < 4; i++) {
            t.roll();
        }
        long w = t.windowId();
        assertEquals(RejectionCode.NOT_YOUR_TURN, t.send(new GameCommand.PayBail("p2", w)).rejection());
        // 现金不足：把 p1 的现金转出（账本一致的合法状态），付费被拒且不消耗随机数
        SessionState ss = t.session();
        GameState poor = ss.game().withLedger(ss.game().ledger().transfer("p1", Ledger.SYSTEM, 2600, "TEST", null));
        EngineState rich = t.state;
        t.state = t.engine.restore(t.engine.snapshot(rich.withDomain(ss.withGame(poor))));
        assertEquals(RejectionCode.INSUFFICIENT_CASH,
                t.send(t.window().window().opensAt() + 100, new GameCommand.PayBail("p1", w)).rejection());
        t.state = rich;
        t.seq = rich.lastSeq();
        long at = t.window().window().opensAt() + 5000;
        long deadline = t.window().window().deadline();
        assertEquals(Outcome.ACCEPTED, t.send(at, new GameCommand.PayBail("p1", w)).outcome());
        assertEquals(2500, t.cash("p1"));
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        assertEquals(deadline - at, t.window().window().deadline() - t.window().window().opensAt());
        assertEquals(RejectionCode.WRONG_STAGE, t.send(new GameCommand.PayBail("p1", t.windowId())).rejection());
        t.roll();
        assertEquals(13, t.position("p1"));
    }

    // ------------------------------------------------------------ 超时、拒绝与随机消耗

    @Test
    void timeoutRollsAutomaticallyAndTheNextWindowStartsAfterTheAnimation() {
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2))), 1).start(2);
        M3bTest.craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().at(13))); // REST at 15 isolates animation timing.
        long deadline = t.window().window().deadline();
        StepResult r = t.tick(deadline);
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.DiceRolled d && d.auto()));
        assertEquals("p2", t.current());
        assertEquals(deadline + DICE_ANIM + 2 * STEP_ANIM, t.window().window().opensAt());
        assertEquals(t.window().window().opensAt() + ROLL_MS, t.window().window().deadline());
    }

    @Test
    void duplicateExpiredAndForeignCommandsHaveNoEffectAndConsumeNoRandomness() {
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 2))), 1).start(2);
        M3bTest.craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().at(13)));
        long w1 = t.windowId();
        assertEquals(RejectionCode.NOT_YOUR_TURN, t.send(new GameCommand.RollDice("p2", w1)).rejection());
        assertEquals(RejectionCode.NOT_MEMBER, t.send(new GameCommand.RollDice("zz", w1)).rejection());
        int cursor = ScriptedRandom.cursor(t.state.rng());
        StepResult ok = t.roll();
        Input last = t.inputs.get(t.inputs.size() - 1);
        assertEquals(Outcome.DUPLICATE, t.engine.step(t.state, last).outcome(), "same seq resent");
        assertEquals(RejectionCode.NOT_YOUR_TURN, t.send(new GameCommand.RollDice("p1", w1)).rejection(), "old turn");
        long w2 = t.windowId();
        assertEquals(RejectionCode.WINDOW_NOT_OPEN, t.send(new GameCommand.RollDice("p2", w2)).rejection(), "inside the animation buffer");
        assertEquals(RejectionCode.WINDOW_MISMATCH, t.send(t.window().window().opensAt(), new GameCommand.RollDice("p2", w1)).rejection());
        assertEquals(cursor + 1, ScriptedRandom.cursor(t.state.rng()), "only the accepted roll consumed randomness");
        assertEquals(1, count(t.log, GameEvent.DiceRolled.class));
        assertNotNull(ok);
    }

    // ------------------------------------------------------------ 控制模式与自动动作

    @Test
    void awayAndConfirmedOfflinePlayersRollAutomaticallyAndModeChangesCancelOldTasks() {
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 2, 6, 6))), 1).start(2);
        M3bTest.craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().at(13)).withPlayer(g.player("p2").orElseThrow().at(13)));
        long s = t.now;
        t.send(s + 100, new GameCommand.SetControl(1, "p1", ControlMode.AWAY));
        long autoTask = t.game().turn().autoTaskId();
        ScheduledTask task = t.state.timers().find(autoTask).orElseThrow();
        assertEquals(TaskKind.AUTO_ACT, task.kind());
        assertEquals(s + 100 + 1000, task.dueAt(), "auto action after the configured delay");
        StepResult r = t.tick(task.dueAt());
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.DiceRolled d && d.auto()));
        assertEquals("p2", t.current());
        // 疑似断线不自动；确认掉线自动；重连取消旧任务
        t.send(t.now + 1, new GameCommand.ConnectionSuspected(1, "p2", 1));
        assertEquals(0, t.game().turn().autoTaskId());
        t.send(t.now + 1, new GameCommand.ConnectionConfirmed(1, "p2", 2));
        long armed = t.game().turn().autoTaskId();
        assertTrue(armed != 0);
        t.send(t.now + 1, new GameCommand.Reconnected(1, "p2", 3));
        assertEquals(0, t.game().turn().autoTaskId());
        assertTrue(t.state.timers().find(armed).isEmpty(), "the old auto task was cancelled");
        t.send(t.now + 1, new GameCommand.ConnectionSuspected(1, "p2", 4));
        t.send(t.now + 1, new GameCommand.ConnectionConfirmed(1, "p2", 5));
        long rearmed = t.game().turn().autoTaskId();
        // 快照恢复后自动任务只执行一次
        t.state = t.engine.restore(t.engine.snapshot(t.state));
        long due = t.state.timers().find(rearmed).orElseThrow().dueAt();
        long autosBefore = t.log.stream().filter(e -> e instanceof GameEvent.DiceRolled d && d.auto()).count();
        t.tick(due);
        t.tick(due + 1);
        assertEquals(autosBefore + 1, t.log.stream().filter(e -> e instanceof GameEvent.DiceRolled d && d.auto()).count());
        assertEquals("p1", t.current());
        assertTrue(t.game().turn().autoTaskId() != 0, "p1 is still away, so the new turn is armed again");
        assertEquals(RejectionCode.UNCHANGED, t.send(t.now + 1, new GameCommand.SetControl(1, "p1", ControlMode.AWAY)).rejection());
    }

    // ------------------------------------------------------------ 覆盖流程暂停个人投骰钟

    @Test
    void anOverlayPausesTheRollClockAndResumesItWithTheRemainingTime() {
        Table t = new Table(XoshiroLemireV1.INSTANCE, 5).start(2);
        t.send(t.now + 100, new GameCommand.SetControl(1, t.current(), ControlMode.AWAY));
        EngineState s = t.state;
        long turnWindow = t.windowId();
        long deadline = t.window().window().deadline();
        DecisionContext<SessionState> ctx = new DecisionContext<>(s, new Evolver<>(SessionDomain.INSTANCE, t.config), SessionState.class,
                XoshiroLemireV1.INSTANCE, t.config);
        GameModule.openOverlay(ctx, FlowKind.ATTACK, t.current(), 0, 10_000, "after-attack");
        GameState paused = ctx.state().game();
        assertTrue(paused.flow().frame(turnWindow).orElseThrow().window().paused());
        assertEquals(0, paused.turn().autoTaskId(), "the auto task does not run while the clock is paused");
        t.engine.validate(ctx.engineState());
        long attack = paused.flow().top().orElseThrow().windowId();
        GameModule.closeOverlay(ctx, attack, CloseReason.ACTED);
        GameState resumed = ctx.state().game();
        FlowFrame turn = resumed.flow().frame(turnWindow).orElseThrow();
        assertFalse(turn.window().paused());
        assertEquals(deadline - s.now(), turn.window().deadline() - turn.window().opensAt(), "the roll clock kept its remaining time");
        assertTrue(resumed.turn().autoTaskId() != 0, "the auto task is re-armed after the resumption");
        t.engine.validate(ctx.engineState());
    }

    // ------------------------------------------------------------ 全局到时

    @Test
    void globalEndBeatsAWindowTimeoutAtTheSameInstantAndRanksTheTable() {
        List<ScriptedRandom.Step> sixes = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            sixes.addAll(dice(DrawPoint.MOVE_DIE, 6));                      // 全是 6：位置只在 0/6/12/18/24，永不进监狱
        }
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), sixes)), 1)
                .start(2, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 15);
        long endsAt = t.game().clock().endsAt();
        long lead = DICE_ANIM + 6 * STEP_ANIM;
        while (true) {
            FlowFrame w = t.window();
            long aligned = endsAt - lead - ROLL_MS;
            if (aligned >= w.window().opensAt() && aligned < w.window().deadline() && aligned >= t.now) {
                t.send(aligned, new GameCommand.RollDice(t.current(), w.windowId()));
                break;
            }
            t.roll();
        }
        assertEquals(endsAt, t.window().window().deadline(), "a turn window now expires exactly at the global end");
        StepResult r = t.tick(endsAt);
        List<Class<?>> kinds = r.events().stream().<Class<?>>map(Object::getClass).toList();
        assertEquals(KernelEvent.TaskFired.class, kinds.get(0));
        assertTrue(kinds.indexOf(GameEvent.DrainingStarted.class) < kinds.indexOf(GameEvent.GameEnded.class));
        assertEquals(0, count(r.events(), GameEvent.DiceRolled.class), "no auto roll after the global end");
        assertNull(t.session().game());
        GameEvent.GameEnded ended = (GameEvent.GameEnded) r.events().stream().filter(e -> e instanceof GameEvent.GameEnded)
                .findFirst().orElseThrow();
        assertEquals("TIME_UP", ended.reason());
        assertEquals(t.session().lastResult(), ended.result());
        assertTrue(t.state.timers().isEmpty());
        assertEquals(2, ended.result().standings().size());
    }

    @Test
    void standingsShareRanksForTies() {
        Table t = new Table(XoshiroLemireV1.INSTANCE, 9).start(3);
        GameState g = t.game();
        Ledger l = g.ledger().transfer(Ledger.SYSTEM, "p1", 500, "TEST", null).transfer(Ledger.SYSTEM, "p2", 500, "TEST", null);
        List<Standing> s = TurnModule.standings(g.withLedger(l), t.config);
        assertEquals(List.of(1, 1, 3), s.stream().map(Standing::rank).toList());
        assertEquals("p3", s.get(2).playerId());
    }

    // ------------------------------------------------------------ 容量与认领

    @Test
    void boardCapacityIsEnforcedAtTheStartBoundary() {
        Table four = new Table(XoshiroLemireV1.INSTANCE, 2).start(4);
        assertEquals(4, four.game().players().size());
        Table room = new Table(XoshiroLemireV1.INSTANCE, 3);
        for (int i = 1; i <= 4; i++) {
            room.send(new RoomCommand.Join("p" + i, "P" + i));
        }
        assertEquals(RejectionCode.ROOM_FULL, room.send(new RoomCommand.Join("p5", "P5")).rejection());
        Table eight = new Table(XoshiroLemireV1.INSTANCE, 4).start(8, RuleConfigs.BOARD_50, EndMode.TIME_LIMIT, 30);
        assertEquals(8, eight.game().players().size());
        assertEquals(RejectionCode.GAME_IN_PROGRESS, eight.send(new RoomCommand.Join("p9", "P9")).rejection());
    }

    @Test
    void everyTaskIsClaimedExactlyOnce() {
        Table t = new Table(XoshiroLemireV1.INSTANCE, 6).start(2);
        GameState g = t.game();
        assertEquals(2, t.state.timers().tasks().size());
        assertTrue(t.state.timers().tasks().stream().anyMatch(x -> x.kind() == TaskKind.GLOBAL_END && x.ref() == g.gameNo()));
        assertTrue(t.state.timers().tasks().stream().anyMatch(x -> x.kind() == TaskKind.TURN_WINDOW && x.ref() == g.turn().windowId()));
        // 漏认领：去掉全局到时任务
        EngineState missing = t.state.withTimers(t.state.timers().cancel(g.clock().taskId()), t.state.nextTaskId());
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(missing)));
        // 错认领：一个 ref 指向别的窗口的自动任务
        EngineState stray = t.state.withTimers(t.state.timers().schedule(new ScheduledTask(t.state.nextTaskId(),
                t.state.lastReceivedAt() + 100, TaskKind.AUTO_ACT, 999)), t.state.nextTaskId() + 1);
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(stray)));
        // 时钟指向错误任务
        EngineState wrongClock = t.state.withDomain(t.session().withGame(
                g.withClock(new com.millionnaire.engine.core.state.GameClock(g.clock().endsAt(),
                        t.state.timers().tasks().stream().filter(x -> x.kind() == TaskKind.TURN_WINDOW).findFirst()
                                .orElseThrow().taskId()))));
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(wrongClock)));
        // 已有的地块类型核对：50 格棋盘的监狱位置
        assertEquals(13, TurnModule.jailIndex(TestBoards.legacyV1().board(RuleConfigs.BOARD_50).orElseThrow()));
        assertEquals(TileType.JAIL, TestBoards.legacyV1().board(RuleConfigs.BOARD_30).orElseThrow().tiles().get(8).type());
    }
}
