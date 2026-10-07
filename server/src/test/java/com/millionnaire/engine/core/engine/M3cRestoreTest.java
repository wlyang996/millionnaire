package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3cRestoreTest {
    static void boardRejects(Table t, List<Roadblock> roadblocks, long last) {
        M3bRestoreTest.rejects(t, g -> g.withBoard(new BoardState(g.board().boardId(), g.board().ownables(), roadblocks, last)));
    }
    @Test void restoreChecksEveryNewRoadblockField() {
        var t = M3cTamperTest.placement(); var r = t.game().board().roadblocks().getFirst();
        for (var name : List.of("id", "tile", "owner", "placedTurn")) {
            Object value = switch (name) { case "id" -> 0L; case "tile" -> 30; case "owner" -> "absent"; default -> t.game().turn().turnNo() + 1; };
            boardRejects(t, List.of((Roadblock) M3cTamperTest.field(r, name, value)), 1);
        }
        boardRejects(t, List.of(r), 0); boardRejects(t, List.of(r), -1);
    }
    @Test void restoreRejectsOnlyAJailTile() {
        var t = M3cTamperTest.placement(); var r = t.game().board().roadblocks().getFirst();
        boardRejects(t, List.of((Roadblock)M3cTamperTest.field(r, "tile", 8)), 1);
    }
    @Test void restoreRejectsDuplicateTilesDuplicateIdsAndUnorderedTiles() {
        var t = M3cTamperTest.placement(); var r = t.game().board().roadblocks().getFirst();
        boardRejects(t, List.of(r, new Roadblock(2, r.tile(), "p2", 1)), 2);
        boardRejects(t, List.of(r, new Roadblock(1, 1, "p2", 1)), 2);
        boardRejects(t, List.of(new Roadblock(2, 1, "p2", 1), r), 2);
    }
    static void segmentRejects(Table t, String name, Object value) {
        M3bRestoreTest.rejects(t, g -> {
            var c = g.turn().chain(); var segments = new java.util.ArrayList<>(c.segments());
            segments.set(0, (MoveSegment)M3cTamperTest.field(segments.getFirst(), name, value));
            return g.withTurn(g.turn().withChain(new MoveChain(c.chainId(), c.turnNo(), c.playerId(), c.origin(),
                    c.eventDrawn(), c.startRewardGiven(), segments, c.walkedSteps(), c.plans())));
        });
    }
    @Test void restoreChecksOnlyChangedPlannedDistance() {
        var t = M3cTest.stopped(); segmentRejects(t, "plannedDistance", 2); segmentRejects(t, "plannedDistance", 5); segmentRejects(t, "plannedDistance", 7);
    }
    static void planRejects(Table t, int index, String name, Object value) {
        M3bRestoreTest.rejects(t, g -> {
            var c = g.turn().chain(); var plans = new java.util.ArrayList<>(c.plans());
            plans.set(index, (MovePlan)M3cTamperTest.field(plans.get(index), name, value));
            return g.withTurn(g.turn().withChain((MoveChain)M3cTamperTest.field(c, "plans", plans)));
        });
    }
    @Test void restoreChecksPersistentPlanFieldsAndSourcesWithoutChangingSegments() {
        var t = M3cTest.stopped();
        planRejects(t, 0, "kind", MoveKind.TARGETED);
        planRejects(t, 0, "distance", 5);
        planRejects(t, 0, "landingId", 1L);
        planRejects(t, 0, "cursor", 0);
        M3bRestoreTest.rejects(t, g -> g.withTurn(g.turn().withChain(
                (MoveChain)M3cTamperTest.field(g.turn().chain(), "plans", List.of()))));
        M3bRestoreTest.rejects(t, g -> g.withTurn(g.turn().withChain(
                (MoveChain)M3cTamperTest.field(g.turn().chain(), "plans", List.of(
                        g.turn().chain().plans().getFirst(), g.turn().chain().plans().getFirst())))));
        var event = M3bTamperTest.move();
        assertEquals(2, event.game().turn().chain().plans().size());
        planRejects(event, 1, "landingId", event.game().turn().lastLandingId());
        planRejects(event, 1, "cursor", 0);
    }
    @Test void restoreChecksOnlyRemovedStopSource() { var t = M3cTest.stopped(); segmentRejects(t, "stoppedBy", null); }
    @Test void restoreChecksEverySingleStopObjectField() {
        var t = M3cTest.stopped(); var r = t.game().turn().chain().segments().getFirst().stoppedBy();
        for (var name : List.of("id", "tile", "owner", "placedTurn")) {
            Object value = switch (name) { case "id" -> 999L; case "tile" -> 0; case "owner" -> "p1"; default -> 999L; };
            segmentRejects(t, "stoppedBy", M3cTamperTest.field(r, name, value));
        }
    }
    @Test void restoreRejectsATriggeredBlockStillOnTheBoard() {
        var t = M3cTest.stopped(); var r = t.game().turn().chain().segments().getFirst().stoppedBy();
        boardRejects(t, List.of(r), t.game().board().lastRoadblockId());
    }
    @Test void restoreRejectsATargetedSegmentWithOnlyAStopSourceAdded() {
        var t = M3cTest.approach(1); M3cTest.target(t, 3);
        segmentRejects(t, "stoppedBy", t.game().board().roadblocks().getFirst());
    }
    @Test void restoreRejectsUnconsumedMechanismOrTriggerCredentials() {
        var t = M3cTamperTest.placement(); var r = t.game().board().roadblocks().getFirst();
        M3bRestoreTest.rejects(t, g -> g.withTurn(g.turn().withTrack((TurnTrack)M3cTamperTest.field(g.turn().track(), "roadblockDue", r))));
        var source = new MovementEffect(MovementEffect.Kind.TARGETED, "p1", 1, t.windowId(), 0, 1, t.now);
        M3bRestoreTest.rejects(t, g -> g.withTurn(g.turn().withTrack(g.turn().track().effect(source))));
    }
}
