package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.GameView;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.SessionView;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.replay.RandomAudit;
import com.millionnaire.engine.replay.RunResult;
import com.millionnaire.engine.replay.Scenario;
import com.millionnaire.engine.replay.ScenarioRunner;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 8 人、50 格、15 分钟限时的完整对局（生产随机协议，脚本化的 8 个客户端驱动）：
 * 包含手动投骰、超时自动投骰、暂离与确认掉线的自动投骰、付费出狱、监狱判定与全局到时。
 * 每一步都核对账本守恒与 8 个客户端视图一致；结束后对<b>每一个</b>截点做快照续跑比对。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LongGameTest {
    private static final long SEED = 20261005L;
    private Table table;
    private int turns;

    @BeforeAll
    void play() {
        table = Table.production(SEED).start(8, RuleConfigs.BOARD_50, EndMode.TIME_LIMIT, 15);
        while (table.session().inGame()) {
            GameState g = table.game();
            long turn = g.turn().turnNo();
            if (turn == 6 && g.player("p3").orElseThrow().control() == ControlMode.MANUAL) {
                table.send(table.now + 1, new GameCommand.SetControl(1, "p3", ControlMode.AWAY));
            } else if (turn == 20 && g.player("p5").orElseThrow().conn() == com.millionnaire.engine.core.state.ConnState.ONLINE) {
                table.send(table.now + 1, new GameCommand.ConnectionSuspected(1, "p5", 1));
                table.send(table.now + 1, new GameCommand.ConnectionConfirmed(1, "p5", 2));
            } else if (turn == 45 && g.player("p5").orElseThrow().conn() != com.millionnaire.engine.core.state.ConnState.ONLINE) {
                table.send(table.now + 1, new GameCommand.Reconnected(1, "p5", 3));
            } else if (turn == 60 && g.player("p3").orElseThrow().control() != ControlMode.MANUAL) {
                table.send(table.now + 1, new GameCommand.SetControl(1, "p3", ControlMode.MANUAL));
            } else {
                drive(g);
            }
            checkStep();
        }
        turns = (int) table.log.stream().filter(e -> e instanceof GameEvent.TurnStarted).count();
    }

    /** 一个"客户端"的固定策略：自动玩家等自动任务；每第 11 回合让窗口超时；狱中偶数回合付费出狱；其余在窗口开放时投骰。 */
    private void drive(GameState g) {
        FlowFrame w = table.window();
        PlayerState p = g.player(g.turn().currentPlayer()).orElseThrow();
        long turn = g.turn().turnNo();
        if (p.automated()) {
            long due = g.turn().autoTaskId() != 0
                    ? table.state.timers().find(g.turn().autoTaskId()).orElseThrow().dueAt() : w.window().deadline();
            table.tick(Math.max(table.now, due));
        } else if (turn % 11 == 0) {
            table.tick(w.window().deadline());
        } else if (g.turn().stage() == TurnStage.JAIL_DECISION && turn % 2 == 0
                && g.ledger().available(p.playerId()) >= table.config.economy().bailCost()) {
            table.send(Math.max(table.now, w.window().opensAt()) + 200, new GameCommand.PayBail(p.playerId(), w.windowId()));
        } else {
            table.roll();
        }
    }

    /** 每步：账本完整校验与守恒；8 个客户端的公开视图一致（只有各自的私有字段可以不同）。 */
    private void checkStep() {
        SessionState s = table.session();
        if (s.game() == null) {
            return;
        }
        Ledger l = s.game().ledger();
        l.verifyInvariants();
        long total = l.systemNet() + l.cash().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(8L * 3000, total, "cash is conserved");
        GameView reference = null;
        for (int i = 1; i <= 8; i++) {
            SessionView v = SessionDomain.INSTANCE.project(table.state, "p" + i);
            GameView g = v.game();
            GameView publicPart = new GameView(g.gameNo(), g.phase(), g.players(), g.orderDraws(), g.board(), g.turnNo(),
                    g.currentPlayer(), g.stage(), g.globalEndsAt(), g.windows(), List.of());
            if (reference == null) {
                reference = publicPart;
            }
            assertEquals(reference, publicPart, "client p" + i + " disagrees");
        }
    }

    @Test
    void theGameCoversEveryM1Rule() {
        List<Event> log = table.log;
        assertTrue(turns >= 30, "turns: " + turns);
        assertTrue(log.stream().anyMatch(e -> e instanceof GameEvent.PlayerJailed), "someone went to jail");
        assertTrue(log.stream().anyMatch(e -> e instanceof GameEvent.JailRolled), "someone rolled a jail judgment");
        assertTrue(log.stream().anyMatch(e -> e instanceof GameEvent.BailPaid), "someone paid bail");
        assertTrue(log.stream().anyMatch(e -> e instanceof GameEvent.StartRewardPaid), "start rewards were paid");
        assertTrue(log.stream().anyMatch(e -> e instanceof GameEvent.DiceRolled d && d.auto()), "auto rolls happened");
        assertTrue(log.stream().anyMatch(e -> e instanceof GameEvent.GameEnded g && g.reason().equals("TIME_UP")));
        assertNull(table.session().game());
        assertEquals(8, table.session().lastResult().standings().size());
        System.out.println("LONG GAME: turns=" + turns + " inputs=" + table.inputs.size() + " events=" + log.size());
    }

    @Test
    void replayIsByteIdenticalAndRebuildsFromEvents() {
        Scenario scenario = table.scenario();
        ScenarioRunner<SessionState> runner = new ScenarioRunner<>(scenario, SessionDomain.INSTANCE);
        RunResult full = runner.run();
        assertEquals(table.state, full.state());
        assertEquals(runner.engine().encodeEvents(table.log), runner.engine().encodeEvents(full.events()));
        assertEquals(full.state(), runner.engine().rebuild(full.events()));
        RandomAudit.verify(full.events());
    }

    @Test
    void snapshotResumeAtEveryStepMatchesTheUninterruptedRun() {
        Scenario scenario = table.scenario();
        ScenarioRunner<SessionState> runner = new ScenarioRunner<>(scenario, SessionDomain.INSTANCE);
        RunResult full = runner.run();
        int n = scenario.inputs().size();
        List<EngineState> prefixes = new ArrayList<>();
        List<List<Event>> stepEvents = new ArrayList<>();
        EngineState s = runner.engine().create(scenario.roomId(), scenario.seed(), scenario.createdAt()).state();
        prefixes.add(s);
        for (int i = 0; i < n; i++) {
            var r = runner.engine().step(s, scenario.inputs().get(i));
            s = r.state();
            prefixes.add(s);
            stepEvents.add(r.events());
        }
        for (int cut = 0; cut <= n; cut++) {
            EngineState restored = runner.engine().restore(runner.engine().snapshot(prefixes.get(cut)));
            assertEquals(prefixes.get(cut), restored);
            RunResult rest = runner.resume(restored, cut);
            assertEquals(full.finalHash(), rest.finalHash(), "cut " + cut);
            assertEquals(full.stepHashes().subList(cut, n), rest.stepHashes(), "cut " + cut);
            List<Event> tail = new ArrayList<>();
            stepEvents.subList(cut, n).forEach(tail::addAll);
            assertEquals(tail, rest.events(), "events after cut " + cut);
        }
    }
}
