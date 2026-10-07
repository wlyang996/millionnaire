package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.serialize.Canonical;
import com.millionnaire.engine.testkit.Table;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Includes internal primitives in a typed test trace. This is not a client command or a production input receipt. */
class M3cReplayTest {
    public static void main(String[] args) {
        var t = trace(404).table();
        System.out.println(Canonical.sha256Hex(t.engine.encodeEvents(t.log).getBytes(StandardCharsets.UTF_8)));
    }
    record Op(Input input, MovementEffect.Kind kind, String player, long window, int distance) {
        static Op input(Input value) { return new Op(value, null, null, 0, 0); }
        static Op effect(MovementEffect.Kind kind, Table t, int distance) { return new Op(null, kind, t.current(), t.windowId(), distance); }
        StepResult run(Table t, EngineState state) {
            if (input != null) { return t.engine.step(state, input); }
            var ctx = new DecisionContext<>(state, new Evolver<>(SessionDomain.INSTANCE, t.config), SessionState.class, XoshiroLemireV1.INSTANCE, t.config);
            RejectionCode why = kind == MovementEffect.Kind.ROADBLOCK ? MovementEffects.placeRoadblock(ctx, player, window)
                    : MovementEffects.targeted(ctx, player, window, distance);
            if (why != null) {
                assertEquals(RejectionCode.NOT_ALLOWED, why, "only the forbidden jail tile may reject a trace placement");
                assertTrue(ctx.events().isEmpty()); assertEquals(state, ctx.engineState());
                return new StepResult(state, List.of(), StepResult.Outcome.REJECTED, why);
            }
            t.engine.validate(ctx.engineState());
            return new StepResult(ctx.engineState(), ctx.events(), StepResult.Outcome.ACCEPTED, null);
        }
    }
    record Trace(Table table, List<EngineState> states, List<Op> ops, List<List<Event>> batches, List<Integer> cuts) { }
    static Trace trace(long seed) {
        var t = Table.production(seed).start(3); var states = new ArrayList<EngineState>(); states.add(t.state);
        var ops = new ArrayList<Op>(); var batches = new ArrayList<List<Event>>(); var cuts = new TreeSet<Integer>(); cuts.add(0);
        int rolls = 0;
        while (t.session().inGame() && ops.size() < 800) {
            var g = t.game(); var w = t.window();
            long at = Math.max(t.now + 1, w.window().opensAt());
            if (at > t.now) { record(t, Op.input(new Input(t.seq + 1, at, new Tick())), states, ops, batches, cuts); if (!t.session().inGame()) { break; } }
            g = t.game(); w = t.window();
            if (g.turn().stage() == TurnStage.PRE_ROLL && g.phase() == GamePhase.RUNNING) {
                rolls++;
                if (g.board().roadblock(t.position(t.current())).isEmpty()) {
                    record(t, Op.effect(MovementEffect.Kind.ROADBLOCK, t, 0), states, ops, batches, cuts);
                }
                if (rolls % 3 == 0) {
                    record(t, Op.effect(MovementEffect.Kind.TARGETED, t, rolls % 6 + 1), states, ops, batches, cuts);
                    continue;
                }
            }
            Command c;
            var l = g.turn().landing();
            if (g.debt() != null) { c = new GameCommand.DeclareBankruptcy(t.current(), w.windowId()); }
            else if (l == null) { c = new GameCommand.RollDice(t.current(), w.windowId()); }
            else {
                c = switch (l.step()) {
                    case BUY -> new GameCommand.DeclinePurchase(t.current(), w.windowId());
                    case UPGRADE -> new GameCommand.SkipUpgrade(t.current(), w.windowId());
                    case BANK -> new GameCommand.FinishBank(t.current(), w.windowId());
                    case EVENT -> new GameCommand.DrawEventCard(t.current(), w.windowId());
                    case DISCARD -> new GameCommand.DiscardCard(t.current(), w.windowId(), l.event().newCardIndex());
                    default -> throw new AssertionError(l);
                };
            }
            record(t, Op.input(new Input(t.seq + 1, Math.max(t.now, w.window().opensAt()), c)), states, ops, batches, cuts);
        }
        if (t.session().inGame()) {
            record(t, Op.input(new Input(t.seq + 1, t.game().clock().endsAt(), new Tick())), states, ops, batches, cuts);
        }
        while (t.session().inGame() && ops.size() < 900) {
            record(t, Op.input(new Input(t.seq + 1, Math.max(t.now, t.window().window().deadline()), new Tick())), states, ops, batches, cuts);
        }
        assertFalse(t.session().inGame(), "trace must finish by global expiry and drain within bound");
        cuts.add(ops.size() / 2); cuts.add(ops.size() - 1);
        assertEquals(t.state, t.engine.rebuild(t.log));
        return new Trace(t, states, ops, batches, new ArrayList<>(cuts));
    }
    static void record(Table t, Op op, List<EngineState> states, List<Op> ops, List<List<Event>> batches, TreeSet<Integer> cuts) {
        var before = t.state; var result = op.run(t, before);
        assertEquals(result, op.run(t, t.engine.restore(t.engine.snapshot(before))), "every stable boundary resumes identically");
        if (op.input() != null) { t.seq = op.input().seq(); t.now = op.input().serverTime(); }
        t.state = result.state(); t.log.addAll(result.events()); states.add(t.state); ops.add(op); batches.add(result.events());
        if (cuts.size() < 18 && result.events().stream().anyMatch(e -> e instanceof GameEvent.RoadblockTriggered
                || e instanceof GameEvent.EventMoveCommitted || e instanceof GameEvent.StartRewardPaid || e instanceof GameEvent.PlayerJailed)) {
            cuts.add(ops.size() - 1); cuts.add(ops.size());
        }
    }
    @Test void longMixedGamesResumeAtEveryBoundaryAndCompleteTailsAtCriticalCuts() {
        var aggregate = new ArrayList<Event>();
        for (long seed : new long[] {404, 20261007}) {
            var trace = trace(seed); var t = trace.table(); aggregate.addAll(t.log);
            for (int cut : trace.cuts()) {
                var state = t.engine.restore(t.engine.snapshot(trace.states().get(cut)));
                for (int i = cut; i < trace.ops().size(); i++) {
                    var result = trace.ops().get(i).run(t, state);
                    assertEquals(trace.batches().get(i), result.events(), "tail events cut=" + cut + " op=" + i);
                    state = result.state(); assertEquals(trace.states().get(i + 1), state);
                }
                assertEquals(t.state, state);
            }
            System.out.println("M3C_TRACE seed=" + seed + " ops=" + trace.ops().size() + " events=" + t.log.size()
                    + " cuts=" + trace.cuts() + " hash=" + Canonical.sha256Hex(t.engine.encodeEvents(t.log).getBytes(StandardCharsets.UTF_8)));
        }
        assertTrue(aggregate.stream().anyMatch(GameEvent.RoadblockPlaced.class::isInstance));
        assertTrue(aggregate.stream().anyMatch(GameEvent.RoadblockTriggered.class::isInstance));
        assertTrue(aggregate.stream().anyMatch(e -> e instanceof GameEvent.PlayerMoved m && m.kind() == MoveKind.TARGETED));
        assertTrue(aggregate.stream().anyMatch(e -> e instanceof GameEvent.PlayerMoved m && m.stoppedBy() != null
                && (m.kind() == MoveKind.EVENT_FORWARD || m.kind() == MoveKind.EVENT_BACKWARD)), "event relocation must be interrupted in the long trace");
        assertTrue(aggregate.stream().anyMatch(GameEvent.StartRewardPaid.class::isInstance));
        assertTrue(aggregate.stream().anyMatch(GameEvent.PlayerJailed.class::isInstance));
    }
    @Test void m3cInternalTraceHasIdenticalBytesInAnotherJvmAndLocale() throws Exception {
        var t = trace(404).table(); String expected = Canonical.sha256Hex(t.engine.encodeEvents(t.log).getBytes(StandardCharsets.UTF_8));
        var javaExe = java.nio.file.Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var process = new ProcessBuilder(javaExe, "-Duser.language=tr", "-Duser.country=TR", "-Duser.timezone=Pacific/Auckland",
                "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), M3cReplayTest.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            // 只比最后一行：子 JVM 可能先打印 "Picked up JAVA_TOOL_OPTIONS ..." 之类的环境提示
            String[] lines = output.split("\\R");
            assertEquals(0, process.exitValue(), output); assertEquals(expected, lines[lines.length - 1].trim(), output);
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
    }
}
