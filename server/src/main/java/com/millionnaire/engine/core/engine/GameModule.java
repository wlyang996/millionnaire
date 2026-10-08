package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.command.SessionCommand.EndGame;
import com.millionnaire.engine.core.command.SessionCommand.StartGame;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.FlowEvent;
import com.millionnaire.engine.core.event.GameEvent.GameAborted;
import com.millionnaire.engine.core.event.GameEvent.GameEnded;
import com.millionnaire.engine.core.event.GameEvent.GameStarted;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.BoardState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowState;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowOrigin;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.DebtPath;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.core.state.Continuation;
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
import com.millionnaire.engine.core.state.TurnTrack;
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
                ctx.emit(new GameAborted(s.game().gameNo(), c.reason(), ctx.engineState().lastSeq()));
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
                List<com.millionnaire.engine.core.state.OwnableState> ownables = LobbyModule.board(rules, x.settings()).tiles()
                        .stream().filter(t -> t.type() == com.millionnaire.engine.config.TileType.PROPERTY
                                || t.type() == com.millionnaire.engine.config.TileType.STATION)
                        .map(t -> com.millionnaire.engine.core.state.OwnableState.unowned(t.index())).toList();
                GameState g = new GameState(x.gameNo(), x.startedAt(), x.settings(), GamePhase.RUNNING,
                        x.seats().stream().map(PlayerState::seated).toList(), List.of(),
                        new BoardState(x.settings().boardId(), ownables), TurnState.notStarted(),
                        FlowState.initial(s.nextWindowId()), Ledger.open(cash), new GameClock(0, 0), null, List.of());
                yield s.withGame(g);
            }
            case GameEnded x -> {
                LobbyModule.check(s.inGame() && s.game().gameNo() == x.gameNo(), "no such game " + x.gameNo());
                LobbyModule.check(s.game().flow().frames().isEmpty(), "windows must be closed before the game ends");
                LobbyModule.check(x.result() != null && s.abortSource() == null,
                        "normal game end requires a result and cannot consume an EndGame source");
                // E2 / E3 / N2：正常终局必须完整关闭事务，亦不得吞掉投骰、移动、奖励、判定等步内衔接记录。
                LobbyModule.check(s.game().debt() == null && s.game().pendingSurrenders().isEmpty()
                        && TurnTrack.NONE.equals(s.game().turn().track()),
                        "game ended with an unfinished debt, surrender or turn track");
                LobbyModule.check(s.game().phase() != GamePhase.DRAINING || "TIME_UP".equals(x.reason()),
                        "a game past its global end can only end as TIME_UP");
                LobbyModule.check(x.result().gameNo() == x.gameNo() && x.result().reason().equals(x.reason())
                        && x.result().standings().equals(TurnModule.standings(s.game(), rules)), "game result does not follow the rules");
                yield backToLobby(s, x.result());
            }
            case GameAborted x -> {
                LobbyModule.check(s.inGame() && s.game().gameNo() == x.gameNo(), "no such game " + x.gameNo());
                var source = s.abortSource();
                LobbyModule.check(source != null && source.inputSeq() == x.inputSeq()
                        && source.command().expectedGameNo() == x.gameNo()
                        && x.reason() != null && !x.reason().isBlank() && x.reason().equals(source.command().reason()),
                        "game abort requires a matching accepted system EndGame source");
                LobbyModule.check(s.game().flow().frames().isEmpty(), "windows must be closed before the game aborts");
                // 管理中止可丢弃进行中的债务、待认输等状态；只由已核对的系统来源提供此例外。
                yield backToLobby(s, null);
            }
            case FlowEvent x -> {
                LobbyModule.check(s.inGame(), "flow event outside a game");
                if (x instanceof GameEvent.FlowRequested requested) {
                    LobbyModule.check(s.game().phase() == GamePhase.RUNNING
                            && s.game().player(requested.request().applicant()).map(PlayerState::alive).orElse(false),
                            "queued request requires a live applicant before global end");
                }
                if (x instanceof GameEvent.FlowDequeued) {
                    LobbyModule.check(s.game().phase() == GamePhase.RUNNING, "cannot dequeue after global end");
                    var t = s.game().turn();
                    LobbyModule.check(t.stage() == TurnStage.NONE && t.track().safePointPhase() == 2
                            && t.landing() == null && t.chain() == null, "dequeue requires the current turn start safe point");
                }
                if (x instanceof GameEvent.SafePointEntered) {
                    var t = s.game().turn();
                    LobbyModule.check(t.stage() == TurnStage.NONE && t.track().safePointPhase() == 1
                            && t.landing() == null && t.chain() == null, "safe point requires a fresh TurnStarted");
                }
                if (x instanceof GameEvent.WindowOpened opened && opened.frame().kind() != FlowKind.TURN) {
                    var frame = opened.frame();
                    // FlowCoordinator is authoritative for queued request/origin equality. This layer owns game timing.
                    String error = frame.kind().queued() ? queuedStageBlocked(s.game())
                            : sourcedOverlayBlocked(s.game(), frame.kind(), frame.owner(), frame.origin());
                    LobbyModule.check(error == null, "illegal overlay source/stage: " + error);
                }
                GameState next = s.game().withFlow(FlowCoordinator.evolve(s.game().flow(), x));
                if (x instanceof GameEvent.SafePointEntered) {
                    next = next.withTurn(next.turn().withTrack(next.turn().track().safePoint(2)));
                } else if (x instanceof GameEvent.FlowDequeued) {
                    next = next.withTurn(next.turn().withTrack(next.turn().track().safePoint(0)));
                }
                yield s.withGame(next);
            }
            default -> {
                LobbyModule.check(s.inGame(), "game event outside a game: " + event);
                yield s.withGame(TurnModule.evolve(s.game(), event, draws, rules));
            }
        };
    }

    private static SessionState backToLobby(SessionState s, com.millionnaire.engine.core.state.GameResult result) {
        RoomState l = s.lobby();
        RoomState back = new RoomState(l.status(), l.hostId(), LobbyModule.unready(l), l.settings());
        // 回房同时消费中止凭据，保证同一终态不能再被重用。
        return new SessionState(back, null, s.game().gameNo(), s.game().flow().nextWindowId(), result);
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
        TreeSet<String> alive = new TreeSet<>();
        g.players().stream().filter(PlayerState::alive).forEach(p -> alive.add(p.playerId()));
        // 对局名册与大厅成员分开（M2 P3）：成员都是参与者；存活参与者都仍是成员（只有被淘汰者可以离房观战结束）
        LobbyModule.expect(ids.size() == g.players().size() && ids.containsAll(members) && members.containsAll(alive),
                "room members must be participants and every alive participant must still be a member");
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
        List<FlowCoordinator.TaskClaim> claims = new java.util.ArrayList<>(TurnModule.validate(engine, g, config, full));
        claims.addAll(FlowCoordinator.validate(engine, g.flow()));
        MinigameModule.validate(g, config);
        CardModule.validate(g, config);
        AuctionModule.validate(g, config);
        TradeModule.validate(g, config);
        for (var frame : g.flow().frames()) {
            if (frame.kind() == FlowKind.TURN) { continue; }
            FlowOrigin o = frame.origin();
            LobbyModule.expect(o != null && frame.owner().equals(o.actor()), "overlay source missing");
            if (frame.kind().queued()) {
                LobbyModule.expect(o.kind() == FlowOrigin.Kind.QUEUED && o.ref() >= 1 && o.ref() < g.flow().nextRequestId()
                        && o.scopeId() == g.flow().startedAtSafePoint() && o.cursor() == -1
                        && g.turn().continuation() instanceof Continuation.BeginTurn, "queued flow origin invalid");
            } else if (frame.kind() == FlowKind.DEBT) {
                LobbyModule.expect(g.debt() != null && o.kind() == FlowOrigin.Kind.DEBT && o.ref() == g.debt().debtId()
                        && o.scopeId() == g.turn().turnNo() && o.cursor() == g.turn().landing().cursor(), "debt flow origin invalid");
            } else if (frame.kind() == FlowKind.ATTACK) {
                LobbyModule.expect(o.scopeId() == g.turn().turnNo() && o.kind() == FlowOrigin.Kind.ACTIVE_CARD
                        && o.ref() == 0 && o.cursor() == -1 && o.actor().equals(g.turn().currentPlayer())
                        && StageTable.rule(StageTable.turnPoint(g)).preemptible(FlowKind.ATTACK), "attack flow origin invalid");
            } else if (frame.kind() == FlowKind.LAND_AUCTION) {
                var a = g.auction();
                var l = g.turn().landing();
                LobbyModule.expect(a != null && o.kind() == FlowOrigin.Kind.LAND_AUCTION && o.scopeId() == g.turn().turnNo()
                        && l != null && o.ref() == l.landingId() && o.cursor() == l.cursor() && o.actor().equals(a.initiator()),
                        "land auction flow origin invalid");
            } else if (frame.kind() == FlowKind.MINIGAME) {
                var m = g.minigame();
                LobbyModule.expect(m != null && o.kind() == FlowOrigin.Kind.MINIGAME && o.scopeId() == g.turn().turnNo()
                        && o.ref() == m.landingId() && o.cursor() == m.cursor() && o.actor().equals(m.picker()),
                        "minigame flow origin invalid");
            } else if (frame.kind() == FlowKind.RESPONSE) {
                // 道具攻击的响应窗挂在当前玩家的回合窗口上，必须对应待响应的攻击与其目标所有者；ATTACK 父窗口为流程骨架
                var parent = g.flow().frame(o.ref()).orElse(null);
                var effect = g.cards().effect();
                boolean cardParent = parent != null && parent.kind() == FlowKind.TURN && effect != null && effect.response() != null
                        && o.actor().equals(effect.target());
                LobbyModule.expect(o.scopeId() == g.turn().turnNo() && o.kind() == FlowOrigin.Kind.ATTACK_RESPONSE
                        && o.cursor() == -1 && parent != null && (parent.kind() == FlowKind.ATTACK || cardParent)
                        && parent.windowId() < frame.windowId() && g.player(o.actor()).map(PlayerState::alive).orElse(false), "response flow origin invalid");
            } else {
                LobbyModule.expect(false, "unregistered synchronous flow origin");
            }
        }
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
        String blocked = overlayBlocked(ctx.state().game(), kind);
        if (blocked != null) { throw new IllegalStateException(blocked); }
        GameState g = ctx.state().game();
        FlowOrigin origin = kind == FlowKind.DEBT
                ? new FlowOrigin(FlowOrigin.Kind.DEBT, g.turn().turnNo(), g.debt().debtId(), g.turn().landing().cursor(), owner)
                : new FlowOrigin(FlowOrigin.Kind.ACTIVE_CARD, g.turn().turnNo(), 0, -1, owner);
        return openSourcedOverlay(ctx, kind, owner, leadMs, durationMs, resumeTag, origin);
    }

    /** 唯一队列启动入口：对象必须等于刚出队凭据，不能用一个自造 FlowRequest 冒充。 */
    static FlowCoordinator.Opened openQueuedOverlay(DecisionContext<SessionState> ctx, FlowRequest request,
                                                   long leadMs, long durationMs, String resumeTag) {
        GameState g = ctx.state().game();
        if (request == null || !request.equals(g.flow().pendingStart())) {
            throw new IllegalStateException("queued flow requires the actual dequeued request");
        }
        FlowOrigin origin = new FlowOrigin(FlowOrigin.Kind.QUEUED, g.flow().safePointNo(), request.requestId(), -1, request.applicant());
        return openSourcedOverlay(ctx, request.kind(), request.applicant(), leadMs, durationMs, resumeTag, origin);
    }

    /** 同步来源入口；攻击响应必须绑定父窗口，租金响应将在 M4 接入费用成立前的任务来源。 */
    static FlowCoordinator.Opened openSourcedOverlay(DecisionContext<SessionState> ctx, FlowKind kind, String owner,
                                                    long leadMs, long durationMs, String resumeTag, FlowOrigin origin) {
        String blocked = sourcedOverlayBlocked(ctx.state().game(), kind, owner, origin);
        if (blocked != null) { throw new IllegalStateException(blocked); }
        if (durationMs == 0) {
            if (kind.queued()) { throw new IllegalStateException("queued flows require a positive duration"); }
            return new FlowCoordinator.Opened.Exhausted();
        }
        TurnModule.suspendAuto(ctx);
        return FlowCoordinator.open(ctx, FLOW, kind, owner, leadMs, durationMs, resumeTag, origin);
    }

    /** 在任何事件前检查，排队申请本身不经过此门禁。 */
    static String overlayBlocked(GameState g, FlowKind kind) {
        if (g.turn().track().liquidating() != null) { return kind + " cannot open inside a liquidation"; }
        if (kind == FlowKind.DEBT) {
            return g.debt() != null && g.debt().path() == DebtPath.MANUAL && g.flow().frames().isEmpty() ? null
                    : "a debt window opens only for an established manual debt on an empty flow stack";
        }
        if (g.debt() != null) { return kind + " cannot open while a debt is open"; }
        var top = g.flow().top().orElse(null);
        if (top != null && top.kind() == FlowKind.TURN && !StageTable.rule(StageTable.turnPoint(g)).preemptible(kind)) {
            return kind + " cannot open over the " + StageTable.turnPoint(g) + " window: it cannot be preempted";
        }
        // Queued kinds are also != ATTACK: the explicit-source gate below rejects them without a duplicate branch.
        if (kind != FlowKind.ATTACK) { return kind + " requires an explicit synchronous source"; }
        return null;
    }

    private static String sourcedOverlayBlocked(GameState g, FlowKind kind, String owner, FlowOrigin origin) {
        if (origin == null || owner == null || !owner.equals(origin.actor())
                || !g.player(owner).map(PlayerState::alive).orElse(false)) { return "missing or foreign flow source"; }
        if (g.turn().track().liquidating() != null) { return "flow cannot open inside a liquidation"; }
        if (kind.queued()) {
            FlowRequest r = g.flow().pendingStart();
            return origin.kind() == FlowOrigin.Kind.QUEUED && r != null && r.kind() == kind && r.applicant().equals(owner)
                    && origin.ref() == r.requestId() && origin.scopeId() == g.flow().safePointNo() && origin.cursor() == -1
                    && queuedStageBlocked(g) == null
                    ? null : "queued flow source/safe point mismatch";
        }
        if (origin.scopeId() != g.turn().turnNo()) { return "source belongs to another turn"; }
        if (kind == FlowKind.DEBT) {
            return overlayBlocked(g, kind) == null && origin.kind() == FlowOrigin.Kind.DEBT && origin.ref() == g.debt().debtId()
                    && owner.equals(g.debt().debtor()) && g.turn().landing() != null && origin.cursor() == g.turn().landing().cursor()
                    ? null : "debt flow source mismatch";
        }
        if (g.debt() != null) { return "flow cannot open while a debt is open"; }
        var top = g.flow().top().orElse(null);
        if (kind == FlowKind.LAND_AUCTION) {
            var a = g.auction();
            var l = g.turn().landing();
            return origin.kind() == FlowOrigin.Kind.LAND_AUCTION && a != null && a.kind() == com.millionnaire.engine.core.state.AuctionState.Kind.LAND
                    && owner.equals(a.initiator()) && l != null && origin.ref() == l.landingId() && origin.cursor() == l.cursor()
                    && top == null && g.turn().stage() == TurnStage.AWAITING_FLOW
                    ? null : "land auction source mismatch";
        }
        if (kind == FlowKind.MINIGAME) {
            var m = g.minigame();
            return origin.kind() == FlowOrigin.Kind.MINIGAME && m != null && origin.ref() == m.landingId()
                    && origin.cursor() == m.cursor() && owner.equals(m.picker()) && top == null
                    && g.turn().stage() == TurnStage.AWAITING_FLOW && g.turn().landing() != null
                    && g.turn().landing().landingId() == m.landingId()
                    ? null : "minigame pick source mismatch";
        }
        if (kind == FlowKind.ATTACK) {
            return origin.kind() == FlowOrigin.Kind.ACTIVE_CARD && origin.ref() == 0 && origin.cursor() == -1
                    && owner.equals(g.turn().currentPlayer()) && g.phase() == GamePhase.RUNNING && top != null
                    && top.kind() == FlowKind.TURN && StageTable.rule(StageTable.turnPoint(g)).preemptible(kind)
                    ? null : "attack stage/action source mismatch";
        }
        if (kind == FlowKind.RESPONSE) {
            // M4 target-set/card authorization is pending; the scaffold binds the real responder and current parent.
            var effect = g.cards().effect();
            boolean cardParent = top != null && top.kind() == FlowKind.TURN && effect != null && effect.response() != null
                    && owner.equals(effect.target()) && top.owner().equals(g.turn().currentPlayer());
            return origin.kind() == FlowOrigin.Kind.ATTACK_RESPONSE && top != null && (top.kind() == FlowKind.ATTACK || cardParent)
                    && origin.ref() == top.windowId() && origin.cursor() == -1
                    ? null : "response requires its current attack parent source";
        }
        return "no synchronous source registered for " + kind;
    }

    private static String queuedStageBlocked(GameState g) {
        return g.flow().frames().isEmpty() && g.debt() == null && g.phase() == GamePhase.RUNNING
                && g.turn().stage() == TurnStage.AWAITING_FLOW && g.turn().continuation() instanceof Continuation.BeginTurn
                ? null : "queued flow source/safe point mismatch";
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
                .map(p -> new GameView.PublicPlayer(p.playerId(), p.position(), g.ledger().cash(p.playerId()),
                        g.ledger().frozen(p.playerId()), p.hand().size(), p.life(), p.inJail(), p.jailFailures(), p.control(),
                        p.conn())).toList();
        List<GameView.OpenWindow> windows = g.flow().frames().stream()
                .map(f -> new GameView.OpenWindow(f.windowId(), f.kind(), f.owner(), f.window().opensAt(),
                        f.window().deadline(), f.window().paused())).toList();
        var mine = viewerId == null ? List.<com.millionnaire.engine.config.CardType>of()
                : g.player(viewerId).map(PlayerState::hand).orElse(List.of());
        return new GameView(g.gameNo(), g.phase(), players, g.orderDraws(), g.board(), g.turn().turnNo(),
                g.turn().currentPlayer(), g.turn().stage(), g.clock().endsAt(), windows, publicLanding(g), publicDebt(g), mine,
                MinigameModule.view(g), CardModule.view(g), AuctionModule.view(g), TradeModule.view(g),
                g.turn().round(), EconomyModule.rentPercent(g));
    }

    /** E6：当前落点的公开部分（步骤与决策是否仍待做）。 */
    private static GameView.PublicLanding publicLanding(GameState g) {
        var l = g.turn().landing();
        return l == null ? null : new GameView.PublicLanding(l.landingId(), l.tile(), l.step(), l.decisionOpen());
    }

    /** E6：进行中债务的公开部分（金额、债权人、段数、是否已点继续、继续是否可用）。 */
    private static GameView.PublicDebt publicDebt(GameState g) {
        var d = g.debt();
        return d == null ? null : new GameView.PublicDebt(d.debtId(), d.debtor(), d.creditor(), d.amount(), d.segment(),
                d.continued(), d.segment() == 2 && !d.continued(), d.windowId());
    }
}
