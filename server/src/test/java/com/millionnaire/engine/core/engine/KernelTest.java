package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.testkit.TestBoards;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.DemoCommand.OpenRound;
import com.millionnaire.engine.testkit.DemoCommand.PauseRound;
import com.millionnaire.engine.testkit.DemoCommand.Peek;
import com.millionnaire.engine.testkit.DemoCommand.ResumeRound;
import com.millionnaire.engine.testkit.DemoCommand.Roll;
import com.millionnaire.engine.testkit.DemoCommand.ScheduleStop;
import com.millionnaire.engine.testkit.DemoCommand.Sit;
import com.millionnaire.engine.testkit.DemoDomain;
import com.millionnaire.engine.testkit.DemoEvent;
import com.millionnaire.engine.testkit.DemoRound;
import com.millionnaire.engine.testkit.DemoState;
import com.millionnaire.engine.testkit.RogueDomain;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.time.Window;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 内核行为（借助演示领域）：输入契约、时间线、定时任务、随机消费、窗口、投影。 */
class KernelTest {
    private final Engine<DemoState> engine = new Engine<>(TestBoards.legacyV1(), DemoDomain.INSTANCE);
    private EngineState state;
    private long seq;

    @BeforeEach
    void setUp() {
        state = engine.create("r", 99, 0).state();
        seq = 0;
    }

    private StepResult send(long at, Command c) {
        StepResult r = engine.step(state, new Input(++seq, at, c));
        state = r.state();
        return r;
    }

    private DemoState demo() {
        return (DemoState) state.domain();
    }

    private void seat(String... players) {
        for (String p : players) {
            send(state.lastReceivedAt() + 1, new Sit(p));
        }
    }

    // ------------------------------------------------------------ 输入契约

    @Test
    void duplicateSameContentIsIgnoredButSameSeqDifferentContentIsContractViolation() {
        seat("a");
        EngineState before = state;
        StepResult dup = engine.step(state, new Input(seq, 1, new Sit("a")));
        assertEquals(Outcome.DUPLICATE, dup.outcome());
        assertSame(before, dup.state());
        assertTrue(dup.events().isEmpty());
        assertThrows(InputOrderException.class, () -> engine.step(before, new Input(seq, 1, new Sit("x"))));
        assertThrows(InputOrderException.class, () -> engine.step(before, new Input(seq, 2, new Sit("a"))));
    }

    @Test
    void staleAndGapInputs() {
        seat("a", "b");
        assertEquals(Outcome.STALE, engine.step(state, new Input(1, 99, new Tick())).outcome());
        assertThrows(InputOrderException.class, () -> engine.step(state, new Input(seq + 2, 100, new Tick())));
        assertEquals(2, state.lastSeq(), "contract violations leave state untouched");
    }

    @Test
    void rejectionOnlyMovesCursorAndWatermark() {
        seat("a", "b");
        send(100, new OpenRound("a", "b", 5000));
        EngineState before = state;
        StepResult r = send(200, new Roll("b", 1));
        assertEquals(RejectionCode.WINDOW_NOT_OPEN, r.rejection());
        assertEquals(1, r.events().size());
        KernelEvent.InputRejected rej = (KernelEvent.InputRejected) r.events().get(0);
        assertEquals("b", rej.recipient());
        assertEquals(200, state.lastReceivedAt());
        // 完整状态比较：只有游标、水位、摘要与事件数允许变化
        assertEquals(before.withInputCursor(state.lastSeq(), state.lastReceivedAt(), state.lastInputDigest())
                .withEventCount(state.eventCount()), state);
    }

    @Test
    void watermarkIsNotLoweredByRegressedInput() {
        seat("a");
        send(5000, new Peek("zz"));                       // 被拒但水位到 5000
        assertEquals(RejectionCode.TIME_REGRESSION, send(4999, new Peek("a")).rejection());
        assertEquals(5000, state.lastReceivedAt());
        assertEquals(Outcome.ACCEPTED, send(5000, new Peek("a")).outcome());
    }

    // ------------------------------------------------------------ 重复 / 过期 / 已拒绝不产生第二次效果

    @Test
    void repeatedRollAfterSuccessHasNoSecondEffect() {
        seat("a", "b");
        send(100, new OpenRound("a", "b", 0));
        assertEquals(Outcome.ACCEPTED, send(200, new Roll("b", 1)).outcome());
        EngineState afterFirst = state;
        for (int i = 0; i < 3; i++) {
            assertEquals(RejectionCode.NO_ACTIVE_WINDOW, send(300 + i, new Roll("b", 1)).rejection());
        }
        assertEquals(afterFirst.rng(), state.rng());
        assertEquals(1, demo().rolls().size());
        send(400, new OpenRound("a", "b", 0));
        assertEquals(RejectionCode.WINDOW_MISMATCH, send(500, new Roll("b", 1)).rejection(), "stale window id");
        assertEquals(afterFirst.rng(), state.rng());
    }

    @Test
    void expiredCommandLosesToTimeoutAtExactDeadline() {
        seat("a", "b");
        send(100, new OpenRound("a", "b", 0));             // 截止 15100
        StepResult r = send(15_100, new Roll("b", 1));
        assertEquals(RejectionCode.NO_ACTIVE_WINDOW, r.rejection());
        assertTrue(r.events().get(0) instanceof KernelEvent.TaskFired);
        DemoEvent.DieRolled rolled = r.events().stream().filter(e -> e instanceof DemoEvent.DieRolled)
                .map(e -> (DemoEvent.DieRolled) e).findFirst().orElseThrow();
        assertTrue(rolled.auto());
        assertEquals(15_100, state.now());
        assertEquals(1, demo().rolls().size());
    }

    @Test
    void commandJustBeforeDeadlineWins() {
        seat("a", "b");
        send(100, new OpenRound("a", "b", 0));
        assertEquals(Outcome.ACCEPTED, send(15_099, new Roll("b", 1)).outcome());
        assertTrue(state.timers().isEmpty());
        send(40_000, new Tick());
        assertEquals(1, demo().rolls().size());
    }

    // ------------------------------------------------------------ 时间与任务

    @Test
    void sameInstantGlobalEndBeatsWindowTimeoutAndSavesRandomness() {
        seat("a", "b");
        send(100, new OpenRound("a", "b", 0));             // 截止 15100
        send(200, new ScheduleStop("a", 15_100));
        EngineState before = state;
        StepResult r = send(20_000, new Tick());
        List<Class<?>> kinds = r.events().stream().<Class<?>>map(Object::getClass).toList();
        assertEquals(List.of(KernelEvent.TaskFired.class, KernelEvent.TaskCancelled.class,
                DemoEvent.RoundAborted.class, KernelEvent.InputAccepted.class), kinds);
        assertEquals(before.rng(), state.rng());
        assertTrue(demo().rolls().isEmpty());
    }

    @Test
    void allDueTasksRunBeforeInputInTotalOrder() {
        seat("a");
        send(100, new ScheduleStop("a", 9000));
        send(200, new ScheduleStop("a", 5000));
        send(300, new ScheduleStop("a", 5000));
        StepResult r = send(10_000, new Tick());
        List<Long> fired = r.events().stream().filter(e -> e instanceof KernelEvent.TaskFired)
                .map(e -> ((KernelEvent.TaskFired) e).taskId()).toList();
        assertEquals(List.of(2L, 3L, 1L), fired);
        assertTrue(engine.nextWakeUp(state).isEmpty());
    }

    @Test
    void pauseAndResumeKeepRemainingTimeAndBuffer() {
        seat("a", "b");
        send(1000, new OpenRound("a", "b", 2000));         // [3000, 18000)
        send(2000, new PauseRound("a"));                    // 缓冲剩 1000，窗口 15000
        assertTrue(state.timers().isEmpty());
        assertEquals(RejectionCode.WINDOW_PAUSED, send(2500, new Roll("b", 1)).rejection());
        send(10_000, new ResumeRound("a"));                 // [11000, 26000)
        assertEquals(11_000, demo().round().window().opensAt());
        assertEquals(26_000, demo().round().window().deadline());
        assertEquals(26_000L, engine.nextWakeUp(state).getAsLong());
        assertEquals(RejectionCode.WINDOW_NOT_OPEN, send(10_500, new Roll("b", 1)).rejection());
        assertEquals(Outcome.ACCEPTED, send(11_000, new Roll("b", 1)).outcome());
    }

    @Test
    void exhaustedResumeRunsAutoActionWithoutCreatingWindow() {
        seat("a", "b");
        // 构造一个剩余时间为 0 的暂停窗口（M1 中"沿用已耗尽的投骰时间"即此情形），经恢复入口校验
        DemoRound exhausted = new DemoRound("b", new Window(1, 50, 100, true, 0, 0), 0);
        EngineState crafted = state.withDomain(new DemoState("a", demo().players(), exhausted, 2, List.of()));
        state = engine.restore(engine.snapshot(crafted));
        StepResult r = send(500, new ResumeRound("a"));
        assertEquals(Outcome.ACCEPTED, r.outcome());
        assertFalse(r.events().stream().anyMatch(e -> e instanceof KernelEvent.TaskScheduled), "no window created");
        assertTrue(r.events().stream().anyMatch(e -> e instanceof DemoEvent.DieRolled d && d.auto()));
        assertNull(demo().round());
    }

    // ------------------------------------------------------------ 随机

    @Test
    void scriptedRandomChecksPointBoundAndConsumption() {
        ScriptedRandom script = ScriptedRandom.withEventCards(List.of(
                ScriptedRandom.step(DrawPoint.MOVE_DIE, 6, 0),
                ScriptedRandom.step(DrawPoint.MOVE_DIE, 6, 5)));
        Engine<DemoState> scripted = new Engine<>(TestBoards.legacyV1(), DemoDomain.INSTANCE, script);
        EngineState s = scripted.create("r", 1, 0).state();
        long n = 0;
        for (Command c : List.of(new Sit("a"), new OpenRound("a", "a", 0), new Roll("a", 1),
                new Roll("a", 1), new OpenRound("a", "a", 0))) {
            s = scripted.step(s, new Input(++n, n * 10, c)).state();
        }
        assertEquals(1, ScriptedRandom.cursor(s.rng()), "the rejected second roll must not consume a scripted draw");
        s = scripted.step(s, new Input(++n, 100_000, new Tick())).state();   // 超时代掷
        script.assertExhausted(s);
        assertEquals(List.of(1, 6), ((DemoState) s.domain()).rolls());
        assertThrows(StateValidationException.class, () -> engine.step(scripted.create("r", 1, 0).state(),
                new Input(1, 1, new Tick())), "a production engine refuses a scripted-protocol game");
    }

    @Test
    void decisionContextExposesWorkingStateAfterEachEmit() {
        seat("a");
        DecisionContext<DemoState> ctx = new DecisionContext<>(state, new Evolver<>(DemoDomain.INSTANCE, engine.config()),
                DemoState.class, com.millionnaire.engine.random.XoshiroLemireV1.INSTANCE, engine.config());
        long taskId = ctx.schedule(5000, com.millionnaire.engine.time.TaskKind.GLOBAL_END, 0);
        assertEquals(taskId + 1, ctx.engineState().nextTaskId());
        ctx.emit(new DemoEvent.Sat("b"));
        assertEquals(List.of("a", "b"), ctx.state().players());
        int v = ctx.draw(DrawPoint.MOVE_DIE, 6);
        assertEquals(1, ctx.engineState().pendingDraws().size(), "draw stays pending until a domain event consumes it");
        assertTrue(v >= 0 && v < 6);
        assertEquals(state.domain(), engine.step(state, new Input(seq + 1, 10, new Tick())).state().domain(),
                "the original state is untouched by the working copy");
    }

    @Test
    void acceptedWithoutConsumingDrawIsKernelFault() {
        Engine<DemoState> rogue = new Engine<>(TestBoards.legacyV1(), new RogueDomain(RogueDomain.Mode.DRAW_WITHOUT_CONSUMING));
        EngineState s = rogue.step(rogue.create("r", 1, 0).state(), new Input(1, 1, new Sit("a"))).state();
        KernelFaultException e = assertThrows(KernelFaultException.class, () -> rogue.step(s, new Input(2, 2, new Peek("a"))));
        assertTrue(e.getMessage().contains("without consuming"), e.getMessage());
    }

    @Test
    void drawThenRejectIsKernelFault() {
        Engine<DemoState> rogue = new Engine<>(TestBoards.legacyV1(), new RogueDomain(RogueDomain.Mode.DRAW_THEN_REJECT));
        EngineState s = rogue.step(rogue.create("r", 1, 0).state(), new Input(1, 1, new Sit("a"))).state();
        assertThrows(KernelFaultException.class, () -> rogue.step(s, new Input(2, 2, new Peek("a"))));
        assertEquals(Outcome.ACCEPTED, rogue.step(s, new Input(2, 2, new Sit("b"))).outcome(), "nothing was committed");
    }

    @Test
    void inconsistentDomainEventIsKernelFault() {
        Engine<DemoState> rogue = new Engine<>(TestBoards.legacyV1(), new RogueDomain(RogueDomain.Mode.INCONSISTENT_EVENT));
        EngineState s = rogue.step(rogue.create("r", 1, 0).state(), new Input(1, 1, new Sit("a"))).state();
        assertThrows(KernelFaultException.class, () -> rogue.step(s, new Input(2, 2, new Peek("a"))));
    }

    // ------------------------------------------------------------ 投影

    @Test
    void privateEventsOnlyReachTheirRecipient() {
        seat("a", "b");
        StepResult peek = send(100, new Peek("a"));
        StepResult rejected = send(200, new Peek("zz"));
        List<Event> all = new java.util.ArrayList<>(peek.events());
        all.addAll(rejected.events());
        assertEquals(1, EventProjector.project(all, "a").size());
        assertTrue(EventProjector.project(all, "a").get(0) instanceof DemoEvent.Peeked);
        assertEquals(List.of(), EventProjector.project(all, "b"));
        assertEquals(1, EventProjector.project(all, "zz").size());
        assertTrue(EventProjector.project(all, "zz").get(0) instanceof KernelEvent.InputRejected);
    }

    @Test
    void randomAndTimerEventsAreNeverProjected() {
        seat("a");
        send(100, new OpenRound("a", "a", 0));
        StepResult r = send(200, new Roll("a", 1));
        List<Event> visible = EventProjector.project(r.events(), "a");
        assertEquals(List.of(DemoEvent.DieRolled.class), visible.stream().<Class<?>>map(Object::getClass).toList());
    }
}
