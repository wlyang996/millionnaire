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
            // 对局中只允许已淘汰的参与者离房（观战者回大厅，M2 P3）；其余大厅命令一律拒绝
            if (command instanceof Leave c && !blank(c.playerId()) && ctx.state().lobby().isMember(c.playerId())
                    && ctx.state().game().player(c.playerId()).map(p -> !p.alive()).orElse(false)) {
                return leave(ctx, c.playerId(), LeaveReason.LEFT);
            }
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
                check(s.status() == RoomStatus.OPEN && !s.isMember(x.playerId()) && validNickname(x.nickname()), "cannot join");
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
        String nickname = c.nickname() == null ? null : trimNickname(c.nickname());
        if (!validNickname(nickname)) {
            return RejectionCode.INVALID_NICKNAME;
        }
        if (s.members().size() >= board(ctx.config(), s.settings()).maxPlayers()) {
            return RejectionCode.ROOM_FULL;
        }
        ctx.emit(new PlayerJoined(c.playerId(), nickname));
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
                && config.timing().rollSecondsOptions().contains(n.rollSeconds())
                && (n.initialCards() == RoomSettings.AS_CONFIG || n.initialCards() >= 0 && n.initialCards() <= config.economy().handLimit());
    }

    /**
     * 昵称规格（产品决定 M1 收尾，选 A；写入 rules-v1）：
     * <ol>
     *   <li><b>trim</b>：加入时去掉首尾的空白与空格类字符（{@link Character#isWhitespace} 或 {@link Character#isSpaceChar}，
     *       含不换行空格 U+00A0、全角空格 U+3000），保存去除后的结果；保存的昵称首尾不得再有此类字符。</li>
     *   <li><b>长度口径</b>：trim 之后按 UTF-16 码元计，1..{@value #MAX_NICKNAME_LENGTH}。基本平面字符计 1，辅助平面字符
     *       （多数 emoji）计 2；组合 emoji（ZWJ 序列、肤色修饰、VS16 等）累加其全部码元。若产品要求按用户看到的字符（字素簇）计数，需另行裁决。</li>
     *   <li>拒绝控制字符（{@link Character#isISOControl}）与孤立代理字符。</li>
     *   <li>拒绝零宽字符 U+200B、U+200C、U+2060、U+FEFF、U+180E，以及不处于两个 emoji 之间的 U+200D（ZWJ）。
     *       ZWJ 合法当且仅当：前一个码点（跳过 VS16 U+FE0F 与肤色修饰 U+1F3FB..1F3FF）与后一个码点都是
     *       {@link Character#isExtendedPictographic} emoji，从而保留正常的 emoji 连接序列。</li>
     *   <li>拒绝双向控制符 U+202A..202E、U+2066..2069、U+200E、U+200F、U+061C。</li>
     *   <li>拒绝纯不可见昵称：至少要有一个可见码点（见 {@link #invisible}：空白、格式字符、默认可忽略码点、填充字符、
     *       单独的组合附加符都不算可见）。</li>
     * </ol>
     * 昵称可重复（D#13）。
     */
    static boolean validNickname(String nickname) {
        if (nickname == null || nickname.isEmpty() || nickname.length() > MAX_NICKNAME_LENGTH
                || !trimNickname(nickname).equals(nickname)) {
            return false;
        }
        for (int i = 0; i < nickname.length(); i++) {
            char ch = nickname.charAt(i);
            if (Character.isHighSurrogate(ch) && i + 1 < nickname.length() && Character.isLowSurrogate(nickname.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(ch)) {
                return false;
            }
        }
        int[] cps = nickname.codePoints().toArray();
        boolean visible = false;
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (Character.isISOControl(cp) || BIDI_OR_ZERO_WIDTH.indexOf(cp) >= 0) {
                return false;
            }
            if (cp == 0x200D && !emojiJoin(cps, i)) {
                return false;
            }
            visible |= !invisible(cp);
        }
        return visible;
    }

    /** 被拒绝的零宽与双向控制码点（ZWJ U+200D 单独判断）。 */
    private static final String BIDI_OR_ZERO_WIDTH = new String(new int[] {0x200B, 0x200C, 0x2060, 0xFEFF, 0x180E,
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C}, 0, 17);

    private static boolean emojiJoin(int[] cps, int zwj) {
        int prev = zwj - 1;
        while (prev >= 0 && (cps[prev] == 0xFE0F || cps[prev] >= 0x1F3FB && cps[prev] <= 0x1F3FF)) {
            prev--;
        }
        return prev >= 0 && zwj + 1 < cps.length && Character.isExtendedPictographic(cps[prev])
                && Character.isExtendedPictographic(cps[zwj + 1]);
    }

    /**
     * 不能单独构成可见昵称的码点（E8）：空白 / 空格类、格式字符 Cf、Unicode 默认可忽略码点（U+034F 组合字形连接符、
     * U+115F / U+1160 / U+3164 / U+FFA0 韩文填充、U+17B4 / U+17B5、U+180B..180F 蒙古文变体选择符、变体选择符、
     * U+1BCA0..1BCA3、U+1D173..1D17A、U+E0000..E0FFF 等）、U+2800 盲文空白，以及组合附加符（Mn / Me，必须依附基字符）。
     * 这些码点<b>不被禁止</b>（如 "e" + U+0301、emoji 的 VS16 / 肤色修饰照常可用），只是不计入"至少一个可见码点"。
     */
    private static boolean invisible(int cp) {
        int type = Character.getType(cp);
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || type == Character.FORMAT
                || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x17B4 || cp == 0x17B5
                || cp >= 0x180B && cp <= 0x180F || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800
                || cp >= 0xFE00 && cp <= 0xFE0F || cp >= 0xFFF0 && cp <= 0xFFF8 || cp >= 0x1BCA0 && cp <= 0x1BCA3
                || cp >= 0x1D173 && cp <= 0x1D17A || cp >= 0xE0000 && cp <= 0xE0FFF;
    }

    /** 去掉首尾的空白与空格类字符（见 {@link #validNickname}）。 */
    static String trimNickname(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && blankChar(s.charAt(start))) {
            start++;
        }
        while (end > start && blankChar(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean blankChar(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
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
