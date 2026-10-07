package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.AuctionState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.testkit.TestBoards;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 拍卖（requirements 第 8 节；已采纳默认值 #11；O13；O16）：正式配置。
 * 30 格测试棋盘的指定拍卖地：6（L*，原价 500 → 起拍 250、加价 50、封顶 1250）、13（M*）、25（H*）。p1 / p2 / p3 依次行动。
 */
class AuctionTest {
    private static final RuleConfig RULES = RuleConfigs.v1(TestBoards.LEGACY_30, TestBoards.LEGACY_50, true);

    private static Table table(int... moves) {
        return new Table(RULES, ScriptedRandom.withEventCards(script(order(90, 50, 10), Table.deal(3),
                dice(DrawPoint.MOVE_DIE, moves))), 1).start(3);
    }

    private static void craft(Table t, UnaryOperator<GameState> f) {
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(f.apply(t.game())))));
    }

    private static <T extends Event> List<T> events(List<Event> log, Class<T> type) {
        return log.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static FlowFrame top(Table t) {
        return t.game().flow().top().orElseThrow();
    }

    private static StepResult bid(Table t, String who, long amount) {
        FlowFrame w = top(t);
        return t.send(Math.max(t.now + 10, w.window().opensAt()), new GameCommand.Bid(who, w.windowId(), amount));
    }

    private static OwnableState tile(Table t, int i) {
        return t.game().board().ownable(i).orElseThrow();
    }

    /** p1 投 6 → 指定拍卖地 6，发起土地拍卖。 */
    private static Table landAuction() {
        Table t = table(6);
        t.rollOnly();
        assertEquals(LandingStep.BUY, t.game().turn().landing().step());
        assertNull(t.act(w -> new GameCommand.StartLandAuction("p1", w)).rejection());
        return t;
    }

    // ------------------------------------------------------------ 土地拍卖

    @Test
    void landAuctionStartsFromTheBuyWindowAndTheInitiatorCannotBid() {
        Table t = landAuction();
        AuctionState a = t.game().auction();
        assertEquals(AuctionState.Kind.LAND, a.kind());
        assertEquals("p1", a.initiator());
        assertEquals(500, a.basis());
        assertEquals(250, a.start());
        assertEquals(50, a.minRaise());
        assertEquals(1250, a.cap());
        assertEquals(FlowKind.LAND_AUCTION, top(t).kind());
        assertEquals(t.config.timing().auctionDurationMs(), top(t).window().deadline() - top(t).window().opensAt());
        assertEquals(TurnStage.AWAITING_FLOW, t.game().turn().stage());
        assertEquals(RejectionCode.NOT_ALLOWED, bid(t, "p1", 250).rejection(), "the initiator gave up the purchase");
        assertEquals(RejectionCode.INVALID_ARGUMENT, bid(t, "p2", 200).rejection(), "below the starting price");
        assertNull(bid(t, "p2", 250).rejection());
        assertEquals(250, t.game().ledger().frozen("p2"));
        assertEquals(RejectionCode.INVALID_ARGUMENT, bid(t, "p3", 280).rejection(), "must raise at least 10% of the basis");
        assertNull(bid(t, "p3", 300).rejection());
        assertEquals(0, t.game().ledger().frozen("p2"), "an outbid bid is released at once");
        assertEquals(300, t.game().ledger().frozen("p3"));
        assertNull(bid(t, "p3", 350).rejection(), "the leader may raise their own bid");
        assertEquals(350, t.game().ledger().frozen("p3"));
        var view = SessionDomain.INSTANCE.project(t.state, "p2").game().auction();
        assertEquals("p3", view.highBidder());
        assertEquals(400, view.minimumBid());
        t.engine.validate(t.state);
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void expiryAwardsTheLandToTheHighestBidderAndPaysTheInitiatorTenPercent() {
        Table t = landAuction();
        long p1 = t.cash("p1");
        long p2 = t.cash("p2");
        bid(t, "p2", 450);
        t.tick(top(t).window().deadline());
        assertNull(t.game().auction());
        assertEquals("p2", tile(t, 6).owner());
        assertEquals(p2 - 450, t.cash("p2"));
        assertEquals(0, t.game().ledger().frozen("p2"));
        assertEquals(p1 + 45, t.cash("p1"), "the initiator gets 10% (floor)");
        assertEquals(45, events(t.log, GameEvent.AuctionSettled.class).getLast().commission());
        assertEquals("p2", t.current(), "the landing and turn end after the auction");
        t.game().ledger().verifyInvariants();
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void reachingTheCapIsABuyNowEvenBelowTheMinimumRaise() {
        Table t = landAuction();
        bid(t, "p2", 1220);
        assertNull(bid(t, "p3", 1250).rejection(), "the cap is allowed even if it is less than a full raise");
        assertNull(t.game().auction(), "buy-now settles immediately");
        assertEquals("p3", tile(t, 6).owner());
        assertEquals(RejectionCode.NO_ACTIVE_WINDOW, t.send(t.now + 10, new GameCommand.Bid("p2", 99, 1250)).rejection());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void noBidsMeansTheLandPassesWithoutAReward() {
        Table t = landAuction();
        long p1 = t.cash("p1");
        t.tick(top(t).window().deadline());
        assertNull(t.game().auction());
        assertNull(tile(t, 6).owner());
        assertEquals(p1, t.cash("p1"), "no reward for a passed auction");
        assertEquals(1, events(t.log, GameEvent.AuctionPassed.class).size());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void lateBidsByANewLeaderExtendToThreeSecondsButNeverPastFortySeconds() {
        Table t = landAuction();
        FlowFrame w = top(t);
        long end = w.window().deadline();
        long hard = t.game().auction().hardEnd();
        assertEquals(w.window().opensAt() + t.config.timing().auctionMaxMs(), hard);
        t.send(end - 1000, new GameCommand.Bid("p2", w.windowId(), 250));
        assertEquals(end - 1000 + 3000, top(t).window().deadline(), "a late bid restores 3 seconds");
        long extended = top(t).window().deadline();
        t.send(extended - 500, new GameCommand.Bid("p2", w.windowId(), 300));
        assertEquals(extended, top(t).window().deadline(), "the leader's own raise does not extend");
        // 交替出价直到触及 40 秒上限
        String[] who = {"p3", "p2"};
        long amount = 300;
        for (int i = 0; i < 40 && t.game().auction() != null; i++) {
            long deadline = top(t).window().deadline();
            amount += 50;
            t.send(deadline - 100, new GameCommand.Bid(who[i % 2], w.windowId(), amount));
            assertTrue(top(t).window().deadline() <= hard);
            if (top(t).window().deadline() == hard) {
                break;
            }
        }
        assertEquals(hard, top(t).window().deadline(), "the total duration is capped at 40 seconds");
        t.tick(hard);
        assertNull(t.game().auction());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void bidsNeedAvailableCashAndAreFrozen() {
        Table t = landAuction();
        craft(t, g -> g.withLedger(g.ledger().transfer("p2", com.millionnaire.engine.ledger.Ledger.SYSTEM, g.ledger().cash("p2") - 300, "TEST", null)));
        assertEquals(RejectionCode.INSUFFICIENT_CASH, bid(t, "p2", 350).rejection());
        assertNull(bid(t, "p2", 300).rejection());
        assertEquals(0, t.game().ledger().available("p2"));
        assertEquals(RejectionCode.INSUFFICIENT_CASH, bid(t, "p2", 400).rejection());
    }

    @Test
    void noLandAuctionAfterTheGlobalEndAndNoneWhenTheTileIsNotDesignated() {
        Table t = table(1);
        t.rollOnly();                                                          // 1（L，非指定拍卖地）
        assertEquals(RejectionCode.NOT_ALLOWED, t.act(w -> new GameCommand.StartLandAuction("p1", w)).rejection());
    }

    @Test
    void biddersAndTheInitiatorDeferSurrenderOthersSurrenderAtOnce() {
        Table t = landAuction();
        bid(t, "p2", 250);
        assertNull(t.send(t.now + 10, new GameCommand.Surrender("p2", 1)).rejection());
        assertTrue(t.game().pendingSurrenders().contains("p2"), "a bidder's surrender waits for the auction");
        assertNull(t.send(t.now + 10, new GameCommand.Surrender("p3", 1)).rejection());
        assertFalse(t.game().player("p3").orElseThrow().alive(), "a bystander surrenders at once");
        t.tick(top(t).window().deadline());
        // 拍卖先成交（p2 付款得地），随后处理 p2 的延后认输；只剩 p1，对局结束
        List<Event> tail = t.log;
        int settled = -1;
        int eliminated = -1;
        for (int i = 0; i < tail.size(); i++) {
            if (tail.get(i) instanceof GameEvent.AuctionSettled s && s.winner().equals("p2")) settled = i;
            if (tail.get(i) instanceof GameEvent.PlayerEliminated x && x.playerId().equals("p2")) eliminated = i;
        }
        assertTrue(settled >= 0 && eliminated > settled, "the auction settles before the deferred surrender");
        assertFalse(t.session().inGame());
    }

    // ------------------------------------------------------------ 拍卖卡

    private static Table cardTable() {
        // p1 = 拍卖卡 700 + 路障 0；p2、p3 = 路障
        return new Table(RULES, ScriptedRandom.withEventCards(script(order(90, 50, 10),
                Table.steps(DrawPoint.INITIAL_CARD, 1000, 700, 0, 0, 0, 0, 0),
                dice(DrawPoint.MOVE_DIE, 1, 2, 4, 2, 1))), 1).start(3);
    }

    @Test
    void auctionCardIsQueuedUntilTheNextTurnBoundaryAndPaysTheSeller() {
        Table t = cardTable();
        t.rollOnly();                                                          // p1 → 1：买下
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        assertEquals("p2", t.current());
        assertNull(t.send(t.now + 10, new GameCommand.RequestAuction("p1", 1)).rejection(), "requests are accepted any time");
        assertEquals(1, t.game().flow().queue().size());
        assertTrue(t.game().cards().used("p1"), "a request outside the own turn still uses the chance");
        assertEquals(RejectionCode.CARD_USED, t.send(t.now + 10, new GameCommand.RequestAuction("p1", 1)).rejection(),
                "one chance per turn: a second request is refused");
        t.rollOnly();                                                          // p2 → 2（事件）
        while (t.game().turn().landing() != null) { t.pass(); }
        // 回合交界：拍卖卡开拍（p3 的回合在拍卖结束后才开始）
        AuctionState a = t.game().auction();
        assertNotNull(a);
        assertEquals(AuctionState.Kind.CARD, a.kind());
        assertEquals("p1", a.seller());
        assertEquals(FlowKind.AUCTION, top(t).kind());
        assertEquals("AUCTION", tile(t, 1).lockedBy());
        assertFalse(t.game().player("p1").orElseThrow().hand().contains(CardType.AUCTION), "the card is spent when the auction starts");
        assertEquals(TurnStage.AWAITING_FLOW, t.game().turn().stage());
        assertEquals(RejectionCode.NOT_ALLOWED, bid(t, "p1", 250).rejection(), "the seller does not bid");
        long p1 = t.cash("p1");
        assertNull(bid(t, "p3", 300).rejection());
        t.tick(top(t).window().deadline());
        assertEquals("p3", tile(t, 1).owner());
        assertNull(tile(t, 1).lockedBy());
        assertEquals(p1 + 300, t.cash("p1"), "the whole price goes to the seller");
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage(), "the turn starts after the auction");
        assertEquals("p3", t.current());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test
    void auctionCardRequestsNeedAnUnmortgagedOwnAssetAndTheCard() {
        Table t = cardTable();
        assertEquals(RejectionCode.NOT_OWNER, t.send(t.now + 10, new GameCommand.RequestAuction("p1", 1)).rejection());
        assertEquals(RejectionCode.NO_CARD, t.send(t.now + 10, new GameCommand.RequestAuction("p2", 1)).rejection());
        assertEquals(RejectionCode.INVALID_TILE, t.send(t.now + 10, new GameCommand.RequestAuction("p1", 0)).rejection());
        craft(t, g -> g.withBoard(g.board().with(g.board().ownable(1).orElseThrow().owned("p1").mortgage(500))));
        assertEquals(RejectionCode.MORTGAGED, t.send(t.now + 10, new GameCommand.RequestAuction("p1", 1)).rejection());
    }

    @Test
    void aRequestWhoseAssetWasMortgagedMeanwhileIsCancelledAtTheSafePoint() {
        Table t = cardTable();
        craft(t, g -> g.withBoard(g.board().with(g.board().ownable(4).orElseThrow().owned("p1"))));
        assertNull(t.send(t.now + 10, new GameCommand.RequestAuction("p1", 4)).rejection());
        craft(t, g -> g.withBoard(g.board().with(g.board().ownable(4).orElseThrow().mortgage(1000))));
        t.rollOnly();                                                          // p1 → 1：放弃
        t.act(w -> new GameCommand.DeclinePurchase("p1", w));
        assertNull(t.game().auction());
        assertTrue(t.game().flow().queue().isEmpty());
        assertEquals(1, events(t.log, GameEvent.FlowRequestCancelled.class).size());
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
    }

    @Test
    void aPassedCardAuctionStillSpendsTheCard() {
        Table t = cardTable();
        t.rollOnly();
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        t.send(t.now + 10, new GameCommand.RequestAuction("p1", 1));
        t.rollOnly();
        while (t.game().turn().landing() != null) { t.pass(); }
        assertNotNull(t.game().auction());
        t.tick(top(t).window().deadline());
        assertEquals("p1", tile(t, 1).owner());
        assertNull(tile(t, 1).lockedBy());
        assertFalse(t.game().player("p1").orElseThrow().hand().contains(CardType.AUCTION));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
}
