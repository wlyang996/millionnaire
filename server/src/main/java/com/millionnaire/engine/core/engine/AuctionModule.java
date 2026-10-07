package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.Pricing;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.Tile;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.AuctionPassed;
import com.millionnaire.engine.core.event.GameEvent.AuctionRequested;
import com.millionnaire.engine.core.event.GameEvent.AuctionSettled;
import com.millionnaire.engine.core.event.GameEvent.AuctionStarted;
import com.millionnaire.engine.core.event.GameEvent.BidPlaced;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.GameEvent.LandAuctionChosen;
import com.millionnaire.engine.core.event.GameEvent.LandingStepEntered;
import com.millionnaire.engine.core.event.GameEvent.TurnStageEntered;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.AuctionState;
import com.millionnaire.engine.core.state.Continuation;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowOrigin;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.GameView;
import com.millionnaire.engine.core.state.LandingResult;
import com.millionnaire.engine.core.state.LandingState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import java.util.ArrayList;
import java.util.List;

/**
 * 拍卖（requirements 第 8 节、第 14 节拍卖卡；已采纳默认值 #11；O13 / C2；O16）：
 * <ul>
 *   <li><b>土地拍卖</b>：落在无主指定拍卖地时，到达者可原价买、发起拍卖或放弃；发起拍卖即放弃本次购买资格，不能出价。
 *       全局到时后只能原价买或放弃。以原价为基数；成交款归系统，发起人得成交价 10%（向下取整）；流拍无奖励。</li>
 *   <li><b>拍卖卡</b>：拍卖自己任意一处未抵押地产或车站，可非自己回合申请（占用主动用卡机会），排队到下一个安全点（回合交界）启动；
 *       欠款处理中不能申请。以标准价值为基数，成交款全归卖家，卖家不能出价；流拍同样消耗拍卖卡。拍卖期间该资产锁定。</li>
 *   <li><b>统一竞拍</b>：20 秒；起拍 = 基数 50%（向上取整），每次至少加基数 10%（向上取整），达到封顶（基数 2.5 倍，向下取整）的报价
 *       可不足最小加价，并立即以一口价成交；只有出价者变化时，最后 3 秒内的出价把剩余时间恢复到 3 秒，总时长最多 40 秒。
 *       报价须有足额可用现金（不靠抵押）；最高报价冻结、被超过立即解冻；有效报价不能撤销，掉线仍有效；托管不出价。</li>
 * </ul>
 * 只在正式规则（道具开启）下生效；旧测试配置里 AUCTION 覆盖窗口仍是 M1 的占位流程。
 */
final class AuctionModule {
    static final String TAG = "AUCTION";
    static final String LOCK = "AUCTION";

    private AuctionModule() {
    }

    static boolean enabled(RuleConfig config) {
        return CardModule.enabled(config);
    }

    static Pricing pricing(RuleConfig config) {
        return new Pricing(config);
    }

    /** 按基数生成拍卖参数。 */
    static AuctionState fresh(RuleConfig config, AuctionState.Kind kind, int tile, String seller, String initiator, long basis,
                              long hardEnd) {
        Pricing p = pricing(config);
        return new AuctionState(kind, tile, seller, initiator, basis, p.auctionStart(basis), p.auctionMinRaise(basis),
                p.auctionCap(basis), 0, null, hardEnd, List.of());
    }

    // ================================================================ 土地拍卖

    /** BUY 窗口内"发起拍卖"（窗口与阶段已由经济模块核对）。 */
    static RejectionCode chooseLandAuction(DecisionContext<SessionState> ctx, GameCommand.StartLandAuction c) {
        GameState g = ctx.state().game();
        if (!enabled(ctx.config())) {
            return RejectionCode.NOT_AVAILABLE;
        }
        if (g.phase() != GamePhase.RUNNING) {
            return RejectionCode.DRAINING;
        }
        LandingState l = g.turn().landing();
        if (!landAuctionLegal(ctx.config(), g, l.tile())) {
            return RejectionCode.NOT_ALLOWED;
        }
        TurnModule.closeDecision(ctx);
        ctx.emit(new LandAuctionChosen(c.actor(), l.tile()));
        EconomyModule.resumeLanding(ctx, 0);
        return null;
    }

    static boolean landAuctionLegal(RuleConfig config, GameState g, int tileIndex) {
        Tile tile = LobbyModule.board(config, g.settings()).tiles().get(tileIndex);
        return enabled(config) && g.phase() == GamePhase.RUNNING && tile.type() == TileType.PROPERTY && tile.auctionDesignated()
                && g.board().ownable(tileIndex).map(o -> o.owner() == null).orElse(false)
                && g.alive().size() >= 2;
    }

    /** 落点推进器执行 AUCTION 任务：等待流程、开拍、开拍卖窗口。 */
    static void beginLand(DecisionContext<SessionState> ctx, long leadMs) {
        GameState g = ctx.state().game();
        LandingState l = g.turn().landing();
        ctx.emit(new LandingStepEntered(l.landingId(), LandingStep.AUCTION, 0, l.cursor()));
        ctx.emit(new TurnStageEntered(TurnStage.AWAITING_FLOW, 0,
                new Continuation.ResumeLanding(g.turn().turnNo(), l.landingId()), Math.addExact(ctx.now(), leadMs)));
        Tile tile = LobbyModule.board(ctx.config(), g.settings()).tiles().get(l.tile());
        long hardEnd = Math.addExact(Math.addExact(ctx.now(), leadMs), ctx.config().timing().auctionMaxMs());
        String initiator = g.turn().currentPlayer();
        ctx.emit(new AuctionStarted(fresh(ctx.config(), AuctionState.Kind.LAND, l.tile(), null, initiator,
                EconomyModule.basePrice(ctx.config(), tile), hardEnd)));
        FlowOrigin origin = new FlowOrigin(FlowOrigin.Kind.LAND_AUCTION, g.turn().turnNo(), l.landingId(), l.cursor(), initiator);
        if (!(GameModule.openSourcedOverlay(ctx, FlowKind.LAND_AUCTION, initiator, leadMs,
                ctx.config().timing().auctionDurationMs(), TAG, origin) instanceof FlowCoordinator.Opened.Window)) {
            throw new IllegalStateException("auctions always have a positive duration");
        }
    }

    // ================================================================ 拍卖卡

    /** 拍卖卡申请的标的条件（申请时与启动前共用）。 */
    static RejectionCode cardRequestCondition(RuleConfig config, GameState g, String applicant, int tileIndex) {
        PlayerState p = g.player(applicant).orElse(null);
        if (p == null) {
            return RejectionCode.NOT_MEMBER;
        }
        if (!p.alive()) {
            return RejectionCode.NOT_ALIVE;
        }
        if (g.phase() != GamePhase.RUNNING) {
            return RejectionCode.DRAINING;
        }
        if (!p.hand().contains(CardType.AUCTION)) {
            return RejectionCode.NO_CARD;
        }
        if (g.debt() != null && g.debt().debtor().equals(applicant)) {
            return RejectionCode.NOT_ALLOWED;
        }
        var board = LobbyModule.board(config, g.settings());
        if (tileIndex < 0 || tileIndex >= board.size() || g.board().ownable(tileIndex).isEmpty()) {
            return RejectionCode.INVALID_TILE;
        }
        OwnableState o = g.board().ownable(tileIndex).orElseThrow();
        if (!applicant.equals(o.owner())) {
            return RejectionCode.NOT_OWNER;
        }
        if (o.mortgaged()) {
            return RejectionCode.MORTGAGED;
        }
        return o.lockedBy() != null ? RejectionCode.ASSET_LOCKED : null;
    }

    static RejectionCode requestCardAuction(DecisionContext<SessionState> ctx, GameCommand.RequestAuction c) {
        GameState g = ctx.state().game();
        if (!enabled(ctx.config())) {
            return RejectionCode.NOT_AVAILABLE;
        }
        RejectionCode why = cardRequestCondition(ctx.config(), g, c.actor(), c.tile());
        if (why != null) {
            return why;
        }
        if (g.cards().used(c.actor())) {
            return RejectionCode.CARD_USED;
        }
        long id = g.flow().nextRequestId();
        why = FlowCoordinator.request(ctx, GameModule.FLOW, FlowKind.AUCTION, c.actor(), c.tile(), null, 0);
        if (why != null) {
            return why;
        }
        ctx.emit(new AuctionRequested(c.actor(), c.tile(), id));
        return null;
    }

    /** 安全点启动前核对排队标的（玩家仍存活、仍持卡、地块仍可拍）；失效的申请直接取消。 */
    static boolean requestStillValid(RuleConfig config, GameState g, FlowRequest r) {
        return r.kind() != FlowKind.AUCTION || cardRequestCondition(config, g, r.applicant(), r.tile()) == null;
    }

    /** 安全点启动拍卖卡的拍卖。 */
    static void beginCard(DecisionContext<SessionState> ctx, FlowRequest r, long leadMs) {
        GameState g = ctx.state().game();
        Tile tile = LobbyModule.board(ctx.config(), g.settings()).tiles().get(r.tile());
        OwnableState o = g.board().ownable(r.tile()).orElseThrow();
        long hardEnd = Math.addExact(Math.addExact(ctx.now(), leadMs), ctx.config().timing().auctionMaxMs());
        ctx.emit(new AuctionStarted(fresh(ctx.config(), AuctionState.Kind.CARD, r.tile(), r.applicant(), null,
                EconomyModule.standardValue(ctx.config(), tile, o.level()), hardEnd)));
        GameModule.openQueuedOverlay(ctx, r, leadMs, ctx.config().timing().auctionDurationMs(), TAG);
    }

    // ================================================================ 出价与结束

    static RejectionCode bid(DecisionContext<SessionState> ctx, GameCommand.Bid c) {
        GameState g = ctx.state().game();
        AuctionState a = g.auction();
        if (a == null) {
            return RejectionCode.NO_ACTIVE_WINDOW;
        }
        FlowFrame top = g.flow().top().orElse(null);
        if (top == null || top.windowId() != c.windowId() || top.kind() != frameKind(a)) {
            return RejectionCode.WINDOW_MISMATCH;
        }
        if (!top.window().acceptsAt(ctx.now())) {
            return top.window().status(ctx.now()) == com.millionnaire.engine.time.Window.Status.NOT_OPEN
                    ? RejectionCode.WINDOW_NOT_OPEN : RejectionCode.WINDOW_MISMATCH;
        }
        PlayerState p = c.actor() == null ? null : g.player(c.actor()).orElse(null);
        if (p == null) {
            return RejectionCode.NOT_MEMBER;
        }
        if (!p.alive()) {
            return RejectionCode.NOT_ALIVE;
        }
        if (c.actor().equals(a.host())) {
            return RejectionCode.NOT_ALLOWED;
        }
        if (c.amount() < a.minimumBid() || c.amount() > a.cap() || a.highBidder() != null && c.amount() <= a.highBid()) {
            return RejectionCode.INVALID_ARGUMENT;
        }
        long own = c.actor().equals(a.highBidder()) ? a.highBid() : 0;
        if (Math.addExact(g.ledger().available(c.actor()), own) < c.amount()) {
            return RejectionCode.INSUFFICIENT_CASH;
        }
        String previous = a.highBidder();
        ctx.emit(new BidPlaced(c.actor(), c.amount()));
        if (c.amount() == a.cap()) {
            settle(ctx, top.windowId(), CloseReason.ACTED);   // 一口价立即成交
            return null;
        }
        // O13：只有出价者变化时，最后 3 秒内的出价把剩余时间恢复到 3 秒（不超过总时长 40 秒）
        long extendMs = ctx.config().timing().auctionExtendMs();
        if (!c.actor().equals(previous) && top.window().deadline() - ctx.now() < extendMs) {
            long deadline = Math.min(Math.addExact(ctx.now(), extendMs), a.hardEnd());
            if (deadline > top.window().deadline()) {
                FlowCoordinator.extend(ctx, GameModule.FLOW, top.windowId(), deadline);
            }
        }
        return null;
    }

    static FlowKind frameKind(AuctionState a) {
        return a.kind() == AuctionState.Kind.LAND ? FlowKind.LAND_AUCTION : FlowKind.AUCTION;
    }

    static void onExpired(DecisionContext<SessionState> ctx, FlowFrame frame) {
        settle(ctx, frame.windowId(), CloseReason.EXPIRED);
    }

    /** 成交或流拍，然后关闭拍卖窗口，按续接继续（土地拍卖 → 落点结束；拍卖卡 → 开始本回合）。 */
    private static void settle(DecisionContext<SessionState> ctx, long windowId, CloseReason reason) {
        AuctionState a = ctx.state().game().auction();
        if (a.highBidder() != null) {
            long commission = a.kind() == AuctionState.Kind.LAND ? pricing(ctx.config()).systemAuctionCommission(a.highBid()) : 0;
            ctx.emit(new AuctionSettled(a.highBidder(), a.tile(), a.highBid(), commission));
        } else {
            ctx.emit(new AuctionPassed(a.tile()));
        }
        GameModule.closeOverlay(ctx, windowId, reason);
    }

    // ================================================================ 演化

    static boolean handles(GameEvent e) {
        return e instanceof LandAuctionChosen || e instanceof AuctionRequested || e instanceof AuctionStarted
                || e instanceof BidPlaced || e instanceof AuctionSettled || e instanceof AuctionPassed;
    }

    static GameState evolve(GameState g, GameEvent event, RuleConfig rules) {
        AuctionState a = g.auction();
        var t = g.turn();
        LandingState l = t.landing();
        return switch (event) {
            case LandAuctionChosen e -> {
                check(e.playerId().equals(t.currentPlayer()) && l != null && l.step() == LandingStep.BUY && l.decisionOpen()
                        && l.tile() == e.tile() && landAuctionLegal(rules, g, e.tile()), "land auction choice mismatch");
                yield g.withTurn(t.withLanding(LandingRules.consume(rules, g, l, LandingResult.AUCTIONED)));
            }
            case AuctionRequested e -> {
                var queue = g.flow().queue();
                FlowRequest r = queue.isEmpty() ? null : queue.getLast();
                check(enabled(rules) && r != null && r.requestId() == e.requestId() && r.kind() == FlowKind.AUCTION
                        && r.applicant().equals(e.applicant()) && r.tile() == e.tile() && !g.cards().used(e.applicant())
                        && cardRequestCondition(rules, g, e.applicant(), e.tile()) == null, "auction request mismatch");
                yield g.withCards(g.cards().use(e.applicant()));
            }
            case AuctionStarted e -> {
                AuctionState n = e.auction();
                check(enabled(rules) && a == null && n != null && n.highBid() == 0 && n.highBidder() == null && n.bidders().isEmpty()
                        && n.hardEnd() > 0, "auction start mismatch");
                Tile tile = LobbyModule.board(rules, g.settings()).tiles().get(n.tile());
                if (n.kind() == AuctionState.Kind.LAND) {
                    check(n.seller() == null && t.currentPlayer().equals(n.initiator()) && l != null && l.step() == LandingStep.AUCTION
                            && l.tile() == n.tile() && t.stage() == TurnStage.AWAITING_FLOW && g.flow().frames().isEmpty()
                            && landAuctionLegal(rules, g, n.tile())
                            && n.equals(fresh(rules, AuctionState.Kind.LAND, n.tile(), null, n.initiator(),
                                EconomyModule.basePrice(rules, tile), n.hardEnd())), "land auction start mismatch");
                    yield g.withAuction(n);
                }
                FlowRequest r = g.flow().pendingStart();
                OwnableState o = g.board().ownable(n.tile()).orElse(null);
                check(n.initiator() == null && r != null && r.kind() == FlowKind.AUCTION && r.applicant().equals(n.seller())
                        && r.tile() == n.tile() && o != null
                        && cardRequestCondition(rules, g, n.seller(), n.tile()) == null
                        && n.equals(fresh(rules, AuctionState.Kind.CARD, n.tile(), n.seller(), null,
                            EconomyModule.standardValue(rules, tile, o.level()), n.hardEnd())), "card auction start mismatch");
                PlayerState seller = g.player(n.seller()).orElseThrow();
                List<CardType> hand = new ArrayList<>(seller.hand());
                hand.remove(CardType.AUCTION);
                yield g.withAuction(n).withPlayer(seller.withHand(hand))
                        .withBoard(g.board().with(new OwnableState(o.tile(), o.owner(), o.level(), false, 0, LOCK)));
            }
            case BidPlaced e -> {
                PlayerState p = g.player(e.bidder()).orElse(null);
                check(a != null && p != null && p.alive() && !e.bidder().equals(a.host()) && e.amount() >= a.minimumBid()
                        && e.amount() <= a.cap() && (a.highBidder() == null || e.amount() > a.highBid()), "bid mismatch");
                Ledger ledger = g.ledger();
                if (a.highBidder() != null) {
                    ledger = ledger.unfreeze(a.highBidder(), a.highBid());
                }
                check(ledger.available(e.bidder()) >= e.amount(), "bid without enough available cash");
                yield g.withLedger(ledger.freeze(e.bidder(), e.amount())).withAuction(a.bid(e.bidder(), e.amount()));
            }
            case AuctionSettled e -> {
                check(a != null && e.winner().equals(a.highBidder()) && e.price() == a.highBid() && e.tile() == a.tile()
                        && e.commission() == (a.kind() == AuctionState.Kind.LAND ? pricing(rules).systemAuctionCommission(e.price()) : 0),
                        "auction settlement mismatch");
                OwnableState o = g.board().ownable(a.tile()).orElseThrow();
                Ledger ledger = g.ledger().unfreeze(e.winner(), e.price());
                GameState done;
                if (a.kind() == AuctionState.Kind.LAND) {
                    ledger = ledger.transfer(e.winner(), Ledger.SYSTEM, e.price(), "AUCTION", "tile-" + a.tile());
                    if (e.commission() > 0) {
                        ledger = ledger.transfer(Ledger.SYSTEM, a.initiator(), e.commission(), "AUCTION_COMMISSION", "tile-" + a.tile());
                    }
                    done = g.withLedger(ledger).withBoard(g.board().with(o.owned(e.winner())));
                    yield landEnded(done.withAuction(null), rules);
                }
                ledger = ledger.transfer(e.winner(), a.seller(), e.price(), "AUCTION", "tile-" + a.tile());
                yield g.withLedger(ledger).withAuction(null)
                        .withBoard(g.board().with(new OwnableState(o.tile(), e.winner(), o.level(), false, 0, null)));
            }
            case AuctionPassed e -> {
                check(a != null && a.highBidder() == null && e.tile() == a.tile(), "auction pass mismatch");
                if (a.kind() == AuctionState.Kind.LAND) {
                    yield landEnded(g.withAuction(null), rules);
                }
                OwnableState o = g.board().ownable(a.tile()).orElseThrow();
                yield g.withAuction(null).withBoard(g.board().with(new OwnableState(o.tile(), o.owner(), o.level(), false, 0, null)));
            }
            default -> throw new IllegalStateException("not an auction event: " + event);
        };
    }

    /** 土地拍卖结束：消费落点的 AUCTION 任务。 */
    private static GameState landEnded(GameState g, RuleConfig rules) {
        LandingState l = g.turn().landing();
        check(l != null && l.step() == LandingStep.AUCTION, "land auction without its landing task");
        return g.withTurn(g.turn().withLanding(LandingRules.consume(rules, g, l, LandingResult.AUCTION_ENDED)));
    }

    // ================================================================ 校验、投影与参与者

    static boolean resting(GameState g, LandingState l) {
        AuctionState a = g.auction();
        return g.turn().stage() == TurnStage.AWAITING_FLOW && a != null && a.kind() == AuctionState.Kind.LAND && a.tile() == l.tile();
    }

    /** 冻结余额的唯一来源：拍卖最高价。 */
    static long held(GameState g, String playerId) {
        AuctionState a = g.auction();
        return a != null && playerId.equals(a.highBidder()) ? a.highBid() : 0;
    }

    /** 资产锁定只能来自进行中的拍卖卡拍卖。 */
    static boolean locks(GameState g, OwnableState o) {
        AuctionState a = g.auction();
        return LOCK.equals(o.lockedBy()) && a != null && a.kind() == AuctionState.Kind.CARD && a.tile() == o.tile();
    }

    /** 会影响拍卖结果的参与者（认输延后到拍卖结束）：卖家 / 发起人与出过价的玩家。 */
    static boolean involved(GameState g, String playerId) {
        AuctionState a = g.auction();
        return a != null && (playerId.equals(a.host()) || a.bidders().contains(playerId));
    }

    static void validate(GameState g, RuleConfig config) {
        AuctionState a = g.auction();
        if (a == null) {
            if (enabled(config)) {
                LobbyModule.expect(g.flow().frames().stream().noneMatch(f -> f.kind() == FlowKind.AUCTION || f.kind() == FlowKind.LAND_AUCTION),
                        "an auction window without an auction");
            }
            return;
        }
        LobbyModule.expect(enabled(config), "auctions require the card rules");
        FlowFrame top = g.flow().top().orElse(null);
        var t = g.turn();
        OwnableState o = g.board().ownable(a.tile()).orElse(null);
        LobbyModule.expect(top != null && g.flow().frames().size() == 1 && top.kind() == frameKind(a) && top.owner().equals(a.host())
                && a.hardEnd() == Math.addExact(top.window().opensAt(), config.timing().auctionMaxMs())
                && top.window().deadline() <= a.hardEnd() && t.stage() == TurnStage.AWAITING_FLOW && o != null
                && g.player(a.host()).map(PlayerState::alive).orElse(false), "auction window invalid");
        Pricing p = pricing(config);
        LobbyModule.expect(a.start() == p.auctionStart(a.basis()) && a.minRaise() == p.auctionMinRaise(a.basis())
                && a.cap() == p.auctionCap(a.basis()), "auction prices do not follow the basis");
        if (a.kind() == AuctionState.Kind.LAND) {
            LobbyModule.expect(a.seller() == null && a.initiator().equals(t.currentPlayer()) && t.landing() != null
                    && t.landing().step() == LandingStep.AUCTION && t.landing().tile() == a.tile() && o.owner() == null
                    && t.continuation() instanceof Continuation.ResumeLanding, "land auction invalid");
        } else {
            LobbyModule.expect(a.initiator() == null && a.seller().equals(o.owner()) && LOCK.equals(o.lockedBy()) && !o.mortgaged()
                    && t.continuation() instanceof Continuation.BeginTurn, "card auction invalid");
        }
        if (a.highBidder() == null) {
            LobbyModule.expect(a.highBid() == 0, "no bid without a bidder");
        } else {
            LobbyModule.expect(a.highBid() >= a.start() && a.highBid() < a.cap() && !a.highBidder().equals(a.host())
                    && a.bidders().contains(a.highBidder()) && g.player(a.highBidder()).map(PlayerState::alive).orElse(false),
                    "auction high bid invalid");
        }
        LobbyModule.expect(a.bidders().stream().allMatch(b -> g.player(b).isPresent() && !b.equals(a.host())),
                "auction bidders invalid");
    }

    static GameView.PublicAuction view(GameState g) {
        AuctionState a = g.auction();
        if (a == null) {
            return null;
        }
        long windowId = g.flow().top().filter(f -> f.kind() == frameKind(a)).map(FlowFrame::windowId).orElse(0L);
        return new GameView.PublicAuction(a.kind().name(), a.tile(), a.seller(), a.initiator(), a.basis(), a.start(), a.minRaise(),
                a.cap(), a.highBid(), a.highBidder(), a.minimumBid(), a.hardEnd(), windowId);
    }

    private static void check(boolean condition, String message) {
        LobbyModule.check(condition, message);
    }
}
