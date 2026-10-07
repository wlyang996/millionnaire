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
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameState;
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
 * 交易卡（requirements 第 14 节；open-decisions #12）：正式配置。p1 / p2 / p3 依次行动；p1 开局拿到交易卡与路障。
 * 30 格测试棋盘 1 号为低价地产（原价 500，标准价值 500 → 交易价 250～1250）。
 */
class TradeTest {
    private static final RuleConfig RULES = RuleConfigs.v1(TestBoards.LEGACY_30, TestBoards.LEGACY_50, true);

    /** p1 = 交易卡（750）+ 路障；p2、p3 = 路障。p1 先投 1 买下 1 号地，p2 投 2（事件）。 */
    private static Table owned() {
        Table t = new Table(RULES, ScriptedRandom.withEventCards(script(order(90, 50, 10),
                Table.steps(DrawPoint.INITIAL_CARD, 1000, 750, 0, 0, 0, 0, 0),
                dice(DrawPoint.MOVE_DIE, 1, 2, 3, 3))), 1).start(3);
        t.rollOnly();
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        assertEquals("p1", tile(t, 1).owner());
        assertEquals("p2", t.current());
        return t;
    }

    private static void craft(Table t, UnaryOperator<GameState> f) {
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(f.apply(t.game())))));
    }

    private static OwnableState tile(Table t, int i) {
        return t.game().board().ownable(i).orElseThrow();
    }

    private static FlowFrame top(Table t) {
        return t.game().flow().top().orElseThrow();
    }

    private static <T extends Event> List<T> events(List<Event> log, Class<T> type) {
        return log.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** p2 走完自己的回合，到回合交界：排队的交易开始。 */
    private static void toBoundary(Table t) {
        t.rollOnly();
        while (t.game().trade() == null && t.game().turn().landing() != null) {
            t.pass();
        }
    }

    private static StepResult answer(Table t, String who, boolean accept) {
        FlowFrame w = top(t);
        return t.send(Math.max(t.now + 10, w.window().opensAt()), new GameCommand.AnswerTrade(who, w.windowId(), accept));
    }

    @Test
    void requestsNeedTheCardAnOwnUnmortgagedAssetALiveBuyerAndAPriceInRange() {
        Table t = owned();
        assertEquals(RejectionCode.INVALID_ARGUMENT, t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p2", 249)).rejection(),
                "at least 50% of the standard value");
        assertEquals(RejectionCode.INVALID_ARGUMENT, t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p2", 1251)).rejection(),
                "at most 2.5 times");
        assertEquals(RejectionCode.INVALID_ARGUMENT, t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p1", 500)).rejection());
        assertEquals(RejectionCode.NO_CARD, t.send(t.now + 10, new GameCommand.RequestTrade("p2", 1, "p1", 500)).rejection());
        assertEquals(RejectionCode.NOT_OWNER, t.send(t.now + 10, new GameCommand.RequestTrade("p1", 3, "p2", 500)).rejection());
        assertTrue(t.game().flow().queue().isEmpty());
        assertFalse(t.game().cards().used("p1"));
    }

    @Test
    void acceptedTradePaysTheSellerAndSpendsTheCard() {
        Table t = owned();
        assertNull(t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p2", 800)).rejection(), "requests are accepted any time");
        assertTrue(t.game().cards().used("p1"));
        toBoundary(t);
        assertNotNull(t.game().trade());
        assertEquals(FlowKind.TRADE, top(t).kind());
        assertEquals(t.config.timing().tradeResponseMs(), top(t).window().deadline() - top(t).window().opensAt());
        assertEquals("TRADE", tile(t, 1).lockedBy());
        assertEquals(TurnStage.AWAITING_FLOW, t.game().turn().stage());
        var view = SessionDomain.INSTANCE.project(t.state, "p2").game().trade();
        assertEquals("p2", view.buyer());
        assertEquals(800, view.price());
        assertEquals(RejectionCode.NOT_YOUR_WINDOW, answer(t, "p3", true).rejection());
        long p1 = t.cash("p1");
        long p2 = t.cash("p2");
        assertNull(answer(t, "p2", true).rejection());
        assertNull(t.game().trade());
        assertEquals("p2", tile(t, 1).owner());
        assertNull(tile(t, 1).lockedBy());
        assertEquals(p1 + 800, t.cash("p1"));
        assertEquals(p2 - 800, t.cash("p2"));
        assertFalse(t.game().player("p1").orElseThrow().hand().contains(CardType.TRADE));
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage(), "the turn starts after the trade");
        assertEquals("p3", t.current());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test
    void declinedOrTimedOutTradesKeepTheCardButNotTheChance() {
        Table t = owned();
        t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p2", 500));
        toBoundary(t);
        assertNull(answer(t, "p2", false).rejection());
        assertEquals("p1", tile(t, 1).owner());
        assertNull(tile(t, 1).lockedBy());
        assertTrue(t.game().player("p1").orElseThrow().hand().contains(CardType.TRADE), "a refused trade keeps the card");
        assertFalse(events(t.log, GameEvent.TradeDeclined.class).getLast().auto());

        Table x = owned();
        x.send(x.now + 10, new GameCommand.RequestTrade("p1", 1, "p3", 500));
        toBoundary(x);
        x.tick(top(x).window().deadline());
        assertTrue(events(x.log, GameEvent.TradeDeclined.class).getLast().auto(), "a timeout is a refusal");
        assertEquals("p1", tile(x, 1).owner());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(x.state, x.engine.rebuild(x.log));
    }

    @Test
    void theBuyerNeedsEnoughAvailableCashToAccept() {
        Table t = owned();
        t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p2", 1200));
        toBoundary(t);
        craft(t, g -> g.withLedger(g.ledger().transfer("p2", com.millionnaire.engine.ledger.Ledger.SYSTEM, g.ledger().cash("p2") - 1000, "TEST", null)));
        assertEquals(RejectionCode.INSUFFICIENT_CASH, answer(t, "p2", true).rejection());
        assertNotNull(t.game().trade(), "the buyer may still refuse");
        assertNull(answer(t, "p2", false).rejection());
    }

    @Test
    void hostedBuyersNeverAccept() {
        Table t = owned();
        t.send(t.now + 1, new GameCommand.SetControl(t.game().gameNo(), "p3", ControlMode.HOSTED));
        t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p3", 500));
        toBoundary(t);
        assertEquals(t.config.timing().autoActDelayMs(), top(t).window().deadline() - top(t).window().opensAt());
        t.tick(top(t).window().deadline());
        assertNull(t.game().trade());
        assertEquals("p1", tile(t, 1).owner());
    }

    @Test
    void aTradeWhoseAssetWasMortgagedMeanwhileIsCancelled() {
        Table t = owned();
        t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p2", 500));
        craft(t, g -> g.withBoard(g.board().with(g.board().ownable(1).orElseThrow().mortgage(500))));
        toBoundary(t);
        assertNull(t.game().trade());
        assertEquals(1, events(t.log, GameEvent.FlowRequestCancelled.class).size());
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
    }

    @Test
    void sellerAndBuyerDeferTheirSurrenderUntilTheTradeEnds() {
        Table t = owned();
        t.send(t.now + 10, new GameCommand.RequestTrade("p1", 1, "p2", 500));
        toBoundary(t);
        assertNull(t.send(t.now + 10, new GameCommand.Surrender("p2", 1)).rejection());
        assertTrue(t.game().pendingSurrenders().contains("p2"));
        assertNull(answer(t, "p2", true).rejection(), "a pending surrender does not cancel the buyer's answer");
        assertFalse(t.game().player("p2").orElseThrow().alive(), "settled after the trade");
        assertNull(tile(t, 1).owner(), "the surrendered buyer's assets are reclaimed");
    }
}
