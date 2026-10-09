package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.testkit.ScriptedRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3bTest {
    static Table event(int kind, List<ScriptedRandom.Step> payload) {
        return new Table(new ScriptedRandom(Table.script(Table.order(90, 10), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 2), Table.steps(DrawPoint.EVENT_KIND, 100, kind), payload)), 1).start(2);
    }
    private static Table timedEvent(int kind, List<ScriptedRandom.Step> payload) {
        var c = com.millionnaire.engine.testkit.TestBoards.legacyV1();
        var timed = new com.millionnaire.engine.config.RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing().withPresentation(3700, 2300, 1800, 2600), c.room(), c.rentInflation(), c.setBonus(), c.startPick());
        return new Table(timed, new ScriptedRandom(Table.script(Table.order(90, 10), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 2), Table.steps(DrawPoint.EVENT_KIND, 100, kind), payload)), 1).start(2);
    }

    @Test void nextRollStartsAfterCashEventPresentationWithFullDuration() {
        var t = timedEvent(0, Table.steps(DrawPoint.EVENT_CASH, 9, 0));
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals("p2", t.current());
        assertEquals(t.now + 2300, t.window().window().opensAt());
        assertEquals(15000, t.window().window().deadline() - t.window().window().opensAt());
        assertEquals(StepResult.Outcome.REJECTED, t.send(t.now, new GameCommand.RollDice("p2", t.windowId())).outcome());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test void eventMoveAddsItsPresentationBeforeTheDestinationWindow() {
        var t = timedEvent(80, Table.script(Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, 0),
                Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 0)));
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(3, t.position("p1"));
        assertEquals(t.now + 3700 + t.config.timing().animPerStepMs(), t.window().window().opensAt());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test void jailEventWaitsForTheEventAndJailAnimations() {
        var t = timedEvent(95, List.of());
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals("p2", t.current());
        assertEquals(t.now + 3700 + 2600, t.window().window().opensAt());
    }

    @Test void rewardIsTransferredFromSystemUsingTheAuditedAmount() {
        var t = event(0, Table.steps(DrawPoint.EVENT_CASH, 9, 8));
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(3500, t.cash("p1"));
        assertEquals(1, t.log.stream().filter(GameEvent.EventRewardPaid.class::isInstance).count());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void fineIsTransferredToTheSystemWithoutAPhantomPlayerCreditor() {
        var t = event(30, Table.steps(DrawPoint.EVENT_CASH, 9, 0));
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(2900, t.cash("p1"));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void timeoutDrawsExactlyOnceAndDuplicateClicksConsumeNoRandom() {
        var t = event(0, Table.steps(DrawPoint.EVENT_CASH, 9, 0));
        t.rollOnly();
        long window = t.windowId();
        t.tick(t.window().window().deadline());
        var rng = t.state.rng();
        assertEquals(StepResult.Outcome.REJECTED, t.send(new GameCommand.DrawEventCard("p1", window)).outcome());
        assertEquals(rng, t.state.rng());
        assertEquals(1, t.log.stream().filter(GameEvent.EventDrawn.class::isInstance).count());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void jailJumpEndsTheTurnAndDoesNotPayStartReward() {
        var t = event(95, List.of());
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(8, t.position("p1"));
        assertTrue(t.game().player("p1").orElseThrow().inJail());
        assertEquals("p2", t.current());
        assertEquals(3000, t.cash("p1"));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void eventRelocationKeepsItsChainAndTriggersAPropertyWindow() {
        var t = event(80, Table.script(Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, 0),
                Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 0)));
        t.rollOnly();
        long chain = t.game().turn().chain().chainId();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(3, t.position("p1"));
        assertEquals(LandingStep.BUY, t.game().turn().landing().step());
        assertEquals(chain, t.game().turn().chain().chainId());
        assertTrue(t.game().turn().chain().eventDrawn());
        assertEquals(2, t.game().turn().chain().segments().size());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void eventForwardAndBackwardLandingsPayRentToTheOwner() {
        for (int direction : new int[] {0, 1}) {
            var t = event(80, Table.script(Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, direction),
                    Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 0)));
            int target = direction == 0 ? 3 : 1;
            craft(t, g -> g.withBoard(g.board().with(new com.millionnaire.engine.core.state.OwnableState(target, "p2", 2, false, 0, null))));
            t.rollOnly();
            t.act(w -> new GameCommand.DrawEventCard("p1", w));
            assertEquals(target, t.position("p1"));
            assertEquals(2550, t.cash("p1"));
            assertEquals(3450, t.cash("p2"));
            assertEquals(List.of(new GameEvent.RentPaid("p1", "p2", target, 450)),
                    t.log.stream().filter(GameEvent.RentPaid.class::isInstance).map(GameEvent.RentPaid.class::cast).toList());
            assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        }
    }

    @Test void cardKindIsPrivateWhileReceiptAndHandSizeArePublic() {
        var t = event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, 999));
        t.rollOnly();
        int start = t.log.size();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        var received = t.log.subList(start, t.log.size());
        assertEquals(3, t.game().player("p1").orElseThrow().hand().size());
        assertEquals(com.millionnaire.engine.config.CardType.CLEAR_LAND, t.game().player("p1").orElseThrow().hand().getLast());
        for (String viewer : new String[] {"p2", "spectator"}) {
            var projected = EventProjector.project(received, viewer);
            assertFalse(projected.stream().anyMatch(GameEvent.EventCardReceived.class::isInstance));
            assertTrue(projected.stream().anyMatch(GameEvent.EventHandCount.class::isInstance));
            assertFalse(t.engine.encodeEvents(projected).contains("CLEAR_LAND"));
        }
        assertTrue(EventProjector.project(received, "p1").stream().anyMatch(GameEvent.EventCardReceived.class::isInstance));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    static void craft(Table t, java.util.function.UnaryOperator<com.millionnaire.engine.core.state.GameState> f) {
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(f.apply(t.game())))));
    }
    @Test void overflowOpensFifteenSecondsAndTimeoutDiscardsOnlyTheNewCard() {
        var t = event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, 999));
        var six = java.util.Collections.nCopies(6, com.millionnaire.engine.config.CardType.ROADBLOCK);
        craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().withHand(six)));
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(LandingStep.DISCARD, t.game().turn().landing().step());
        assertEquals(7, t.game().player("p1").orElseThrow().hand().size());
        assertEquals(15_000, t.window().window().deadline() - t.window().window().opensAt());
        var snapshot = t.engine.snapshot(t.state);
        var input = new com.millionnaire.engine.core.command.Input(t.seq + 1, t.window().window().deadline(), new com.millionnaire.engine.core.command.Tick());
        var expected = t.engine.step(t.state, input);
        assertEquals(expected, t.engine.step(t.engine.restore(snapshot), input));
        t.tick(input.serverTime());
        assertEquals(six, t.game().player("p1").orElseThrow().hand());
    }
    @Test void manualDiscardCanKeepTheNewCardAndSelectOneOfRepeatedOldCards() {
        var t = event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, 999));
        craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().withHand(java.util.Collections.nCopies(6,
                com.millionnaire.engine.config.CardType.ROADBLOCK))));
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        var rng = t.state.rng();
        assertEquals(StepResult.Outcome.REJECTED, t.act(w -> new GameCommand.DiscardCard("p1", w, -1)).outcome());
        assertEquals(rng, t.state.rng());
        t.act(w -> new GameCommand.DiscardCard("p1", w, 2));
        assertEquals(6, t.game().player("p1").orElseThrow().hand().size());
        assertEquals(com.millionnaire.engine.config.CardType.CLEAR_LAND, t.game().player("p1").orElseThrow().hand().getLast());
    }
    @Test void fineDebtLocksManualSystemSourceAndUsesExistingTwoSegmentWindows() {
        var t = event(30, Table.steps(DrawPoint.EVENT_CASH, 9, 8));
        craft(t, g -> g.withLedger(g.ledger().transfer("p1", com.millionnaire.engine.ledger.Ledger.SYSTEM, 2900, "TEST", null))
                .withBoard(g.board().with(com.millionnaire.engine.core.state.OwnableState.unowned(1).owned("p1"))));
        t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        var d = t.game().debt();
        assertEquals(1, d.debtId());
        assertEquals(com.millionnaire.engine.core.state.DebtPath.MANUAL, d.path());
        assertNull(d.creditor());
        assertEquals(com.millionnaire.engine.core.state.FeeSource.Kind.FINE, d.source().kind());
        t.tick(t.window().window().deadline());
        assertEquals(2, t.game().debt().segment());
        t.act(w -> new GameCommand.EmergencyMortgage("p1", w, 1));
        assertNull(t.game().debt());
        assertEquals(0, t.cash("p1"));
    }
    @Test void eventLandingWaitsForThePlayerAndDoesNotDrawAtLanding() {
        var t = event(0, Table.steps(DrawPoint.EVENT_CASH, 9, 0));
        var before = t.state.rng();
        t.rollOnly();
        assertNotNull(t.game().turn().landing(), "event must open a draw window instead of ending the turn");
        assertEquals("p1", t.current());
        assertEquals(LandingStep.EVENT, t.game().turn().landing().step());
        assertEquals(15_000, t.window().window().deadline() - t.window().window().opensAt());
        assertEquals(before.s1() + 1, t.state.rng().s1(), "only the movement die is drawn at landing");
    }
    @Test void successorGateIsAnExecutablePredicateRatherThanAnInitialRuleEnum() {
        assertTrue(LandingRules.Gate.class.isInterface(), "successors must accept independent predicates");
    }
}
