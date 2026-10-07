package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3cTamperTest {
    static Object field(Object record, String name, Object value) {
        try {
            var fields = record.getClass().getRecordComponents();
            var values = new Object[fields.length]; var types = new Class<?>[fields.length]; boolean found = false;
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType(); values[i] = fields[i].getAccessor().invoke(record);
                if (fields[i].getName().equals(name)) { values[i] = value; found = true; }
            }
            assertTrue(found, name);
            return record.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static void changed(Table t, int i, String name, Object value) {
        var log = new ArrayList<>(t.log); log.set(i, (Event) field(log.get(i), name, value));
        M2bReviewTest.rejectsAt(t, log, i);
    }
    static Table placement() { var t = M2bReviewTest.table(2); M3cTest.place(t); t.tick(t.now + 1); return t; }
    static Table targeted() { var t = M2bReviewTest.table(2); M3cTest.target(t, 1); return t; }
    record Case(Table table, Class<? extends Event> type) { }
    static List<Case> cases() {
        var place = placement(); var target = targeted(); var stopped = M3cTest.stopped();
        return List.of(new Case(place, GameEvent.MovementEffectCommitted.class), new Case(place, GameEvent.RoadblockPlaced.class),
                new Case(target, GameEvent.MovementEffectCommitted.class), new Case(stopped, GameEvent.RoadblockTriggered.class));
    }
    @Test void deletionOfEveryNewEventFailsAtItsRequiredSuccessor() {
        for (var c : cases()) {
            int i = M2bReviewTest.indexOf(c.table().log, c.type(), 0);
            var log = new ArrayList<>(c.table().log); log.remove(i);
            int rejected = c.table().game().board().roadblocks().isEmpty() && c.type() == GameEvent.MovementEffectCommitted.class
                    ? M2bReviewTest.indexOf(log, GameEvent.MoveChainStarted.class, 0) : i;
            M2bReviewTest.rejectsAt(c.table(), log, rejected);
        }
    }
    @Test void duplicationOfEveryNewEventFailsAtTheDuplicate() {
        for (var c : cases()) {
            int i = M2bReviewTest.indexOf(c.table().log, c.type(), 0);
            var log = new ArrayList<>(c.table().log); log.add(i + 1, log.get(i));
            M2bReviewTest.rejectsAt(c.table(), log, i + 1);
        }
    }
    @Test void insertionOfEveryNewEventWithForeignTurnFailsAtInsertion() {
        for (var c : cases()) {
            int i = M2bReviewTest.indexOf(c.table().log, c.type(), 0); var e = c.table().log.get(i);
            Event foreign = e instanceof GameEvent.MovementEffectCommitted committed
                    ? new GameEvent.MovementEffectCommitted((MovementEffect) field(committed.source(), "turnNo", 999L))
                    : (Event) field(e, "turnNo", 999L);
            var log = new ArrayList<>(c.table().log); log.add(i, foreign);
            M2bReviewTest.rejectsAt(c.table(), log, i);
        }
    }
    @Test void advancingEveryNewEventBeforeTheFirstTurnFailsAtThatEvent() {
        for (var c : cases()) {
            int i = M2bReviewTest.indexOf(c.table().log, GameEvent.TurnStarted.class, 0);
            var log = new ArrayList<>(c.table().log); log.add(i, log.get(M2bReviewTest.indexOf(log, c.type(), 0)));
            M2bReviewTest.rejectsAt(c.table(), log, i);
        }
    }
    @Test void placementChecksOnlyChangedPlayer() { var t = placement(); changed(t, M2bReviewTest.indexOf(t.log, GameEvent.RoadblockPlaced.class, 0), "playerId", "p2"); }
    @Test void placementChecksOnlyChangedTurn() { var t = placement(); changed(t, M2bReviewTest.indexOf(t.log, GameEvent.RoadblockPlaced.class, 0), "turnNo", 2L); }
    @Test void placementChecksOnlyChangedWindow() { var t = placement(); changed(t, M2bReviewTest.indexOf(t.log, GameEvent.RoadblockPlaced.class, 0), "windowId", t.windowId() + 1); }
    @Test void placementChecksEverySingleObjectField() {
        var t = placement(); int i = M2bReviewTest.indexOf(t.log, GameEvent.RoadblockPlaced.class, 0);
        var r = ((GameEvent.RoadblockPlaced) t.log.get(i)).roadblock();
        for (var name : List.of("id", "tile", "owner", "placedTurn")) {
            Object value = switch (name) { case "id", "placedTurn" -> 2L; case "tile" -> 1; default -> "p2"; };
            changed(t, i, "roadblock", field(r, name, value));
        }
    }
    @Test void sourceChecksEverySingleAssociationAndParameter() {
        for (var t : List.of(placement(), targeted())) {
            int i = M2bReviewTest.indexOf(t.log, GameEvent.MovementEffectCommitted.class, 0);
            var source = ((GameEvent.MovementEffectCommitted)t.log.get(i)).source();
            for (var name : List.of("playerId", "turnNo", "windowId", "from", "at", "distance", "kind")) {
                Object value = switch (name) {
                    case "playerId" -> "p2"; case "turnNo" -> source.turnNo() + 1;
                    case "windowId" -> source.windowId() + 1; case "from" -> 1; case "at" -> source.at() + 1;
                    case "distance" -> 7; default -> null;
                };
                changed(t, i, "source", field(source, name, value));
            }
        }
    }
    static void triggerField(String name, Object value) {
        var t = M3cTest.stopped(); changed(t, M2bReviewTest.indexOf(t.log, GameEvent.RoadblockTriggered.class, 0), name, value);
    }
    @Test void triggerChecksOnlyChangedPlayer() { triggerField("playerId", "p2"); }
    @Test void triggerChecksOnlyChangedTurn() { triggerField("turnNo", 999L); }
    @Test void triggerChecksOnlyChangedChain() { triggerField("chainId", 999L); }
    @Test void triggerChecksOnlyChangedSegment() { triggerField("segmentNo", 2); }
    @Test void triggerChecksEverySingleObjectField() {
        var t = M3cTest.stopped(); int i = M2bReviewTest.indexOf(t.log, GameEvent.RoadblockTriggered.class, 0);
        var r = ((GameEvent.RoadblockTriggered)t.log.get(i)).roadblock();
        for (var name : List.of("id", "tile", "owner", "placedTurn")) {
            Object value = switch (name) { case "id", "placedTurn" -> 999L; case "tile" -> 3; default -> "p1"; };
            changed(t, i, "roadblock", field(r, name, value));
        }
    }
    @Test void movementChecksOnlyChangedActualDistance() {
        var t = M3cTest.stopped(); changed(t, M2bReviewTest.lastIndexOf(t.log, GameEvent.PlayerMoved.class), "steps", 6);
    }
    @Test void movementChecksOnlyChangedPlannedDistance() {
        var t = M3cTest.stopped(); changed(t, M2bReviewTest.lastIndexOf(t.log, GameEvent.PlayerMoved.class), "plannedDistance", 3);
    }
    @Test void movementChecksOnlyChangedStopSource() {
        var t = M3cTest.stopped(); changed(t, M2bReviewTest.lastIndexOf(t.log, GameEvent.PlayerMoved.class), "stoppedBy", null);
    }
    @Test void targetedCannotBeChangedToATriggeredSegment() {
        var t = M3cTest.approach(1); M3cTest.target(t, 3);
        changed(t, M2bReviewTest.lastIndexOf(t.log, GameEvent.PlayerMoved.class), "stoppedBy", t.game().board().roadblocks().getFirst());
    }
    @Test void deletionOfTheTriggerCannotBeHiddenByTheFollowingRewardOrLanding() {
        var t = M3cTest.stopped(); int i = M2bReviewTest.indexOf(t.log, GameEvent.RoadblockTriggered.class, 0);
        var log = new ArrayList<>(t.log); log.remove(i); M2bReviewTest.rejectsAt(t, log, i);
    }
    @Test void nonexistentAndSelfTriggersFailWithoutAnotherPendingCheck() {
        var t = placement(); var g = t.game(); var block = g.board().roadblocks().getFirst();
        assertThrows(IllegalStateException.class, () -> MovementEffects.trigger(g,
                new GameEvent.RoadblockTriggered("p2", g.turn().turnNo(), 1, 1, block)));
        // Only a real stopped movement establishes roadblockDue. A bare remove event cannot clear the board.
        int i = t.log.size(); var log = new ArrayList<>(t.log);
        log.add(new GameEvent.RoadblockTriggered("p1", g.turn().turnNo(), 1, 1, block));
        M2bReviewTest.rejectsAt(t, log, i);
    }
    @Test void theImmediateTriggerGateIsTestedWithoutLaterLandingOrBoundaryGuards() {
        var t = M3cTest.stopped(); int trigger = M2bReviewTest.indexOf(t.log, GameEvent.RoadblockTriggered.class, 0);
        var pending = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, t.log.subList(0, trigger));
        assertNotNull(((SessionState)pending.domain()).game().turn().track().roadblockDue());
        assertThrows(IllegalStateException.class, () -> SessionDomain.INSTANCE.checkEvent(pending,
                new GameEvent.GameClockStarted(999, 999), t.config));
    }
}
