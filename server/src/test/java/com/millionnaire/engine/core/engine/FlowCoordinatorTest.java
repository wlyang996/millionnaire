package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowState;
import com.millionnaire.engine.testkit.FlowTestCommand.Act;
import com.millionnaire.engine.testkit.FlowTestCommand.Apply;
import com.millionnaire.engine.testkit.FlowTestCommand.GlobalEndAt;
import com.millionnaire.engine.testkit.FlowTestCommand.OpenKind;
import com.millionnaire.engine.testkit.FlowTestCommand.OpenResponse;
import com.millionnaire.engine.testkit.FlowTestCommand.OpenTurn;
import com.millionnaire.engine.testkit.FlowTestCommand.SafePoint;
import com.millionnaire.engine.testkit.FlowTestDomain;
import com.millionnaire.engine.testkit.FlowTestState;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import com.millionnaire.engine.time.Window;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FlowCoordinator 与两个假模块（回合、申请）的集成测试：跨模块时钟、互斥与恢复。 */
class FlowCoordinatorTest {
    private final Engine<FlowTestState> engine = new Engine<>(RuleConfigs.defaultV1(), FlowTestDomain.INSTANCE);
    private EngineState state;
    private long seq;

    @BeforeEach
    void setUp() {
        state = engine.create("r", 1, 0).state();
        seq = 0;
    }

    private StepResult send(long at, Command c) {
        StepResult r = engine.step(state, new Input(++seq, at, c));
        state = r.state();
        return r;
    }

    private FlowState flow() {
        return ((FlowTestState) state.domain()).flow();
    }

    private List<String> log() {
        return ((FlowTestState) state.domain()).log();
    }

    @Test
    void openWindowIsNotPreemptedAndQueuedRequestStartsAtNextSafePoint() {
        send(0, new OpenTurn("a", 0, 15_000));                          // 窗口 1
        assertEquals(Outcome.ACCEPTED, send(1000, new Apply("b")).outcome());
        assertEquals(RejectionCode.ALREADY_REQUESTED, send(1100, new Apply("b")).rejection(), "one request per applicant");
        send(1200, new Apply("c"));
        assertFalse(flow().top().orElseThrow().window().paused(), "the turn window keeps running");
        assertEquals(RejectionCode.NOT_ALLOWED, send(2000, new SafePoint()).rejection(), "not a safe point while a window is open");
        send(3000, new Act("a", 1));
        send(3000, new SafePoint());                                      // 每个安全点最多启动一个
        assertEquals(FlowKind.AUCTION, flow().top().orElseThrow().kind());
        assertEquals("b", flow().top().orElseThrow().owner());
        assertEquals(1, flow().queue().size(), "c still waits in FIFO order");
        assertEquals("c", flow().queue().get(0).applicant());
    }

    @Test
    void legalInterruptionPausesAndResumesWithRemainingTime() {
        send(0, new OpenTurn("a", 0, 15_000));                          // [0, 15000)
        send(5000, new OpenResponse("b", 10_000));                       // 暂停窗口 1，剩余 10000
        FlowFrame turn = flow().frames().get(0);
        assertTrue(turn.window().paused());
        assertEquals(10_000, turn.window().pausedRemainingMs());
        assertEquals(0, turn.deadlineTaskId());
        send(7000, new Act("b", 2));                                      // 关闭响应窗，恢复窗口 1
        FlowFrame resumed = flow().top().orElseThrow();
        assertEquals(1, resumed.windowId());
        assertEquals(17_000, resumed.window().deadline());
        assertEquals(17_000L, engine.nextWakeUp(state).getAsLong());
        send(7100, new OpenResponse("b", 1000));
        assertEquals(RejectionCode.NOT_ALLOWED, send(7200, new OpenKind("c", FlowKind.AUCTION, 1000)).rejection(),
                "an overlay cannot nest inside a response window");
        assertEquals(RejectionCode.NOT_ALLOWED, send(7300, new OpenKind("c", FlowKind.RESPONSE, 1000)).rejection(),
                "no response inside a response");
    }

    @Test
    void zeroTimeWindowIsExhaustedImmediatelyWithoutCreatingAWindow() {
        StepResult r = send(0, new OpenTurn("a", 0, 0));
        assertEquals(Outcome.ACCEPTED, r.outcome());
        assertTrue(flow().frames().isEmpty());
        assertTrue(state.timers().isEmpty());
        assertEquals(List.of("auto-exhausted:TURN:a"), log());
    }

    @Test
    void exhaustedParentIsClosedAndAutoActedWhenTheChildCloses() {
        // 构造：父窗口暂停且剩余 0（如沿用已耗尽的投骰时间），子响应窗运行中
        send(0, new OpenTurn("a", 0, 15_000));
        send(5000, new OpenResponse("b", 10_000));
        FlowState f = flow();
        FlowFrame parent = f.frames().get(0);
        FlowFrame exhausted = new FlowFrame(parent.windowId(), parent.kind(), parent.owner(),
                new Window(parent.windowId(), parent.window().opensAt(), parent.window().deadline(), true, 0, 0), 0,
                parent.resumeTag());
        FlowState crafted = f.withFrames(List.of(exhausted, f.frames().get(1)));
        state = engine.restore(engine.snapshot(state.withDomain(new FlowTestState(crafted, log()))));
        send(6000, new Act("b", 2));
        assertTrue(flow().frames().isEmpty(), "no zero-length window was created");
        assertEquals(List.of("auto-exhausted:1"), log());
        assertTrue(state.timers().isEmpty());
    }

    @Test
    void globalEndAndResumptionAtTheSameInstantFollowTaskPriority() {
        send(0, new OpenTurn("a", 0, 15_000));
        send(5000, new OpenResponse("b", 10_000));                       // 响应窗截止 15000
        send(5100, new GlobalEndAt(15_000));                             // 同刻，全局到时优先
        send(20_000, new Tick());
        assertEquals(List.of("global-end@15000", "auto:2"), log());
        FlowFrame turn = flow().top().orElseThrow();
        assertEquals(1, turn.windowId());
        assertEquals(25_000, turn.window().deadline(), "resumed at 15000 with the 10000 ms it had left");
    }

    @Test
    void staleCommandsAndTasksCannotActOnANewWindow() {
        send(0, new OpenTurn("a", 0, 15_000));                          // 窗口 1
        send(1000, new Act("a", 1));
        send(2000, new OpenTurn("a", 0, 15_000));                       // 窗口 2（会话内单调编号）
        assertEquals(RejectionCode.WINDOW_MISMATCH, send(3000, new Act("a", 1)).rejection());
        assertTrue(state.timers().tasks().stream().noneMatch(t -> t.ref() == 1), "closing a window cancels its tasks");
        // 过期任务（ref 指向已关闭窗口）即使到期也不会作用于新窗口
        assertTrue(FlowCoordinator.expiredFrame(flow(), new ScheduledTask(1, 15_000, TaskKind.TURN_WINDOW, 1)).isEmpty());
        assertTrue(FlowCoordinator.expiredFrame(flow(), new ScheduledTask(99, 17_000, TaskKind.TURN_WINDOW, 2)).isEmpty(),
                "same window but different task id is stale too");
    }

    @Test
    void validationRejectsBrokenStacksAndUnclaimedTasks() {
        send(0, new OpenTurn("a", 0, 15_000));
        send(5000, new OpenResponse("b", 10_000));
        FlowState f = flow();
        FlowFrame running = f.frames().get(1);
        FlowFrame turn = f.frames().get(0);
        // 两个窗口同时运行
        FlowFrame turnRunning = new FlowFrame(turn.windowId(), turn.kind(), turn.owner(),
                Window.open(1, 0, 0, 15_000), 1, turn.resumeTag());
        assertInvalid(f.withFrames(List.of(turnRunning, running)));
        // 窗口 ID 未分配
        assertInvalid(f.withNextWindowId(2));
        // 嵌套违规：RESPONSE 在栈底
        assertInvalid(f.withFrames(List.of(running)));
        // 无主任务
        EngineState orphan = state.withTimers(state.timers().schedule(new ScheduledTask(state.nextTaskId(), 99_999,
                TaskKind.FLOW, 77)), state.nextTaskId() + 1);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(orphan)));
    }

    private void assertInvalid(FlowState f) {
        EngineState bad = state.withDomain(new FlowTestState(f, log()));
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(bad)));
    }
}
