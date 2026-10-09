package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.BoardTemplate;
import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.GameCommand.ConnectionConfirmed;
import com.millionnaire.engine.core.command.GameCommand.ConnectionSuspected;
import com.millionnaire.engine.core.command.GameCommand.PayBail;
import com.millionnaire.engine.core.command.GameCommand.Reconnected;
import com.millionnaire.engine.core.command.GameCommand.ResumeControl;
import com.millionnaire.engine.core.command.GameCommand.RollDice;
import com.millionnaire.engine.core.command.GameCommand.SetControl;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.AutoActArmed;
import com.millionnaire.engine.core.event.GameEvent.AutoActDisarmed;
import com.millionnaire.engine.core.event.GameEvent.BailPaid;
import com.millionnaire.engine.core.event.GameEvent.CardDealt;
import com.millionnaire.engine.core.event.GameEvent.CardsDealt;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.GameEvent.ConnectionChanged;
import com.millionnaire.engine.core.event.GameEvent.ControlChanged;
import com.millionnaire.engine.core.event.GameEvent.DiceRolled;
import com.millionnaire.engine.core.event.GameEvent.DrainingStarted;
import com.millionnaire.engine.core.event.GameEvent.GameClockStarted;
import com.millionnaire.engine.core.event.GameEvent.GameEnded;
import com.millionnaire.engine.core.event.GameEvent.JailFailed;
import com.millionnaire.engine.core.event.GameEvent.JailReleased;
import com.millionnaire.engine.core.event.GameEvent.JailRolled;
import com.millionnaire.engine.core.event.GameEvent.Landed;
import com.millionnaire.engine.core.event.GameEvent.OrderNumberDrawn;
import com.millionnaire.engine.core.event.GameEvent.PlayerJailed;
import com.millionnaire.engine.core.event.GameEvent.PlayerMoved;
import com.millionnaire.engine.core.event.GameEvent.MoveChainStarted;
import com.millionnaire.engine.core.event.GameEvent.ReleaseReason;
import com.millionnaire.engine.core.event.GameEvent.StartRewardPaid;
import com.millionnaire.engine.core.event.GameEvent.TurnEnded;
import com.millionnaire.engine.core.event.GameEvent.TurnOrderFixed;
import com.millionnaire.engine.core.event.GameEvent.TurnStageEntered;
import com.millionnaire.engine.core.event.GameEvent.TurnStarted;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ConnState;
import com.millionnaire.engine.core.state.Continuation;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.GameClock;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameResult;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LifeState;
import com.millionnaire.engine.core.state.OrderDraw;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.Standing;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.core.state.TurnState;
import com.millionnaire.engine.core.state.TurnTrack;
import com.millionnaire.engine.core.state.MoveChain;
import com.millionnaire.engine.core.state.MoveSegment;
import com.millionnaire.engine.core.state.MoveKind;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.Draw;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.serialize.Immutable;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 回合模块（M1）：开局定序与发牌、全局时钟、回合轮转与安全点、投骰与移动、起点奖励、监狱、控制模式与连接、
 * 自动动作、全局到时（可跨步的 DRAINING）与结算。
 * <p><b>回合阶段与窗口栈分开</b>：JAIL_DECISION / PRE_ROLL / LANDING 各绑定一个栈底 TURN 窗口；
 * AWAITING_FLOW 表示回合存在但没有 TURN 窗口，正在等待覆盖流程返回，返回后按 continuation 继续。
 * <p>本模块认领的定时任务：GLOBAL_END（ref = 局号）、AUTO_ACT（ref = 回合窗口 ID）；窗口截止任务由 FlowCoordinator 认领。
 */
final class TurnModule {
    static final String START_REWARD = "START_REWARD";
    static final String BAIL = "BAIL";

    private TurnModule() {
    }

    // ================================================================ 开局

    /** 开局后：定序、发牌、开始全局时钟、开始第一个回合。 */
    static void begin(DecisionContext<SessionState> ctx) {
        drawOrder(ctx);
        deal(ctx);
        GameState g = game(ctx);
        long endsAt = Math.addExact(g.startedAt(), gameDurationMs(ctx.config(), g));
        long taskId = ctx.schedule(endsAt, TaskKind.GLOBAL_END, g.gameNo());
        ctx.emit(new GameClockStarted(endsAt, taskId));
        startNextTurn(ctx, 0);
    }

    /** 限时模式按所选局时；破产模式按 120 分钟硬上限（O17）。 */
    static long gameDurationMs(RuleConfig config, GameState g) {
        int minutes = g.settings().endMode() == EndMode.TIME_LIMIT ? g.settings().timeLimitMinutes()
                : config.timing().bankruptcyModeCapMinutes();
        return Math.multiplyExact((long) minutes, 60_000L);
    }

    private static void drawOrder(DecisionContext<SessionState> ctx) {
        int max = ctx.config().economy().orderNumberMax();
        List<String> drawers = game(ctx).players().stream().map(PlayerState::playerId).toList();
        while (!drawers.isEmpty()) {
            ctx.emit(new GameEvent.OrderRoundStarted(drawers));
            for (String id : drawers) {
                ctx.emit(new OrderNumberDrawn(id, ctx.draw(DrawPoint.ORDER_NUMBER, max) + 1));
            }
            drawers = TurnOrder.tiedGroups(game(ctx).orderDraws()).stream().flatMap(List::stream).toList();
        }
        ctx.emit(new TurnOrderFixed(TurnOrder.order(game(ctx).orderDraws())));
    }

    /** 开局发牌（R2）：按行动顺序，每人连续抽房间设置的张数（默认沿用 initialHandSize；0 = 不发）；牌面私发，张数公开。 */
    private static void deal(DecisionContext<SessionState> ctx) {
        int total = CardDeck.totalWeight(ctx.config());
        int count = game(ctx).settings().dealCount(ctx.config());
        for (PlayerState p : game(ctx).players()) {
            for (int k = 0; k < count; k++) {
                ctx.emit(new CardDealt(p.playerId(), CardDeck.pick(ctx.config(), ctx.draw(DrawPoint.INITIAL_CARD, total))));
            }
            ctx.emit(new CardsDealt(p.playerId(), game(ctx).player(p.playerId()).orElseThrow().hand().size()));
        }
    }

    // ================================================================ 命令

    static RejectionCode decide(DecisionContext<SessionState> ctx, GameCommand command) {
        if (!ctx.state().inGame()) {
            return RejectionCode.NOT_IN_GAME;
        }
        GameState g = game(ctx);
        return switch (command) {
            case GameCommand.DrawEventCard c -> EventModule.decide(ctx, c);
            case GameCommand.PickStartCard c -> EventModule.decide(ctx, c);
            case GameCommand.DiscardCard c -> EventModule.decide(ctx, c);
            case GameCommand.PickTooth c -> MinigameModule.decide(ctx, c);
            case GameCommand.UseCard c -> CardModule.decide(ctx, c);
            case GameCommand.RespondCard c -> CardModule.decide(ctx, c);
            case GameCommand.RequestAuction c -> AuctionModule.requestCardAuction(ctx, c);
            case GameCommand.Bid c -> AuctionModule.bid(ctx, c);
            case GameCommand.RequestTrade c -> TradeModule.request(ctx, c);
            case GameCommand.AnswerTrade c -> TradeModule.answer(ctx, c);
            case RollDice c -> {
                RejectionCode why = checkTurnWindow(ctx, c.actor(), c.windowId());
                if (why != null) {
                    yield why;
                }
                if (!StageTable.rule(StageTable.turnPoint(g)).allows(c)) {
                    yield RejectionCode.WRONG_STAGE;
                }
                act(ctx, false, false);
                yield null;
            }
            case PayBail c -> {
                RejectionCode why = checkTurnWindow(ctx, c.actor(), c.windowId());
                if (why != null) {
                    yield why;
                }
                if (!StageTable.rule(StageTable.turnPoint(g)).allows(c)) {
                    yield RejectionCode.WRONG_STAGE;
                }
                long bail = ctx.config().economy().bailCost();
                if (g.ledger().available(c.actor()) < bail) {
                    yield RejectionCode.INSUFFICIENT_CASH;
                }
                payBail(ctx, bail);
                yield null;
            }
            case ResumeControl c -> {
                if (c.gameNo() != g.gameNo()) {
                    yield RejectionCode.GAME_MISMATCH;
                }
                if (c.actor() != null && g.player(c.actor()).map(p -> p.conn() == ConnState.OFFLINE).orElse(false)) {
                    yield RejectionCode.CONTROL_NOT_MANUAL;
                }
                yield change(ctx, c.actor(), p -> p.control() == com.millionnaire.engine.core.state.ControlMode.MANUAL
                        ? null : new ControlChanged(c.actor(), com.millionnaire.engine.core.state.ControlMode.MANUAL));
            }
            case SetControl c -> {
                if (c.gameNo() != g.gameNo()) {
                    yield RejectionCode.GAME_MISMATCH;
                }
                if (c.mode() == null) {
                    yield RejectionCode.INVALID_ARGUMENT;
                }
                yield change(ctx, c.playerId(), p -> p.control() == c.mode() ? null : new ControlChanged(c.playerId(), c.mode()));
            }
            case ConnectionSuspected c -> connection(ctx, c.gameNo(), c.playerId(), c.observation(), ConnState.SUSPECT);
            case ConnectionConfirmed c -> connection(ctx, c.gameNo(), c.playerId(), c.observation(), ConnState.OFFLINE);
            case Reconnected c -> connection(ctx, c.gameNo(), c.playerId(), c.observation(), ConnState.ONLINE);
            default -> EconomyModule.decide(ctx, command);
        };
    }

    /**
     * 连接状态合法转换表（已裁决 2）：在线 → 疑似断线 → 确认掉线；疑似断线或确认掉线 → 在线（重连）。
     * 其他转换（例如确认掉线后迟到的疑似断线）一律拒绝；观测序号不大于已采纳序号的过期判定也拒绝。
     */
    static boolean legalTransition(ConnState from, ConnState to) {
        return switch (to) {
            case SUSPECT -> from == ConnState.ONLINE;
            case OFFLINE -> from == ConnState.SUSPECT;
            // 在线时收到更新的重连通知：状态不变，只推进观测水位（C1），使之后迟到的旧判定成为过期观测
            case ONLINE -> true;
        };
    }

    private static RejectionCode connection(DecisionContext<SessionState> ctx, long gameNo, String playerId, long observation,
                                            ConnState to) {
        if (gameNo != game(ctx).gameNo()) {
            return RejectionCode.GAME_MISMATCH;
        }
        Optional<PlayerState> p = playerId == null ? Optional.empty() : game(ctx).player(playerId);
        if (p.isEmpty()) {
            return RejectionCode.NOT_MEMBER;
        }
        if (observation <= p.get().connObservation()) {
            return RejectionCode.STALE_OBSERVATION;
        }
        if (!legalTransition(p.get().conn(), to)) {
            return RejectionCode.INVALID_TRANSITION;
        }
        boolean changed = p.get().conn() != to;
        ctx.emit(new ConnectionChanged(playerId, to, observation));
        if (changed) {
            policyChanged(ctx, playerId);
        }
        return null;
    }

    static RejectionCode checkTurnWindow(DecisionContext<SessionState> ctx, String actor, long windowId) {
        // 业务命令在 SessionDomain 入口统一恢复，本层核对恢复后的手动状态与窗口。
        GameState g = game(ctx);
        if (actor == null || g.player(actor).isEmpty()) {
            return RejectionCode.NOT_MEMBER;
        }
        if (!g.player(actor).orElseThrow().alive()) {
            return RejectionCode.NOT_ALIVE;
        }
        if (!actor.equals(g.turn().currentPlayer())) {
            return RejectionCode.NOT_YOUR_TURN;
        }
        PlayerState self = g.player(actor).orElseThrow();
        if (self.automated()) {
            return RejectionCode.CONTROL_NOT_MANUAL;
        }
        RejectionCode why = FlowCoordinator.checkWindow(g.flow(), windowId, actor, ctx.now());
        if (why != null) {
            return why;
        }
        return windowId == g.turn().windowId() && g.turn().stage().windowed() ? null : RejectionCode.WINDOW_MISMATCH;
    }

    private static RejectionCode change(DecisionContext<SessionState> ctx, String playerId,
                                        Function<PlayerState, GameEvent> event) {
        Optional<PlayerState> p = playerId == null ? Optional.empty() : game(ctx).player(playerId);
        if (p.isEmpty()) {
            return RejectionCode.NOT_MEMBER;
        }
        GameEvent e = event.apply(p.get());
        if (e == null) {
            return RejectionCode.UNCHANGED;
        }
        ctx.emit(e);
        policyChanged(ctx, playerId);
        return null;
    }

    /**
     * 控制或连接策略实际变化。只有<b>当前玩家本人</b>的策略变化才撤销并重排其自动任务（任务版本随 taskId 变化）；
     * 其他玩家的切换、重连、疑似断线不得改变当前任务的 ID 与到期时刻（C2）。
     */
    static void policyChanged(DecisionContext<SessionState> ctx, String playerId) {
        if (playerId.equals(game(ctx).turn().currentPlayer())) {
            disarm(ctx);
            reconcileAuto(ctx);
        }
    }

    // ================================================================ 回合推进

    private static boolean allAway(GameState g) {
        return g.players().stream().anyMatch(PlayerState::alive) && g.players().stream().filter(PlayerState::alive)
                .allMatch(p -> p.control() != com.millionnaire.engine.core.state.ControlMode.MANUAL);
    }

    private static void startNextTurn(DecisionContext<SessionState> ctx, long leadMs) {
        GameState g = game(ctx);
        if (g.phase() == GamePhase.DRAINING) {
            finish(ctx, "TIME_UP");
            return;
        }
        Optional<String> next = nextAlive(g);
        if (next.isEmpty()) {
            finish(ctx, "NO_PLAYERS");
            return;
        }
        // 全员连续暂离 / 托管达到配置的玩家回合数，在回合交界按净资产结算。
        // 只是掉线（控制仍是手动）的不算：全员掉线另有 120 秒重连保留（open-decisions #6）
        if (ctx.config().timing().endWhenAllAway() && allAway(g)
                && g.turn().allAwayTurns() >= ctx.config().timing().allAwayTurns()) {
            finish(ctx, "ALL_AWAY");
            return;
        }
        ctx.emit(new TurnStarted(Math.addExact(g.turn().turnNo(), 1), next.get()));
        // 回合交界是安全点：最多启动一个排队流程，回合在流程返回后再开始（O12）
        FlowCoordinator.enterSafePoint(ctx, GameModule.FLOW);
        // 排队标的已失效（玩家出局、卡已弃、地块已抵押或易主）的申请先取消（正式规则；旧测试配置的占位申请没有标的）
        if (AuctionModule.enabled(ctx.config())) {
            for (FlowRequest r : List.copyOf(game(ctx).flow().queue())) {
                if (!AuctionModule.requestStillValid(ctx.config(), game(ctx), r) || !TradeModule.requestStillValid(ctx.config(), game(ctx), r)) {
                    FlowCoordinator.cancelRequest(ctx, GameModule.FLOW, r.requestId());
                }
            }
        }
        Optional<FlowRequest> request = FlowCoordinator.dequeueAtSafePoint(ctx, GameModule.FLOW);
        if (request.isPresent()) {
            // 保存最早可开放时刻（含移动动画），覆盖窗口先消费这段缓冲，返回后不再重复累计（C6）
            ctx.emit(new TurnStageEntered(TurnStage.AWAITING_FLOW, 0, new Continuation.BeginTurn(game(ctx).turn().turnNo()),
                    Math.addExact(ctx.now(), leadMs)));
            OverlayModule.start(ctx, request.get(), leadMs);
            return;
        }
        beginStage(ctx, leadMs);
    }

    private static void beginStage(DecisionContext<SessionState> ctx, long leadMs) {
        PlayerState p = game(ctx).player(game(ctx).turn().currentPlayer()).orElseThrow();
        // 回合起始：在狱 → 狱中判定窗口，否则 → 投骰窗口（opus 4.5 TURN_START）；两者时限同为投骰操作时间
        openStage(ctx, p.inJail() ? TurnStage.JAIL_DECISION : TurnStage.PRE_ROLL, leadMs, rollMs(ctx), null);
    }

    private static int indexOf(GameState g, String playerId) {
        List<PlayerState> ps = g.players();
        for (int i = 0; i < ps.size(); i++) {
            if (ps.get(i).playerId().equals(playerId)) {
                return i;
            }
        }
        return -1;
    }

    /** 按行动顺序取下一位存活玩家（破产者跳过）。 */
    static Optional<String> nextAlive(GameState g) {
        List<PlayerState> ps = g.players();
        int start = 0;
        if (g.turn().currentPlayer() != null) {
            for (int i = 0; i < ps.size(); i++) {
                if (ps.get(i).playerId().equals(g.turn().currentPlayer())) {
                    start = i + 1;
                }
            }
        }
        for (int k = 0; k < ps.size(); k++) {
            PlayerState p = ps.get((start + k) % ps.size());
            if (p.life() == LifeState.ALIVE) {
                return Optional.of(p.playerId());
            }
        }
        return Optional.empty();
    }

    /**
     * 开启回合阶段窗口（栈底 TURN 窗口）。时长为 0（沿用的投骰时间已耗尽）时不开窗口，立即执行该阶段的自动动作，
     * 并把本应等待的动画缓冲继续累加给后续窗口（T10）。
     */
    private static void openStage(DecisionContext<SessionState> ctx, TurnStage stage, long leadMs, long durationMs,
                                  Continuation continuation) {
        String player = game(ctx).turn().currentPlayer();
        switch (FlowCoordinator.open(ctx, GameModule.FLOW, FlowKind.TURN, player, leadMs, durationMs, stage.name())) {
            case FlowCoordinator.Opened.Window w -> {
                ctx.emit(new TurnStageEntered(stage, w.windowId(), continuation, 0));
                reconcileAuto(ctx);
            }
            case FlowCoordinator.Opened.Exhausted x -> {
                ctx.emit(new TurnStageEntered(stage, 0, continuation, 0));
                perform(ctx, stage, true, 0, leadMs);
            }
        }
    }

    /** 落点推进器的决策窗口入口（栈必须为空）：开 LANDING 阶段窗口，结束后按 ResumeLanding 继续。 */
    static void openLandingWindow(DecisionContext<SessionState> ctx, long leadMs, long durationMs, Continuation continuation) {
        if (!game(ctx).flow().frames().isEmpty()) {
            throw new IllegalStateException("a decision window must be the bottom of the flow stack");
        }
        openStage(ctx, TurnStage.LANDING, leadMs, durationMs, continuation);
    }

    /** 玩家对落点决策窗口的操作：撤自动任务并以 ACTED 关闭回合窗口（随后由经济模块推进）。 */
    static void closeDecision(DecisionContext<SessionState> ctx) {
        disarm(ctx);
        FlowCoordinator.close(ctx, GameModule.FLOW, game(ctx).turn().windowId(), CloseReason.ACTED);
    }

    /** 当前玩家被淘汰或认输：撤自动任务、取消全部窗口（不恢复、不执行默认动作）。 */
    static void abortTurnWindows(DecisionContext<SessionState> ctx) {
        disarm(ctx);
        FlowCoordinator.cancelAll(ctx, GameModule.FLOW);
    }

    /**
     * 阶段决策窗口的独立入口（M2 起用于买地、升级、银行等落点决策）：在没有窗口时开一个 LANDING 阶段的 TURN 窗口；
     * 玩家操作或超时后按 continuation 继续（M1 中超时默认动作即"继续"）。
     */
    static void openDecision(DecisionContext<SessionState> ctx, Continuation continuation, long leadMs, long durationMs) {
        if (!game(ctx).flow().frames().isEmpty()) {
            throw new IllegalStateException("a decision window must be the bottom of the flow stack");
        }
        if (!(continuation instanceof Continuation.EndTurn)) {
            throw new IllegalArgumentException("unsupported continuation for a decision window: " + continuation);
        }
        openStage(ctx, TurnStage.LANDING, leadMs, durationMs, continuation);
    }

    /** 当前玩家对回合窗口的操作（手动、超时或自动）。 */
    private static void act(DecisionContext<SessionState> ctx, boolean auto, boolean timedOut) {
        GameState g = game(ctx);
        TurnState t = g.turn();
        FlowFrame frame = g.flow().frame(t.windowId()).orElseThrow(() -> new IllegalStateException("turn window missing"));
        long remaining = Math.max(0, frame.window().deadline() - Math.max(ctx.now(), frame.window().opensAt()));
        disarm(ctx);
        FlowCoordinator.close(ctx, GameModule.FLOW, t.windowId(), timedOut ? CloseReason.EXPIRED : CloseReason.ACTED);
        perform(ctx, t.stage(), auto, remaining, 0);
    }

    private static void perform(DecisionContext<SessionState> ctx, TurnStage stage, boolean auto, long remaining,
                                long carriedLeadMs) {
        switch (stage) {
            case PRE_ROLL -> rollAndMove(ctx, auto, carriedLeadMs);
            case JAIL_DECISION -> jailRoll(ctx, auto, remaining);
            case LANDING -> {
                if (game(ctx).turn().landing() == null) {
                    resume(ctx, game(ctx).turn().continuation(), carriedLeadMs);
                } else {
                    EconomyModule.landingDefault(ctx);
                }
            }
            default -> throw new IllegalStateException("no action for stage " + stage);
        }
    }

    private static void rollAndMove(DecisionContext<SessionState> ctx, boolean auto, long carriedLeadMs) {
        String player = game(ctx).turn().currentPlayer();
        int die = ctx.draw(DrawPoint.MOVE_DIE, ctx.config().economy().dieFaces()) + 1;
        ctx.emit(new DiceRolled(player, die, auto));
        BoardTemplate board = board(ctx);
        int from = game(ctx).player(player).orElseThrow().position();
        ctx.emit(new MoveChainStarted(Math.addExact(game(ctx).turn().lastChainId(), 1), game(ctx).turn().turnNo(), player, from));
        MoveSegment segment = MovementRules.segment(1, MoveKind.DICE, from, die, board.size());
        moveAndLand(ctx, segment, Math.addExact(carriedLeadMs, ctx.config().timing().animDiceMs()));
    }

    /** 共用移动→奖励→普通落点路径。M3b/c 在来源授权接入后复用，不在重定向前结束回合。 */
    static void moveAndLand(DecisionContext<SessionState> ctx, MoveSegment segment, long carriedLeadMs) {
        String player = game(ctx).turn().currentPlayer();
        BoardTemplate board = board(ctx);
        MoveSegment actual = MovementRules.resolve(segment, game(ctx).board(), player, board.size(), ctx.config().startPick().enabled());
        int to = actual.to();
        ctx.emit(new PlayerMoved(player, actual.from(), to, actual.distance(), game(ctx).turn().chain().chainId(), actual.number(), actual.kind(), actual.plannedDistance(), actual.stoppedBy()));
        if (actual.stoppedBy() != null) {
            ctx.emit(new GameEvent.RoadblockTriggered(player, game(ctx).turn().turnNo(), game(ctx).turn().chain().chainId(), actual.number(), actual.stoppedBy()));
        }
        // O1：前进经过或落在起点才领奖，每回合最多一次；开局不领
        if (game(ctx).turn().track().rewardDue()) {
            ctx.emit(new StartRewardPaid(player, ctx.config().economy().startReward(), game(ctx).turn().turnNo()));
        }
        TileType type = board.tiles().get(to).type();
        ctx.emit(new Landed(player, to, type, placeholder(type)));
        MoveSegment verified = game(ctx).turn().chain().segments().getLast();
        long animation = Math.addExact(carriedLeadMs,
                Math.multiplyExact((long) verified.walked().size(), ctx.config().timing().animPerStepMs()));
        if (type == TileType.JAIL) {
            ctx.emit(new PlayerJailed(player));
            endTurn(ctx, animation);
        } else {
            // 落点推进器：地产、车站、银行、事件格、游戏区（虎口拔牙）等
            EconomyModule.land(ctx, to, animation);
        }
    }

    private static void jailRoll(DecisionContext<SessionState> ctx, boolean auto, long remaining) {
        String player = game(ctx).turn().currentPlayer();
        int value = ctx.draw(DrawPoint.JAIL_DIE, ctx.config().economy().dieFaces()) + 1;
        ctx.emit(new JailRolled(player, value, auto));
        long animation = ctx.config().timing().animDiceMs();
        if (value % 2 == 0) {
            ctx.emit(new JailReleased(player, ReleaseReason.EVEN_ROLL));
            openStage(ctx, TurnStage.PRE_ROLL, animation, remaining, null);
            return;
        }
        int failures = game(ctx).player(player).orElseThrow().jailFailures() + 1;
        if (failures >= 3) {
            // O9：第三次失败当回合释放并移动，沿用剩余投骰时间
            ctx.emit(new JailReleased(player, ReleaseReason.THIRD_FAILURE));
            openStage(ctx, TurnStage.PRE_ROLL, animation, remaining, null);
        } else {
            ctx.emit(new JailFailed(player, failures));
            endTurn(ctx, animation);
        }
    }

    private static void payBail(DecisionContext<SessionState> ctx, long bail) {
        GameState g = game(ctx);
        String player = g.turn().currentPlayer();
        FlowFrame frame = g.flow().frame(g.turn().windowId()).orElseThrow();
        long remaining = Math.max(0, frame.window().deadline() - ctx.now());
        disarm(ctx);
        FlowCoordinator.close(ctx, GameModule.FLOW, g.turn().windowId(), CloseReason.ACTED);
        ctx.emit(new BailPaid(player, bail));
        ctx.emit(new JailReleased(player, ReleaseReason.BAIL));
        // 付费出狱没有骰子动画，不加缓冲
        openStage(ctx, TurnStage.PRE_ROLL, 0, remaining, null);
    }

    /** 出狱卡（O9）：在狱中判定窗口使用，立即释放并沿用本回合剩余投骰时间（同付费出狱）。 */
    static void releaseWithCard(DecisionContext<SessionState> ctx) {
        GameState g = game(ctx);
        String player = g.turn().currentPlayer();
        FlowFrame frame = g.flow().frame(g.turn().windowId()).orElseThrow();
        long remaining = Math.max(0, frame.window().deadline() - ctx.now());
        disarm(ctx);
        FlowCoordinator.close(ctx, GameModule.FLOW, g.turn().windowId(), CloseReason.ACTED);
        ctx.emit(new JailReleased(player, ReleaseReason.CARD));
        openStage(ctx, TurnStage.PRE_ROLL, 0, remaining, null);
    }

    /** 尚未接入效果的格子：事件格（M3b）与游戏区（虎口拔牙）都已接入，现无占位格。 */
    static boolean placeholder(TileType type) {
        return false;
    }

    static void endTurn(DecisionContext<SessionState> ctx, long leadMs) {
        TurnState t = game(ctx).turn();
        ctx.emit(new TurnEnded(t.turnNo(), t.currentPlayer()));
        startNextTurn(ctx, leadMs);
    }

    /** 按类型化续接继续回合。 */
    private static void resume(DecisionContext<SessionState> ctx, Continuation continuation, long leadMs) {
        switch (continuation) {
            case Continuation.EndTurn e -> endTurn(ctx, leadMs);
            case Continuation.BeginTurn b -> {
                if (game(ctx).phase() == GamePhase.DRAINING) {
                    // 全局到时后不启动新回合（O16）
                    finish(ctx, "TIME_UP");
                } else {
                    beginStage(ctx, leadMs);
                }
            }
            case Continuation.ResumeLanding r -> EconomyModule.resumeLanding(ctx, leadMs);
        }
    }

    // ================================================================ 覆盖流程衔接

    /** 覆盖流程即将暂停回合窗口：撤下自动任务（恢复后由 reconcileAuto 重新安排）。 */
    static void suspendAuto(DecisionContext<SessionState> ctx) {
        if (ctx.state().inGame()) {
            disarm(ctx);
        }
    }

    /** 回合窗口在覆盖流程结束恢复时已无剩余时间：立即执行该阶段的自动动作（不开零长度窗口）。 */
    static void onTurnWindowExhausted(DecisionContext<SessionState> ctx) {
        TurnState t = game(ctx).turn();
        ctx.emit(new TurnStageEntered(t.stage(), 0, t.continuation(), 0));
        perform(ctx, t.stage(), true, 0, 0);
    }

    /**
     * 覆盖流程关闭之后：栈空且回合在等待流程返回 → 按 continuation 继续；DRAINING 中回合窗口仍处于投骰前 → 结束对局；
     * 否则重新核对自动任务。
     */
    static void afterOverlay(DecisionContext<SessionState> ctx) {
        if (!ctx.state().inGame()) {
            return;
        }
        // 流程全部结束：先处理延后认输批次（可能结束对局或当前回合）
        if (EconomyModule.processDeferred(ctx)) {
            return;
        }
        GameState g = game(ctx);
        TurnState t = g.turn();
        if (t.stage() == TurnStage.AWAITING_FLOW && g.flow().frames().isEmpty()) {
            // 只补足尚未播完的缓冲（若流程提前结束），不重复累计
            resume(ctx, t.continuation(), Math.max(0, t.notBefore() - ctx.now()));
            return;
        }
        if (g.phase() == GamePhase.DRAINING && t.stage().beforeRoll() && g.flow().frames().size() == 1) {
            finish(ctx, "TIME_UP");
            return;
        }
        reconcileAuto(ctx);
    }

    // ================================================================ 自动动作（C9、T5）

    /**
     * 使自动任务与当前控制策略一致：当前玩家需要自动操作、窗口在栈顶运行中则安排（若尚未安排），否则取消。
     * 任务到期时刻不早于窗口开放时刻 + 延时；执行时还会再次核对策略与任务版本。
     */
    static void reconcileAuto(DecisionContext<SessionState> ctx) {
        if (!ctx.state().inGame()) {
            return;
        }
        GameState g = game(ctx);
        TurnState t = g.turn();
        if (!t.stage().windowed() || t.windowId() == 0) {
            disarm(ctx);
            return;
        }
        Optional<FlowFrame> frame = g.flow().top().filter(f -> f.windowId() == t.windowId());
        boolean running = frame.isPresent() && !frame.get().window().paused();
        boolean wanted = running && g.player(t.currentPlayer()).orElseThrow().automated();
        if (!wanted) {
            disarm(ctx);
            return;
        }
        if (t.autoTaskId() != 0) {
            return;
        }
        long due = autoDue(ctx.config(), frame.get(), ctx.now());
        if (due < frame.get().window().deadline()) {
            long taskId = ctx.schedule(due, TaskKind.AUTO_ACT, t.windowId());
            ctx.emit(new AutoActArmed(t.windowId(), taskId));
        }
    }

    static long autoDue(RuleConfig config, FlowFrame frame, long now) {
        return Math.addExact(Math.max(frame.window().opensAt(), now), config.timing().autoActDelayMs());
    }

    private static void disarm(DecisionContext<SessionState> ctx) {
        long id = game(ctx).turn().autoTaskId();
        if (id == 0) {
            return;
        }
        if (ctx.tasks().stream().anyMatch(task -> task.taskId() == id)) {
            ctx.cancel(id);
        }
        ctx.emit(new AutoActDisarmed(id));
    }

    // ================================================================ 定时任务

    static void onTask(DecisionContext<SessionState> ctx, ScheduledTask task) {
        if (!ctx.state().inGame()) {
            return;
        }
        GameState g = game(ctx);
        switch (task.kind()) {
            case GLOBAL_END -> {
                if (task.taskId() == g.clock().taskId()) {
                    onGlobalEnd(ctx);
                }
            }
            case AUTO_ACT -> {
                if (task.taskId() != g.turn().autoTaskId()) {
                    return;
                }
                ctx.emit(new AutoActDisarmed(task.taskId()));
                TurnState t = game(ctx).turn();
                Optional<FlowFrame> frame = game(ctx).flow().top().filter(f -> f.windowId() == t.windowId());
                if (t.windowId() == task.ref() && frame.isPresent() && frame.get().window().acceptsAt(ctx.now())
                        && game(ctx).player(t.currentPlayer()).orElseThrow().automated()) {
                    act(ctx, true, false);
                } else {
                    reconcileAuto(ctx);
                }
            }
            case TURN_WINDOW -> FlowCoordinator.expiredFrame(g.flow(), task)
                    .filter(f -> f.kind() == FlowKind.TURN && f.windowId() == g.turn().windowId())
                    .ifPresent(f -> act(ctx, true, true));
            default -> throw new IllegalStateException("task " + task + " is not owned by the turn module");
        }
    }

    /**
     * 全局到时（O16）：进入 DRAINING，取消并退还全部排队申请，不再启动新回合。当前玩家尚未投骰且没有覆盖流程时直接结算；
     * 已启动的覆盖流程与落点决策照常完成，之后在回合交界（或流程返回时）结算。
     */
    private static void onGlobalEnd(DecisionContext<SessionState> ctx) {
        ctx.emit(new DrainingStarted(ctx.now()));
        FlowCoordinator.cancelQueue(ctx, GameModule.FLOW);
        GameState g = game(ctx);
        // E9：全局到时后不允许银行抵押 / 赎回，开着的银行窗口立即结束（视为自动"结束银行"），落点随之结束
        if (g.turn().stage() == TurnStage.LANDING && g.turn().landing() != null
                && g.turn().landing().step() == com.millionnaire.engine.core.state.LandingStep.BANK
                && g.flow().frames().size() == 1) {
            closeDecision(ctx);
            ctx.emit(new GameEvent.BankFinished(g.turn().currentPlayer(), true));
            EconomyModule.finishLanding(ctx, 0);
            return;
        }
        if (g.turn().stage().beforeRoll() && g.flow().frames().size() == 1) {
            finish(ctx, "TIME_UP");
        }
    }

    /** 结算并回到大厅：取消全部窗口与任务，按净资产排名（M1 净资产 = 现金）。 */
    static void finish(DecisionContext<SessionState> ctx, String reason) {
        GameState g = game(ctx);
        terminate(ctx);
        ctx.emit(new GameEnded(g.gameNo(), reason, new GameResult(g.gameNo(), reason, standings(game(ctx), ctx.config()))));
    }

    /** 清理对局占用的窗口、排队申请与任务（结算或管理端中止）。 */
    static void terminate(DecisionContext<SessionState> ctx) {
        disarm(ctx);
        FlowCoordinator.cancelAll(ctx, GameModule.FLOW);
        FlowCoordinator.cancelQueue(ctx, GameModule.FLOW);
        long clockTask = game(ctx).clock().taskId();
        if (clockTask != 0 && ctx.tasks().stream().anyMatch(t -> t.taskId() == clockTask)) {
            ctx.cancel(clockTask);
        }
    }

    /**
     * 结算名次（requirements 第 16 节、O8、O21、规则补充 v1）：存活者按净资产降序、再比现金，完全相同并列（1、1、3）；
     * 淘汰者排在存活者之后，越晚出局越靠前（按淘汰序号降序）。若处理完最后一批（延后认输批次）后零存活，
     * 该批按批次前净资产降序排在最前，相同并列。淘汰者的结算净资产与现金记为 0（该批例外，记批次前净资产）。
     */
    static List<Standing> standings(GameState g, RuleConfig config) {
        List<Standing> alive = new ArrayList<>();
        for (PlayerState p : g.players()) {
            if (p.alive()) {
                alive.add(new Standing(p.playerId(), 0, EconomyModule.netWorth(config, g, p.playerId()),
                        g.ledger().cash(p.playerId())));
            }
        }
        alive.sort(Comparator.comparingLong(Standing::netWorth).reversed().thenComparing(
                Comparator.comparingLong(Standing::cash).reversed()));
        List<PlayerState> out = g.players().stream().filter(p -> !p.alive())
                .sorted(Comparator.comparingLong((PlayerState p) -> p.elimination().seq()).reversed()).toList();
        long lastBatch = EconomyModule.maxBatch(g);
        List<Standing> rows = new ArrayList<>(alive);
        List<Standing> finalBatch = new ArrayList<>();
        for (PlayerState p : out) {
            if (alive.isEmpty() && p.elimination().batch() == lastBatch) {
                finalBatch.add(new Standing(p.playerId(), 0, p.elimination().netWorthBefore(), 0));
            }
        }
        finalBatch.sort(Comparator.comparingLong(Standing::netWorth).reversed());
        rows.addAll(finalBatch);
        List<Standing> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Standing s = rows.get(i);
            int rank = i + 1;
            if (i > 0 && s.netWorth() == rows.get(i - 1).netWorth() && s.cash() == rows.get(i - 1).cash()) {
                rank = result.get(i - 1).rank();
            }
            result.add(new Standing(s.playerId(), rank, s.netWorth(), s.cash()));
        }
        for (PlayerState p : out) {
            if (!(alive.isEmpty() && p.elimination().batch() == lastBatch)) {
                result.add(new Standing(p.playerId(), result.size() + 1, 0, 0));
            }
        }
        return result;
    }

    // ================================================================ 演化（T3：核对规则派生值）

    static GameState evolve(GameState g, GameEvent event, Draws draws, RuleConfig rules) {
        TurnState t = g.turn();
        TurnTrack k = t.track();
        BoardTemplate board = LobbyModule.board(rules, g.settings());
        return switch (event) {
            case GameEvent.OrderRoundStarted e -> {
                List<String> expected = g.orderDraws().isEmpty() ? g.players().stream().map(PlayerState::playerId).toList()
                        : TurnOrder.tiedGroups(g.orderDraws()).stream().flatMap(List::stream).toList();
                check(t.turnNo() == 0 && k.drawQueue().isEmpty() && !expected.isEmpty() && e.drawers().equals(expected),
                        "order round must be all seats first, then exactly the tied groups");
                yield g.withTurn(t.withTrack(k.draws(e.drawers())));
            }
            case OrderNumberDrawn e -> {
                Draw d = draws.take(DrawPoint.ORDER_NUMBER);
                check(d.bound() == rules.economy().orderNumberMax() && d.value() + 1 == e.value(),
                        "order draw " + e.value() + " does not match " + d);
                check(!k.drawQueue().isEmpty() && k.drawQueue().get(0).equals(e.playerId()),
                        "order draw by " + e.playerId() + " out of turn");
                GameState next = g.withTurn(t.withTrack(k.draws(k.drawQueue().subList(1, k.drawQueue().size()))));
                List<OrderDraw> out = new ArrayList<>();
                boolean found = false;
                for (OrderDraw o : g.orderDraws()) {
                    if (o.playerId().equals(e.playerId())) {
                        out.add(new OrderDraw(o.playerId(), Immutable.append(o.draws(), e.value())));
                        found = true;
                    } else {
                        out.add(o);
                    }
                }
                if (!found) {
                    check(g.player(e.playerId()).isPresent(), "order draw for unknown player");
                    out.add(new OrderDraw(e.playerId(), List.of(e.value())));
                }
                yield next.withOrderDraws(out);
            }
            case TurnOrderFixed e -> {
                check(k.drawQueue().isEmpty() && e.order().equals(TurnOrder.order(g.orderDraws())),
                        "turn order does not follow the draws");
                yield g.withPlayers(e.order().stream().map(id -> g.player(id).orElseThrow()).toList());
            }
            case CardDealt e -> {
                Draw d = draws.take(DrawPoint.INITIAL_CARD);
                PlayerState p = g.player(e.recipient()).orElseThrow(() -> new IllegalStateException("deal to unknown player"));
                String expectedRecipient = g.players().stream()
                        .filter(x -> x.hand().size() < g.settings().dealCount(rules)).map(PlayerState::playerId)
                        .findFirst().orElse(null);
                check(e.recipient().equals(expectedRecipient), "cards are dealt in turn order, consecutively per player");
                check(d.bound() == CardDeck.totalWeight(rules) && CardDeck.pick(rules, d.value()) == e.card()
                        && p.hand().size() < rules.economy().handLimit() && t.turnNo() == 0, "dealt card does not match " + d);
                yield g.withPlayer(p.withHand(Immutable.append(p.hand(), e.card())));
            }
            case CardsDealt e -> {
                check(t.turnNo() == 0 && g.player(e.playerId()).orElseThrow().hand().size() == e.handCount()
                        && e.handCount() == g.settings().dealCount(rules), "hand count mismatch");
                yield g;
            }
            case GameClockStarted e -> {
                check(e.endsAt() == Math.addExact(g.startedAt(), gameDurationMs(rules, g)), "clock end does not follow the settings");
                yield g.withClock(new GameClock(e.endsAt(), e.taskId()));
            }
            case DrainingStarted e -> {
                check(g.phase() == GamePhase.RUNNING && e.at() == g.clock().endsAt(), "draining must start at the global end");
                yield g.withPhase(GamePhase.DRAINING).withClock(new GameClock(g.clock().endsAt(), 0));
            }
            case TurnStarted e -> {
                check(e.turnNo() == t.turnNo() + 1 && t.stage() == TurnStage.NONE && k.equals(TurnTrack.NONE)
                        && nextAlive(g).map(e.playerId()::equals).orElse(false), "turn sequence broken");
                // 每个自己的回合开始恢复一次主动用卡机会
                // 行动顺序绕回（下一位不在当前玩家之后）记新一轮；第一个回合是第 1 轮
                long round = t.currentPlayer() == null || indexOf(g, e.playerId()) <= indexOf(g, t.currentPlayer()) ? t.round() + 1 : t.round();
                int rentPercent = rules.rentInflation().percent(g.settings().endMode(), round);
                yield g.withTurn(t.next(e.turnNo(), e.playerId(), round, rentPercent).withTrack(TurnTrack.NONE.safePoint(1)))
                        .withCards(g.cards().turnStarted(e.playerId()));
            }
            case TurnStageEntered e -> {
                check(!(e.continuation() instanceof Continuation.BeginTurn) || t.stage() == TurnStage.NONE,
                        "BeginTurn can only start at the turn boundary");
                check(k.safePointPhase() != 1, "turn stage started before its safe point");
                check(e.stage() != TurnStage.NONE && Continuation.allowedFor(e.stage(), e.continuation(), t)
                        && (e.stage() == TurnStage.AWAITING_FLOW ? e.notBefore() >= 0 : e.notBefore() == 0),
                        "continuation " + e.continuation() + " / notBefore not allowed for " + e.stage());
                check(e.stage() != TurnStage.JAIL_DECISION || g.player(t.currentPlayer()).orElseThrow().inJail(),
                        "jail decision outside jail");
                yield g.withTurn(t.withStage(e.stage(), e.windowId(), e.continuation(), e.notBefore()).withTrack(k.safePoint(0)));
            }
            case TurnEnded e -> {
                check(e.turnNo() == t.turnNo() && t.autoTaskId() == 0 && k.equals(TurnTrack.NONE) && t.landing() == null
                        && g.debt() == null && e.playerId().equals(t.currentPlayer()), "turn end mismatch");
                yield g.withTurn(t.ended().withAllAwayTurns(allAway(g) ? Math.addExact(t.allAwayTurns(), 1) : 0));
            }
            case DiceRolled e -> {
                Draw d = draws.take(DrawPoint.MOVE_DIE);
                check(d.bound() == rules.economy().dieFaces() && d.value() + 1 == e.value() && current(g, e.playerId())
                        && k.pendingDie() == 0 && k.pendingLanding() < 0 && !g.player(e.playerId()).orElseThrow().inJail(),
                        "die " + e.value() + " does not match " + d);
                yield g.withTurn(t.withTrack(k.die(e.value())));
            }
            case GameEvent.MovementEffectCommitted e -> MovementEffects.commit(g, e, rules);
            case GameEvent.RoadblockPlaced e -> MovementEffects.place(g, e);
            case GameEvent.RoadblockTriggered e -> MovementEffects.trigger(g, e);
            case MoveChainStarted e -> {
                check(t.chain() == null && t.landing() == null && t.stage() == TurnStage.PRE_ROLL
                        && g.flow().frames().isEmpty() && current(g, e.playerId()) && e.turnNo() == t.turnNo()
                        && e.chainId() == Math.addExact(t.lastChainId(), 1)
                        && (k.pendingDie() > 0 && k.movementEffect() == null || k.pendingDie() == 0 && k.movementEffect() != null
                            && k.movementEffect().kind() == com.millionnaire.engine.core.state.MovementEffect.Kind.TARGETED)
                        && e.origin() == g.player(e.playerId()).orElseThrow().position(), "move chain source mismatch");
                yield g.withTurn(t.withChain(new MoveChain(e.chainId(), e.turnNo(), e.playerId(), e.origin(), false,
                        t.startRewardGiven(), List.of(), 0, List.of(new com.millionnaire.engine.core.state.MovePlan(
                            k.movementEffect() == null ? MoveKind.DICE : MoveKind.TARGETED,
                            k.movementEffect() == null ? k.pendingDie() : k.movementEffect().distance(), 0, -1)))));
            }
            case PlayerMoved e -> {
                PlayerState p = g.player(e.playerId()).orElseThrow();
                MoveChain chain = t.chain();
                check(current(g, e.playerId()) && chain != null && e.chainId() == chain.chainId()
                        && p.position() == e.from() && t.landing() == null && g.flow().frames().isEmpty(),
                        "move does not follow the committed source/chain");
                int planned;
                var prior = chain.segments().isEmpty() ? null : chain.segments().getLast();
                boolean continuing = prior != null && MovementRules.remainingAtStart(prior) > 0
                        && chain.plans().size() == chain.segments().size() + 1
                        && chain.plans().getLast().cursor() == 0;
                if (continuing) {
                    var authority = chain.plans().getLast();
                    check(rules.startPick().enabled() && e.from() == 0 && e.kind() == prior.kind()
                            && e.segmentNo() == chain.segments().size() + 1
                            && authority.landingId() == t.lastLandingId()
                            && authority.distance() == MovementRules.remainingAtStart(prior), "start continuation source mismatch");
                    planned = authority.distance();
                } else if (e.kind() == MoveKind.DICE) {
                    check(chain.segments().isEmpty() && e.segmentNo() == 1 && k.pendingDie() > 0 && k.movementEffect() == null,
                            "dice movement source mismatch");
                    planned = k.pendingDie();
                } else if (e.kind() == MoveKind.TARGETED) {
                    var source = k.movementEffect();
                    check(source != null && source.kind() == com.millionnaire.engine.core.state.MovementEffect.Kind.TARGETED
                            && source.playerId().equals(e.playerId()) && source.turnNo() == t.turnNo() && source.from() == e.from()
                            && k.pendingDie() == 0 && chain.segments().isEmpty() && e.segmentNo() == 1,
                            "targeted movement source mismatch");
                    planned = source.distance();
                } else {
                    check(k.eventMove() != null && k.eventMove().playerId().equals(e.playerId())
                            && k.eventMove().landingId() == t.lastLandingId() && k.eventMove().cursor() == 1
                            && e.kind() == k.eventMove().kind() && chain.eventDrawn()
                            && e.segmentNo() == chain.segments().size() + 1, "event movement source mismatch");
                    planned = k.eventMove().distance();
                }
                check(e.plannedDistance() == planned, "movement planned distance mismatch");
                MoveSegment plan = e.kind() == MoveKind.TO_JAIL
                        ? MovementRules.jailJump(e.segmentNo(), e.from(), jailIndex(board), board.size())
                        : MovementRules.segment(e.segmentNo(), e.kind(), e.from(), planned, board.size());
                MoveSegment segment = MovementRules.resolve(plan, g.board(), e.playerId(), board.size(), rules.startPick().enabled());
                check(e.steps() == segment.distance(), "move does not follow the committed source/chain: actual distance mismatch");
                check(e.to() == segment.to(), "move endpoint mismatch");
                check(java.util.Objects.equals(e.stoppedBy(), segment.stoppedBy()), "movement stop source mismatch");
                boolean reward = chain.canReward(segment) && !t.startRewardGiven();
                yield g.withPlayer(p.at(e.to())).withTurn(t.withChain(chain.append(segment)).withTrack(k.moved(e.to(), reward, segment.stoppedBy())));
            }
            case StartRewardPaid e -> {
                check(k.rewardDue() && t.chain() != null && !t.chain().startRewardGiven() && !t.startRewardGiven() && current(g, e.playerId()) && e.amount() == rules.economy().startReward()
                        && e.turnNo() == t.turnNo(), "start reward not due or wrong amount");
                Ledger l = g.ledger().transfer(Ledger.SYSTEM, e.playerId(), e.amount(), START_REWARD, "turn-" + e.turnNo());
                yield g.withLedger(l).withTurn(t.rewardGiven().withChain(t.chain().rewarded()).withTrack(k.rewarded()));
            }
            case Landed e -> {
                TileType type = board.tiles().get(e.tile()).type();
                check(current(g, e.playerId()) && t.chain() != null && k.pendingLanding() == e.tile() && !k.rewardDue()
                        && g.player(e.playerId()).orElseThrow().position() == e.tile() && e.type() == type
                        && e.placeholder() == placeholder(type), "landing mismatch");
                yield g.withTurn(t.withTrack(k.landed(type == TileType.JAIL, type == TileType.JAIL ? -1 : e.tile())));
            }
            case PlayerJailed e -> {
                PlayerState p = g.player(e.playerId()).orElseThrow();
                check(k.jailLanding() && current(g, e.playerId()) && p.position() == jailIndex(board), "jailed without landing on jail");
                yield g.withPlayer(p.jail(true, 0)).withTurn(t.withTrack(k.jailed()));
            }
            case JailRolled e -> {
                Draw d = draws.take(DrawPoint.JAIL_DIE);
                check(d.bound() == rules.economy().dieFaces() && d.value() + 1 == e.value() && current(g, e.playerId())
                        && g.player(e.playerId()).orElseThrow().inJail() && k.jailRoll() == 0,
                        "jail roll " + e.value() + " does not match " + d);
                yield g.withTurn(t.withTrack(k.judged(e.value())));
            }
            case JailFailed e -> {
                PlayerState p = g.player(e.playerId()).orElseThrow();
                check(current(g, e.playerId()) && t.stage() == TurnStage.JAIL_DECISION && k.jailRoll() % 2 == 1 && p.inJail()
                        && e.failures() == p.jailFailures() + 1 && e.failures() < 3, "jail failure does not follow the roll");
                yield g.withPlayer(p.jail(true, e.failures())).withTurn(t.withTrack(k.judged(0)));
            }
            case BailPaid e -> {
                check(g.player(e.playerId()).orElseThrow().inJail() && current(g, e.playerId())
                        && e.amount() == rules.economy().bailCost() && !k.bailPaid() && k.jailRoll() == 0, "bail mismatch");
                Ledger l = g.ledger().transfer(e.playerId(), Ledger.SYSTEM, e.amount(), BAIL, "turn-" + t.turnNo());
                yield g.withLedger(l).withTurn(t.withTrack(k.bail(true)));
            }
            case JailReleased e -> {
                PlayerState p = g.player(e.playerId()).orElseThrow();
                boolean ok = switch (e.reason()) {
                    case EVEN_ROLL -> k.jailRoll() != 0 && k.jailRoll() % 2 == 0;
                    case THIRD_FAILURE -> k.jailRoll() % 2 == 1 && p.jailFailures() == 2;
                    case BAIL -> k.bailPaid();
                    case CARD -> g.cards().effect() != null && g.cards().effect().card() == com.millionnaire.engine.config.CardType.JAIL_RELEASE
                            && g.cards().effect().actor().equals(e.playerId()) && k.jailRoll() == 0 && !k.bailPaid();
                };
                check(ok && p.inJail() && current(g, e.playerId()), "release reason " + e.reason() + " does not follow the track");
                GameState released = g.withPlayer(p.jail(false, 0)).withTurn(t.withTrack(TurnTrack.NONE));
                yield e.reason() == ReleaseReason.CARD ? released.withCards(g.cards().effect(null)) : released;
            }
            case ControlChanged e -> {
                GameState changed = g.withPlayer(g.player(e.playerId()).orElseThrow().control(e.mode()));
                yield allAway(changed) ? changed : changed.withTurn(t.withAllAwayTurns(0));
            }
            case ConnectionChanged e -> {
                PlayerState p = g.player(e.playerId()).orElseThrow();
                check(e.observation() > p.connObservation() && legalTransition(p.conn(), e.conn()), "illegal connection change");
                yield g.withPlayer(p.conn(e.conn(), e.observation()));
            }
            case GameEvent.AutoActArmed e -> {
                check(t.autoTaskId() == 0 && t.windowId() == e.windowId(), "auto task mismatch");
                yield g.withTurn(t.withAutoTask(e.taskId()));
            }
            case AutoActDisarmed e -> {
                check(t.autoTaskId() == e.taskId(), "auto task mismatch");
                yield g.withTurn(t.withAutoTask(0));
            }
            default -> {
                if (EventModule.handles(event)) { yield EventModule.evolve(g, event, draws, rules); }
                if (MinigameModule.handles(event)) { yield MinigameModule.evolve(g, event, draws, rules); }
                if (CardModule.handles(event)) { yield CardModule.evolve(g, event, rules); }
                if (AuctionModule.handles(event)) { yield AuctionModule.evolve(g, event, rules); }
                if (TradeModule.handles(event)) { yield TradeModule.evolve(g, event, rules); }
                if (EconomyModule.handles(event)) {
                    yield EconomyModule.evolve(g, event, rules);
                }
                throw new IllegalStateException("not a turn event: " + event);
            }
        };
    }

    private static boolean current(GameState g, String playerId) {
        return playerId.equals(g.turn().currentPlayer());
    }

    private static void check(boolean condition, String message) {
        LobbyModule.check(condition, message);
    }

    // ================================================================ 校验

    /**
     * 回合模块校验（固定顺序：定序 → 账本 → 手牌 → 监狱 → 全局时钟与阶段 → 回合与窗口绑定 → 自动任务），返回认领的任务。
     * 账本每次都完整重放（T2：个人余额必须与历史一致，不能只看守恒总额）。
     */
    static List<FlowCoordinator.TaskClaim> validate(EngineState engine, GameState g, RuleConfig config, boolean full) {
        List<FlowCoordinator.TaskClaim> claims = new ArrayList<>();
        LobbyModule.expect(g.orderDraws().size() == g.players().size()
                && g.orderDraws().stream().allMatch(o -> o != null && g.player(o.playerId()).isPresent() && !o.draws().isEmpty()
                        && o.draws().stream().allMatch(v -> v != null && v >= 1 && v <= config.economy().orderNumberMax())),
                "order draws invalid");
        LobbyModule.expect(TurnOrder.tiedGroups(g.orderDraws()).isEmpty()
                && TurnOrder.order(g.orderDraws()).equals(g.players().stream().map(PlayerState::playerId).toList()),
                "player order must follow the order draws");
        Ledger l = g.ledger();
        TreeSet<String> ids = new TreeSet<>(g.players().stream().map(PlayerState::playerId).toList());
        LobbyModule.expect(l != null && l.cash() != null && l.opening() != null && l.cash().keySet().equals(ids),
                "ledger accounts must be the players");
        for (Map.Entry<String, Long> e : new TreeMap<>(l.opening()).entrySet()) {
            LobbyModule.expect(e.getValue() != null && e.getValue() == g.settings().initialCash(),
                    "opening balance of " + e.getKey() + " must be the initial cash");
        }
        EconomyModule.validate(engine, g, config, full);
        for (PlayerState p : g.players()) {
            LobbyModule.expect(p.life() != null && p.control() != null && p.conn() != null && p.connObservation() >= 0,
                    "player fields missing");
            boolean discarding = g.turn().landing() != null && g.turn().landing().step() == com.millionnaire.engine.core.state.LandingStep.DISCARD
                    && p.playerId().equals(g.turn().currentPlayer());
            LobbyModule.expect(p.hand().size() <= config.economy().handLimit() + (discarding ? 1 : 0), "hand over the limit for " + p.playerId());
        }
        int jail = jailIndex(LobbyModule.board(config, g.settings()));
        for (PlayerState p : g.players()) {
            LobbyModule.expect(p.inJail() ? p.position() == jail && p.jailFailures() >= 0 && p.jailFailures() <= 2
                    : p.jailFailures() == 0, "jail state invalid for " + p.playerId());
        }
        GameClock c = g.clock();
        LobbyModule.expect(c != null && c.endsAt() == Math.addExact(g.startedAt(), gameDurationMs(config, g)), "clock mismatch");
        TurnState t = g.turn();
        LobbyModule.expect(t != null && t.allAwayTurns() >= 0 && t.allAwayTurns() <= t.turnNo(), "all-away turn count invalid");
        LobbyModule.expect(t != null && t.turnNo() >= 1 && t.currentPlayer() != null && t.stage() != TurnStage.NONE
                && t.track().equals(TurnTrack.NONE), "a running game is always inside a turn stage between steps");
        if (g.phase() == GamePhase.RUNNING) {
            ScheduledTask clockTask = engine.timers().find(c.taskId()).orElse(null);
            LobbyModule.expect(clockTask != null && clockTask.kind() == TaskKind.GLOBAL_END && clockTask.ref() == g.gameNo()
                    && clockTask.dueAt() == c.endsAt(), "global end task mismatch");
            claims.add(new FlowCoordinator.TaskClaim(TaskKind.GLOBAL_END, g.gameNo()));
        } else {
            // DRAINING 只能停留在已获准的收尾中：落点决策、等待覆盖流程返回，或覆盖流程仍在进行
            LobbyModule.expect(c.taskId() == 0 && g.flow().queue().isEmpty()
                    && (!t.stage().beforeRoll() || g.flow().frames().size() > 1), "draining without pending settlement");
        }
        validateChain(g, config);
        MovementEffects.validate(g, config);
        PlayerState cur = g.player(t.currentPlayer()).orElse(null);
        // 当前玩家只有在他人流程进行中认输时才可能已被淘汰（回合在流程返回后结束，E2 / 待确认默认 5）
        LobbyModule.expect(cur != null && (cur.life() == LifeState.ALIVE || EconomyModule.flowsRunning(g)),
                "current player must be alive");
        LobbyModule.expect(!cur.alive() || (t.stage() == TurnStage.JAIL_DECISION) == (cur.inJail() && t.stage().beforeRoll()),
                "stage must match jail state");
        LobbyModule.expect(Continuation.allowedFor(t.stage(), t.continuation(), t),
                "continuation " + t.continuation() + " not allowed for stage " + t.stage());
        LobbyModule.expect(t.stage() == TurnStage.AWAITING_FLOW ? t.notBefore() >= 0 : t.notBefore() == 0,
                "notBefore only for a turn awaiting a flow");
        FlowFrame bottom = g.flow().frames().isEmpty() ? null : g.flow().frames().get(0);
        if (t.stage().windowed()) {
            LobbyModule.expect(bottom != null && bottom.kind() == FlowKind.TURN && bottom.windowId() == t.windowId()
                    && t.currentPlayer().equals(bottom.owner()) && t.stage().name().equals(bottom.resumeTag()),
                    "turn must be bound to the bottom TURN window");
        } else {
            LobbyModule.expect(t.windowId() == 0 && bottom != null
                    && g.flow().frames().stream().noneMatch(f -> f.kind() == FlowKind.TURN),
                    "a turn awaiting a flow has no TURN window but at least one running flow");
            LobbyModule.expect(bottom.window().paused() || bottom.window().opensAt() >= t.notBefore(),
                    "the flow opened before the pending animation ended");
        }
        FlowFrame top = g.flow().top().orElse(null);
        boolean turnWindowRunning = t.stage().windowed() && top == bottom && !bottom.window().paused();
        if (t.autoTaskId() != 0) {
            ScheduledTask auto = engine.timers().find(t.autoTaskId()).orElse(null);
            LobbyModule.expect(auto != null && auto.kind() == TaskKind.AUTO_ACT && auto.ref() == t.windowId()
                    && turnWindowRunning && cur.automated()
                    && auto.dueAt() >= bottom.window().opensAt() + config.timing().autoActDelayMs()
                    && auto.dueAt() < bottom.window().deadline(), "auto task inconsistent");
            claims.add(new FlowCoordinator.TaskClaim(TaskKind.AUTO_ACT, t.windowId()));
        } else if (turnWindowRunning && cur.automated()) {
            LobbyModule.expect(autoDue(config, bottom, engine.now()) >= bottom.window().deadline(),
                    "auto task missing for an automated player");
        }
        return claims;
    }

    /** 链状态是持久领域状态；快照必须与当前位置、任务落点、逐格路径及回合奖励一致。 */
    private static void validateChain(GameState g, RuleConfig config) {
        TurnState t = g.turn();
        LobbyModule.expect(t.lastChainId() >= 0 && t.lastChainId() < Long.MAX_VALUE
                && t.lastDebtId() >= 0 && t.lastDebtId() < Long.MAX_VALUE, "movement/debt counters invalid");
        MoveChain c = t.chain();
        if (c == null) {
            LobbyModule.expect(t.landing() == null && !t.startRewardGiven(), "landing or reward without a chain");
            return;
        }
        int size = LobbyModule.board(config, g.settings()).size();
        LobbyModule.expect(c.chainId() == t.lastChainId() && c.chainId() >= 1 && c.turnNo() == t.turnNo()
                && t.currentPlayer().equals(c.playerId()) && c.origin() >= 0 && c.origin() < size
                && c.startRewardGiven() == t.startRewardGiven()
                && c.segments() != null && c.segments().size() >= 1 && c.segments().size() <= 4 && !t.stage().beforeRoll(), "move chain invalid");
        MoveSegment segment = c.segments().get(0);
        LobbyModule.expect(segment != null && (segment.kind() == MoveKind.DICE || segment.kind() == MoveKind.TARGETED) && segment.number() == 1
                && segment.from() == c.origin() && segment.plannedDistance() >= 1 && segment.plannedDistance() <= config.economy().dieFaces()
                && MovementRules.valid(c, size, config.startPick().enabled())
                && c.plans().getFirst().landingId() == 0 && c.plans().getFirst().cursor() == -1
                && c.segments().getLast().to() == g.player(c.playerId()).orElseThrow().position(), "move chain segment/flags invalid");
        int eventMoves = 0;
        for (int i = 1; i < c.segments().size(); i++) {
            var previous = c.segments().get(i - 1);
            var second = c.segments().get(i);
            var authority = c.plans().get(i);
            if (authority.cursor() == 0) {
                LobbyModule.expect(config.startPick().enabled() && MovementRules.remainingAtStart(previous) > 0
                        && second.from() == 0 && second.kind() == previous.kind()
                        && second.plannedDistance() == MovementRules.remainingAtStart(previous)
                        && authority.landingId() >= 1 && authority.landingId() < t.lastLandingId(), "start continuation invalid");
                continue;
            }
            eventMoves++;
            TileType from = LobbyModule.board(config, g.settings()).tiles().get(previous.to()).type();
            // 事件位移：抽卡事件的前进 / 后退（距离在事件区间内），或 2026-10-08 的去车站 / 回起点（前进，距离不足一圈）
            boolean drawnMove = second.plannedDistance() >= config.economy().eventMoveMinSteps()
                    && second.plannedDistance() <= config.economy().eventMoveMaxSteps();
            boolean jump = second.kind() == MoveKind.EVENT_FORWARD && second.plannedDistance() >= 1 && second.plannedDistance() < size;
            LobbyModule.expect(c.eventDrawn() && (from == TileType.EVENT || from == TileType.FIXED_EVENT || from == TileType.UNLUCKY_EVENT)
                    && (second.kind() == MoveKind.EVENT_FORWARD || second.kind() == MoveKind.EVENT_BACKWARD)
                    && (drawnMove || jump), "event movement segment invalid");
            LobbyModule.expect(authority.landingId() >= 1 && authority.landingId() < t.lastLandingId()
                    && authority.cursor() == 1, "event movement plan source invalid");
        }
        LobbyModule.expect(eventMoves <= 1, "repeated event movement");
        if (eventMoves == 0) {
            LobbyModule.expect(c.eventDrawn() == (t.landing() != null && t.landing().event() != null
                    && t.landing().tasks().getFirst() != com.millionnaire.engine.core.state.LandingStep.START_PICK), "move chain invalid");
        }
    }

    static int jailIndex(BoardTemplate board) {
        return board.tiles().stream().filter(x -> x.type() == TileType.JAIL).findFirst().orElseThrow().index();
    }

    // ================================================================ helpers

    private static GameState game(DecisionContext<SessionState> ctx) {
        return ctx.state().game();
    }

    private static BoardTemplate board(DecisionContext<SessionState> ctx) {
        return LobbyModule.board(ctx.config(), game(ctx).settings());
    }

    private static long rollMs(DecisionContext<SessionState> ctx) {
        return Math.multiplyExact((long) game(ctx).settings().rollSeconds(), 1000L);
    }
}
