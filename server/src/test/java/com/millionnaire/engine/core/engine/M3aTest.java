package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3aTest {
    @Test
    void aDirectTradeAtRollOpensAtIsRejectedWithoutEvents() {
        Table t = M2bReviewTest.table(3);
        t.tick(t.window().window().opensAt());
        var c = M2bReviewTest.ctx(t);
        assertThrows(IllegalStateException.class,
                () -> GameModule.openOverlay(c, FlowKind.TRADE, "p2", 0, 15_000, "x"));
        assertTrue(c.events().isEmpty());
        assertEquals(t.state, c.engineState());
    }

    static Table realDebt() {
        Table t = M2bReviewTest.table(2, 1, 5, 2, 1, 1, 1, 1);
        for (String p : List.of("p1", "p2", "p1", "p2")) {
            t.rollThenResolveEvent();
            t.act(w -> new GameCommand.BuyProperty(p, w));
            t.act(w -> new GameCommand.UpgradeProperty(p, w));
        }
        t.rollThenResolveEvent();
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        t.rollThenResolveEvent();
        t.rollThenResolveEvent();
        assertEquals(400, t.cash("p1"));
        assertEquals(FlowKind.DEBT, t.window().kind());
        assertEquals(t.state, t.engine.rebuild(t.log));
        return t;
    }

    @Test
    void theFirstDebtHasItsOwnNumberAfterSeveralDebtFreeLandings() {
        Table t = realDebt();
        assertEquals(1, t.game().debt().debtId());
        assertNotEquals(t.game().turn().landing().landingId(), t.game().debt().debtId());
    }
    @Test
    void requestsCanQueueDuringBuyButCannotStartUntilDequeuedAtTheNextSafePoint() {
        Table t = M2bReviewTest.table(3, 1);
        t.rollThenResolveEvent();
        var c = M2bReviewTest.ctx(t);
        assertNull(FlowCoordinator.request(c, GameModule.FLOW, FlowKind.TRADE, "p2"));
        assertNull(FlowCoordinator.request(c, GameModule.FLOW, FlowKind.AUCTION, "p3"));
        M2bReviewTest.commit(t, c);
        assertEquals(2, t.game().flow().queue().size());
        c = M2bReviewTest.ctx(t);
        var denied = c;
        var queued = t.game().flow().queue().getFirst();
        assertThrows(IllegalStateException.class, () -> GameModule.openQueuedOverlay(denied, queued, 0, 15_000, "x"));
        assertTrue(c.events().isEmpty());
        assertEquals(t.state, c.engineState());
        t.act(w -> new GameCommand.DeclinePurchase("p1", w));
        assertEquals(FlowKind.TRADE, t.window().kind());
        assertNull(t.game().flow().pendingStart());
        assertEquals(1, t.game().flow().queue().size());
        c = M2bReviewTest.ctx(t);
        var notSafe = c;
        assertThrows(IllegalStateException.class, () -> FlowCoordinator.dequeueAtSafePoint(notSafe, GameModule.FLOW));
        assertTrue(c.events().isEmpty());
        var consumed = c;
        assertThrows(IllegalStateException.class, () -> GameModule.openQueuedOverlay(consumed, queued, 0, 15_000, "x"));
        assertTrue(c.events().isEmpty());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void stagePermissionsSeparateAttacksFromQueueAndRequireTheResponseParent() {
        Table t = M2bReviewTest.table(3);
        t.tick(t.window().window().opensAt());
        var c = M2bReviewTest.ctx(t);
        assertFalse(StageTable.rule(StageTable.Point.ROLL).preemptible(FlowKind.TRADE));
        assertFalse(StageTable.rule(StageTable.Point.ROLL).preemptible(FlowKind.AUCTION));
        assertTrue(StageTable.rule(StageTable.Point.ROLL).preemptible(FlowKind.ATTACK));
        assertFalse(StageTable.rule(StageTable.Point.BUY).preemptible(FlowKind.ATTACK));
        assertThrows(IllegalStateException.class, () -> GameModule.openOverlay(c, FlowKind.ATTACK, "p2", 0, 15_000, "x"));
        assertThrows(IllegalStateException.class, () -> GameModule.openSourcedOverlay(c, FlowKind.RESPONSE, "p1", 0, 5000, "x",
                new FlowOrigin(FlowOrigin.Kind.ATTACK_RESPONSE, t.game().turn().turnNo(), t.windowId(), -1, "p1")));
        assertTrue(c.events().isEmpty());
        GameModule.openOverlay(c, FlowKind.ATTACK, "p1", 0, 15_000, "x");
        M2bReviewTest.commit(t, c);
        var response = M2bReviewTest.ctx(t);
        long turn = t.game().turn().turnNo();
        long parent = t.window().windowId();
        assertThrows(IllegalStateException.class, () -> GameModule.openSourcedOverlay(response, FlowKind.RESPONSE, "p1", 0, 5000, "x",
                new FlowOrigin(FlowOrigin.Kind.ATTACK_RESPONSE, turn, parent + 1, -1, "p1")));
        assertThrows(IllegalStateException.class, () -> GameModule.openSourcedOverlay(response, FlowKind.RESPONSE, "p2", 0, 5000, "x",
                new FlowOrigin(FlowOrigin.Kind.ATTACK_RESPONSE, turn, parent, -1, "p1")));
        assertTrue(response.events().isEmpty());
        GameModule.openSourcedOverlay(response, FlowKind.RESPONSE, "p1", 0, 5000, "x",
                new FlowOrigin(FlowOrigin.Kind.ATTACK_RESPONSE, turn, parent, -1, "p1"));
        M2bReviewTest.commit(t, response);
        assertEquals(FlowKind.RESPONSE, t.window().kind());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

}
