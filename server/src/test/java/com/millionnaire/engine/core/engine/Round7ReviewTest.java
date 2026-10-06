package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.ConfigValidator;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.Tier;
import com.millionnaire.engine.config.TierPricing;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.event.RoomEvent;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.ledger.JournalEntry;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.ledger.LedgerException;
import com.millionnaire.engine.ledger.Leg;
import com.millionnaire.engine.money.Money;
import com.millionnaire.engine.money.Ratio;
import com.millionnaire.engine.money.Rounding;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.replay.RandomAudit;
import com.millionnaire.engine.serialize.Canonical;
import com.millionnaire.engine.testkit.DemoCommand;
import com.millionnaire.engine.testkit.DemoDomain;
import com.millionnaire.engine.testkit.DemoEvent;
import com.millionnaire.engine.testkit.DemoState;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import com.millionnaire.engine.time.TimerQueue;
import com.millionnaire.engine.time.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 第 7 轮评审 R1–R13 的反例回归（修复前全部失败，见 m0-fix-report）。细粒度测试在各模块测试类中。
 */
class Round7ReviewTest {
    private final RuleConfig config = RuleConfigs.defaultV1();

    @Test
    void r1LoneSurrogateRejectedAndEmojiLossless() {
        assertThrows(IllegalArgumentException.class, () -> Canonical.bytes("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> Canonical.bytes("a\uDC00b"));
        String emoji = "😀";
        byte[] bytes = Canonical.bytes(emoji);
        assertEquals(emoji, Canonical.decode(bytes, String.class));
        assertNotEquals(Canonical.sha256Hex("?"), Canonical.sha256Hex(emoji));
    }

    @Test
    void r2NonAdjacentDuplicateTaskIdRejectedByConstructorAndDecoder() {
        List<ScheduledTask> dup = List.of(new ScheduledTask(1, 100, TaskKind.FLOW, 0),
                new ScheduledTask(2, 150, TaskKind.FLOW, 0), new ScheduledTask(1, 200, TaskKind.FLOW, 0));
        assertThrows(IllegalArgumentException.class, () -> new TimerQueue(dup));
        String text = "{\"tasks\":[{\"taskId\":1,\"dueAt\":100,\"kind\":\"FLOW\",\"ref\":0},"
                + "{\"taskId\":2,\"dueAt\":150,\"kind\":\"FLOW\",\"ref\":0},{\"taskId\":1,\"dueAt\":200,\"kind\":\"FLOW\",\"ref\":0}]}";
        assertThrows(IllegalArgumentException.class, () -> Canonical.decode(text, TimerQueue.class));
        assertThrows(IllegalArgumentException.class, () -> new TimerQueue(List.of(new ScheduledTask(0, 1, TaskKind.FLOW, 0))));
    }

    @Test
    void r3IllegalRatiosGiveLocatableErrors() {
        assertTrue(errorsWithLowEmergency(new Ratio(-80, 100)).stream().anyMatch(e -> e.contains("emergencyMortgage numerator")));
        assertTrue(errorsWithLowEmergency(new Ratio(80, 0)).stream().anyMatch(e -> e.contains("emergencyMortgage denominator")));
        assertTrue(errorsWithLowEmergency(new Ratio(101, 100)).stream().anyMatch(e -> e.contains("emergencyMortgage must be <= 100%")));
    }

    @Test
    void r4ReceiveWatermarkSurvivesRejection() {
        Engine<DemoState> engine = new Engine<>(config, DemoDomain.INSTANCE);
        EngineState s = engine.create("r", 1, 0).state();
        s = engine.step(s, new Input(1, 1, new DemoCommand.Sit("a"))).state();
        s = engine.step(s, new Input(2, 2, new DemoCommand.Sit("b"))).state();
        s = engine.step(s, new Input(3, 100, new DemoCommand.OpenRound("a", "b", 5000))).state();
        StepResult early = engine.step(s, new Input(4, 5000, new DemoCommand.Roll("b", 1)));
        assertEquals(RejectionCode.WINDOW_NOT_OPEN, early.rejection());
        s = early.state();
        assertEquals(5000, s.lastReceivedAt());
        assertEquals(100, s.now(), "business time unchanged by rejection");
        StepResult back = engine.step(s, new Input(5, 200, new DemoCommand.PauseRound("a")));
        assertEquals(RejectionCode.TIME_REGRESSION, back.rejection());
        assertFalse(((DemoState) back.state().domain()).round().window().paused());
    }

    @Test
    void r5InconsistentSnapshotsAndLedgerHistoriesRejected() {
        Engine<SessionState> engine = new Engine<>(config, SessionDomain.INSTANCE);
        EngineState s = engine.step(engine.create("r", 1, 0).state(), new Input(1, 1, new RoomCommand.Join("a", "A"))).state();
        SessionState session = (SessionState) s.domain();
        EngineState noHost = s.withDomain(session.withLobby(new RoomState(session.lobby().status(), null,
                session.lobby().members(), session.lobby().settings())));
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(noHost)));

        Map<String, Long> initial = new TreeMap<>(Map.of("a", 100L, "b", 100L));
        Ledger transientOverdraft = new Ledger(200, initial, initial, Map.of("a", 0L, "b", 0L), 0, List.of(
                new JournalEntry(1, "X", null, List.of(new Leg("a", -150), new Leg("b", 150))),
                new JournalEntry(2, "Y", null, List.of(new Leg("b", -150), new Leg("a", 150)))));
        assertThrows(LedgerException.class, transientOverdraft::verifyInvariants);
        Ledger zeroLeg = new Ledger(200, initial, initial, Map.of("a", 0L, "b", 0L), 0, List.of(
                new JournalEntry(7, "", null, List.of(new Leg("a", 0)))));
        assertThrows(LedgerException.class, zeroLeg::verifyInvariants);
        Ledger withHistory = Ledger.open(initial).transfer("a", "b", 10, "RENT", null);
        assertThrows(LedgerException.class, () -> withHistory.transact(x -> Ledger.open(Map.of("a", 90L, "b", 110L))));
    }

    @Test
    void r6DerivedAmountOverflowIsConfigError() {
        List<TierPricing> tiers = config.tiers().stream().map(t -> t.tier() == Tier.LOW
                ? new TierPricing(t.tier(), Long.MAX_VALUE - 7, 300, t.rents(), new Ratio(1, 1)) : t).toList();
        RuleConfig bad = new RuleConfig(config.ruleVersion(), config.boards(), tiers, config.station(), config.economy(),
                config.ratios(), config.cardWeights(), config.eventWeights(), config.timing(), config.room());
        List<String> errors = assertDoesNotThrow(() -> ConfigValidator.validate(bad));
        assertTrue(errors.stream().anyMatch(e -> e.contains("tier[LOW].basePrice")), errors.toString());
    }

    @Test
    void r7EventTileReplacedByRestFailsRulesV1Spec() {
        String layout = RuleConfigs.LAYOUT_30.replaceFirst(" E ", " R ");
        RuleConfig bad = new RuleConfig(config.ruleVersion(),
                List.of(RuleConfigs.board(RuleConfigs.BOARD_30, 2, 4, layout), config.boards().get(1)),
                config.tiers(), config.station(), config.economy(), config.ratios(), config.cardWeights(),
                config.eventWeights(), config.timing(), config.room());
        assertTrue(ConfigValidator.validate(bad).stream().anyMatch(e -> e.contains("must have 5 EVENT")));
    }

    @Test
    void r8TamperedDomainValueAndVersionMismatchDetected() {
        Engine<DemoState> engine = new Engine<>(config, DemoDomain.INSTANCE);
        List<Event> log = new ArrayList<>();
        StepResult g = engine.create("r", 1, 0);
        log.addAll(g.events());
        EngineState s = g.state();
        for (Input in : List.of(new Input(1, 1, new DemoCommand.Sit("a")),
                new Input(2, 2, new DemoCommand.OpenRound("a", "a", 0)), new Input(3, 3, new DemoCommand.Roll("a", 1)))) {
            StepResult r = engine.step(s, in);
            s = r.state();
            log.addAll(r.events());
        }
        List<Event> tampered = log.stream().map(e -> e instanceof DemoEvent.DieRolled d
                ? new DemoEvent.DieRolled(d.playerId(), d.windowId(), 999, d.auto()) : e).toList();
        // J5：重建遇到不一致统一为 StateValidationException，原始断言失败保留为 cause
        StateValidationException e = assertThrows(StateValidationException.class, () -> engine.rebuild(tampered));
        assertInstanceOf(IllegalStateException.class, e.getCause());

        KernelEvent.Genesis gen = (KernelEvent.Genesis) log.get(0);
        List<Event> oldVersion = new ArrayList<>(log);
        oldVersion.set(0, new KernelEvent.Genesis(gen.roomId(), gen.configHash(), "engine-0.0.9", gen.domainId(),
                gen.rngProtocol(), gen.rng(), gen.at(), gen.initial()));
        assertThrows(StateValidationException.class, () -> engine.rebuild(oldVersion));
        assertDoesNotThrow(() -> RandomAudit.verify(log));
    }

    @Test
    void r9ScriptedRandomAndWorkingStateContract() {
        DecisionContext<SessionState> ctx = new DecisionContext<>(new Engine<>(config, SessionDomain.INSTANCE).create("r", 1, 0).state(),
                new Evolver<>(SessionDomain.INSTANCE, config), SessionState.class, XoshiroLemireV1.INSTANCE, config);
        ctx.emit(new RoomEvent.PlayerJoined("a", "A"));
        assertTrue(ctx.state().lobby().isMember("a"), "emit must be visible to later reads");

        ScriptedRandom script = ScriptedRandom.withEventCards(List.of(ScriptedRandom.step(DrawPoint.MOVE_DIE, 6, 5)));
        Engine<DemoState> engine = new Engine<>(config, DemoDomain.INSTANCE, script);
        EngineState s = engine.create("r", 1, 0).state();
        s = engine.step(s, new Input(1, 1, new DemoCommand.Sit("a"))).state();
        s = engine.step(s, new Input(2, 2, new DemoCommand.OpenRound("a", "a", 0))).state();
        s = engine.step(s, new Input(3, 3, new DemoCommand.Roll("a", 1))).state();
        assertEquals(List.of(6), ((DemoState) s.domain()).rolls());
        script.assertExhausted(s);
    }

    @Test
    void r10ZeroRemainingResumeIsExhaustedNotException() {
        Window w = new Window(1, 0, 100, true, 0, 0);
        assertInstanceOf(Window.Resumption.Exhausted.class, w.resume(200));
        assertThrows(IllegalArgumentException.class, () -> new Window(1, 0, 100, true, 5, 30), "lead>0 requires full window");
    }

    @Test
    void r11PrivateRejectionNamesRecipientAndIsProjectedOnlyToIt() {
        Engine<SessionState> engine = new Engine<>(config, SessionDomain.INSTANCE);
        EngineState s = engine.step(engine.create("r", 1, 0).state(), new Input(1, 1, new RoomCommand.Join("a", "A"))).state();
        StepResult r = engine.step(s, new Input(2, 2, new RoomCommand.Kick("b", "a")));
        Event rejected = r.events().get(r.events().size() - 1);
        assertEquals("b", rejected.recipient());
        assertEquals(List.of(rejected), EventProjector.project(r.events(), "b"));
        assertEquals(List.of(), EventProjector.project(r.events(), "a"));
    }

    @Test
    void r13NullRoundingRejected() {
        assertThrows(NullPointerException.class, () -> Money.div(7, 2, null));
        assertThrows(NullPointerException.class, () -> Money.mulDiv(Long.MAX_VALUE, 3, 7, null));
        assertEquals(4, Money.div(7, 2, Rounding.CEIL));
    }

    @Test
    void tickHasNoActorAndNeverBecomesPrivate() {
        Engine<SessionState> engine = new Engine<>(config, SessionDomain.INSTANCE);
        EngineState s = engine.step(engine.create("r", 1, 0).state(), new Input(1, 100, new Tick())).state();
        StepResult r = engine.step(s, new Input(2, 50, new Tick()));
        assertEquals(RejectionCode.TIME_REGRESSION, r.rejection());
        assertEquals(List.of(), EventProjector.project(r.events(), null));
    }

    private List<String> errorsWithLowEmergency(Ratio emergency) {
        List<TierPricing> tiers = config.tiers().stream().map(t -> t.tier() == Tier.LOW
                ? new TierPricing(t.tier(), t.basePrice(), t.upgradeCost(), t.rents(), emergency) : t).toList();
        RuleConfig c = new RuleConfig(config.ruleVersion(), config.boards(), tiers, config.station(), config.economy(),
                config.ratios(), config.cardWeights(), config.eventWeights(), config.timing(), config.room());
        return assertDoesNotThrow(() -> ConfigValidator.validate(c));
    }
}
