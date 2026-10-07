package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.testkit.Table;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import java.util.List;

class M3cTest {
    // Reflection keeps the behavior contracts executable before the internal API exists.
    static RejectionCode effect(Table t, String method, Object... extra) {
        var c = M2bReviewTest.ctx(t);
        try {
            var type = Class.forName("com.millionnaire.engine.core.engine.MovementEffects");
            var args = new Object[3 + extra.length];
            args[0] = c; args[1] = t.current(); args[2] = t.windowId();
            System.arraycopy(extra, 0, args, 3, extra.length);
            var signature = extra.length == 0
                    ? new Class<?>[] {DecisionContext.class, String.class, long.class}
                    : new Class<?>[] {DecisionContext.class, String.class, long.class, int.class};
            var result = (RejectionCode) type.getDeclaredMethod(method, signature).invoke(null, args);
            if (result == null) { M2bReviewTest.commit(t, c); }
            else { assertTrue(c.events().isEmpty()); assertEquals(t.state, c.engineState()); }
            return result;
        } catch (InvocationTargetException e) {
            throw new AssertionError("internal effect failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            return fail("missing M3c internal movement primitive: " + method, e);
        }
    }
    @Test void placementRejectsTheSecondBlockWithoutChangingStateOrRandomness() {
        var t = M2bReviewTest.table(2, 1);
        t.tick(t.window().window().opensAt());
        var rng = t.state.rng(); var hand = t.game().player(t.current()).orElseThrow().hand();
        assertNull(effect(t, "placeRoadblock"));
        var state = t.state;
        assertEquals(RejectionCode.ALREADY_OWNED, effect(t, "placeRoadblock"));
        assertEquals(state, t.state); assertEquals(rng, t.state.rng());
        assertEquals(hand, t.game().player("p1").orElseThrow().hand());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void targetedReplacesTheDieAndKeepsRandomnessAndOrdinaryLanding() {
        var t = M2bReviewTest.table(2);
        t.tick(t.window().window().opensAt());
        var rng = t.state.rng();
        assertNull(effect(t, "targeted", 1));
        assertEquals(1, t.position("p1"));
        assertEquals(com.millionnaire.engine.core.state.LandingStep.BUY, t.game().turn().landing().step());
        assertEquals(rng, t.state.rng());
        assertFalse(t.log.stream().anyMatch(com.millionnaire.engine.core.event.GameEvent.DiceRolled.class::isInstance));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    static void ready(Table t) { t.tick(Math.max(t.now, t.window().window().opensAt())); }
    static void target(Table t, int steps) { ready(t); assertNull(effect(t, "targeted", steps)); }
    static void place(Table t) { ready(t); assertNull(effect(t, "placeRoadblock")); }
    static void settle(Table t) {
        while (t.session().inGame() && t.game().turn().stage() == TurnStage.LANDING) { t.pass(); }
    }
    static GameEvent.PlayerMoved lastMove(Table t) {
        return (GameEvent.PlayerMoved) t.log.get(M2bReviewTest.lastIndexOf(t.log, GameEvent.PlayerMoved.class));
    }

    /** Real history, no snapshot edits: p2 places at 1, p1 approaches from 28 after targeted turns. */
    static Table approach(int die) { return approach(die, false); }
    static Table approach(int die, boolean buy) {
        var t = M2bReviewTest.table(2, die);
        target(t, 6); settle(t); target(t, 1);
        if (buy) { t.act(w -> new GameCommand.BuyProperty("p2", w)); }
        settle(t);
        target(t, 6); settle(t); place(t); target(t, 3); settle(t);
        target(t, 6); settle(t); target(t, 1); settle(t);
        target(t, 6); settle(t); target(t, 1); settle(t);
        target(t, 4); settle(t); target(t, 3); settle(t);
        assertEquals(28, t.position("p1")); assertEquals(1, t.game().board().roadblocks().size());
        return t;
    }
    static Table stopped() { var t = approach(6); t.rollOnly(); return t; }

    @Test void diceStopsAtTheFirstForeignBlockAndLandsNormallyWithActualAnimation() {
        var t = stopped(); var move = lastMove(t); var segment = t.game().turn().chain().segments().getLast();
        assertEquals(1, t.position("p1")); assertEquals(3, move.steps()); assertEquals(6, move.plannedDistance());
        assertEquals(List.of(29, 0, 1), segment.walked()); assertEquals(move.stoppedBy(), segment.stoppedBy());
        assertEquals("p2", segment.stoppedBy().owner()); assertTrue(t.game().board().roadblocks().isEmpty());
        assertEquals(LandingStep.BUY, t.game().turn().landing().step()); assertEquals(4000, t.cash("p1"));
        assertEquals(t.now + t.config.timing().animDiceMs() + 3 * t.config.timing().animPerStepMs(), t.window().window().opensAt());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void aBlockOnThePlannedEndpointStillTriggersAndIsRemoved() {
        var t = approach(3); t.rollOnly();
        assertEquals(3, lastMove(t).steps()); assertEquals(3, lastMove(t).plannedDistance());
        assertNotNull(lastMove(t).stoppedBy()); assertTrue(t.game().board().roadblocks().isEmpty());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void interruptedMovementPaysRentOnTheActualLanding() {
        var t = approach(6, true); t.rollOnly();
        assertEquals(1, t.position("p1")); assertEquals(3900, t.cash("p1")); assertEquals(2600, t.cash("p2"));
        var rent = t.log.stream().filter(GameEvent.RentCharged.class::isInstance).map(GameEvent.RentCharged.class::cast).findFirst().orElseThrow();
        assertEquals(1, rent.tile()); assertEquals(100, rent.amount()); assertEquals("p2", rent.owner());
        assertTrue(t.game().board().roadblocks().isEmpty()); assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void targetedIgnoresBothAlongThePathAndAtItsEndpoint() {
        for (int steps : new int[] {3, 6}) {
            var t = approach(1); var block = t.game().board().roadblocks().getFirst(); var rng = t.state.rng();
            target(t, steps);
            assertEquals(List.of(block), t.game().board().roadblocks()); assertNull(lastMove(t).stoppedBy());
            assertEquals(3000, t.cash("p1")); assertEquals(rng, t.state.rng());
            assertFalse(t.log.stream().anyMatch(GameEvent.RoadblockTriggered.class::isInstance));
            assertEquals(t.state, t.engine.rebuild(t.log));
        }
    }
    @Test void targetedLandingOnStartPaysOnceButPassingStartPaysNothing() {
        var t = approach(1); target(t, 2);
        assertEquals(0, t.position("p1")); assertEquals(4000, t.cash("p1"));
        assertEquals(1, t.log.stream().filter(GameEvent.StartRewardPaid.class::isInstance).count());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void backwardEventStopsOnStartAndNeverPaysReward() {
        var script = Table.script(Table.order(90, 10), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 2), Table.steps(DrawPoint.EVENT_KIND, 100, 80),
                Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, 1), Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 2),
                Table.dice(DrawPoint.MOVE_DIE, 2), Table.steps(DrawPoint.EVENT_KIND, 100, 80),
                Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, 1), Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 2));
        var t = new Table(new ScriptedRandom(script), 1).start(2);
        place(t); t.roll(); // Owner crosses its own block backward and keeps it.
        assertEquals(29, t.position("p1")); assertEquals(1, t.game().board().roadblocks().size());
        t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p2", w));
        var move = lastMove(t);
        assertEquals(MoveKind.EVENT_BACKWARD, move.kind()); assertEquals(0, move.to());
        assertEquals(2, move.steps()); assertEquals(3, move.plannedDistance());
        assertTrue(t.game().board().roadblocks().isEmpty()); assertEquals(3000, t.cash("p2"));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void departureTileAndTheOwnersBlockDoNotIntercept() {
        var t = M2bReviewTest.table(2, 1); place(t); t.rollOnly();
        assertEquals(1, t.position("p1")); assertNull(lastMove(t).stoppedBy());
        assertEquals(1, t.game().board().roadblocks().size()); assertEquals(t.state, t.engine.rebuild(t.log));
        var board = t.game().board();
        assertNull(MovementRules.resolve(MovementRules.segment(1, MoveKind.DICE, 29, 2, 30), board, "p1", 30).stoppedBy());
        assertNull(MovementRules.resolve(MovementRules.segment(1, MoveKind.DICE, 0, 1, 30), board, "p2", 30).stoppedBy());
    }
    @Test void forwardAndBackwardWalkersUseTheFirstEnteredForeignObject() {
        var t = M2bReviewTest.table(2);
        var b = t.game().board().placed(new Roadblock(1, 29, "p2", 1)).placed(new Roadblock(2, 0, "p2", 1));
        var forward = MovementRules.resolve(MovementRules.segment(1, MoveKind.EVENT_FORWARD, 28, 3, 30), b, "p1", 30);
        assertEquals(29, forward.to()); assertFalse(forward.startEligible()); assertEquals(1, forward.distance());
        var backward = MovementRules.resolve(MovementRules.segment(1, MoveKind.EVENT_BACKWARD, 1, 3, 30), b, "p1", 30);
        assertEquals(0, backward.to()); assertFalse(backward.startEligible()); assertEquals(1, backward.distance());
        assertEquals(2, b.roadblocks().size(), "geometry does not mutate the board");
    }
    @Test void jailJumpIgnoresRoadblocksOnItsWholeRoute() {
        var t = M3bTest.event(95, List.of()); place(t); t.rollOnly();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(MoveKind.TO_JAIL, lastMove(t).kind()); assertNull(lastMove(t).stoppedBy());
        assertEquals(0, lastMove(t).plannedDistance()); assertEquals(8, t.position("p1"));
        assertEquals(1, t.game().board().roadblocks().size()); assertEquals(3000, t.cash("p1"));
        assertEquals("p2", t.current()); assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void positionsAndOwnersArePublicToEveryViewer() {
        var t = M2bReviewTest.table(2); place(t);
        for (String viewer : List.of("p1", "p2", "spectator")) {
            assertEquals(t.game().board().roadblocks(), SessionDomain.INSTANCE.project(t.state, viewer).game().board().roadblocks());
            assertTrue(EventProjector.project(t.log, viewer).stream().anyMatch(GameEvent.RoadblockPlaced.class::isInstance));
            assertFalse(EventProjector.project(t.log, viewer).stream().anyMatch(GameEvent.MovementEffectCommitted.class::isInstance));
        }
    }
    @Test void forwardEventForcedOntoAnotherEventDoesNotDrawTwice() {
        var script = Table.script(Table.order(90, 10), Table.deal(2), Table.steps(DrawPoint.EVENT_KIND, 100, 80),
                Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, 0), Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 2));
        var t = new Table(new ScriptedRandom(script), 1).start(2, com.millionnaire.engine.config.RuleConfigs.BOARD_50,
                com.millionnaire.engine.config.EndMode.TIME_LIMIT, 30);
        // Isolated restored-board fixture. This test does not claim a Genesis replay of the crafted placement.
        M3bTest.craft(t, g -> g.withBoard(g.board().placed(new Roadblock(1, 21, "p2", 1))));
        target(t, 6); settle(t); target(t, 1); settle(t);
        target(t, 6); settle(t); target(t, 3); settle(t);
        target(t, 6); assertEquals(LandingStep.EVENT, t.game().turn().landing().step());
        var base = t.state; int from = t.log.size();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(21, t.position("p1")); assertEquals("p2", t.current());
        assertEquals(MoveKind.EVENT_FORWARD, lastMove(t).kind()); assertNotNull(lastMove(t).stoppedBy());
        assertTrue(t.game().board().roadblocks().isEmpty());
        assertEquals(1, t.log.stream().filter(GameEvent.EventDrawn.class::isInstance).count());
        assertEquals(t.state, new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(base, t.log.subList(from, t.log.size())));
    }
    @Test void anInterceptionBeforeStartDoesNotPayForTheUnwalkedSuffix() {
        var t = M2bReviewTest.table(2, 6);
        M3bTest.craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().at(28))
                .withBoard(g.board().placed(new Roadblock(1, 29, "p2", 1))));
        var base = t.state; int from = t.log.size(); t.rollOnly();
        assertEquals(29, t.position("p1")); assertEquals(3000, t.cash("p1"));
        assertEquals(1, lastMove(t).steps()); assertEquals(6, lastMove(t).plannedDistance());
        assertEquals(0, t.log.stream().filter(GameEvent.StartRewardPaid.class::isInstance).count());
        assertEquals(t.state, new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(base, t.log.subList(from, t.log.size())));
    }
}

