package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.ConfigValidator;
import com.millionnaire.engine.config.EconomyConfig;
import com.millionnaire.engine.config.RatioConfig;
import com.millionnaire.engine.config.RoomOptions;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.ledger.LedgerException;
import com.millionnaire.engine.money.Ratio;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.replay.RandomAudit;
import com.millionnaire.engine.testkit.DemoCommand;
import com.millionnaire.engine.testkit.DemoDomain;
import com.millionnaire.engine.testkit.DemoRound;
import com.millionnaire.engine.testkit.DemoState;
import com.millionnaire.engine.testkit.RogueDomain;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import com.millionnaire.engine.time.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 第 8 轮评审 N1–N8 与新增契约的反例回归（修复前全部失败，见 m0-fix2-report）。 */
class Round8ReviewTest {
    private final RuleConfig base = RuleConfigs.defaultV1();

    @Test
    void n1NullOrDuplicateCashOptionIsAnError() {
        RoomOptions o = base.room();
        List<String> nullErrors = ConfigValidator.validate(withRoom(new RoomOptions(2, Arrays.asList(2000L, 3000L, 5000L, null),
                o.defaultBoardId(), 3000, o.defaultEndMode(), 30, 15)));
        assertTrue(nullErrors.contains("room.initialCashOptions must not contain null"), nullErrors.toString());
        List<String> dupErrors = ConfigValidator.validate(withRoom(new RoomOptions(2, List.of(2000L, 2000L, 3000L),
                o.defaultBoardId(), 3000, o.defaultEndMode(), 30, 15)));
        assertTrue(dupErrors.contains("room.initialCashOptions contains duplicate 2000"), dupErrors.toString());
    }

    @Test
    void n2RoundedRangesPayablePricesAndDrawCandidates() {
        RatioConfig r = base.ratios();
        RatioConfig tiny = new RatioConfig(r.upgradeValueShare(), r.bankMortgage(), r.redeemFeeOffBank(),
                Ratio.of(1, 1000), r.auctionMinRaise(), Ratio.of(1, 1000), r.forcedPurchase(), r.tradeMin(), r.tradeMax(),
                r.systemAuctionCommission());
        List<String> errors = ConfigValidator.validate(withRatios(tiny));
        assertTrue(errors.stream().anyMatch(e -> e.contains("level 0 auction cap rounds to 0")), errors.toString());
        RatioConfig inverted = new RatioConfig(r.upgradeValueShare(), r.bankMortgage(), r.redeemFeeOffBank(),
                Ratio.of(1, 3), r.auctionMinRaise(), Ratio.of(1, 3), r.forcedPurchase(), r.tradeMin(), r.tradeMax(),
                r.systemAuctionCommission());
        List<String> inv = ConfigValidator.validate(withRatios(inverted));
        assertTrue(inv.stream().anyMatch(e -> e.contains("auction start 334 exceeds cap 333 after rounding")), inv.toString());

        EconomyConfig e = base.economy();
        EconomyConfig wide = new EconomyConfig(e.startReward(), e.miniGameWinReward(), e.bailCost(), 1,
                1_000_000_000_000L, 1, e.eventMoveMinSteps(), e.eventMoveMaxSteps(), e.dieFaces(), e.maxLevel(),
                e.handLimit(), e.initialHandSize(), e.orderNumberMax());
        List<String> wideErrors = ConfigValidator.validate(new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(),
                base.station(), wide, base.ratios(), base.cardWeights(), base.eventWeights(), base.timing(), base.room()));
        assertTrue(wideErrors.stream().anyMatch(x -> x.contains("candidate count 1000000000000 exceeds")), wideErrors.toString());
    }

    @Test
    void n3IllegalMemoryStateIsNeverAccepted() {
        Engine<SessionState> engine = new Engine<>(base, SessionDomain.INSTANCE);
        EngineState s = engine.step(engine.create("r", 1, 0).state(), new Input(1, 1, new RoomCommand.Join("a", "A"))).state();
        SessionState session = (SessionState) s.domain();
        RoomState room = session.lobby();
        EngineState noHost = s.withDomain(session.withLobby(new RoomState(room.status(), null, room.members(), room.settings())));
        assertThrows(StateValidationException.class, () -> engine.step(noHost, new Input(2, 2, new Tick())));
    }

    @Test
    void n3CursorOverflowRejectedOnRestore() {
        Engine<SessionState> engine = new Engine<>(base, SessionDomain.INSTANCE);
        EngineState s = engine.step(engine.create("r", 1, 0).state(), new Input(1, 1, new RoomCommand.Join("a", "A"))).state();
        EngineState overflow = s.withInputCursor(Long.MAX_VALUE, s.lastReceivedAt(), s.lastInputDigest()).withEventCount(1);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(overflow)));
        EngineState maxCount = s.withInputCursor(Long.MAX_VALUE - 1, s.lastReceivedAt(), s.lastInputDigest())
                .withEventCount(Long.MAX_VALUE);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(maxCount)));
    }

    @Test
    void n4DrawThenRejectIsAKernelFaultAndCommitsNothing() {
        Engine<DemoState> engine = new Engine<>(base, new RogueDomain(RogueDomain.Mode.DRAW_THEN_REJECT));
        EngineState s = engine.step(engine.create("r", 1, 0).state(), new Input(1, 1, new DemoCommand.Sit("a"))).state();
        EngineState at = s;
        KernelFaultException e = assertThrows(KernelFaultException.class,
                () -> engine.step(at, new Input(2, 2, new DemoCommand.Peek("a"))));
        assertTrue(e.getMessage().contains("after drawing randomness"), e.getMessage());
        assertEquals(1, at.lastSeq(), "the caller's state is untouched");
    }

    @Test
    void n4ScriptedRandomResumesFromSnapshotState() {
        List<ScriptedRandom.Step> script = List.of(ScriptedRandom.step(DrawPoint.MOVE_DIE, 6, 0),
                ScriptedRandom.step(DrawPoint.MOVE_DIE, 6, 4));
        Engine<DemoState> first = new Engine<>(base, DemoDomain.INSTANCE, ScriptedRandom.withEventCards(script));
        EngineState s = first.create("r", 1, 0).state();
        long n = 0;
        for (Command c : List.of(new DemoCommand.Sit("a"), new DemoCommand.OpenRound("a", "a", 0), new DemoCommand.Roll("a", 1))) {
            s = first.step(s, new Input(++n, n, c)).state();
        }
        ScriptedRandom fresh = ScriptedRandom.withEventCards(script);
        Engine<DemoState> resumed = new Engine<>(base, DemoDomain.INSTANCE, fresh);
        EngineState r = resumed.restore(first.snapshot(s));
        r = resumed.step(r, new Input(4, 4, new DemoCommand.OpenRound("a", "a", 0))).state();
        r = resumed.step(r, new Input(5, 5, new DemoCommand.Roll("a", 2))).state();
        assertEquals(List.of(1, 5), ((DemoState) r.domain()).rolls());
        fresh.assertExhausted(r);
    }

    @Test
    void n5WindowTaskMustMatchKindRefAndDue() {
        Engine<DemoState> engine = new Engine<>(base, DemoDomain.INSTANCE);
        EngineState s = engine.step(engine.create("r", 1, 0).state(), new Input(1, 1, new DemoCommand.Sit("a"))).state();
        DemoState round = new DemoState("a", List.of("a"), new DemoRound("a", Window.open(1, 1, 0, 1000), 1), 2, List.of());
        EngineState wrongKind = s.withTimers(s.timers().schedule(new ScheduledTask(1, 2000, TaskKind.GLOBAL_END, 0)), 2)
                .withDomain(round);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(wrongKind)));
        EngineState wrongDue = s.withTimers(s.timers().schedule(new ScheduledTask(1, 1002, TaskKind.TURN_WINDOW, 1)), 2)
                .withDomain(round);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(wrongDue)));
        EngineState extra = s.withTimers(s.timers()
                        .schedule(new ScheduledTask(1, 1001, TaskKind.TURN_WINDOW, 1))
                        .schedule(new ScheduledTask(2, 1001, TaskKind.TURN_WINDOW, 1)), 3)
                .withDomain(round);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(extra)));
        EngineState good = s.withTimers(s.timers().schedule(new ScheduledTask(1, 1001, TaskKind.TURN_WINDOW, 1)), 2)
                .withDomain(round);
        assertEquals(good, engine.restore(engine.snapshot(good)));
    }

    @Test
    void contractInvalidUnicodeIsRefusedBeforeSeqIsConsumed() {
        Engine<SessionState> engine = new Engine<>(base, SessionDomain.INSTANCE);
        EngineState s = engine.create("r", 1, 0).state();
        RoomCommand bad = new RoomCommand.Join("a", "x\uD800");
        assertThrows(InvalidInputException.class, () -> engine.admitClient(bad));
        assertThrows(InvalidInputException.class, () -> engine.step(s, new Input(1, 1, bad)));
        StepResult next = engine.step(s, new Input(1, 2, new RoomCommand.Join("a", "A")));
        assertEquals(StepResult.Outcome.ACCEPTED, next.outcome(), "seq 1 was never consumed, so no gap");
    }

    @Test
    void contractUndeclaredVisibilityIsNotPublic() {
        record Leaky(String secret) implements Event {
        }
        assertFalse(EventProjector.visibleTo(new Leaky("card"), "a"));
        assertTrue(EventProjector.project(List.of(new Leaky("card")), "a").isEmpty());
    }

    @Test
    void contractEmitRejectsKernelEvents() {
        Engine<SessionState> engine = new Engine<>(base, SessionDomain.INSTANCE);
        DecisionContext<SessionState> ctx = new DecisionContext<>(engine.create("r", 1, 0).state(),
                new Evolver<>(SessionDomain.INSTANCE, base), SessionState.class, XoshiroLemireV1.INSTANCE, base);
        assertThrows(IllegalArgumentException.class, () -> ctx.emit(new KernelEvent.TaskScheduled(
                new ScheduledTask(1, 99, TaskKind.FLOW, 0))));
        assertThrows(IllegalArgumentException.class, () -> ctx.emit(new KernelEvent.InputAccepted(1, 1, "x")));
    }

    @Test
    void n7BlankLedgerAccountRejectedOnRestore() {
        Ledger blank = new Ledger(100, Map.of("", 100L), Map.of("", 100L), Map.of("", 0L), 0, List.of());
        assertThrows(LedgerException.class, blank::verifyInvariants);
        Ledger system = new Ledger(100, Map.of(Ledger.SYSTEM, 100L), Map.of(Ledger.SYSTEM, 100L),
                Map.of(Ledger.SYSTEM, 0L), 0, List.of());
        assertThrows(LedgerException.class, system::verifyInvariants);
    }

    @Test
    void n8AuditChecksGenesisProtocolAndSingleGenesis() {
        Engine<DemoState> engine = new Engine<>(base, DemoDomain.INSTANCE);
        StepResult g0 = engine.create("r", 1, 0);
        List<Event> log = new ArrayList<>(g0.events());
        EngineState s = g0.state();
        long n = 0;
        for (Command c : List.of(new DemoCommand.Sit("a"), new DemoCommand.OpenRound("a", "a", 0), new DemoCommand.Roll("a", 1))) {
            StepResult r = engine.step(s, new Input(++n, n, c));
            s = r.state();
            log.addAll(r.events());
        }
        RandomAudit.verify(log);
        KernelEvent.Genesis g = (KernelEvent.Genesis) log.get(0);
        List<Event> other = new ArrayList<>(log);
        other.set(0, new KernelEvent.Genesis(g.roomId(), g.configHash(), g.engineVersion(), g.domainId(), "other-proto",
                g.rng(), g.at(), g.initial()));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> RandomAudit.verify(other));
        assertTrue(e.getMessage().contains("game was created with other-proto"), e.getMessage());
        List<Event> twice = new ArrayList<>(log);
        twice.add(2, g);
        assertThrows(IllegalStateException.class, () -> RandomAudit.verify(twice));
        assertNotNull(s);
        assertSame(DemoState.class, s.domain().getClass());
    }

    private RuleConfig withRoom(RoomOptions r) {
        return new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), base.station(), base.economy(),
                base.ratios(), base.cardWeights(), base.eventWeights(), base.timing(), r);
    }

    private RuleConfig withRatios(RatioConfig r) {
        return new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), base.station(), base.economy(), r,
                base.cardWeights(), base.eventWeights(), base.timing(), base.room());
    }
}
