package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M2 P2：真实命令入口闭环。全部经 {@link Engine#step}（玩家命令与 Tick），不直接驱动任何模块、不改写状态：
 * 投骰 → 落点 → 缴租不足 → 债务覆盖窗口（回合 AWAITING_FLOW）→ 全局到时（DRAINING 跨步）→ 应急抵押筹足即付 → 续接
 * ResumeLanding → 落点结束 → 不开新回合，到时结算。并在各截点做快照恢复 + 续跑比对。
 */
class M2ClosedLoopTest {

    private record Cut(String name, String snapshot, int inputs, int events) {
    }

    private static Cut cut(Table t, String name) {
        return new Cut(name, t.engine.snapshot(t.state), t.inputs.size(), t.log.size());
    }

    private static void assertResumable(Table t, List<Cut> cuts) {
        for (Cut c : cuts) {
            EngineState s = t.engine.restore(c.snapshot());
            List<Event> tail = new ArrayList<>();
            for (Input in : t.inputs.subList(c.inputs(), t.inputs.size())) {
                StepResult r = t.engine.step(s, in);
                s = r.state();
                tail.addAll(r.events());
            }
            assertEquals(t.state, s, "state after resuming at " + c.name());
            assertEquals(t.log.subList(c.events(), t.log.size()), tail, "events after resuming at " + c.name());
        }
    }

    /** 积极手动策略推进一步：能买就买、能升就升、银行直接结束、欠款逐项应急抵押。 */
    private static void step(Table t) {
        GameState g = t.game();
        FlowFrame w = g.flow().top().orElseThrow();
        String cur = g.turn().currentPlayer();
        long at = Math.max(t.now + 10, w.window().opensAt());
        if (w.kind() == FlowKind.DEBT) {
            OwnableState o = g.board().ownedBy(w.owner()).stream().filter(x -> !x.mortgaged()).findFirst().orElseThrow();
            t.send(at, new GameCommand.EmergencyMortgage(w.owner(), w.windowId(), o.tile()));
            return;
        }
        switch (g.turn().stage()) {
            case JAIL_DECISION, PRE_ROLL -> t.send(at, new GameCommand.RollDice(cur, w.windowId()));
            case LANDING -> t.send(at, switch (g.turn().landing().step()) {
                case BUY -> new GameCommand.BuyProperty(cur, w.windowId());
                case UPGRADE -> new GameCommand.UpgradeProperty(cur, w.windowId());
                case BANK -> new GameCommand.FinishBank(cur, w.windowId());
                default -> throw new IllegalStateException();
            });
            default -> t.tick(w.window().deadline());
        }
    }

    /** 在固定种子中找第一个"债务窗口跨过全局到时"的对局，停在该窗口刚打开时。 */
    private static Table debtAcrossTheGlobalEnd() {
        for (long seed = 1; seed < 500; seed++) {
            Table t = Table.production(seed);
            t.send(new RoomCommand.Join("p1", "P1"));
            t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_30, 2000, EndMode.TIME_LIMIT, 15, 15)));
            for (int i = 2; i <= 3; i++) {
                t.send(new RoomCommand.Join("p" + i, "P" + i));
            }
            for (int i = 1; i <= 3; i++) {
                t.send(new RoomCommand.SetReady("p" + i, true));
            }
            t.send(new SessionCommand.StartGame("p1"));
            long endsAt = t.game().clock().endsAt();
            while (t.session().inGame() && t.game().phase() == GamePhase.RUNNING) {
                FlowFrame top = t.game().flow().top().orElseThrow();
                if (top.kind() == FlowKind.DEBT && top.window().deadline() > endsAt && top.window().opensAt() < endsAt) {
                    return t;
                }
                step(t);
            }
        }
        throw new AssertionError("no seed produced a debt window across the global end");
    }

    @Test
    void rentDebtAcrossTheGlobalEndResolvesThroughCommandsAndSettles() {
        Table t = debtAcrossTheGlobalEnd();
        long endsAt = t.game().clock().endsAt();
        String debtor = t.game().debt().debtor();
        List<Cut> cuts = new ArrayList<>();
        assertEquals(TurnStage.AWAITING_FLOW, t.game().turn().stage());
        assertEquals(LandingStep.DEBT, t.game().turn().landing().step());
        cuts.add(cut(t, "AWAITING_FLOW on a debt"));
        StepResult end = t.tick(endsAt);
        assertTrue(end.events().stream().anyMatch(e -> e instanceof GameEvent.DrainingStarted));
        assertEquals(GamePhase.DRAINING, t.game().phase(), "draining persists while the debt is open");
        assertEquals(FlowKind.DEBT, t.window().kind());
        cuts.add(cut(t, "DRAINING with an open debt"));
        while (t.session().inGame()) {
            GameState g = t.game();
            assertEquals(GamePhase.DRAINING, g.phase());
            assertTrue(g.turn().stage() != TurnStage.PRE_ROLL && g.turn().stage() != TurnStage.JAIL_DECISION,
                    "no new roll after the global end");
            step(t);
            if (t.session().inGame()) {
                cuts.add(cut(t, "draining step " + t.inputs.size()));
            }
        }
        assertTrue(t.log.stream().anyMatch(e -> e instanceof GameEvent.DebtPaid), "the debt was paid through commands");
        GameEvent.GameEnded done = t.log.stream().filter(e -> e instanceof GameEvent.GameEnded).map(e -> (GameEvent.GameEnded) e)
                .findFirst().orElseThrow();
        assertEquals("TIME_UP", done.reason());
        assertTrue(done.result().standings().stream().anyMatch(s -> s.playerId().equals(debtor)));
        assertNull(t.session().game());
        assertTrue(t.state.timers().isEmpty());
        assertEquals(t.state, t.engine.rebuild(t.log), "the whole command-driven run rebuilds from events");
        assertResumable(t, cuts);
    }

    @Test
    void buyWindowAcrossTheGlobalEndIsResumableAtEveryCut() {
        Table t = M2EconomyTest.nearEndBuyWindow();
        List<Cut> cuts = new ArrayList<>();
        cuts.add(cut(t, "LANDING buy window before the end"));
        t.tick(t.game().clock().endsAt());
        cuts.add(cut(t, "DRAINING with a buy window"));
        String p = t.current();
        t.act(w -> new GameCommand.BuyProperty(p, w));
        if (t.session().inGame()) {
            cuts.add(cut(t, "DRAINING with an upgrade window"));
            t.pass();
        }
        assertNull(t.session().game());
        assertResumable(t, cuts);
    }
}
