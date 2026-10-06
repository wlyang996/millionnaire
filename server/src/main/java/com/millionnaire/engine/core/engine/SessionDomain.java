package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.event.RoomEvent;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.SessionView;
import com.millionnaire.engine.core.state.View;
import com.millionnaire.engine.serialize.TypeRegistry;
import com.millionnaire.engine.time.ScheduledTask;
import java.util.List;

/**
 * 唯一的生产领域：房间会话 = 大厅 + 可选对局。大厅 → 开局 → 回房在同一状态链上进行，
 * 接收序号、随机状态、定时任务与持久化链都不中断（不热切换领域、不重新创世）。
 * 规则按模块分派：{@link LobbyModule}（大厅）、{@link GameModule}（对局，M1 骨架）。
 */
public final class SessionDomain implements Domain<SessionState>, View<SessionView> {
    public static final String ID = "session-v1";
    public static final SessionDomain INSTANCE = new SessionDomain();

    private static final TypeRegistry TYPES = TypeRegistry.builder()
            .add(Command.class, RoomCommand.class, SessionCommand.class, GameCommand.class)
            .add(Event.class, RoomEvent.class, GameEvent.class)
            .add(DomainState.class, SessionState.class)
            .build();

    private SessionDomain() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Class<SessionState> stateType() {
        return SessionState.class;
    }

    @Override
    public TypeRegistry types() {
        return TYPES;
    }

    @Override
    public SessionState initialState(RuleConfig config) {
        return new SessionState(RoomState.initial(config), null, 0, 1, null);
    }

    @Override
    public RejectionCode decide(DecisionContext<SessionState> ctx, Command command) {
        return switch (command) {
            case RoomCommand c -> LobbyModule.decide(ctx, c);
            case SessionCommand c -> GameModule.decide(ctx, c);
            case GameCommand c -> TurnModule.decide(ctx, c);
            default -> RejectionCode.UNSUPPORTED_COMMAND;
        };
    }

    @Override
    public void onTask(DecisionContext<SessionState> ctx, ScheduledTask task) {
        // 按任务所属模块分派：覆盖窗口到期 → 覆盖流程模块；全局到时、自动动作、回合窗口到期 → 回合模块
        if (task.kind() == com.millionnaire.engine.time.TaskKind.FLOW) {
            OverlayModule.onTask(ctx, task);
        } else {
            TurnModule.onTask(ctx, task);
        }
    }

    /** 步边界：对局中回合的步内衔接记录必须为空（C4）。 */
    @Override
    public void checkBoundary(SessionState state) {
        if (state.inGame() && !state.game().turn().track().equals(com.millionnaire.engine.core.state.TurnTrack.NONE)) {
            throw new IllegalStateException("turn track not empty at a step boundary: " + state.game().turn().track());
        }
    }

    @Override
    public SessionState evolve(SessionState state, Event event, Draws draws, RuleConfig rules) {
        return switch (event) {
            case RoomEvent e -> state.withLobby(LobbyModule.evolve(state.lobby(), e));
            case GameEvent e -> GameModule.evolve(state, e, draws, rules);
            default -> throw new IllegalStateException("not a session event: " + event);
        };
    }

    /**
     * 固定顺序的校验清单（内核部分已由 Engine 先行完成）：大厅 → 对局（局号/时间、设置、玩家、棋盘、回合、流程）
     * → 跨模块（全部定时任务都必须被某个模块认领，且认领与任务双向一致）。只在步的入口与出口执行，不在每次 emit 后执行。
     */
    @Override
    public void validate(EngineState engine, SessionState state, RuleConfig config, boolean full) {
        LobbyModule.expect(state != null, "session state missing");
        LobbyModule.validate(state.lobby(), config);
        List<FlowCoordinator.TaskClaim> claims = GameModule.validate(engine, state, config, full);
        for (ScheduledTask t : engine.timers().tasks()) {
            LobbyModule.expect(claims.contains(new FlowCoordinator.TaskClaim(t.kind(), t.ref())),
                    "task " + t.taskId() + " (" + t.kind() + " ref " + t.ref() + ") is not claimed by any module");
        }
        LobbyModule.expect(claims.size() == engine.timers().tasks().size(), "every claim must have exactly one task");
    }

    @Override
    public SessionView project(EngineState state, String viewerId) {
        SessionState s = (SessionState) state.domain();
        RoomState l = s.lobby();
        return new SessionView(state.roomId(), l.status(), l.hostId(), l.members(), l.settings(),
                s.game() == null ? null : GameModule.view(s.game(), viewerId), s.gamesPlayed(), s.lastResult());
    }
}
