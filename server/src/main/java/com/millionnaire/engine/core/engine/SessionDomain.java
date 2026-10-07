package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.Input;
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
        RejectionCode control = BusinessCommands.beforeCommand(ctx, command);
        if (control != null) { return control; }
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

    @Override
    public SessionState acceptSystemInput(SessionState state, Input input) {
        if (input.command() instanceof SessionCommand.EndGame c) {
            return state.withAbortSource(new SessionState.AbortSource(input.seq(), c));
        }
        var expectedControl = BusinessCommands.expected(state, input.command());
        if (expectedControl != null) {
            state = state.withControlSource(expectedControl);
        }
        if (state.inGame() && state.game().debt() != null) {
            var g = state.game();
            var d = g.debt();
            boolean confirmed = input.command() instanceof GameCommand.DeclareBankruptcy c
                    && d.path() == com.millionnaire.engine.core.state.DebtPath.MANUAL
                    && FlowCoordinator.checkWindow(g.flow(), c.windowId(), c.actor(), input.serverTime()) == null
                    && d.debtor().equals(c.actor());
            confirmed |= input.command() instanceof GameCommand.Surrender c
                    && c.gameNo() == g.gameNo() && d.debtor().equals(c.actor());
            if (confirmed) {
                return state.withGame(g.withTurn(g.turn().withTrack(g.turn().track().bankruptcy(d.debtId()))));
            }
        }
        return state;
    }

    @Override
    public boolean recordsInputSource(Command command) {
        return Domain.super.recordsInputSource(command) || command instanceof GameCommand.DeclareBankruptcy
                || command instanceof GameCommand.Surrender || command instanceof GameCommand.ResumeControl
                || BusinessCommands.kind(command) == BusinessCommands.Kind.BUSINESS;
    }

    @Override
    public SessionState acceptTask(SessionState state, ScheduledTask task) {
        if (state.inGame() && state.game().debt() != null) {
            var g = state.game();
            var d = g.debt();
            var frame = FlowCoordinator.expiredFrame(g.flow(), task).orElse(null);
            if (d.path() == com.millionnaire.engine.core.state.DebtPath.MANUAL && d.segment() == 2
                    && frame != null && frame.kind() == com.millionnaire.engine.core.state.FlowKind.DEBT
                    && frame.windowId() == d.windowId()) {
                return state.withGame(g.withTurn(g.turn().withTrack(g.turn().track().bankruptcy(d.debtId()))));
            }
        }
        return state;
    }

    /** 步边界：对局中回合的步内衔接记录必须为空（C4）。 */
    @Override
    public void checkBoundary(SessionState state) {
        if (state.controlSource() != null) {
            throw new IllegalStateException("unconsumed control source at a step boundary");
        }
        if (state.abortSource() != null) {
            throw new IllegalStateException("unconsumed EndGame source at a step boundary");
        }
        if (state.inGame() && state.game().flow().pendingStart() != null) {
            throw new IllegalStateException("unconsumed dequeued request at a step boundary");
        }
        if (state.inGame() && !state.game().turn().track().equals(com.millionnaire.engine.core.state.TurnTrack.NONE)) {
            throw new IllegalStateException("turn track not empty at a step boundary: " + state.game().turn().track());
        }
    }

    @Override
    public SessionState evolve(SessionState state, Event event, Draws draws, RuleConfig rules) {
        return switch (event) {
            case RoomEvent e -> state.withLobby(LobbyModule.evolve(state.lobby(), e));
            case GameEvent.ControlChanged e -> {
                LobbyModule.check(e.equals(state.controlSource()), "control change requires its matching accepted input source");
                yield GameModule.evolve(state.withControlSource(null), e, draws, rules);
            }
            case GameEvent e -> GameModule.evolve(state, e, draws, rules);
            default -> throw new IllegalStateException("not a session event: " + event);
        };
    }

    @Override
    public void checkEvent(EngineState engine, Event event, RuleConfig rules) {
        SessionState current = (SessionState) engine.domain();
        if (event instanceof GameEvent.MovementEffectCommitted e) {
            LobbyModule.check(e.source() != null && e.source().at() == engine.now(), "internal movement source time mismatch");
        }
        if (current.inGame()) {
            var track = current.game().turn().track();
            if (track.roadblockDue() != null && !(event instanceof GameEvent.RoadblockTriggered)) {
                throw new IllegalStateException("stopped movement must immediately trigger its roadblock");
            }
            var source = track.movementEffect();
            if (source != null && source.kind() == com.millionnaire.engine.core.state.MovementEffect.Kind.ROADBLOCK
                    && !(event instanceof GameEvent.RoadblockPlaced)) {
                throw new IllegalStateException("placement must immediately consume its movement source");
            }
            if (source != null && source.kind() == com.millionnaire.engine.core.state.MovementEffect.Kind.TARGETED) {
                boolean closing = event instanceof GameEvent.AutoActDisarmed || event instanceof com.millionnaire.engine.core.event.KernelEvent.TaskCancelled
                        || event instanceof GameEvent.WindowClosed;
                if (current.game().turn().chain() != null ? !(event instanceof GameEvent.PlayerMoved)
                        : !closing && !(event instanceof GameEvent.MoveChainStarted)) {
                    throw new IllegalStateException("targeted source must close its window and start its movement");
                }
            }
        }
        if (current.controlSource() != null && !(event instanceof GameEvent.ControlChanged)) {
            throw new IllegalStateException("control change must immediately follow its accepted input source");
        }
        if (current.inGame() && current.game().turn().landing() != null) {
            var result = current.game().turn().landing().event();
            if (result != null && result.countPending() && !(event instanceof GameEvent.EventHandCount)) {
                throw new IllegalStateException("private card result must immediately report its public hand count");
            }
        }
        if (current.inGame() && current.game().turn().track().safePointPhase() == 1
                && !(event instanceof GameEvent.SafePointEntered)) {
            throw new IllegalStateException("SafePointEntered must immediately follow TurnStarted");
        }
        if (event instanceof GameEvent.WindowOpened e) {
            var f = e.frame();
            TaskLinks.requireWindowTask(engine, f.window(), f.deadlineTaskId(), FlowCoordinator.taskKind(f.kind()));
        } else if (event instanceof GameEvent.WindowResumed e) {
            SessionState s = (SessionState) engine.domain();
            var f = s.game().flow().frame(e.windowId()).orElseThrow();
            if (!(f.window().resume(e.at()) instanceof com.millionnaire.engine.time.Window.Resumption.Reopened r)) {
                throw new IllegalStateException("exhausted window cannot resume");
            }
            TaskLinks.requireWindowTask(engine, r.window(), e.deadlineTaskId(), FlowCoordinator.taskKind(f.kind()));
        }
    }

    /**
     * 固定顺序的校验清单（内核部分已由 Engine 先行完成）：大厅 → 对局（局号/时间、设置、玩家、棋盘、回合、流程）
     * → 跨模块（全部定时任务都必须被某个模块认领，且认领与任务双向一致）。只在步的入口与出口执行，不在每次 emit 后执行。
     */
    @Override
    public void validate(EngineState engine, SessionState state, RuleConfig config, boolean full) {
        LobbyModule.expect(state != null, "session state missing");
        // 快照/重建结果只允许步边界状态，不能恢复一份可供后续冒用的管理中止凭据。
        LobbyModule.expect(state.controlSource() == null, "unconsumed control source at a step boundary");
        LobbyModule.expect(state.abortSource() == null, "unconsumed EndGame source at a step boundary");
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
