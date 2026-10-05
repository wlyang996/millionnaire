package com.millionnaire.engine.testkit;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.engine.DecisionContext;
import com.millionnaire.engine.core.engine.Domain;
import com.millionnaire.engine.core.engine.Draws;
import com.millionnaire.engine.core.engine.StateValidationException;
import com.millionnaire.engine.core.engine.TaskLinks;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.serialize.Immutable;
import com.millionnaire.engine.serialize.TypeRegistry;
import com.millionnaire.engine.testkit.DemoCommand.OpenRound;
import com.millionnaire.engine.testkit.DemoCommand.PauseRound;
import com.millionnaire.engine.testkit.DemoCommand.Peek;
import com.millionnaire.engine.testkit.DemoCommand.ResumeRound;
import com.millionnaire.engine.testkit.DemoCommand.Roll;
import com.millionnaire.engine.testkit.DemoCommand.ScheduleStop;
import com.millionnaire.engine.testkit.DemoCommand.Sit;
import com.millionnaire.engine.testkit.DemoCommand.Stand;
import com.millionnaire.engine.testkit.DemoEvent.AbortReason;
import com.millionnaire.engine.testkit.DemoEvent.DieRolled;
import com.millionnaire.engine.testkit.DemoEvent.Peeked;
import com.millionnaire.engine.testkit.DemoEvent.RoundAborted;
import com.millionnaire.engine.testkit.DemoEvent.RoundOpened;
import com.millionnaire.engine.testkit.DemoEvent.RoundPaused;
import com.millionnaire.engine.testkit.DemoEvent.RoundResumed;
import com.millionnaire.engine.testkit.DemoEvent.Sat;
import com.millionnaire.engine.testkit.DemoEvent.Stood;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import com.millionnaire.engine.time.Window;
import java.util.List;
import java.util.TreeSet;

/**
 * 演示领域（测试夹具，非产品规则）：用来走通内核的计时窗口、缓冲、暂停恢复、超时代掷、
 * 同刻优先级、随机消费与私有事件。
 */
public final class DemoDomain implements Domain<DemoState> {
    public static final String ID = "demo-test";
    public static final long ROUND_MS = 15_000;
    public static final long MAX_LEAD_MS = 10_000;
    private static final int FACES = 6;

    public static final DemoDomain INSTANCE = new DemoDomain();

    private static final TypeRegistry TYPES = TypeRegistry.builder()
            .add(Command.class, DemoCommand.class)
            .add(Event.class, DemoEvent.class)
            .add(DomainState.class, DemoState.class)
            .build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Class<DemoState> stateType() {
        return DemoState.class;
    }

    @Override
    public TypeRegistry types() {
        return TYPES;
    }

    @Override
    public DemoState initialState(RuleConfig config) {
        return new DemoState(null, List.of(), null, 1, List.of());
    }

    @Override
    public RejectionCode decide(DecisionContext<DemoState> ctx, Command command) {
        if (!(command instanceof DemoCommand dc)) {
            return RejectionCode.UNSUPPORTED_COMMAND;
        }
        DemoState s = ctx.state();
        return switch (dc) {
            case Sit c -> {
                if (c.playerId() == null || c.playerId().isBlank()) {
                    yield RejectionCode.INVALID_ARGUMENT;
                }
                if (s.players().contains(c.playerId())) {
                    yield RejectionCode.ALREADY_MEMBER;
                }
                ctx.emit(new Sat(c.playerId()));
                yield null;
            }
            case Stand c -> {
                if (!s.players().contains(c.playerId())) {
                    yield RejectionCode.NOT_MEMBER;
                }
                if (s.round() != null && s.round().playerId().equals(c.playerId())) {
                    abort(ctx, s.round(), AbortReason.PLAYER_LEFT);
                }
                ctx.emit(new Stood(c.playerId()));
                yield null;
            }
            case OpenRound c -> {
                if (!isHost(s, c.actor())) {
                    yield RejectionCode.NOT_HOST;
                }
                if (s.round() != null) {
                    yield RejectionCode.WINDOW_ALREADY_ACTIVE;
                }
                if (!s.players().contains(c.playerId())) {
                    yield RejectionCode.NOT_MEMBER;
                }
                if (c.leadMs() < 0 || c.leadMs() > MAX_LEAD_MS) {
                    yield RejectionCode.INVALID_ARGUMENT;
                }
                Window w = Window.open(s.nextWindowId(), ctx.now(), c.leadMs(), ROUND_MS);
                long taskId = ctx.schedule(w.deadline(), TaskKind.TURN_WINDOW, w.windowId());
                ctx.emit(new RoundOpened(c.playerId(), w, taskId));
                yield null;
            }
            case PauseRound c -> {
                if (!isHost(s, c.actor())) {
                    yield RejectionCode.NOT_HOST;
                }
                if (s.round() == null) {
                    yield RejectionCode.NO_ACTIVE_WINDOW;
                }
                if (s.round().window().paused()) {
                    yield RejectionCode.WINDOW_PAUSED;
                }
                ctx.cancel(s.round().deadlineTaskId());
                ctx.emit(new RoundPaused(s.round().window().windowId(), ctx.now()));
                yield null;
            }
            case ResumeRound c -> {
                if (!isHost(s, c.actor())) {
                    yield RejectionCode.NOT_HOST;
                }
                if (s.round() == null) {
                    yield RejectionCode.NO_ACTIVE_WINDOW;
                }
                if (!s.round().window().paused()) {
                    yield RejectionCode.NOT_PAUSED;
                }
                switch (s.round().window().resume(ctx.now())) {
                    case Window.Resumption.Reopened r -> {
                        long taskId = ctx.schedule(r.window().deadline(), TaskKind.TURN_WINDOW, r.window().windowId());
                        ctx.emit(new RoundResumed(r.window().windowId(), ctx.now(), taskId));
                    }
                    // 剩余时间为零：不创建窗口，立即执行超时自动动作
                    case Window.Resumption.Exhausted x -> autoRoll(ctx, s.round());
                }
                yield null;
            }
            case Roll c -> {
                DemoRound r = s.round();
                if (r == null) {
                    yield RejectionCode.NO_ACTIVE_WINDOW;
                }
                if (r.window().windowId() != c.windowId()) {
                    yield RejectionCode.WINDOW_MISMATCH;
                }
                if (!r.playerId().equals(c.actor())) {
                    yield RejectionCode.NOT_YOUR_WINDOW;
                }
                RejectionCode closed = switch (r.window().status(ctx.now())) {
                    case PAUSED -> RejectionCode.WINDOW_PAUSED;
                    case NOT_OPEN -> RejectionCode.WINDOW_NOT_OPEN;
                    case EXPIRED -> RejectionCode.WINDOW_MISMATCH;  // 到期任务总先处理，正常不可达
                    case OPEN -> null;
                };
                if (closed != null) {
                    yield closed;
                }
                ctx.cancel(r.deadlineTaskId());
                int value = ctx.draw(DrawPoint.MOVE_DIE, FACES) + 1;
                ctx.emit(new DieRolled(c.actor(), c.windowId(), value, false));
                yield null;
            }
            case ScheduleStop c -> {
                if (!isHost(s, c.actor())) {
                    yield RejectionCode.NOT_HOST;
                }
                if (c.at() <= ctx.now()) {
                    yield RejectionCode.INVALID_ARGUMENT;
                }
                ctx.schedule(c.at(), TaskKind.GLOBAL_END, 0);
                yield null;
            }
            case Peek c -> {
                if (!s.players().contains(c.actor())) {
                    yield RejectionCode.NOT_MEMBER;
                }
                ctx.emit(new Peeked(c.actor(), s.rolls().size()));
                yield null;
            }
        };
    }

    @Override
    public void onTask(DecisionContext<DemoState> ctx, ScheduledTask task) {
        DemoRound round = ctx.state().round();
        if (task.kind() == TaskKind.TURN_WINDOW && round != null && round.deadlineTaskId() == task.taskId()) {
            autoRoll(ctx, round);
        } else if (task.kind() == TaskKind.GLOBAL_END && round != null) {
            abort(ctx, round, AbortReason.STOPPED);
        }
    }

    @Override
    public DemoState evolve(DemoState s, Event event, Draws draws) {
        if (!(event instanceof DemoEvent e)) {
            throw new IllegalStateException("not a demo event: " + event);
        }
        return switch (e) {
            case Sat x -> {
                check(!s.players().contains(x.playerId()), "already seated");
                yield s.with(Immutable.append(s.players(), x.playerId()), s.round(), s.nextWindowId(), s.rolls());
            }
            case Stood x -> {
                check(s.players().contains(x.playerId()), "not seated");
                check(s.round() == null || !s.round().playerId().equals(x.playerId()), "round player must be aborted first");
                yield s.with(s.players().stream().filter(p -> !p.equals(x.playerId())).toList(), s.round(),
                        s.nextWindowId(), s.rolls());
            }
            case RoundOpened x -> {
                check(s.round() == null && x.window().windowId() == s.nextWindowId() && !x.window().paused(),
                        "round id mismatch");
                check(s.players().contains(x.playerId()), "round player must be seated");
                yield s.with(s.players(), new DemoRound(x.playerId(), x.window(), x.deadlineTaskId()),
                        x.window().windowId() + 1, s.rolls());
            }
            case RoundPaused x -> {
                DemoRound r = round(s, x.windowId());
                yield s.with(s.players(), new DemoRound(r.playerId(), r.window().pause(x.at()), 0), s.nextWindowId(), s.rolls());
            }
            case RoundResumed x -> {
                DemoRound r = round(s, x.windowId());
                if (!(r.window().resume(x.at()) instanceof Window.Resumption.Reopened reopened)) {
                    throw new IllegalStateException("exhausted window cannot be resumed as reopened");
                }
                yield s.with(s.players(), new DemoRound(r.playerId(), reopened.window(), x.deadlineTaskId()),
                        s.nextWindowId(), s.rolls());
            }
            case DieRolled x -> {
                DemoRound r = round(s, x.windowId());
                check(r.playerId().equals(x.playerId()), "roll by wrong player");
                int drawn = draws.take(DrawPoint.MOVE_DIE, FACES) + 1;
                check(x.value() == drawn, "rolled value " + x.value() + " does not match draw " + drawn);
                yield s.with(s.players(), null, s.nextWindowId(), Immutable.append(s.rolls(), x.value()));
            }
            case RoundAborted x -> {
                round(s, x.windowId());
                yield s.with(s.players(), null, s.nextWindowId(), s.rolls());
            }
            case Peeked x -> {
                check(s.players().contains(x.recipient()) && x.rollsSeen() == s.rolls().size(), "peek mismatch");
                yield s;
            }
        };
    }

    @Override
    public void validate(EngineState engine, DemoState s, RuleConfig config) {
        expect(s.players() != null && s.rolls() != null, "fields missing");
        expect(s.players().stream().noneMatch(p -> p == null || p.isBlank())
                && new TreeSet<>(s.players()).size() == s.players().size(), "players must be unique and non-blank");
        expect(s.hostId() == null ? s.players().isEmpty() : s.hostId().equals(s.players().get(0)), "host must be first player");
        expect(s.rolls().stream().allMatch(v -> v >= 1 && v <= FACES), "roll out of range");
        expect(s.nextWindowId() >= 1, "nextWindowId must be positive");
        DemoRound r = s.round();
        if (r != null) {
            expect(s.players().contains(r.playerId()), "round player must be seated");
            expect(r.window().windowId() < s.nextWindowId(), "window id not yet allocated");
            // 双向核对：任务的 ID、kind、ref、dueAt 与窗口一致，且同 ref 的窗口任务只有这一个
            TaskLinks.requireWindowTask(engine, r.window(), r.deadlineTaskId(), TaskKind.TURN_WINDOW);
        }
        TaskLinks.requireNoOrphans(engine, TaskKind.TURN_WINDOW,
                r == null || r.window().paused() ? List.of() : List.of(r.window().windowId()));
        for (ScheduledTask t : engine.timers().tasks()) {
            expect(t.kind() == TaskKind.TURN_WINDOW || t.kind() == TaskKind.GLOBAL_END && t.ref() == 0,
                    "unexpected task " + t);
        }
    }

    // ------------------------------------------------------------ helpers

    private static void autoRoll(DecisionContext<DemoState> ctx, DemoRound round) {
        int value = ctx.draw(DrawPoint.MOVE_DIE, FACES) + 1;
        ctx.emit(new DieRolled(round.playerId(), round.window().windowId(), value, true));
    }

    private static void abort(DecisionContext<DemoState> ctx, DemoRound round, AbortReason reason) {
        if (!round.window().paused()) {
            ctx.cancel(round.deadlineTaskId());
        }
        ctx.emit(new RoundAborted(round.window().windowId(), reason));
    }

    private static boolean isHost(DemoState s, String actor) {
        return s.hostId() != null && s.hostId().equals(actor);
    }

    private static DemoRound round(DemoState s, long windowId) {
        check(s.round() != null && s.round().window().windowId() == windowId, "no round " + windowId);
        return s.round();
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
