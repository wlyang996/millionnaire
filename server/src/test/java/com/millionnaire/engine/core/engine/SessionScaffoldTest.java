package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.testkit.TestBoards;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.OrderDraw;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.ScriptedRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M1 第一批脚手架在生产会话上的接线：窗口 ID、随机贯通、流程在对局状态中的校验与性能基线。 */
class SessionScaffoldTest {
    private final RuleConfig config = TestBoards.legacyV1();

    private static List<Command> lobby(int players, boolean fiftyTiles) {
        List<Command> cs = new ArrayList<>();
        cs.add(new RoomCommand.Join("p1", "P1"));
        if (fiftyTiles) {
            cs.add(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_50, 3000, EndMode.TIME_LIMIT, 30, 15)));
        }
        for (int i = 2; i <= players; i++) {
            cs.add(new RoomCommand.Join("p" + i, "P" + i));
        }
        for (int i = 1; i <= players; i++) {
            cs.add(new RoomCommand.SetReady("p" + i, true));
        }
        cs.add(new SessionCommand.StartGame("p1"));
        return cs;
    }

    private static <S extends com.millionnaire.engine.core.state.DomainState> EngineState run(Engine<S> engine, List<Command> cs) {
        EngineState s = engine.create("r", 3, 0).state();
        long n = s.lastSeq();
        for (Command c : cs) {
            n++;
            s = engine.step(s, new Input(n, n * 10, c)).state();
        }
        return s;
    }

    @Test
    void windowIdsAreMonotonicAcrossGames() {
        Engine<SessionState> engine = new Engine<>(config, SessionDomain.INSTANCE);
        EngineState s = run(engine, lobby(2, false));
        SessionState ss = (SessionState) s.domain();
        assertEquals(1, ss.game().turn().windowId(), "the first turn window is window 1");
        assertEquals(2, ss.game().flow().nextWindowId());
        long n = s.lastSeq();
        s = engine.step(s, new Input(++n, 1000, new SessionCommand.EndGame(1, "done"))).state();
        assertEquals(2, ((SessionState) s.domain()).nextWindowId(), "the session takes the allocator back");
        for (Command c : List.of(new RoomCommand.SetReady("p1", true), new RoomCommand.SetReady("p2", true),
                new SessionCommand.StartGame("p1"))) {
            s = engine.step(s, new Input(++n, 1000 + n, c)).state();
        }
        assertEquals(2, ((SessionState) s.domain()).game().turn().windowId(), "game 2 does not restart window ids at 1");
        SessionState s2 = (SessionState) s.domain();
        EngineState regressed = s.withDomain(new SessionState(s2.lobby(), s2.game(), s2.gamesPlayed(), 99, s2.lastResult()));
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(regressed)));
    }

    @Test
    void orderDrawsFlowThroughDrawsIntoGameEvolve() {
        ScriptedRandom script = ScriptedRandom.withEventCards(List.of(
                ScriptedRandom.step(DrawPoint.ORDER_NUMBER, 100, 41),
                ScriptedRandom.step(DrawPoint.ORDER_NUMBER, 100, 99),
                ScriptedRandom.step(DrawPoint.ORDER_NUMBER, 100, 0),
                ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 0), ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 999),
                ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 120), ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 120),
                ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 0), ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 0)));
        Engine<SessionState> engine = new Engine<>(config, SessionDomain.INSTANCE, script);
        EngineState s = run(engine, lobby(3, false));
        GameState g = ((SessionState) s.domain()).game();
        assertEquals(List.of(new OrderDraw("p1", List.of(42)), new OrderDraw("p2", List.of(100)), new OrderDraw("p3", List.of(1))),
                g.orderDraws());
        assertEquals(List.of("p2", "p1", "p3"), g.players().stream().map(p -> p.playerId()).toList());
        script.assertExhausted(s);

        Engine<SessionState> prod = new Engine<>(config, SessionDomain.INSTANCE);
        List<Event> log = new ArrayList<>(prod.create("r", 3, 0).events());
        EngineState p = prod.create("r", 3, 0).state();
        long n = 0;
        for (Command c : lobby(3, false)) {
            n++;
            StepResult r = prod.step(p, new Input(n, n * 10, c));
            p = r.state();
            log.addAll(r.events());
        }
        List<Event> tampered = log.stream().map(e -> e instanceof GameEvent.OrderNumberDrawn d
                ? new GameEvent.OrderNumberDrawn(d.playerId(), d.value() == 100 ? 1 : d.value() + 1) : e).toList();
        StateValidationException e = assertThrows(StateValidationException.class, () -> prod.rebuild(tampered));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals(p, prod.rebuild(log));
    }

    @Test
    void validationBaselineAtMaximumScale() {
        Engine<SessionState> engine = new Engine<>(config, SessionDomain.INSTANCE);
        EngineState s = run(engine, lobby(8, true));
        // 最大流程规模：回合窗口（已由开局打开）+ 覆盖流程 + 响应窗（栈深 3），其余 7 人各有一个排队申请
        DecisionContext<SessionState> ctx = new DecisionContext<>(s, new Evolver<>(SessionDomain.INSTANCE, config),
                SessionState.class, XoshiroLemireV1.INSTANCE, config);
        String current = ((SessionState) s.domain()).game().turn().currentPlayer();
        GameModule.openOverlay(ctx, FlowKind.ATTACK, current, 0, 10_000, "post-attack");
        GameModule.openSourcedOverlay(ctx, FlowKind.RESPONSE, current, 0, 10_000, "respond",
                new com.millionnaire.engine.core.state.FlowOrigin(com.millionnaire.engine.core.state.FlowOrigin.Kind.ATTACK_RESPONSE,
                        ((SessionState) s.domain()).game().turn().turnNo(), ctx.state().game().flow().top().orElseThrow().windowId(), -1, current));
        for (int i = 2; i <= 8; i++) {
            assertEquals(null, FlowCoordinator.request(ctx, GameModule.FLOW, i % 2 == 0 ? FlowKind.AUCTION : FlowKind.TRADE, "p" + i));
        }
        EngineState big = ctx.engineState();
        engine.validate(big);
        assertEquals(3, ((SessionState) big.domain()).game().flow().frames().size());
        int iterations = 20_000;
        for (int i = 0; i < 2_000; i++) {
            engine.validate(big);
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            engine.validate(big);
        }
        long perCall = (System.nanoTime() - start) / iterations;
        // 只记录数字，不设严格阈值
        System.out.println("BASELINE validate(8 players, 50 tiles, 3 frames, 7 requests, 2 tasks, light ledger): " + perCall + " ns/call");
        assertTrue(perCall > 0);
    }
}
