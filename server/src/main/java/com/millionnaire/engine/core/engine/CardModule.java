package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.BoardTemplate;
import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.Pricing;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.Tile;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.AttackBlocked;
import com.millionnaire.engine.core.event.GameEvent.CardUsed;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.GameEvent.PropertyBuilt;
import com.millionnaire.engine.core.event.GameEvent.PropertyCleared;
import com.millionnaire.engine.core.event.GameEvent.PropertyDemolished;
import com.millionnaire.engine.core.event.GameEvent.PropertyDowngraded;
import com.millionnaire.engine.core.event.GameEvent.PropertyForceBought;
import com.millionnaire.engine.core.event.GameEvent.QueryRevealed;
import com.millionnaire.engine.core.event.GameEvent.RentWaived;
import com.millionnaire.engine.core.event.GameEvent.RentWaiverDeclined;
import com.millionnaire.engine.core.event.GameEvent.ResponseDeclined;
import com.millionnaire.engine.core.event.GameEvent.ResponseOffered;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.CardState;
import com.millionnaire.engine.core.state.Continuation;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowOrigin;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.GameView;
import com.millionnaire.engine.core.state.LandingResult;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.MovementEffect;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import java.util.ArrayList;
import java.util.List;

/**
 * 道具（requirements 第 14 节；已采纳默认值 #9 卡牌窗口、#10 拆楼 / 清地、#14 路障、#17 托管；O4 响应窗；O9 出狱卡）：
 * <ul>
 *   <li><b>主动卡</b>只在自己回合的投骰前（含狱中判定，仅出狱与查询）或落点结算后的用卡阶段使用，两阶段共一次；
 *       每个自己的回合开始恢复一次机会；全局到时后不能再用（O16）；托管 / 暂离 / 掉线不主动用卡。
 *       未确认或条件不满足不消耗；生效消耗；被响应抵挡时双方的卡都消耗。</li>
 *   <li><b>落点后用卡阶段</b>：落点结算完、回合结束前，若手动玩家还有机会且手里有此刻可用的卡，开 15 秒窗口（可直接结束回合）；
 *       用掉一张卡后回合随即结束，超时也结束回合。</li>
 *   <li><b>响应卡</b>（免租、房屋保护、拒绝购买）只在持卡时弹 10 秒窗口，可提前选"不使用"，超时不使用；
 *       托管、确认掉线、暂离的持卡者立即自动使用。响应先于付款与现金不足判定。不占主动机会。</li>
 *   <li>目标格一律是使用者当前位置；降级 / 拆楼 / 清地只对他人未抵押普通地产，强购对他人未抵押地产或车站（价格 = 标准价值 × 1.5 向下取整，
 *       现金须足额，款归原主，等级保留）；建造对自己未抵押、未满级、不是本回合刚买下的普通地产。</li>
 * </ul>
 * 拍卖卡与交易卡走申请队列（另行接入），这里一律拒绝。
 */
final class CardModule {
    private CardModule() {
    }

    static final List<CardType> RESPONSE_CARDS = List.of(CardType.RENT_WAIVER, CardType.REFUSE_PURCHASE, CardType.HOUSE_PROTECTION);

    static boolean enabled(RuleConfig config) {
        return config.economy().cardsEnabled();
    }

    static boolean holds(GameState g, String player, CardType card) {
        return g.player(player).map(p -> p.hand().contains(card)).orElse(false);
    }

    // ================================================================ 条件（决策与演化共用）

    /** 攻击卡对应的响应卡。 */
    static CardType responseTo(CardType attack) {
        return switch (attack) {
            case DOWNGRADE, DEMOLISH, CLEAR_LAND -> CardType.HOUSE_PROTECTION;
            case FORCED_PURCHASE -> CardType.REFUSE_PURCHASE;
            default -> null;
        };
    }

    /**
     * 地产类卡对当前位置是否成立（不看阶段与机会）：返回不成立的原因，成立为 null。
     */
    static RejectionCode tileCondition(RuleConfig config, GameState g, String player, CardType card, int tileIndex) {
        BoardTemplate board = LobbyModule.board(config, g.settings());
        Tile tile = board.tiles().get(tileIndex);
        OwnableState o = g.board().ownable(tileIndex).orElse(null);
        if (o == null || o.owner() == null || o.lockedBy() != null) {
            return RejectionCode.NO_TARGET;
        }
        boolean property = tile.type() == TileType.PROPERTY;
        boolean mine = player.equals(o.owner());
        return switch (card) {
            case BUILD -> mine && property && !o.mortgaged() && o.level() < config.economy().maxLevel()
                    && g.cards().boughtTile() != tileIndex ? null : RejectionCode.NO_TARGET;
            case DOWNGRADE, DEMOLISH -> !mine && property && !o.mortgaged() && o.level() >= 1 && ownerAlive(g, o)
                    ? null : RejectionCode.NO_TARGET;
            case CLEAR_LAND -> !mine && property && !o.mortgaged() && ownerAlive(g, o) ? null : RejectionCode.NO_TARGET;
            case FORCED_PURCHASE -> {
                if (mine || o.mortgaged() || !ownerAlive(g, o) || !(property || tile.type() == TileType.STATION)) {
                    yield RejectionCode.NO_TARGET;
                }
                yield g.ledger().available(player) >= forcedPrice(config, g, tileIndex) ? null : RejectionCode.INSUFFICIENT_CASH;
            }
            default -> RejectionCode.NOT_ALLOWED;
        };
    }

    private static boolean ownerAlive(GameState g, OwnableState o) {
        return g.player(o.owner()).map(PlayerState::alive).orElse(false);
    }

    static long forcedPrice(RuleConfig config, GameState g, int tileIndex) {
        OwnableState o = g.board().ownable(tileIndex).orElseThrow();
        Tile tile = LobbyModule.board(config, g.settings()).tiles().get(tileIndex);
        return new Pricing(config).forcedPurchasePrice(EconomyModule.standardValue(config, tile, o.level()));
    }

    /** 查询对象：其他存活玩家。 */
    static RejectionCode queryTarget(GameState g, String player, String target) {
        if (target == null || target.equals(player)) {
            return RejectionCode.INVALID_ARGUMENT;
        }
        return g.player(target).map(PlayerState::alive).orElse(false) ? null : RejectionCode.NO_TARGET;
    }

    /** 主动卡在此刻（自己回合的投骰前 / 狱中判定 / 落点后阶段）是否有可用的目标，供落点后阶段决定是否开窗口。 */
    static boolean usable(RuleConfig config, GameState g, String player, CardType card, boolean postLanding) {
        PlayerState p = g.player(player).orElseThrow();
        int pos = p.position();
        BoardTemplate board = LobbyModule.board(config, g.settings());
        return switch (card) {
            case ROADBLOCK -> !p.inJail() && board.tiles().get(pos).type() != TileType.JAIL && g.board().roadblock(pos).isEmpty();
            case FIXED_MOVE -> !postLanding && !p.inJail();
            case JAIL_RELEASE -> !postLanding && p.inJail();
            case QUERY -> g.players().stream().anyMatch(x -> x.alive() && !x.playerId().equals(player));
            case BUILD, DOWNGRADE, DEMOLISH, CLEAR_LAND, FORCED_PURCHASE -> !p.inJail() && tileCondition(config, g, player, card, pos) == null;
            default -> false;
        };
    }

    /** 落点结算完毕时是否开"落点后用卡阶段"。 */
    static boolean offerPostLanding(RuleConfig config, GameState g) {
        String player = g.turn().currentPlayer();
        PlayerState p = g.player(player).orElse(null);
        if (!enabled(config) || p == null || !p.alive() || p.automated() || p.inJail() || g.phase() != GamePhase.RUNNING
                || g.cards().used(player) || g.debt() != null || g.minigame() != null || !g.flow().frames().isEmpty()) {
            return false;
        }
        return p.hand().stream().distinct().anyMatch(c -> usable(config, g, player, c, true));
    }

    /** 当前回合窗口是否为落点后用卡阶段（LANDING、没有落点、续接为结束回合）。 */
    static boolean postLandingWindow(GameState g) {
        var t = g.turn();
        return t.stage() == TurnStage.LANDING && t.landing() == null && t.continuation() instanceof Continuation.EndTurn;
    }

    // ================================================================ 命令

    static RejectionCode decide(DecisionContext<SessionState> ctx, GameCommand command) {
        return switch (command) {
            case GameCommand.UseCard c -> useCard(ctx, c);
            case GameCommand.RespondCard c -> respond(ctx, c);
            case GameCommand.FinishTurn c -> finishTurn(ctx, c);
            default -> throw new IllegalArgumentException("not a card command: " + command);
        };
    }

    private static RejectionCode finishTurn(DecisionContext<SessionState> ctx, GameCommand.FinishTurn c) {
        RejectionCode why = TurnModule.checkTurnWindow(ctx, c.actor(), c.windowId());
        if (why != null) {
            return why;
        }
        GameState g = ctx.state().game();
        if (!postLandingWindow(g)) {
            return RejectionCode.WRONG_STAGE;
        }
        TurnModule.closeDecision(ctx);
        TurnModule.endTurn(ctx, 0);
        return null;
    }

    private static RejectionCode useCard(DecisionContext<SessionState> ctx, GameCommand.UseCard c) {
        GameState g = ctx.state().game();
        if (!enabled(ctx.config())) {
            return RejectionCode.NOT_AVAILABLE;
        }
        RejectionCode why = TurnModule.checkTurnWindow(ctx, c.actor(), c.windowId());
        if (why != null) {
            return why;
        }
        if (!StageTable.rule(StageTable.turnPoint(g)).allows(c)) {
            return RejectionCode.WRONG_STAGE;
        }
        if (g.phase() != GamePhase.RUNNING) {
            return RejectionCode.DRAINING;
        }
        if (c.card() == null) {
            return RejectionCode.INVALID_ARGUMENT;
        }
        if (c.card() == CardType.AUCTION || c.card() == CardType.TRADE) {
            return RejectionCode.NOT_AVAILABLE;
        }
        if (RESPONSE_CARDS.contains(c.card())) {
            return RejectionCode.NOT_ALLOWED;
        }
        String actor = c.actor();
        if (!holds(g, actor, c.card())) {
            return RejectionCode.NO_CARD;
        }
        if (g.cards().used(actor)) {
            return RejectionCode.CARD_USED;
        }
        boolean post = postLandingWindow(g);
        boolean jail = g.turn().stage() == TurnStage.JAIL_DECISION;
        PlayerState p = g.player(actor).orElseThrow();
        int pos = p.position();
        switch (c.card()) {
            case ROADBLOCK -> {
                why = MovementEffects.rejection(g, ctx.config(), ctx.now(), actor, c.windowId(), MovementEffect.Kind.ROADBLOCK, 0);
                if (why != null) {
                    return why;
                }
                ctx.emit(new CardUsed(actor, CardType.ROADBLOCK, pos, null, true));
                MovementEffects.placeRoadblock(ctx, actor, c.windowId());
                afterActive(ctx, post);
            }
            case FIXED_MOVE -> {
                why = MovementEffects.rejection(g, ctx.config(), ctx.now(), actor, c.windowId(), MovementEffect.Kind.TARGETED, c.steps());
                if (why != null) {
                    return why;
                }
                ctx.emit(new CardUsed(actor, CardType.FIXED_MOVE, -1, null, true));
                MovementEffects.targeted(ctx, actor, c.windowId(), c.steps());
            }
            case JAIL_RELEASE -> {
                if (!jail || !p.inJail()) {
                    return RejectionCode.WRONG_STAGE;
                }
                ctx.emit(new CardUsed(actor, CardType.JAIL_RELEASE, -1, null, true));
                TurnModule.releaseWithCard(ctx);
            }
            case QUERY -> {
                why = queryTarget(g, actor, c.target());
                if (why != null) {
                    return why;
                }
                ctx.emit(new CardUsed(actor, CardType.QUERY, -1, c.target(), true));
                ctx.emit(new QueryRevealed(actor, c.target(), ctx.state().game().player(c.target()).orElseThrow().hand()));
                afterActive(ctx, post);
            }
            case BUILD -> {
                if (jail) {
                    return RejectionCode.WRONG_STAGE;
                }
                why = tileCondition(ctx.config(), g, actor, CardType.BUILD, pos);
                if (why != null) {
                    return why;
                }
                ctx.emit(new CardUsed(actor, CardType.BUILD, pos, actor, true));
                ctx.emit(new PropertyBuilt(actor, pos, g.board().ownable(pos).orElseThrow().level() + 1));
                afterActive(ctx, post);
            }
            case DOWNGRADE, DEMOLISH, CLEAR_LAND, FORCED_PURCHASE -> {
                if (jail) {
                    return RejectionCode.WRONG_STAGE;
                }
                why = tileCondition(ctx.config(), g, actor, c.card(), pos);
                if (why != null) {
                    return why;
                }
                attack(ctx, actor, c.card(), pos, post);
            }
            default -> {
                return RejectionCode.NOT_ALLOWED;
            }
        }
        return null;
    }

    /** 主动卡生效后：落点后阶段随即结束回合；投骰前（含狱中）窗口继续计时。 */
    private static void afterActive(DecisionContext<SessionState> ctx, boolean post) {
        if (post && ctx.state().inGame() && postLandingWindow(ctx.state().game())
                && ctx.state().game().flow().frames().size() == 1) {
            TurnModule.closeDecision(ctx);
            TurnModule.endTurn(ctx, 0);
        }
    }

    /** 攻击卡：目标所有者持有对应响应卡则先问他（托管 / 掉线 / 暂离立即自动使用），否则直接生效。 */
    private static void attack(DecisionContext<SessionState> ctx, String actor, CardType card, int tile, boolean post) {
        GameState g = ctx.state().game();
        String owner = g.board().ownable(tile).orElseThrow().owner();
        CardType response = responseTo(card);
        ctx.emit(new CardUsed(actor, card, tile, owner, true));
        if (!holds(g, owner, response)) {
            applyAttack(ctx);
            afterActive(ctx, post);
            return;
        }
        if (MinigameModule.automatic(g, owner)) {
            block(ctx, true);
            afterActive(ctx, post);
            return;
        }
        ctx.emit(new ResponseOffered(actor, owner, tile, card, response));
        long parent = g.turn().windowId();
        FlowOrigin origin = new FlowOrigin(FlowOrigin.Kind.ATTACK_RESPONSE, g.turn().turnNo(), parent, -1, owner);
        if (!(GameModule.openSourcedOverlay(ctx, FlowKind.RESPONSE, owner, 0, ctx.config().timing().responseWindowMs(),
                "CARD_RESPONSE", origin) instanceof FlowCoordinator.Opened.Window)) {
            throw new IllegalStateException("response windows always have a positive duration");
        }
    }

    private static void block(DecisionContext<SessionState> ctx, boolean auto) {
        CardState.Effect e = ctx.state().game().cards().effect();
        CardType response = responseTo(e.card());
        ctx.emit(new CardUsed(e.target(), response, e.tile(), e.actor(), false));
        ctx.emit(new AttackBlocked(e.actor(), e.target(), e.tile(), e.card(), response, auto));
    }

    private static void applyAttack(DecisionContext<SessionState> ctx) {
        GameState g = ctx.state().game();
        CardState.Effect e = g.cards().effect();
        OwnableState o = g.board().ownable(e.tile()).orElseThrow();
        switch (e.card()) {
            case DOWNGRADE -> ctx.emit(new PropertyDowngraded(e.actor(), e.target(), e.tile(), o.level() - 1));
            case DEMOLISH -> ctx.emit(new PropertyDemolished(e.actor(), e.target(), e.tile()));
            case CLEAR_LAND -> ctx.emit(new PropertyCleared(e.actor(), e.target(), e.tile()));
            case FORCED_PURCHASE -> ctx.emit(new PropertyForceBought(e.actor(), e.target(), e.tile(),
                    forcedPrice(ctx.config(), g, e.tile())));
            default -> throw new IllegalStateException("not an attack card " + e.card());
        }
    }

    /** 响应：攻击的响应窗（RESPONSE 覆盖窗口）或免租的落点决策窗口。 */
    private static RejectionCode respond(DecisionContext<SessionState> ctx, GameCommand.RespondCard c) {
        GameState g = ctx.state().game();
        FlowFrame top = g.flow().top().orElse(null);
        if (top != null && top.kind() == FlowKind.RESPONSE) {
            CardState.Effect e = g.cards().effect();
            if (e == null || e.response() == null) {
                return RejectionCode.NO_ACTIVE_WINDOW;
            }
            RejectionCode why = FlowCoordinator.checkWindow(g.flow(), c.windowId(), c.actor(), ctx.now());
            if (why != null) {
                return why;
            }
            if (c.use()) {
                block(ctx, false);
            } else {
                ctx.emit(new ResponseDeclined(e.target(), e.tile(), false));
                applyAttack(ctx);
            }
            closeResponse(ctx, top.windowId(), CloseReason.ACTED);
            return null;
        }
        // 免租：落点 RESPONSE 步骤的回合窗口
        RejectionCode why = TurnModule.checkTurnWindow(ctx, c.actor(), c.windowId());
        if (why != null) {
            return why;
        }
        if (!StageTable.rule(StageTable.turnPoint(g)).allows(c)) {
            return RejectionCode.WRONG_STAGE;
        }
        TurnModule.closeDecision(ctx);
        if (c.use()) {
            waiveRent(ctx, false, 0);
        } else {
            declineWaiver(ctx, false);
        }
        return null;
    }

    /** 响应窗到期：响应者已改为托管 / 掉线 / 暂离则自动使用，否则视为不使用。 */
    static void onResponseExpired(DecisionContext<SessionState> ctx, FlowFrame frame) {
        CardState.Effect e = ctx.state().game().cards().effect();
        if (e == null || e.response() == null || !frame.owner().equals(e.target())) {
            throw new IllegalStateException("response window without its pending attack");
        }
        if (MinigameModule.automatic(ctx.state().game(), e.target())) {
            block(ctx, true);
        } else {
            ctx.emit(new ResponseDeclined(e.target(), e.tile(), true));
            applyAttack(ctx);
        }
        closeResponse(ctx, frame.windowId(), CloseReason.EXPIRED);
    }

    /** 关闭响应窗、恢复回合窗口；攻击发生在落点后阶段时随即结束回合。 */
    private static void closeResponse(DecisionContext<SessionState> ctx, long windowId, CloseReason reason) {
        long turn = ctx.state().game().turn().turnNo();
        GameModule.closeOverlay(ctx, windowId, reason);
        if (!ctx.state().inGame()) {
            return;
        }
        GameState g = ctx.state().game();
        if (g.turn().turnNo() == turn && postLandingWindow(g) && g.flow().frames().size() == 1
                && g.flow().top().map(f -> f.kind() == FlowKind.TURN && !f.window().paused()).orElse(false)) {
            TurnModule.closeDecision(ctx);
            TurnModule.endTurn(ctx, 0);
        }
    }

    // ================================================================ 免租（落点 RESPONSE 步骤）

    /** 托管 / 掉线 / 暂离的缴租者持有免租卡：不开窗口，立即使用。 */
    static void autoWaive(DecisionContext<SessionState> ctx, long leadMs) {
        GameState g = ctx.state().game();
        var l = g.turn().landing();
        ctx.emit(new GameEvent.LandingStepEntered(l.landingId(), LandingStep.RESPONSE, 0, l.cursor()));
        waiveRent(ctx, true, leadMs);
    }

    static void waiveRent(DecisionContext<SessionState> ctx, boolean auto, long leadMs) {
        GameState g = ctx.state().game();
        String payer = g.turn().currentPlayer();
        int tile = g.turn().landing().tile();
        String owner = g.board().ownable(tile).orElseThrow().owner();
        ctx.emit(new CardUsed(payer, CardType.RENT_WAIVER, tile, owner, false));
        ctx.emit(new RentWaived(payer, owner, tile, auto));
        EconomyModule.resumeLanding(ctx, leadMs);
    }

    static void declineWaiver(DecisionContext<SessionState> ctx, boolean auto) {
        GameState g = ctx.state().game();
        ctx.emit(new RentWaiverDeclined(g.turn().currentPlayer(), g.turn().landing().tile(), auto));
        EconomyModule.resumeLanding(ctx, 0);
    }

    /** 免租步骤的前提：他人未抵押地产或车站、缴租者持有免租卡（决策与演化共用）。 */
    static boolean rentResponseDue(RuleConfig config, GameState g, int tileIndex) {
        String payer = g.turn().currentPlayer();
        return enabled(config) && holds(g, payer, CardType.RENT_WAIVER) && g.board().ownable(tileIndex)
                .map(o -> o.owner() != null && !o.owner().equals(payer) && !o.mortgaged()).orElse(false);
    }

    // ================================================================ 演化

    static boolean handles(GameEvent e) {
        return e instanceof CardUsed || e instanceof ResponseOffered || e instanceof ResponseDeclined || e instanceof AttackBlocked
                || e instanceof PropertyBuilt || e instanceof PropertyDowngraded || e instanceof PropertyDemolished
                || e instanceof PropertyCleared || e instanceof PropertyForceBought || e instanceof QueryRevealed
                || e instanceof RentWaived || e instanceof RentWaiverDeclined;
    }

    static GameState evolve(GameState g, GameEvent event, RuleConfig rules) {
        CardState cards = g.cards();
        CardState.Effect e = cards.effect();
        return switch (event) {
            case CardUsed x -> {
                PlayerState p = g.player(x.playerId()).orElse(null);
                check(enabled(rules) && p != null && p.alive() && p.hand().contains(x.card()), "card used without holding it");
                List<com.millionnaire.engine.config.CardType> hand = new ArrayList<>(p.hand());
                hand.remove(x.card());
                GameState next = g.withPlayer(p.withHand(hand));
                if (x.active()) {
                    check(!RESPONSE_CARDS.contains(x.card()) && x.playerId().equals(g.turn().currentPlayer())
                            && !cards.used(x.playerId()) && e == null && g.phase() == GamePhase.RUNNING
                            && activeLegal(rules, g, x), "illegal active card use " + x);
                    CardState used = cards.use(x.playerId());
                    boolean pending = x.card() != CardType.ROADBLOCK && x.card() != CardType.FIXED_MOVE;
                    yield next.withCards(pending ? used.effect(new CardState.Effect(x.playerId(), x.card(), x.tile(), x.target(), null)) : used);
                }
                // 响应卡：免租在落点 RESPONSE 步骤内；房屋保护 / 拒绝购买抵挡当前攻击
                if (x.card() == CardType.RENT_WAIVER) {
                    var l = g.turn().landing();
                    check(l != null && l.step() == LandingStep.RESPONSE && l.decisionOpen() && l.tile() == x.tile()
                            && x.playerId().equals(g.turn().currentPlayer()) && rentResponseDue(rules, g, l.tile()), "rent waiver out of place");
                } else {
                    check(e != null && x.card() == responseTo(e.card()) && x.playerId().equals(e.target()) && x.tile() == e.tile()
                            && e.actor().equals(x.target()), "response card without its attack");
                }
                yield next;
            }
            case ResponseOffered x -> {
                check(e != null && e.response() == null && e.actor().equals(x.attacker()) && e.target().equals(x.owner())
                        && e.tile() == x.tile() && e.card() == x.attack() && x.response() == responseTo(e.card())
                        && holds(g, x.owner(), x.response()) && !g.player(x.owner()).orElseThrow().automated(), "response offer mismatch");
                yield g.withCards(cards.effect(e.awaiting(x.response())));
            }
            case ResponseDeclined x -> {
                check(e != null && e.response() != null && e.target().equals(x.owner()) && e.tile() == x.tile(), "response decline mismatch");
                yield g.withCards(cards.effect(e.awaiting(null)));
            }
            case AttackBlocked x -> {
                check(e != null && e.actor().equals(x.attacker()) && e.target().equals(x.owner()) && e.tile() == x.tile()
                        && e.card() == x.attack() && x.response() == responseTo(e.card()), "attack block mismatch");
                yield g.withCards(cards.effect(null));
            }
            case PropertyBuilt x -> {
                OwnableState o = g.board().ownable(x.tile()).orElse(null);
                check(e != null && e.card() == CardType.BUILD && e.actor().equals(x.playerId()) && e.tile() == x.tile()
                        && o != null && x.playerId().equals(o.owner()) && x.level() == o.level() + 1
                        && x.level() <= rules.economy().maxLevel() && !o.mortgaged(), "free build mismatch");
                yield g.withBoard(g.board().with(o.level(x.level()))).withCards(cards.effect(null));
            }
            case PropertyDowngraded x -> {
                OwnableState o = attacked(g, rules, e, CardType.DOWNGRADE, x.attacker(), x.owner(), x.tile());
                check(x.level() == o.level() - 1, "downgrade level mismatch");
                yield g.withBoard(g.board().with(o.level(x.level()))).withCards(cards.effect(null));
            }
            case PropertyDemolished x -> {
                OwnableState o = attacked(g, rules, e, CardType.DEMOLISH, x.attacker(), x.owner(), x.tile());
                yield g.withBoard(g.board().with(o.level(0))).withCards(cards.effect(null));
            }
            case PropertyCleared x -> {
                OwnableState o = attacked(g, rules, e, CardType.CLEAR_LAND, x.attacker(), x.owner(), x.tile());
                yield g.withBoard(g.board().with(OwnableState.unowned(o.tile()))).withCards(cards.effect(null));
            }
            case PropertyForceBought x -> {
                OwnableState o = attacked(g, rules, e, CardType.FORCED_PURCHASE, x.buyer(), x.owner(), x.tile());
                check(x.price() == forcedPrice(rules, g, x.tile()) && g.ledger().available(x.buyer()) >= x.price(),
                        "forced purchase price mismatch");
                Ledger ledger = g.ledger().transfer(x.buyer(), x.owner(), x.price(), "FORCED_PURCHASE", "tile-" + x.tile());
                yield g.withLedger(ledger).withBoard(g.board().with(o.owned(x.buyer()))).withCards(cards.effect(null));
            }
            case QueryRevealed x -> {
                check(e != null && e.card() == CardType.QUERY && e.actor().equals(x.recipient()) && e.target().equals(x.target())
                        && x.cards().equals(g.player(x.target()).orElseThrow().hand()), "query snapshot mismatch");
                yield g.withCards(cards.effect(null));
            }
            case RentWaived x -> {
                var l = g.turn().landing();
                check(l != null && l.step() == LandingStep.RESPONSE && l.decisionOpen() && l.tile() == x.tile()
                        && x.payer().equals(g.turn().currentPlayer())
                        && g.board().ownable(x.tile()).map(o -> x.owner().equals(o.owner()) && !o.mortgaged()).orElse(false),
                        "rent waiver mismatch");
                yield g.withTurn(g.turn().withLanding(LandingRules.consume(rules, g, l, LandingResult.WAIVED)));
            }
            case RentWaiverDeclined x -> {
                var l = g.turn().landing();
                check(l != null && l.step() == LandingStep.RESPONSE && l.decisionOpen() && l.tile() == x.tile()
                        && x.payer().equals(g.turn().currentPlayer()), "rent waiver decline mismatch");
                yield g.withTurn(g.turn().withLanding(LandingRules.consume(rules, g, l, LandingResult.DECLINED)));
            }
            default -> throw new IllegalStateException("not a card event: " + event);
        };
    }

    /** 主动卡用出时的合法性（演化核对；与决策同一套条件）。 */
    private static boolean activeLegal(RuleConfig rules, GameState g, CardUsed x) {
        var t = g.turn();
        PlayerState p = g.player(x.playerId()).orElseThrow();
        boolean jail = t.stage() == TurnStage.JAIL_DECISION;
        boolean preRoll = t.stage() == TurnStage.PRE_ROLL && t.chain() == null;
        boolean post = postLandingWindow(g);
        boolean window = g.flow().frames().size() == 1 && g.flow().top().map(f -> f.kind() == FlowKind.TURN
                && f.windowId() == t.windowId()).orElse(false) && !p.automated() && g.debt() == null && g.minigame() == null;
        if (!window || !(jail || preRoll || post)) {
            return false;
        }
        return switch (x.card()) {
            case ROADBLOCK -> !jail && x.tile() == p.position() && x.target() == null;
            case FIXED_MOVE -> preRoll && x.tile() == -1 && x.target() == null;
            case JAIL_RELEASE -> jail && p.inJail() && x.tile() == -1 && x.target() == null;
            case QUERY -> x.tile() == -1 && queryTarget(g, x.playerId(), x.target()) == null;
            case BUILD -> !jail && x.tile() == p.position() && x.playerId().equals(x.target())
                    && tileCondition(rules, g, x.playerId(), x.card(), x.tile()) == null;
            case DOWNGRADE, DEMOLISH, CLEAR_LAND, FORCED_PURCHASE -> !jail && x.tile() == p.position()
                    && g.board().ownable(x.tile()).map(o -> o.owner() != null && o.owner().equals(x.target())).orElse(false)
                    && tileCondition(rules, g, x.playerId(), x.card(), x.tile()) == null;
            default -> false;
        };
    }

    /** 攻击生效的共同核对：待结算的同一张攻击卡、没有等待中的响应、目标仍成立。 */
    private static OwnableState attacked(GameState g, RuleConfig rules, CardState.Effect e, CardType card, String attacker,
                                         String owner, int tile) {
        OwnableState o = g.board().ownable(tile).orElse(null);
        check(e != null && e.card() == card && e.response() == null && e.actor().equals(attacker) && e.target().equals(owner)
                && e.tile() == tile && o != null && owner.equals(o.owner())
                && tileCondition(rules, g, attacker, card, tile) == null, card + " effect mismatch");
        return o;
    }

    // ================================================================ 校验与投影

    static void validate(GameState g, RuleConfig config) {
        CardState c = g.cards();
        LobbyModule.expect(c != null && c.chanceUsed().stream().allMatch(p -> g.player(p).isPresent())
                && c.chanceUsed().equals(c.chanceUsed().stream().distinct().sorted().toList()), "card chances invalid");
        LobbyModule.expect(c.boughtTile() == -1 || g.board().ownable(c.boughtTile()).isPresent(), "bought tile invalid");
        LobbyModule.expect(enabled(config) || c.chanceUsed().isEmpty() && c.effect() == null, "cards disabled but used");
        CardState.Effect e = c.effect();
        boolean responseFrame = g.flow().frames().stream().anyMatch(f -> f.kind() == FlowKind.RESPONSE
                && f.origin() != null && g.flow().frame(f.origin().ref()).map(p -> p.kind() == FlowKind.TURN).orElse(false));
        if (e == null) {
            LobbyModule.expect(!responseFrame, "a card response window without a pending attack");
            return;
        }
        FlowFrame top = g.flow().top().orElse(null);
        OwnableState o = g.board().ownable(e.tile()).orElse(null);
        LobbyModule.expect(e.response() != null && e.response() == responseTo(e.card()) && e.actor().equals(g.turn().currentPlayer())
                && c.used(e.actor()) && top != null && top.kind() == FlowKind.RESPONSE && top.owner().equals(e.target())
                && g.flow().frames().size() == 2 && g.flow().frames().get(0).kind() == FlowKind.TURN
                && o != null && e.target().equals(o.owner()) && holds(g, e.target(), e.response())
                && g.player(e.target()).map(PlayerState::alive).orElse(false)
                && tileCondition(config, g, e.actor(), e.card(), e.tile()) == null, "pending card attack invalid");
    }

    static GameView.PublicCards view(GameState g) {
        CardState c = g.cards();
        CardState.Effect e = c.effect();
        GameView.PublicResponse response = null;
        if (e != null && e.response() != null) {
            long windowId = g.flow().top().filter(f -> f.kind() == FlowKind.RESPONSE).map(FlowFrame::windowId).orElse(0L);
            response = new GameView.PublicResponse(e.actor(), e.target(), e.tile(), e.card(), e.response(), windowId);
        }
        return new GameView.PublicCards(c.chanceUsed(), response);
    }

    private static void check(boolean condition, String message) {
        LobbyModule.check(condition, message);
    }
}
