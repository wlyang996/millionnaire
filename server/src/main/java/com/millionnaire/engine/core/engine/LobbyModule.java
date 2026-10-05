package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.BoardTemplate;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.RoomCommand.ChangeSettings;
import com.millionnaire.engine.core.command.RoomCommand.Join;
import com.millionnaire.engine.core.command.RoomCommand.Kick;
import com.millionnaire.engine.core.command.RoomCommand.Leave;
import com.millionnaire.engine.core.command.RoomCommand.SetReady;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.event.RoomEvent;
import com.millionnaire.engine.core.event.RoomEvent.HostChanged;
import com.millionnaire.engine.core.event.RoomEvent.LeaveReason;
import com.millionnaire.engine.core.event.RoomEvent.PlayerJoined;
import com.millionnaire.engine.core.event.RoomEvent.PlayerLeft;
import com.millionnaire.engine.core.event.RoomEvent.ReadyChanged;
import com.millionnaire.engine.core.event.RoomEvent.RoomClosed;
import com.millionnaire.engine.core.event.RoomEvent.SettingsChanged;
import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.RoomStatus;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.serialize.Immutable;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 大厅模块（requirements 第 2 节）：加入、离开、踢人、准备、修改设置、房主移交、无人关房。
 * 对局进行中，大厅命令一律拒绝（开局后不补人、不踢人；离开与重连属于对局规则，M1 处理）。
 */
final class LobbyModule {
    static final int MAX_NICKNAME_LENGTH = 32;

    private LobbyModule() {
    }

    static RejectionCode decide(DecisionContext<SessionState> ctx, RoomCommand command) {
        if (ctx.state().lobby().status() == RoomStatus.CLOSED) {
            return RejectionCode.ROOM_CLOSED;
        }
        if (ctx.state().inGame()) {
            return RejectionCode.GAME_IN_PROGRESS;
        }
        return switch (command) {
            case Join c -> join(ctx, c);
            case Leave c -> leave(ctx, c.playerId(), LeaveReason.LEFT);
            case Kick c -> kick(ctx, c);
            case SetReady c -> setReady(ctx, c);
            case ChangeSettings c -> changeSettings(ctx, c);
        };
    }

    static RoomState evolve(RoomState s, RoomEvent event) {
        return switch (event) {
            case PlayerJoined x -> {
                check(s.status() == RoomStatus.OPEN && !s.isMember(x.playerId()), "cannot join");
                yield new RoomState(s.status(), s.hostId(),
                        Immutable.append(s.members(), new Member(x.playerId(), x.nickname(), false)), s.settings());
            }
            case PlayerLeft x -> {
                check(s.isMember(x.playerId()), "not a member");
                yield new RoomState(s.status(), s.hostId(),
                        s.members().stream().filter(m -> !m.playerId().equals(x.playerId())).toList(), s.settings());
            }
            case HostChanged x -> {
                check(x.hostId() == null || s.isMember(x.hostId()), "host must be a member");
                yield new RoomState(s.status(), x.hostId(), s.members(), s.settings());
            }
            case ReadyChanged x -> {
                check(s.isMember(x.playerId()), "not a member");
                yield new RoomState(s.status(), s.hostId(), s.members().stream()
                        .map(m -> m.playerId().equals(x.playerId()) ? m.withReady(x.ready()) : m).toList(), s.settings());
            }
            case SettingsChanged x -> new RoomState(s.status(), s.hostId(), unready(s), x.settings());
            case RoomClosed x -> {
                check(s.members().isEmpty() && s.hostId() == null, "closing room must be empty");
                yield new RoomState(RoomStatus.CLOSED, null, s.members(), s.settings());
            }
        };
    }

    static java.util.List<Member> unready(RoomState s) {
        return s.members().stream().map(m -> m.withReady(false)).toList();
    }

    static void validate(RoomState s, RuleConfig config) {
        expect(s != null && s.status() != null && s.members() != null && s.settings() != null, "lobby fields missing");
        expect(validSettings(config, s.settings()), "invalid room settings");
        TreeSet<String> ids = new TreeSet<>();
        for (Member m : s.members()) {
            expect(m != null && !blank(m.playerId()) && ids.add(m.playerId()), "member ids must be unique and non-blank");
            expect(validNickname(m.nickname()), "invalid nickname for " + m.playerId());
        }
        expect(s.members().size() <= board(config, s.settings()).maxPlayers(), "room over capacity");
        expect((s.hostId() == null) == s.members().isEmpty(), "host must exist iff room has members");
        expect(s.hostId() == null || s.isMember(s.hostId()), "host must be a member");
        expect(s.status() == RoomStatus.OPEN || s.members().isEmpty(), "closed room must be empty");
    }

    // ------------------------------------------------------------ 判定

    private static RejectionCode join(DecisionContext<SessionState> ctx, Join c) {
        RoomState s = ctx.state().lobby();
        if (blank(c.playerId())) {
            return RejectionCode.INVALID_ARGUMENT;
        }
        if (s.isMember(c.playerId())) {
            return RejectionCode.ALREADY_MEMBER;
        }
        if (!validNickname(c.nickname())) {
            return RejectionCode.INVALID_NICKNAME;
        }
        if (s.members().size() >= board(ctx.config(), s.settings()).maxPlayers()) {
            return RejectionCode.ROOM_FULL;
        }
        ctx.emit(new PlayerJoined(c.playerId(), c.nickname()));
        if (ctx.state().lobby().hostId() == null) {
            ctx.emit(new HostChanged(c.playerId()));
        }
        return null;
    }

    private static RejectionCode kick(DecisionContext<SessionState> ctx, Kick c) {
        if (!ctx.state().lobby().isHost(c.actor())) {
            return RejectionCode.NOT_HOST;
        }
        if (c.actor().equals(c.target())) {
            return RejectionCode.CANNOT_KICK_SELF;
        }
        return leave(ctx, c.target(), LeaveReason.KICKED);
    }

    private static RejectionCode leave(DecisionContext<SessionState> ctx, String playerId, LeaveReason reason) {
        RoomState before = ctx.state().lobby();
        if (blank(playerId) || !before.isMember(playerId)) {
            return RejectionCode.NOT_MEMBER;
        }
        ctx.emit(new PlayerLeft(playerId, reason));
        RoomState after = ctx.state().lobby();
        if (after.members().isEmpty()) {
            // 无人房间关闭（requirements 第 2 节）；终态不留挂起任务
            ctx.tasks().forEach(t -> ctx.cancel(t.taskId()));
            ctx.emit(new HostChanged(null));
            ctx.emit(new RoomClosed());
        } else if (before.isHost(playerId)) {
            // 房主离开，交给最早加入的玩家
            ctx.emit(new HostChanged(after.members().get(0).playerId()));
        }
        return null;
    }

    private static RejectionCode setReady(DecisionContext<SessionState> ctx, SetReady c) {
        Optional<Member> m = blank(c.playerId()) ? Optional.empty() : ctx.state().lobby().member(c.playerId());
        if (m.isEmpty()) {
            return RejectionCode.NOT_MEMBER;
        }
        if (m.get().ready() == c.ready()) {
            return RejectionCode.UNCHANGED;
        }
        ctx.emit(new ReadyChanged(c.playerId(), c.ready()));
        return null;
    }

    private static RejectionCode changeSettings(DecisionContext<SessionState> ctx, ChangeSettings c) {
        RoomState s = ctx.state().lobby();
        if (!s.isHost(c.actor())) {
            return RejectionCode.NOT_HOST;
        }
        RoomSettings n = c.settings();
        if (!validSettings(ctx.config(), n)) {
            return RejectionCode.INVALID_SETTINGS;
        }
        if (n.equals(s.settings())) {
            return RejectionCode.UNCHANGED;
        }
        if (s.members().size() > board(ctx.config(), n).maxPlayers()) {
            return RejectionCode.CAPACITY_EXCEEDED;
        }
        ctx.emit(new SettingsChanged(n));
        return null;
    }

    // ------------------------------------------------------------ helpers

    static boolean validSettings(RuleConfig config, RoomSettings n) {
        return n != null && n.endMode() != null && n.boardId() != null
                && config.board(n.boardId()).isPresent()
                && config.room().initialCashOptions().contains(n.initialCash())
                && config.timing().timeLimitMinutesOptions().contains(n.timeLimitMinutes())
                && config.timing().rollSecondsOptions().contains(n.rollSeconds());
    }

    /** 昵称：非空白、≤ 32 个 UTF-16 单元、无控制字符与孤立代理字符。 */
    static boolean validNickname(String nickname) {
        if (blank(nickname) || nickname.length() > MAX_NICKNAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < nickname.length(); i++) {
            char ch = nickname.charAt(i);
            if (Character.isISOControl(ch)) {
                return false;
            }
            if (Character.isHighSurrogate(ch) && i + 1 < nickname.length() && Character.isLowSurrogate(nickname.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(ch)) {
                return false;
            }
        }
        return true;
    }

    static BoardTemplate board(RuleConfig config, RoomSettings settings) {
        return config.board(settings.boardId())
                .orElseThrow(() -> new IllegalStateException("unknown board " + settings.boardId()));
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    static void expect(boolean condition, String message) {
        if (!condition) {
            throw new StateValidationException(message);
        }
    }
}
