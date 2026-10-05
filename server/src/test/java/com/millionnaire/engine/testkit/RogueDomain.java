package com.millionnaire.engine.testkit;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.engine.DecisionContext;
import com.millionnaire.engine.core.engine.Domain;
import com.millionnaire.engine.core.engine.Draws;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.serialize.TypeRegistry;
import com.millionnaire.engine.time.ScheduledTask;

/** 故意违反内核纪律的测试领域：其余行为委托给 {@link DemoDomain}，Peek 命令触发指定的违规。 */
public final class RogueDomain implements Domain<DemoState> {

    /** 违规方式。 */
    public enum Mode {
        /** 先抽随机，再返回普通拒绝。 */
        DRAW_THEN_REJECT,
        /** 抽随机后接受命令，但没有领域事件消费该结果。 */
        DRAW_WITHOUT_CONSUMING,
        /** 发出一个演化时必然断言失败的领域事件。 */
        INCONSISTENT_EVENT,
        /** 发出能正常演化、但使状态违反校验的事件（空白玩家入座），由提交前出口校验拦截。 */
        INVALID_RESULT,
        /** 初始状态即不合法（有房主却没有玩家）。 */
        BAD_INITIAL
    }

    private final Mode mode;

    public RogueDomain(Mode mode) {
        this.mode = mode;
    }

    @Override
    public String id() {
        return DemoDomain.ID;
    }

    @Override
    public Class<DemoState> stateType() {
        return DemoState.class;
    }

    @Override
    public TypeRegistry types() {
        return DemoDomain.INSTANCE.types();
    }

    @Override
    public DemoState initialState(RuleConfig config) {
        if (mode == Mode.BAD_INITIAL) {
            return new DemoState("ghost", java.util.List.of(), null, 1, java.util.List.of());
        }
        return DemoDomain.INSTANCE.initialState(config);
    }

    @Override
    public RejectionCode decide(DecisionContext<DemoState> ctx, Command command) {
        if (!(command instanceof DemoCommand.Peek p)) {
            return DemoDomain.INSTANCE.decide(ctx, command);
        }
        switch (mode) {
            case DRAW_THEN_REJECT -> {
                ctx.draw(DrawPoint.MOVE_DIE, 6);
                return RejectionCode.NOT_MEMBER;
            }
            case DRAW_WITHOUT_CONSUMING -> {
                ctx.draw(DrawPoint.MOVE_DIE, 6);
                ctx.emit(new DemoEvent.Peeked(p.actor(), ctx.state().rolls().size()));
                return null;
            }
            case INVALID_RESULT -> {
                ctx.emit(new DemoEvent.Sat(" "));
                return null;
            }
            default -> {
                ctx.emit(new DemoEvent.Stood("nobody-seated"));
                return null;
            }
        }
    }

    @Override
    public void onTask(DecisionContext<DemoState> ctx, ScheduledTask task) {
        DemoDomain.INSTANCE.onTask(ctx, task);
    }

    @Override
    public DemoState evolve(DemoState state, Event event, Draws draws, RuleConfig rules) {
        return DemoDomain.INSTANCE.evolve(state, event, draws, rules);
    }

    @Override
    public void validate(EngineState engine, DemoState state, RuleConfig config, boolean full) {
        DemoDomain.INSTANCE.validate(engine, state, config, full);
    }
}
