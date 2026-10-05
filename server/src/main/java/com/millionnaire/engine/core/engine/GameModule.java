package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.command.SessionCommand.EndGame;
import com.millionnaire.engine.core.command.SessionCommand.StartGame;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.GameEnded;
import com.millionnaire.engine.core.event.GameEvent.GameStarted;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.RoomStatus;
import com.millionnaire.engine.core.state.SessionState;
import java.util.List;
import java.util.TreeSet;

/**
 * 对局模块（M1 骨架）：只实现开局与回房边界。M1 在此（或拆成回合、拍卖、债务、小游戏等同级模块）实现对局规则。
 */
final class GameModule {
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
                if (c.reason() == null || c.reason().isBlank()) {
                    yield RejectionCode.INVALID_ARGUMENT;
                }
                ctx.emit(new GameEnded(s.game().gameNo(), c.reason()));
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
        if (!lobby.members().stream().allMatch(Member::ready)) {
            return RejectionCode.NOT_ALL_READY;
        }
        List<String> seats = lobby.members().stream().map(Member::playerId).toList();
        ctx.emit(new GameStarted(Math.addExact(s.gamesPlayed(), 1), seats, lobby.settings(), ctx.now()));
        return null;
    }

    static SessionState evolve(SessionState s, GameEvent event) {
        return switch (event) {
            case GameStarted x -> {
                LobbyModule.check(!s.inGame() && x.gameNo() == s.gamesPlayed() + 1, "game number mismatch");
                LobbyModule.check(x.seats().equals(s.lobby().members().stream().map(Member::playerId).toList()),
                        "seats must be the lobby members");
                yield new SessionState(s.lobby(), new GameState(x.gameNo(), x.seats(), x.settings(), x.startedAt()), s.gamesPlayed());
            }
            case GameEnded x -> {
                LobbyModule.check(s.inGame() && s.game().gameNo() == x.gameNo(), "no such game " + x.gameNo());
                RoomState l = s.lobby();
                RoomState back = new RoomState(l.status(), l.hostId(), LobbyModule.unready(l), l.settings());
                yield new SessionState(back, null, x.gameNo());
            }
        };
    }

    static void validate(SessionState s, RuleConfig config) {
        LobbyModule.expect(s.gamesPlayed() >= 0 && s.gamesPlayed() < Long.MAX_VALUE, "gamesPlayed out of range");
        GameState g = s.game();
        if (g == null) {
            return;
        }
        LobbyModule.expect(s.lobby().status() == RoomStatus.OPEN, "a closed room cannot host a game");
        LobbyModule.expect(g.gameNo() == s.gamesPlayed() + 1, "game number must follow gamesPlayed");
        LobbyModule.expect(g.seats() != null && g.seats().size() >= config.room().minPlayersToStart()
                && new TreeSet<>(g.seats()).size() == g.seats().size(), "seats must be unique and enough to start");
        LobbyModule.expect(g.seats().stream().allMatch(s.lobby()::isMember), "every seat must be a room member");
        LobbyModule.expect(LobbyModule.validSettings(config, g.settings())
                && g.seats().size() <= LobbyModule.board(config, g.settings()).maxPlayers(), "invalid game settings");
    }
}
