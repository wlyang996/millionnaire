package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.command.SessionCommand.EndGame;
import com.millionnaire.engine.core.command.SessionCommand.StartGame;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.FlowEvent;
import com.millionnaire.engine.core.event.GameEvent.GameEnded;
import com.millionnaire.engine.core.event.GameEvent.GameStarted;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.BoardState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowState;
import com.millionnaire.engine.core.state.GameClock;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.GameView;
import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.RoomStatus;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnState;
import com.millionnaire.engine.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 对局边界模块：开局（开立账本、交给回合模块定序并开始第一回合）、管理端中止、子状态构造与校验、按观察者投影。
 * 回合内规则在 {@link TurnModule}，两者都由 {@link SessionDomain} 显式分派。
 */
final class GameModule {
    /** 流程状态访问器（供 {@link FlowCoordinator} 使用）。 */
    static final Function<SessionState, FlowState> FLOW = s -> s.game().flow();

    private GameModule() {
    }

    static RejectionCode decide(DecisionContext<SessionState> ctx, SessionCommand command) {
        SessionState s = ctx.state();
        if (s.lobby().status() == RoomStatus.CLOSED) {
            return RejectionCode.ROOM_CLOSED;
        }
        return switch (command) {
            case StartGame c -> start(ctx, c);
            case EndGame c -> {
                if (!s.inGame()) {
                    yield RejectionCode.NOT_IN_GAME;
                }
                if (c.expectedGameNo() != s.game().gameNo()) {
                    yield RejectionCode.GAME_MISMATCH;
                }
                if (c.reason() == null || c.reason().isBlank()) {
                    yield RejectionCode.INVALID_ARGUMENT;
                }
                TurnModule.terminate(ctx);
                ctx.emit(new GameEnded(s.game().gameNo(), c.reason(), null));
                yield null;
            }
        };
    }

    private static RejectionCode start(DecisionContext<SessionState> ctx, StartGame c) {
        SessionState s = ctx.state();
        RoomState lobby = s.lobby();
        if (s.inGame()) {
            return RejectionCode.GAME_IN_PROGRESS;
        }
        if (!lobby.isHost(c.actor())) {
            return RejectionCode.NOT_HOST;
        }
        if (lobby.members().size() < ctx.config().room().minPlayersToStart()) {
            return RejectionCode.NOT_ENOUGH_PLAYERS;
        }
        // 容量在开局边界再核对一次：30 格最多 4 人、50 格最多 8 人
        if (lobby.members().size() > LobbyModule.board(ctx.config(), lobby.settings()).maxPlayers()) {
            return RejectionCode.CAPACITY_EXCEEDED;
        }
        if (!lobby.members().stream().allMatch(Member::ready)) {
            return RejectionCode.NOT_ALL_READY;
        }
        List<String> seats = lobby.members().stream().map(Member::playerId).toList();
        ctx.emit(new GameStarted(Math.addExact(s.gamesPlayed(), 1), seats, lobby.settings(), ctx.now()));
        TurnModule.begin(ctx);
        return null;
    }

    static SessionState evolve(SessionState s, GameEvent event, Draws draws, RuleConfig rules) {
        return switch (event) {
            case GameStarted x -> {
                LobbyModule.check(!s.inGame() && x.gameNo() == s.gamesPlayed() + 1, "game number mismatch");
                LobbyModule.check(x.seats().equals(s.lobby().members().stream().map(Member::playerId).toList()),
                        "seats must be the lobby members");
                LobbyModule.check(x.settings().equals(s.lobby().settings()), "settings must be frozen from the lobby");
                Map<String, Long> cash = new TreeMap<>();
                x.seats().forEach(id -> cash.put(id, x.settings().initialCash()));
                GameState g = new GameState(x.gameNo(), x.startedAt(), x.settings(), GamePhase.RUNNING,
                        x.seats().stream().map(PlayerState::seated).toList(), List.of(),
                        new BoardState(x.settings().boardId()), TurnState.notStarted(), FlowState.initial(s.nextWindowId()),
                        Ledger.open(cash), new GameClock(0, 0));
                yield s.withGame(g);
            }
            case GameEnded x -> {
                LobbyModule.check(s.inGame() && s.game().gameNo() == x.gameNo(), "no such game " + x.gameNo());
                LobbyModule.check(s.game().flow().frames().isEmpty(), "windows must be closed before the game ends");
                RoomState l = s.lobby();
                RoomState back = new RoomState(l.status(), l.hostId(), LobbyModule.unready(l), l.settings());
                yield new SessionState(back, null, x.gameNo(), s.game().flow().nextWindowId(), x.result());
            }
            case FlowEvent x -> {
                LobbyModule.check(s.inGame(), "flow event outside a game");
                yield s.withGame(s.game().withFlow(FlowCoordinator.evolve(s.game().flow(), x)));
            }
            default -> {
                LobbyModule.check(s.inGame(), "game event outside a game: " + event);
                yield s.withGame(TurnModule.evolve(s.game(), event, draws, rules));
            }
        };
    }

    /**
     * 对局校验（固定顺序：局号与时间 → 设置 → 参与者 → 棋盘 → 回合模块 → 流程），返回认领的全部任务。
     */
    static List<FlowCoordinator.TaskClaim> validate(EngineState engine, SessionState s, RuleConfig config, boolean full) {
        LobbyModule.expect(s.gamesPlayed() >= 0 && s.gamesPlayed() < Long.MAX_VALUE, "gamesPlayed out of range");
        LobbyModule.expect(s.nextWindowId() >= 1 && s.nextWindowId() < Long.MAX_VALUE, "nextWindowId out of range");
        GameState g = s.game();
        if (g == null) {
            return List.of();
        }
        RoomState lobby = s.lobby();
        LobbyModule.expect(lobby.status() == RoomStatus.OPEN, "a closed room cannot host a game");
        LobbyModule.expect(g.gameNo() == s.gamesPlayed() + 1, "game number must follow gamesPlayed");
        LobbyModule.expect(g.startedAt() <= engine.now(), "game cannot start in the future");
        LobbyModule.expect(g.settings() != null && g.settings().equals(lobby.settings()), "frozen settings differ from lobby");
        LobbyModule.expect(g.players() != null && g.players().stream().allMatch(p -> p != null && p.playerId() != null),
                "players missing");
        TreeSet<String> ids = new TreeSet<>();
        g.players().forEach(p -> ids.add(p.playerId()));
        TreeSet<String> members = new TreeSet<>();
        lobby.members().forEach(m -> members.add(m.playerId()));
        LobbyModule.expect(ids.size() == g.players().size() && ids.equals(members), "participants must equal the room members");
        int boardSize = LobbyModule.board(config, g.settings()).size();
        LobbyModule.expect(ids.size() >= config.room().minPlayersToStart()
                && ids.size() <= LobbyModule.board(config, g.settings()).maxPlayers(), "participant count out of board capacity");
        for (PlayerState p : g.players()) {
            LobbyModule.expect(p.position() >= 0 && p.position() < boardSize, "position out of board for " + p.playerId());
            LobbyModule.expect(p.hand() != null && p.hand().size() <= config.economy().handLimit() + 1
                    && p.hand().stream().allMatch(java.util.Objects::nonNull), "invalid hand for " + p.playerId());
        }
        LobbyModule.expect(g.board() != null && g.settings().boardId().equals(g.board().boardId()), "board mismatch");
        LobbyModule.expect(g.flow() != null && g.flow().nextWindowId() >= s.nextWindowId(), "window ids must not go back");
        List<FlowCoordinator.TaskClaim> claims = new java.util.ArrayList<>(TurnModule.validate(engine, g, config));
        claims.addAll(FlowCoordinator.validate(engine, g.flow()));
        return claims;
    }

    /**
     * 覆盖流程（拍卖、攻击、债务、小游戏等，M2 起）开窗的唯一入口：先撤下回合窗口的自动任务，再由
     * FlowCoordinator 暂停回合窗口（个人投骰钟在打断期间暂停，全局时钟不停）。
     */
    static FlowCoordinator.Opened openOverlay(DecisionContext<SessionState> ctx, com.millionnaire.engine.core.state.FlowKind kind,
                                              String owner, long leadMs, long durationMs, String resumeTag) {
        if (!kind.overlay() && kind != com.millionnaire.engine.core.state.FlowKind.RESPONSE) {
            throw new IllegalArgumentException(kind + " is not an overlay; TURN stage windows use TurnModule.openDecision");
        }
        if (durationMs == 0) {
            // 零时长：不创建窗口、不暂停父窗口，因此也不撤父窗口的自动任务（T5）；由调用方立即执行自动动作
            return new FlowCoordinator.Opened.Exhausted();
        }
        TurnModule.suspendAuto(ctx);
        return FlowCoordinator.open(ctx, FLOW, kind, owner, leadMs, durationMs, resumeTag);
    }

    /**
     * 覆盖流程关窗的唯一入口：恢复父窗口后重新核对回合自动任务；若回合窗口恢复时已耗尽，立即执行回合自动动作。
     */
    static FlowCoordinator.Closed closeOverlay(DecisionContext<SessionState> ctx, long windowId,
                                               GameEvent.CloseReason reason) {
        FlowCoordinator.Closed closed = FlowCoordinator.close(ctx, FLOW, windowId, reason);
        for (com.millionnaire.engine.core.state.FlowFrame f : closed.exhausted()) {
            if (f.kind() == com.millionnaire.engine.core.state.FlowKind.TURN) {
                TurnModule.onTurnWindowExhausted(ctx);
            }
        }
        // 统一收尾：流程返回后按 continuation 继续、DRAINING 收尾，或重新安排自动任务
        TurnModule.afterOverlay(ctx);
        return closed;
    }

    /** 按观察者投影：公开字段对所有人可见，手牌只给本人。 */
    static GameView view(GameState g, String viewerId) {
        List<GameView.PublicPlayer> players = g.players().stream()
                .map(p -> new GameView.PublicPlayer(p.playerId(), p.position(), g.ledger().cash(p.playerId()), p.hand().size(),
                        p.life(), p.inJail(), p.jailFailures(), p.control(), p.conn())).toList();
        List<GameView.OpenWindow> windows = g.flow().frames().stream()
                .map(f -> new GameView.OpenWindow(f.windowId(), f.kind(), f.owner(), f.window().opensAt(),
                        f.window().deadline(), f.window().paused())).toList();
        var mine = viewerId == null ? List.<com.millionnaire.engine.config.CardType>of()
                : g.player(viewerId).map(PlayerState::hand).orElse(List.of());
        return new GameView(g.gameNo(), g.phase(), players, g.orderDraws(), g.board(), g.turn().turnNo(),
                g.turn().currentPlayer(), g.turn().stage(), g.clock().endsAt(), windows, mine);
    }
}
