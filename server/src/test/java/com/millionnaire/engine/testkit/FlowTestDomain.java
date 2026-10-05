package com.millionnaire.engine.testkit;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.engine.DecisionContext;
import com.millionnaire.engine.core.engine.Domain;
import com.millionnaire.engine.core.engine.Draws;
import com.millionnaire.engine.core.engine.FlowCoordinator;
import com.millionnaire.engine.core.engine.StateValidationException;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.FlowState;
import com.millionnaire.engine.serialize.Immutable;
import com.millionnaire.engine.serialize.TypeRegistry;
import com.millionnaire.engine.testkit.FlowTestCommand.Act;
import com.millionnaire.engine.testkit.FlowTestCommand.Apply;
import com.millionnaire.engine.testkit.FlowTestCommand.GlobalEndAt;
import com.millionnaire.engine.testkit.FlowTestCommand.OpenKind;
import com.millionnaire.engine.testkit.FlowTestCommand.OpenResponse;
import com.millionnaire.engine.testkit.FlowTestCommand.OpenTurn;
import com.millionnaire.engine.testkit.FlowTestCommand.SafePoint;
import com.millionnaire.engine.testkit.FlowTestEvent.Logged;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 两个简化假模块（测试夹具）与 {@link FlowCoordinator} 的集成：
 * 回合模块开 TURN 窗口、在合法打断点开 RESPONSE；申请模块排队拍卖、在安全点启动。
 * 窗口到期或耗尽时记录自动动作日志；全局到时只记录日志（DRAINING 留给 M1）。
 */
public final class FlowTestDomain implements Domain<FlowTestState> {
    public static final FlowTestDomain INSTANCE = new FlowTestDomain();
    public static final long AUCTION_MS = 20_000;
    private static final Function<FlowTestState, FlowState> FLOW = FlowTestState::flow;
    private static final TypeRegistry TYPES = TypeRegistry.builder()
            .add(Command.class, FlowTestCommand.class)
            .add(Event.class, GameEvent.FlowEvent.class, FlowTestEvent.class)
            .add(DomainState.class, FlowTestState.class)
            .build();

    @Override
    public String id() {
        return "flow-test";
    }

    @Override
    public Class<FlowTestState> stateType() {
        return FlowTestState.class;
    }

    @Override
    public TypeRegistry types() {
        return TYPES;
    }

    @Override
    public FlowTestState initialState(RuleConfig config) {
        return new FlowTestState(FlowState.initial(1), List.of());
    }

    @Override
    public RejectionCode decide(DecisionContext<FlowTestState> ctx, Command command) {
        if (!(command instanceof FlowTestCommand c)) {
            return RejectionCode.UNSUPPORTED_COMMAND;
        }
        FlowState f = ctx.state().flow();
        return switch (c) {
            case OpenTurn x -> open(ctx, FlowKind.TURN, x.actor(), x.leadMs(), x.durationMs());
            case OpenResponse x -> open(ctx, FlowKind.RESPONSE, x.actor(), 0, x.durationMs());
            case OpenKind x -> open(ctx, x.kind(), x.actor(), 0, x.durationMs());
            case Act x -> {
                RejectionCode why = FlowCoordinator.checkWindow(f, x.windowId(), x.actor(), ctx.now());
                if (why != null) {
                    yield why;
                }
                close(ctx, x.windowId(), CloseReason.ACTED);
                yield null;
            }
            case Apply x -> FlowCoordinator.request(ctx, FLOW, FlowKind.AUCTION, x.actor());
            case SafePoint x -> {
                if (!FlowCoordinator.atSafePoint(f)) {
                    yield RejectionCode.NOT_ALLOWED;
                }
                FlowCoordinator.enterSafePoint(ctx, FLOW);
                Optional<FlowRequest> next = FlowCoordinator.dequeueAtSafePoint(ctx, FLOW);
                // 同一安全点再取一次必须为空（每个安全点最多启动一个）
                if (next.isPresent() && FlowCoordinator.dequeueAtSafePoint(ctx, FLOW).isPresent()) {
                    throw new IllegalStateException("a second flow started at the same safe point");
                }
                next.ifPresent(r -> FlowCoordinator.open(ctx, FLOW, r.kind(), r.applicant(), 0, AUCTION_MS, "after-auction"));
                yield null;
            }
            case GlobalEndAt x -> {
                if (x.at() <= ctx.now()) {
                    yield RejectionCode.INVALID_ARGUMENT;
                }
                ctx.schedule(x.at(), TaskKind.GLOBAL_END, 0);
                yield null;
            }
        };
    }

    private static RejectionCode open(DecisionContext<FlowTestState> ctx, FlowKind kind, String owner, long lead, long duration) {
        if (FlowCoordinator.nestingError(ctx.state().flow(), kind) != null) {
            return RejectionCode.NOT_ALLOWED;
        }
        if (FlowCoordinator.open(ctx, FLOW, kind, owner, lead, duration, kind.name()) instanceof FlowCoordinator.Opened.Exhausted) {
            ctx.emit(new Logged("auto-exhausted:" + kind + ":" + owner));
        }
        return null;
    }

    private static void close(DecisionContext<FlowTestState> ctx, long windowId, CloseReason reason) {
        FlowCoordinator.Closed closed = FlowCoordinator.close(ctx, FLOW, windowId, reason);
        for (FlowFrame f : closed.exhausted()) {
            ctx.emit(new Logged("auto-exhausted:" + f.windowId()));
        }
    }

    @Override
    public void onTask(DecisionContext<FlowTestState> ctx, ScheduledTask task) {
        if (task.kind() == TaskKind.GLOBAL_END) {
            ctx.emit(new Logged("global-end@" + task.dueAt()));
            return;
        }
        FlowCoordinator.expiredFrame(ctx.state().flow(), task).ifPresent(frame -> {
            ctx.emit(new Logged("auto:" + frame.windowId()));
            close(ctx, frame.windowId(), CloseReason.EXPIRED);
        });
    }

    @Override
    public FlowTestState evolve(FlowTestState state, Event event, Draws draws, RuleConfig rules) {
        return switch (event) {
            case GameEvent.FlowEvent e -> new FlowTestState(FlowCoordinator.evolve(state.flow(), e), state.log());
            case Logged e -> new FlowTestState(state.flow(), Immutable.append(state.log(), e.line()));
            default -> throw new IllegalStateException("unexpected event " + event);
        };
    }

    @Override
    public void validate(EngineState engine, FlowTestState state, RuleConfig config, boolean full) {
        List<FlowCoordinator.TaskClaim> claims = FlowCoordinator.validate(engine, state.flow());
        for (ScheduledTask t : engine.timers().tasks()) {
            if (t.kind() == TaskKind.GLOBAL_END) {
                continue;
            }
            if (!claims.contains(new FlowCoordinator.TaskClaim(t.kind(), t.ref()))) {
                throw new StateValidationException("unclaimed task " + t);
            }
        }
    }
}
