package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Command;
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

/**
 * 唯一的生产领域：房间会话 = 大厅 + 可选对局。大厅 → 开局 → 回房在同一状态链上进行，
 * 接收序号、随机状态、定时任务与持久化链都不中断（不热切换领域、不重新创世）。
 * 规则按模块分派：{@link LobbyModule}（大厅）、{@link GameModule}（对局，M1 骨架）。
 */
public final class SessionDomain implements Domain<SessionState>, View<SessionView> {
    public static final String ID = "session-v1";
    public static final SessionDomain INSTANCE = new SessionDomain();

    private static final TypeRegistry TYPES = TypeRegistry.builder()
            .add(Command.class, RoomCommand.class, SessionCommand.class)
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
        return new SessionState(RoomState.initial(config), null, 0);
    }

    @Override
    public RejectionCode decide(DecisionContext<SessionState> ctx, Command command) {
        return switch (command) {
            case RoomCommand c -> LobbyModule.decide(ctx, c);
            case SessionCommand c -> GameModule.decide(ctx, c);
            default -> RejectionCode.UNSUPPORTED_COMMAND;
        };
    }

    @Override
    public void onTask(DecisionContext<SessionState> ctx, ScheduledTask task) {
        // M0/M1 骨架尚无会话任务；validate 保证不存在挂起任务
    }

    @Override
    public SessionState evolve(SessionState state, Event event, Draws draws) {
        return switch (event) {
            case RoomEvent e -> state.withLobby(LobbyModule.evolve(state.lobby(), e));
            case GameEvent e -> GameModule.evolve(state, e);
            default -> throw new IllegalStateException("not a session event: " + event);
        };
    }

    @Override
    public void validate(EngineState engine, SessionState state, RuleConfig config) {
        LobbyModule.expect(state != null, "session state missing");
        LobbyModule.validate(state.lobby(), config);
        GameModule.validate(state, config);
        LobbyModule.expect(engine.timers().isEmpty(), "session skeleton never schedules tasks");
    }

    @Override
    public SessionView project(EngineState state, String viewerId) {
        SessionState s = (SessionState) state.domain();
        RoomState l = s.lobby();
        return new SessionView(state.roomId(), l.status(), l.hostId(), l.members(), l.settings(), s.game(), s.gamesPlayed());
    }
}
