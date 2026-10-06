package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.time.ScheduledTask;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 第 11 轮评审 C1–C6 的反例回归（修复前全部失败，见 m1d-report）。 */
class Round11ReviewTest {

    private static DecisionContext<SessionState> ctx(Table t) {
        return new DecisionContext<>(t.state, new Evolver<>(SessionDomain.INSTANCE, t.config), SessionState.class,
                XoshiroLemireV1.INSTANCE, t.config);
    }

    private static void commit(Table t, DecisionContext<SessionState> c) {
        t.engine.validate(c.engineState());
        t.state = c.engineState();
        t.log.addAll(c.events());
    }

    @Test
    void c1AFreshOnlineReconnectAdvancesTheWatermarkSoALateSuspectIsStale() {
        Table t = Table.production(1).start(2);
        String q = t.game().players().get(1).playerId();
        t.send(new GameCommand.Reconnected(1, q, 2));          // 仍在线：只推进观测水位
        assertEquals(2, t.game().player(q).orElseThrow().connObservation());
        assertEquals(RejectionCode.STALE_OBSERVATION, t.send(new GameCommand.ConnectionSuspected(1, q, 1)).rejection(),
                "a late suspect from before the reconnect must not downgrade the player");
    }

    @Test
    void c2OtherPlayersCannotPostponeTheCurrentPlayersAutoAction() {
        Table t = Table.production(2).start(2);
        String p = t.current();
        String q = t.game().players().stream().map(x -> x.playerId()).filter(id -> !id.equals(p)).findFirst().orElseThrow();
        long s = t.now;
        t.send(s + 60, new GameCommand.SetControl(1, p, ControlMode.AWAY));
        long id = t.game().turn().autoTaskId();
        long due = t.state.timers().find(id).orElseThrow().dueAt();
        assertEquals(s + 1060, due);
        t.send(s + 960, new GameCommand.SetControl(1, q, ControlMode.AWAY));
        t.send(s + 970, new GameCommand.ResumeControl(q, 1));
        t.send(s + 980, new GameCommand.ConnectionSuspected(1, q, 1));
        t.send(s + 990, new GameCommand.Reconnected(1, q, 2));
        assertEquals(id, t.game().turn().autoTaskId(), "the task id is unchanged");
        assertEquals(due, t.state.timers().find(id).orElseThrow().dueAt(), "the due time is unchanged");
        t.tick(due);
        assertTrue(t.log.stream().anyMatch(e -> e instanceof GameEvent.DiceRolled d && d.auto() && d.playerId().equals(p)));
    }

    @Test
    void c3AJailFailureCannotBeBookedToAnotherPlayer() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 2, 6, 6),
                dice(DrawPoint.JAIL_DIE, 1))), 1).start(2);
        for (int i = 0; i < 5; i++) {
            t.roll();                                           // p1、p2 都进狱；p1 判定 1 → 失败
        }
        assertEquals(1, t.game().player("p1").orElseThrow().jailFailures());
        List<Event> forged = t.log.stream().map(e -> e instanceof GameEvent.JailFailed f && f.playerId().equals("p1")
                ? new GameEvent.JailFailed("p2", 1) : e).toList();
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(forged));
    }

    @Test
    void c4TurnTrackMustBeEmptyAtEveryInputBoundary() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 3))), 1).start(2);
        t.roll();
        t.send(t.now, new Tick());
        List<Event> log = new ArrayList<>(t.log);
        Event tickAccepted = log.remove(log.size() - 1);
        assertTrue(tickAccepted instanceof KernelEvent.InputAccepted);
        int dice = -1;
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i) instanceof GameEvent.DiceRolled) {
                dice = i;
            }
        }
        log.add(dice + 1, tickAccepted);                         // 把 Tick 的输入游标插进同一步的衔接中间
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(log));
    }

    @Test
    void c5AnUnknownContinuationIsRejectedOnRestore() {
        Table t = Table.production(5).start(3);
        DecisionContext<SessionState> c = ctx(t);
        String other = c.state().game().players().stream().map(p -> p.playerId())
                .filter(id -> !id.equals(c.state().game().turn().currentPlayer())).findFirst().orElseThrow();
        FlowCoordinator.request(c, GameModule.FLOW, FlowKind.AUCTION, other);
        commit(t, c);
        t.roll();
        GameState g = t.game();
        assertEquals(TurnStage.AWAITING_FLOW, g.turn().stage());
        // M2：续接已类型化，不存在拼错的字符串；改为绑定错误回合 / 错误类别的续接，同样在恢复时被拒
        var wrongTurn = new com.millionnaire.engine.core.state.Continuation.BeginTurn(g.turn().turnNo() + 1);
        var wrongKind = new com.millionnaire.engine.core.state.Continuation.EndTurn(g.turn().turnNo());
        for (var bad : List.of(wrongTurn, wrongKind)) {
            EngineState typo = t.state.withDomain(t.session().withGame(g.withTurn(
                    g.turn().withStage(g.turn().stage(), g.turn().windowId(), bad, g.turn().notBefore()))));
            assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(typo)));
        }
    }

    @Test
    void c6AQueuedFlowDoesNotOpenBeforeTheMoveAnimationEnds() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2))), 1).start(2);
        DecisionContext<SessionState> c = ctx(t);
        FlowCoordinator.request(c, GameModule.FLOW, FlowKind.AUCTION, "p2");
        commit(t, c);
        long at = t.now + 10;
        t.send(at, new GameCommand.RollDice("p1", t.windowId()));
        assertEquals(TurnStage.AWAITING_FLOW, t.game().turn().stage());
        long animationEnd = at + 1500 + 2 * 250;              // M2：落在事件格（无落点窗口），排队流程直接接在动画之后
        assertEquals(animationEnd, t.window().window().opensAt(), "the auction opens after the move animation");
        long auctionEnd = t.window().window().deadline();
        t.tick(auctionEnd);
        assertEquals(auctionEnd, t.window().window().opensAt(), "the buffer was consumed once, not added again");
        ScheduledTask any = t.state.timers().tasks().get(0);
        assertTrue(any.dueAt() > auctionEnd);
    }
}
