package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.GameEvent.FlowDequeued;
import com.millionnaire.engine.core.event.GameEvent.FlowEvent;
import com.millionnaire.engine.core.event.GameEvent.FlowRequestCancelled;
import com.millionnaire.engine.core.event.GameEvent.FlowRequested;
import com.millionnaire.engine.core.event.GameEvent.SafePointEntered;
import com.millionnaire.engine.core.event.GameEvent.WindowClosed;
import com.millionnaire.engine.core.event.GameEvent.WindowOpened;
import com.millionnaire.engine.core.event.GameEvent.WindowPaused;
import com.millionnaire.engine.core.event.GameEvent.WindowResumed;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.FlowState;
import com.millionnaire.engine.serialize.Immutable;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import com.millionnaire.engine.time.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 流程协调（与具体规则无关）。规则取自 open-decisions "规则补充 v1"（O12）与 opus-analysis 4.5：
 * <ul>
 *   <li><b>不抢占</b>：窗口开启后不被申请打断；拍卖/交易申请进入 FIFO 队列，只在安全点（无任何窗口时）启动，
 *       每个安全点最多启动一个；每位申请者至多一个待处理申请。</li>
 *   <li><b>允许的嵌套</b>（栈深 ≤ 3）：TURN 只在栈底；覆盖流程在空栈或 TURN 之上；RESPONSE 在任一非 RESPONSE 之上。
 *       开启子窗口时暂停父窗口（保存剩余缓冲与剩余时长）；栈顶之外的窗口都必须暂停。</li>
 *   <li><b>恢复与耗尽</b>：关闭栈顶后恢复父窗口；父窗口剩余为 0 则以 EXHAUSTED 关闭并交还模块立即执行自动动作，
 *       不创建零长度窗口；以 0 时长开启窗口同样直接返回耗尽。</li>
 *   <li><b>旧任务不作用新窗口</b>：任务以 windowId 为 ref 且 ID 精确匹配才视为当前窗口的到期；关闭窗口时取消其任务。</li>
 * </ul>
 * 窗口 ID 由 {@link FlowState#nextWindowId()} 在会话内单调分配；模块不得自行编号，也不得直接暂停别人的窗口。
 */
public final class FlowCoordinator {
    /** 最大栈深：回合窗口 + 覆盖流程 + 响应窗。 */
    public static final int MAX_DEPTH = 3;

    private FlowCoordinator() {
    }

    /** 开窗结果。 */
    public sealed interface Opened {
        /** 已开启窗口。 */
        record Window(long windowId) implements Opened {
        }

        /** 时长为 0：未创建窗口，调用方应立即执行该窗口的自动动作。 */
        record Exhausted() implements Opened {
        }
    }

    /**
     * 关窗结果：被关窗口的恢复位置，以及因恢复时剩余为 0 而以 EXHAUSTED 关闭的父窗口（按关闭先后），
     * 调用方须依次立即执行它们的自动动作。
     */
    public record Closed(String resumeTag, List<FlowFrame> exhausted) {
        public Closed {
            exhausted = Immutable.list(exhausted);
        }
    }

    /** 嵌套规则：返回违规原因，合法时返回 null。 */
    public static String nestingError(FlowState f, FlowKind kind) {
        if (f.frames().size() >= MAX_DEPTH) {
            return "flow stack depth limit " + MAX_DEPTH;
        }
        Optional<FlowFrame> top = f.top();
        return switch (kind) {
            case TURN -> top.isEmpty() ? null : "TURN window only at the bottom";
            case RESPONSE -> top.isPresent() && top.get().kind() != FlowKind.RESPONSE ? null
                    : "RESPONSE needs a non-response parent";
            default -> top.isEmpty() || top.get().kind() == FlowKind.TURN ? null
                    : kind + " cannot nest inside " + top.get().kind();
        };
    }

    /** 开启窗口（暂停当前栈顶）。违反嵌套规则属于模块程序错误。 */
    public static <S extends DomainState> Opened open(DecisionContext<S> ctx, Function<S, FlowState> flow, FlowKind kind,
                                                      String owner, long leadMs, long durationMs, String resumeTag) {
        FlowState f = flow.apply(ctx.state());
        String error = nestingError(f, kind);
        if (error != null) {
            throw new IllegalStateException(error);
        }
        if (durationMs == 0) {
            return new Opened.Exhausted();
        }
        Optional<FlowFrame> top = f.top();
        if (top.isPresent() && !top.get().window().paused()) {
            ctx.cancel(top.get().deadlineTaskId());
            ctx.emit(new WindowPaused(top.get().windowId(), ctx.now()));
        }
        long id = f.nextWindowId();
        Window w = Window.open(id, ctx.now(), leadMs, durationMs);
        long taskId = ctx.schedule(w.deadline(), taskKind(kind), id);
        ctx.emit(new WindowOpened(new FlowFrame(id, kind, owner, w, taskId, resumeTag)));
        return new Opened.Window(id);
    }

    /** 关闭栈顶窗口并恢复父窗口；返回恢复位置与需立即自动处理的耗尽窗口。 */
    public static <S extends DomainState> Closed close(DecisionContext<S> ctx, Function<S, FlowState> flow, long windowId,
                                                       CloseReason reason) {
        FlowFrame top = flow.apply(ctx.state()).top()
                .filter(t -> t.windowId() == windowId)
                .orElseThrow(() -> new IllegalStateException("window " + windowId + " is not the top of the flow stack"));
        cancelPending(ctx, top);
        ctx.emit(new WindowClosed(windowId, reason));
        List<FlowFrame> exhausted = new ArrayList<>();
        Optional<FlowFrame> parent;
        while ((parent = flow.apply(ctx.state()).top()).isPresent() && parent.get().window().paused()) {
            FlowFrame p = parent.get();
            switch (p.window().resume(ctx.now())) {
                case Window.Resumption.Reopened r -> {
                    long taskId = ctx.schedule(r.window().deadline(), taskKind(p.kind()), p.windowId());
                    ctx.emit(new WindowResumed(p.windowId(), ctx.now(), taskId));
                    return new Closed(top.resumeTag(), exhausted);
                }
                case Window.Resumption.Exhausted x -> {
                    ctx.emit(new WindowClosed(p.windowId(), CloseReason.EXHAUSTED));
                    exhausted.add(p);
                }
            }
        }
        return new Closed(top.resumeTag(), exhausted);
    }

    /** 取消全部窗口（自顶向下，不恢复），用于对局终止等。 */
    public static <S extends DomainState> void cancelAll(DecisionContext<S> ctx, Function<S, FlowState> flow) {
        Optional<FlowFrame> top;
        while ((top = flow.apply(ctx.state()).top()).isPresent()) {
            cancelPending(ctx, top.get());
            ctx.emit(new WindowClosed(top.get().windowId(), CloseReason.CANCELLED));
        }
    }

    /** 提交排队申请（不打断任何窗口）。 */
    public static <S extends DomainState> RejectionCode request(DecisionContext<S> ctx, Function<S, FlowState> flow,
                                                                FlowKind kind, String applicant) {
        if (!kind.queued()) {
            throw new IllegalArgumentException(kind + " is not a queued flow");
        }
        FlowState f = flow.apply(ctx.state());
        if (f.queue().stream().anyMatch(r -> r.applicant().equals(applicant))) {
            return RejectionCode.ALREADY_REQUESTED;
        }
        ctx.emit(new FlowRequested(new FlowRequest(f.nextRequestId(), kind, applicant, ctx.now())));
        return null;
    }

    /** 是否为安全点：没有任何窗口。 */
    public static boolean atSafePoint(FlowState f) {
        return f.frames().isEmpty();
    }

    /** 进入一个新的安全点（编号递增）；之后本安全点最多启动一个排队流程。 */
    public static <S extends DomainState> void enterSafePoint(DecisionContext<S> ctx, Function<S, FlowState> flow) {
        FlowState f = flow.apply(ctx.state());
        if (!atSafePoint(f)) {
            throw new IllegalStateException("not a safe point: " + f.frames().size() + " window(s) open");
        }
        ctx.emit(new SafePointEntered(Math.addExact(f.safePointNo(), 1)));
    }

    /** 取消全部排队申请（全局到时 DRAINING：未启动的申请全部取消并退还）。 */
    public static <S extends DomainState> void cancelQueue(DecisionContext<S> ctx, Function<S, FlowState> flow) {
        for (FlowRequest r : flow.apply(ctx.state()).queue()) {
            ctx.emit(new FlowRequestCancelled(r.requestId()));
        }
    }

    /** 在安全点取出队首申请（每个安全点最多一个）；调用方随后开启对应流程。 */
    public static <S extends DomainState> Optional<FlowRequest> dequeueAtSafePoint(DecisionContext<S> ctx,
                                                                                  Function<S, FlowState> flow) {
        FlowState f = flow.apply(ctx.state());
        if (!atSafePoint(f)) {
            throw new IllegalStateException("not a safe point: " + f.frames().size() + " window(s) open");
        }
        if (f.queue().isEmpty() || f.startedAtSafePoint() >= f.safePointNo()) {
            return Optional.empty();
        }
        FlowRequest head = f.queue().get(0);
        ctx.emit(new FlowDequeued(head.requestId()));
        return Optional.of(head);
    }

    /** 到期任务是否属于当前窗口（ID、ref 精确匹配且运行中）；否则为过期任务，忽略。 */
    public static Optional<FlowFrame> expiredFrame(FlowState f, ScheduledTask task) {
        return f.frames().stream()
                .filter(fr -> fr.windowId() == task.ref() && fr.deadlineTaskId() == task.taskId() && !fr.window().paused())
                .findFirst();
    }

    /** 携带 windowId 的命令是否作用于当前可操作窗口；返回拒绝原因或 null。 */
    public static RejectionCode checkWindow(FlowState f, long windowId, String actor, long at) {
        Optional<FlowFrame> top = f.top();
        if (top.isEmpty()) {
            return RejectionCode.NO_ACTIVE_WINDOW;
        }
        FlowFrame t = top.get();
        if (t.windowId() != windowId) {
            return RejectionCode.WINDOW_MISMATCH;
        }
        if (actor == null || !actor.equals(t.owner())) {
            return RejectionCode.NOT_YOUR_WINDOW;
        }
        return switch (t.window().status(at)) {
            case PAUSED -> RejectionCode.WINDOW_PAUSED;
            case NOT_OPEN -> RejectionCode.WINDOW_NOT_OPEN;
            case EXPIRED -> RejectionCode.WINDOW_MISMATCH;
            case OPEN -> null;
        };
    }

    /** 纯函数演化流程事件。 */
    public static FlowState evolve(FlowState f, FlowEvent event) {
        return switch (event) {
            case WindowOpened e -> {
                FlowFrame fr = e.frame();
                check(fr.windowId() == f.nextWindowId(), "window id must be allocated in order");
                check(nestingError(f, fr.kind()) == null, "illegal nesting: " + nestingError(f, fr.kind()));
                check(f.top().map(t -> t.window().paused()).orElse(true), "parent window must be paused first");
                check(!fr.window().paused() && fr.window().windowId() == fr.windowId(), "new window must be running");
                yield f.withFrames(Immutable.append(f.frames(), fr)).withNextWindowId(Math.addExact(fr.windowId(), 1));
            }
            case WindowPaused e -> {
                FlowFrame t = top(f, e.windowId());
                yield replaceTop(f, new FlowFrame(t.windowId(), t.kind(), t.owner(), t.window().pause(e.at()), 0, t.resumeTag()));
            }
            case WindowResumed e -> {
                FlowFrame t = top(f, e.windowId());
                if (!(t.window().resume(e.at()) instanceof Window.Resumption.Reopened r)) {
                    throw new IllegalStateException("exhausted window cannot be resumed");
                }
                yield replaceTop(f, new FlowFrame(t.windowId(), t.kind(), t.owner(), r.window(), e.deadlineTaskId(), t.resumeTag()));
            }
            case WindowClosed e -> {
                top(f, e.windowId());
                yield f.withFrames(f.frames().subList(0, f.frames().size() - 1));
            }
            case FlowRequested e -> {
                FlowRequest r = e.request();
                check(r.requestId() == f.nextRequestId() && r.kind().queued(), "request id must be allocated in order");
                check(f.queue().stream().noneMatch(q -> q.applicant().equals(r.applicant())), "one request per applicant");
                yield f.withQueue(Immutable.append(f.queue(), r)).withNextRequestId(Math.addExact(r.requestId(), 1));
            }
            case SafePointEntered e -> {
                check(e.safePointNo() == f.safePointNo() + 1 && f.frames().isEmpty(), "safe points are numbered in order");
                yield f.withSafePoint(e.safePointNo(), f.startedAtSafePoint());
            }
            case FlowRequestCancelled e -> {
                check(f.queue().stream().anyMatch(q -> q.requestId() == e.requestId()), "no such request");
                yield f.withQueue(f.queue().stream().filter(q -> q.requestId() != e.requestId()).toList());
            }
            case FlowDequeued e -> {
                check(f.frames().isEmpty() && !f.queue().isEmpty() && f.queue().get(0).requestId() == e.requestId(),
                        "only the queue head can start, and only at a safe point");
                check(f.safePointNo() > f.startedAtSafePoint(), "at most one flow may start per safe point");
                yield f.withQueue(f.queue().subList(1, f.queue().size())).withSafePoint(f.safePointNo(), f.safePointNo());
            }
        };
    }

    /**
     * 校验流程状态，并返回本流程认领的任务引用（供跨模块的反向孤儿检查）。
     * 检查：ID 范围与唯一、嵌套合法、仅栈顶运行、每个窗口与任务双向一致（{@link TaskLinks}）、队列 FIFO 与唯一申请者。
     */
    public static List<TaskClaim> validate(EngineState engine, FlowState f) {
        expect(f != null && f.frames() != null && f.queue() != null, "flow fields missing");
        expect(f.nextWindowId() >= 1 && f.nextWindowId() < Long.MAX_VALUE
                && f.nextRequestId() >= 1 && f.nextRequestId() < Long.MAX_VALUE, "flow counters out of range");
        expect(f.safePointNo() >= 0 && f.startedAtSafePoint() >= 0 && f.startedAtSafePoint() <= f.safePointNo(),
                "safe point markers out of range");
        List<TaskClaim> claims = new ArrayList<>();
        FlowState prefix = FlowState.initial(f.nextWindowId());
        TreeSet<Long> ids = new TreeSet<>();
        for (int i = 0; i < f.frames().size(); i++) {
            FlowFrame fr = f.frames().get(i);
            expect(fr != null && fr.kind() != null && fr.window() != null, "flow frame missing fields");
            expect(fr.windowId() >= 1 && fr.windowId() < f.nextWindowId() && ids.add(fr.windowId())
                    && fr.window().windowId() == fr.windowId(), "window id " + fr.windowId() + " invalid");
            expect(nestingError(prefix, fr.kind()) == null, "illegal nesting at depth " + i + ": " + fr.kind());
            boolean isTop = i == f.frames().size() - 1;
            expect(isTop || fr.window().paused(), "only the top window may run");
            TaskLinks.requireWindowTask(engine, fr.window(), fr.deadlineTaskId(), taskKind(fr.kind()));
            if (!fr.window().paused()) {
                claims.add(new TaskClaim(taskKind(fr.kind()), fr.windowId()));
            }
            prefix = prefix.withFrames(Immutable.append(prefix.frames(), fr));
        }
        TreeSet<String> applicants = new TreeSet<>();
        long prev = 0;
        for (FlowRequest r : f.queue()) {
            expect(r != null && r.kind() != null && r.kind().queued() && r.applicant() != null, "invalid request");
            expect(r.requestId() > prev && r.requestId() < f.nextRequestId(), "requests must be FIFO with allocated ids");
            expect(applicants.add(r.applicant()), "one pending request per applicant");
            prev = r.requestId();
        }
        return claims;
    }

    /** 模块对一个定时任务的认领（kind + ref）。 */
    public record TaskClaim(TaskKind kind, long ref) {
    }

    /** 窗口类别对应的任务类别（同刻优先级见 {@link TaskKind}）。 */
    public static TaskKind taskKind(FlowKind kind) {
        return kind == FlowKind.TURN ? TaskKind.TURN_WINDOW : TaskKind.FLOW;
    }

    /** 取消窗口仍挂起的截止任务（到期处理时任务已由 TaskFired 出队，无需再取消）。 */
    private static <S extends DomainState> void cancelPending(DecisionContext<S> ctx, FlowFrame frame) {
        if (!frame.window().paused() && ctx.tasks().stream().anyMatch(t -> t.taskId() == frame.deadlineTaskId())) {
            ctx.cancel(frame.deadlineTaskId());
        }
    }

    private static FlowFrame top(FlowState f, long windowId) {
        return f.top().filter(t -> t.windowId() == windowId)
                .orElseThrow(() -> new IllegalStateException("window " + windowId + " is not the top of the flow stack"));
    }

    private static FlowState replaceTop(FlowState f, FlowFrame frame) {
        List<FlowFrame> frames = new ArrayList<>(f.frames());
        frames.set(frames.size() - 1, frame);
        return f.withFrames(frames);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new StateValidationException(message);
        }
    }
}
