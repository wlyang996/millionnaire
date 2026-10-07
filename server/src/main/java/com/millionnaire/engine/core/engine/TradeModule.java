package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.Pricing;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.Tile;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.GameEvent.TradeCompleted;
import com.millionnaire.engine.core.event.GameEvent.TradeDeclined;
import com.millionnaire.engine.core.event.GameEvent.TradeRequested;
import com.millionnaire.engine.core.event.GameEvent.TradeStarted;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.Continuation;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.GameView;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TradeState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易卡（requirements 第 14 节；open-decisions #12 价格下限；已采纳默认值 #9 / #17）：
 * <ul>
 *   <li>卖自己任意一块未抵押的地产或车站给指定买家，自定价：标准价值 50%（向上取整）～ 2.5 倍（向下取整），不允许免费赠送；</li>
 *   <li>可非自己回合申请，占用主动用卡机会，排队到下一个安全点（回合交界）开始；开始前标的失效（卖家出局、卡已不在、地块已抵押或易主、
 *       买家出局、价格已不在区间内）则取消；</li>
 *   <li>开始后资产锁定，买家 15 秒内同意且可用现金足额才成交（款归卖家、产权转买家、等级保留、交易卡消耗）；拒绝或超时视为拒绝，
 *       交易卡保留、机会不退；托管 / 暂离 / 掉线的买家不接受交易（只等自动动作延时即拒绝）；</li>
 *   <li>交易进行中卖家与买家的认输延后到交易结束。</li>
 * </ul>
 * 只在正式规则（道具开启）下生效；旧测试配置里 TRADE 覆盖窗口仍是 M1 的占位流程。
 */
final class TradeModule {
    static final String TAG = "TRADE";
    static final String LOCK = "TRADE";

    private TradeModule() {
    }

    static boolean enabled(RuleConfig config) {
        return CardModule.enabled(config);
    }

    /** 申请与开始前共用的标的条件。 */
    static RejectionCode condition(RuleConfig config, GameState g, String seller, int tileIndex, String buyer, long price) {
        PlayerState p = g.player(seller).orElse(null);
        if (p == null) {
            return RejectionCode.NOT_MEMBER;
        }
        if (!p.alive()) {
            return RejectionCode.NOT_ALIVE;
        }
        if (g.phase() != GamePhase.RUNNING) {
            return RejectionCode.DRAINING;
        }
        if (!p.hand().contains(CardType.TRADE)) {
            return RejectionCode.NO_CARD;
        }
        var board = LobbyModule.board(config, g.settings());
        if (tileIndex < 0 || tileIndex >= board.size() || g.board().ownable(tileIndex).isEmpty()) {
            return RejectionCode.INVALID_TILE;
        }
        OwnableState o = g.board().ownable(tileIndex).orElseThrow();
        if (!seller.equals(o.owner())) {
            return RejectionCode.NOT_OWNER;
        }
        if (o.mortgaged()) {
            return RejectionCode.MORTGAGED;
        }
        if (o.lockedBy() != null) {
            return RejectionCode.ASSET_LOCKED;
        }
        if (buyer == null || buyer.equals(seller) || !g.player(buyer).map(PlayerState::alive).orElse(false)) {
            return RejectionCode.INVALID_ARGUMENT;
        }
        long std = standard(config, g, tileIndex);
        Pricing pricing = new Pricing(config);
        return price >= pricing.tradeMin(std) && price <= pricing.tradeMax(std) ? null : RejectionCode.INVALID_ARGUMENT;
    }

    static long standard(RuleConfig config, GameState g, int tileIndex) {
        Tile tile = LobbyModule.board(config, g.settings()).tiles().get(tileIndex);
        return EconomyModule.standardValue(config, tile, g.board().ownable(tileIndex).orElseThrow().level());
    }

    static RejectionCode request(DecisionContext<SessionState> ctx, GameCommand.RequestTrade c) {
        GameState g = ctx.state().game();
        if (!enabled(ctx.config())) {
            return RejectionCode.NOT_AVAILABLE;
        }
        RejectionCode why = condition(ctx.config(), g, c.actor(), c.tile(), c.buyer(), c.price());
        if (why != null) {
            return why;
        }
        if (g.cards().used(c.actor())) {
            return RejectionCode.CARD_USED;
        }
        long id = g.flow().nextRequestId();
        why = FlowCoordinator.request(ctx, GameModule.FLOW, FlowKind.TRADE, c.actor(), c.tile(), c.buyer(), c.price());
        if (why != null) {
            return why;
        }
        ctx.emit(new TradeRequested(c.actor(), c.buyer(), c.tile(), c.price(), id));
        return null;
    }

    static boolean requestStillValid(RuleConfig config, GameState g, FlowRequest r) {
        return r.kind() != FlowKind.TRADE || condition(config, g, r.applicant(), r.tile(), r.counterparty(), r.price()) == null;
    }

    /** 安全点开始交易：锁定资产，开交易窗口（所有者为卖家；买家用 AnswerTrade 答复）。 */
    static void begin(DecisionContext<SessionState> ctx, FlowRequest r, long leadMs) {
        GameState g = ctx.state().game();
        ctx.emit(new TradeStarted(r.applicant(), r.counterparty(), r.tile(), r.price()));
        // 托管 / 暂离 / 掉线的买家不接受交易：只等自动动作延时即按超时拒绝
        long duration = g.player(r.counterparty()).map(PlayerState::automated).orElse(true)
                ? Math.min(ctx.config().timing().autoActDelayMs(), ctx.config().timing().tradeResponseMs())
                : ctx.config().timing().tradeResponseMs();
        GameModule.openQueuedOverlay(ctx, r, leadMs, duration, TAG);
    }

    static RejectionCode answer(DecisionContext<SessionState> ctx, GameCommand.AnswerTrade c) {
        GameState g = ctx.state().game();
        TradeState t = g.trade();
        if (t == null) {
            return RejectionCode.NO_ACTIVE_WINDOW;
        }
        FlowFrame top = g.flow().top().orElse(null);
        if (top == null || top.kind() != FlowKind.TRADE || top.windowId() != c.windowId()) {
            return RejectionCode.WINDOW_MISMATCH;
        }
        if (!top.window().acceptsAt(ctx.now())) {
            return top.window().status(ctx.now()) == com.millionnaire.engine.time.Window.Status.NOT_OPEN
                    ? RejectionCode.WINDOW_NOT_OPEN : RejectionCode.WINDOW_MISMATCH;
        }
        if (c.actor() == null || !c.actor().equals(t.buyer())) {
            return RejectionCode.NOT_YOUR_WINDOW;
        }
        if (c.accept()) {
            if (g.ledger().available(t.buyer()) < t.price()) {
                return RejectionCode.INSUFFICIENT_CASH;
            }
            ctx.emit(new TradeCompleted(t.seller(), t.buyer(), t.tile(), t.price()));
        } else {
            ctx.emit(new TradeDeclined(t.buyer(), t.tile(), false));
        }
        GameModule.closeOverlay(ctx, top.windowId(), CloseReason.ACTED);
        return null;
    }

    static void onExpired(DecisionContext<SessionState> ctx, FlowFrame frame) {
        TradeState t = ctx.state().game().trade();
        ctx.emit(new TradeDeclined(t.buyer(), t.tile(), true));
        GameModule.closeOverlay(ctx, frame.windowId(), CloseReason.EXPIRED);
    }

    // ================================================================ 演化

    static boolean handles(GameEvent e) {
        return e instanceof TradeRequested || e instanceof TradeStarted || e instanceof TradeCompleted || e instanceof TradeDeclined;
    }

    static GameState evolve(GameState g, GameEvent event, RuleConfig rules) {
        TradeState t = g.trade();
        return switch (event) {
            case TradeRequested e -> {
                var queue = g.flow().queue();
                FlowRequest r = queue.isEmpty() ? null : queue.getLast();
                check(enabled(rules) && r != null && r.requestId() == e.requestId() && r.kind() == FlowKind.TRADE
                        && r.applicant().equals(e.seller()) && r.tile() == e.tile() && e.buyer().equals(r.counterparty())
                        && r.price() == e.price() && !g.cards().used(e.seller())
                        && condition(rules, g, e.seller(), e.tile(), e.buyer(), e.price()) == null, "trade request mismatch");
                yield g.withCards(g.cards().use(e.seller()));
            }
            case TradeStarted e -> {
                FlowRequest r = g.flow().pendingStart();
                check(enabled(rules) && t == null && g.auction() == null && r != null && r.kind() == FlowKind.TRADE
                        && r.applicant().equals(e.seller()) && e.buyer().equals(r.counterparty()) && r.tile() == e.tile()
                        && r.price() == e.price() && condition(rules, g, e.seller(), e.tile(), e.buyer(), e.price()) == null,
                        "trade start mismatch");
                OwnableState o = g.board().ownable(e.tile()).orElseThrow();
                yield g.withTrade(new TradeState(e.seller(), e.buyer(), e.tile(), e.price()))
                        .withBoard(g.board().with(new OwnableState(o.tile(), o.owner(), o.level(), false, 0, LOCK)));
            }
            case TradeCompleted e -> {
                check(t != null && t.equals(new TradeState(e.seller(), e.buyer(), e.tile(), e.price()))
                        && g.ledger().available(e.buyer()) >= e.price(), "trade completion mismatch");
                OwnableState o = g.board().ownable(e.tile()).orElseThrow();
                PlayerState seller = g.player(e.seller()).orElseThrow();
                List<CardType> hand = new ArrayList<>(seller.hand());
                check(hand.remove(CardType.TRADE), "trade completed without the trade card");
                Ledger ledger = g.ledger().transfer(e.buyer(), e.seller(), e.price(), "TRADE", "tile-" + e.tile());
                yield g.withLedger(ledger).withTrade(null).withPlayer(seller.withHand(hand))
                        .withBoard(g.board().with(new OwnableState(o.tile(), e.buyer(), o.level(), false, 0, null)));
            }
            case TradeDeclined e -> {
                check(t != null && t.buyer().equals(e.buyer()) && t.tile() == e.tile(), "trade decline mismatch");
                OwnableState o = g.board().ownable(e.tile()).orElseThrow();
                yield g.withTrade(null).withBoard(g.board().with(new OwnableState(o.tile(), o.owner(), o.level(), false, 0, null)));
            }
            default -> throw new IllegalStateException("not a trade event: " + event);
        };
    }

    // ================================================================ 校验、投影与参与者

    static boolean locks(GameState g, OwnableState o) {
        TradeState t = g.trade();
        return LOCK.equals(o.lockedBy()) && t != null && t.tile() == o.tile();
    }

    static boolean involved(GameState g, String playerId) {
        TradeState t = g.trade();
        return t != null && (playerId.equals(t.seller()) || playerId.equals(t.buyer()));
    }

    static void validate(GameState g, RuleConfig config) {
        TradeState t = g.trade();
        if (t == null) {
            if (enabled(config)) {
                LobbyModule.expect(g.flow().frames().stream().noneMatch(f -> f.kind() == FlowKind.TRADE), "a trade window without a trade");
            }
            return;
        }
        LobbyModule.expect(enabled(config) && g.auction() == null, "trades require the card rules");
        FlowFrame top = g.flow().top().orElse(null);
        OwnableState o = g.board().ownable(t.tile()).orElse(null);
        Pricing pricing = new Pricing(config);
        LobbyModule.expect(top != null && g.flow().frames().size() == 1 && top.kind() == FlowKind.TRADE && top.owner().equals(t.seller())
                && g.turn().stage() == TurnStage.AWAITING_FLOW && g.turn().continuation() instanceof Continuation.BeginTurn
                && o != null && t.seller().equals(o.owner()) && LOCK.equals(o.lockedBy()) && !o.mortgaged()
                && g.player(t.seller()).map(p -> p.alive() && p.hand().contains(CardType.TRADE)).orElse(false)
                && !t.buyer().equals(t.seller()) && g.player(t.buyer()).map(PlayerState::alive).orElse(false)
                && t.price() >= pricing.tradeMin(standard(config, g, t.tile())) && t.price() <= pricing.tradeMax(standard(config, g, t.tile())),
                "trade invalid");
        Tile tile = LobbyModule.board(config, g.settings()).tiles().get(t.tile());
        LobbyModule.expect(tile.type() == TileType.PROPERTY || tile.type() == TileType.STATION, "trade tile invalid");
    }

    static GameView.PublicTrade view(GameState g) {
        TradeState t = g.trade();
        if (t == null) {
            return null;
        }
        long windowId = g.flow().top().filter(f -> f.kind() == FlowKind.TRADE).map(FlowFrame::windowId).orElse(0L);
        return new GameView.PublicTrade(t.seller(), t.buyer(), t.tile(), t.price(), windowId);
    }

    private static void check(boolean condition, String message) {
        LobbyModule.check(condition, message);
    }
}
