package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.config.*;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class M3bTamperTest {
    static Table realOverflow() {
        var script = Table.script(Table.order(90, 10), Table.deal(2));
        int position = 0;
        int[] moves = {2, 5, 3, 6, 1, 4, 5};
        for (int move : moves) {
            position += move;
            for (int player = 0; player < 2; player++) {
                script.addAll(Table.dice(DrawPoint.MOVE_DIE, move));
                if (Set.of(2, 10, 17, 21, 26).contains(position)) {
                    script.addAll(Table.steps(DrawPoint.EVENT_KIND, 100, 55));
                    script.addAll(Table.steps(DrawPoint.EVENT_CARD, 1000, 0));
                }
            }
        }
        var t = new Table(new ScriptedRandom(script), 1).start(2);
        for (int i = 0; i < 12; i++) { t.roll(); }
        t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(LandingStep.DISCARD, t.game().turn().landing().step());
        t.tick(t.window().window().deadline());
        assertEquals(t.state, t.engine.rebuild(t.log));
        return t;
    }
    record Case(Table table, Class<? extends Event> type) { }
    static List<Case> cases() {
        var r = reward(); var m = move();
        var f = M3bTest.event(30, Table.steps(DrawPoint.EVENT_CASH, 9, 0));
        f.rollOnly(); f.act(w -> new GameCommand.DrawEventCard("p1", w));
        var card = M3bTest.event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, 999));
        card.rollOnly(); card.act(w -> new GameCommand.DrawEventCard("p1", w));
        return List.of(new Case(r, GameEvent.EventDrawn.class), new Case(r, GameEvent.EventRewardPaid.class),
                new Case(f, GameEvent.FeeCharged.class), new Case(f, GameEvent.FeePaid.class),
                new Case(m, GameEvent.EventMoveCommitted.class), new Case(card, GameEvent.EventCardReceived.class),
                new Case(card, GameEvent.EventHandCount.class), new Case(realOverflow(), GameEvent.EventCardDiscarded.class));
    }
    @Test void deletionOfEveryNewEventFailsAtItsImmediateRequiredSuccessor() {
        for (var c : cases()) {
            int i = M2bReviewTest.indexOf(c.table().log, c.type(), 0);
            var log = new ArrayList<>(c.table().log); log.remove(i);
            M2bReviewTest.rejectsAt(c.table(), log, i);
        }
    }
    @Test void duplicationOfEveryNewEventFailsAtTheDuplicate() {
        for (var c : cases()) {
            int i = M2bReviewTest.indexOf(c.table().log, c.type(), 0);
            var log = new ArrayList<>(c.table().log); log.add(i + 1, log.get(i));
            M2bReviewTest.rejectsAt(c.table(), log, i + 1);
        }
    }
    static Event foreignLanding(Event e) throws ReflectiveOperationException {
        if (e instanceof GameEvent.FeeCharged f) {
            var s = f.source(); return new GameEvent.FeeCharged(f.payer(), new FeeSource(s.kind(), s.landingId() + 1, s.cursor(), s.tile(), s.creditor(), s.amount()));
        }
        if (e instanceof GameEvent.FeePaid f) {
            var s = f.source(); return new GameEvent.FeePaid(f.payer(), new FeeSource(s.kind(), s.landingId() + 1, s.cursor(), s.tile(), s.creditor(), s.amount()));
        }
        var fields = e.getClass().getRecordComponents();
        var values = new Object[fields.length]; var types = new Class<?>[fields.length];
        for (int i = 0; i < fields.length; i++) {
            types[i] = fields[i].getType(); values[i] = fields[i].getAccessor().invoke(e);
            if (fields[i].getName().equals("landingId")) { values[i] = (Long) values[i] + 1; }
        }
        return (Event) e.getClass().getDeclaredConstructor(types).newInstance(values);
    }
    @Test void insertionOfEveryNewEventWithAForeignLandingFailsAtTheInsertion() throws ReflectiveOperationException {
        for (var c : cases()) {
            int i = M2bReviewTest.indexOf(c.table().log, c.type(), 0);
            var log = new ArrayList<>(c.table().log); log.add(i, foreignLanding(log.get(i)));
            M2bReviewTest.rejectsAt(c.table(), log, i);
        }
    }
    @Test void advancingEveryNewEventBeforeLandingStartFailsAtThatEvent() {
        for (var c : cases()) {
            int original = M2bReviewTest.indexOf(c.table().log, c.type(), 0);
            int i = M2bReviewTest.indexOf(c.table().log, GameEvent.LandingStarted.class, 0);
            var log = new ArrayList<>(c.table().log); log.add(i, log.get(original));
            M2bReviewTest.rejectsAt(c.table(), log, i);
        }
    }
    @Test void automaticDiscardCannotSelectAnOldCopyEvenWhenAllCardFacesMatch() {
        changed(realOverflow(), GameEvent.EventCardDiscarded.class, e -> new GameEvent.EventCardDiscarded(e.recipient(), e.landingId(), e.cursor(), 0, e.card(), e.auto()));
    }
    static Table reward() {
        var t = M3bTest.event(0, Table.steps(DrawPoint.EVENT_CASH, 9, 0));
        t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w)); return t;
    }
    static Table move() {
        var t = M3bTest.event(80, Table.script(Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, 0), Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 0)));
        t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w)); return t;
    }
    static <E extends Event> void changed(Table t, Class<E> type, UnaryOperator<E> f) {
        int i = M2bReviewTest.indexOf(t.log, type, 0);
        var log = new ArrayList<>(t.log); log.set(i, f.apply(type.cast(log.get(i))));
        M2bReviewTest.rejectsAt(t, log, i);
    }
    @Test void onlyTheDrawKindIsChanged() {
        changed(reward(), GameEvent.EventDrawn.class, e -> new GameEvent.EventDrawn(e.playerId(), e.landingId(), e.cursor(), EventKind.CASH_FINE, e.amount(), e.moveKind(), e.distance(), e.auto()));
    }
    @Test void onlyTheDrawAmountIsChanged() {
        changed(reward(), GameEvent.EventDrawn.class, e -> new GameEvent.EventDrawn(e.playerId(), e.landingId(), e.cursor(), e.kind(), e.amount() + 50, e.moveKind(), e.distance(), e.auto()));
    }
    @Test void onlyTheDrawDirectionIsChanged() {
        changed(move(), GameEvent.EventDrawn.class, e -> new GameEvent.EventDrawn(e.playerId(), e.landingId(), e.cursor(), e.kind(), e.amount(), MoveKind.EVENT_BACKWARD, e.distance(), e.auto()));
    }
    @Test void onlyTheDrawDistanceIsChanged() {
        changed(move(), GameEvent.EventDrawn.class, e -> new GameEvent.EventDrawn(e.playerId(), e.landingId(), e.cursor(), e.kind(), e.amount(), e.moveKind(), 2, e.auto()));
    }
    @Test void onlyTheDrawPlayerIsChanged() {
        changed(reward(), GameEvent.EventDrawn.class, e -> new GameEvent.EventDrawn("p2", e.landingId(), e.cursor(), e.kind(), e.amount(), e.moveKind(), e.distance(), e.auto()));
    }
    @Test void onlyTheDrawLandingIsChanged() {
        changed(reward(), GameEvent.EventDrawn.class, e -> new GameEvent.EventDrawn(e.playerId(), e.landingId() + 1, e.cursor(), e.kind(), e.amount(), e.moveKind(), e.distance(), e.auto()));
    }
    @Test void onlyTheDrawCursorIsChanged() {
        changed(reward(), GameEvent.EventDrawn.class, e -> new GameEvent.EventDrawn(e.playerId(), e.landingId(), 1, e.kind(), e.amount(), e.moveKind(), e.distance(), e.auto()));
    }
    @Test void onlyTheRewardAmountIsChanged() {
        changed(reward(), GameEvent.EventRewardPaid.class, e -> new GameEvent.EventRewardPaid(e.playerId(), e.landingId(), e.cursor(), e.amount() + 50));
    }
    @Test void onlyTheCardKindIsChanged() {
        var t = M3bTest.event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, 999));
        t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w));
        changed(t, GameEvent.EventCardReceived.class, e -> new GameEvent.EventCardReceived(e.recipient(), e.landingId(), e.cursor(), CardType.ROADBLOCK));
    }
    @Test void onlyThePublicHandCountIsChanged() {
        var t = M3bTest.event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, 999));
        t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w));
        changed(t, GameEvent.EventHandCount.class, e -> new GameEvent.EventHandCount(e.playerId(), e.landingId(), e.cursor(), 4, e.discarded()));
    }
    @Test void onlyTheRelocationDistanceIsChanged() {
        changed(move(), GameEvent.EventMoveCommitted.class, e -> new GameEvent.EventMoveCommitted(e.playerId(), e.landingId(), e.cursor(), e.kind(), 2));
    }
    @Test void aMovedSegmentCannotOmitItsCommittedEventSource() {
        var t = move();
        var log = new ArrayList<>(t.log);
        log.remove(M2bReviewTest.indexOf(log, GameEvent.EventMoveCommitted.class, 0));
        M2bReviewTest.rejectsAt(t, log, M2bReviewTest.indexOf(log, GameEvent.LandingFinished.class, 0));
    }
    @Test void aWrongJailDestinationIsRejectedAtTheMovementEvent() {
        var t = M3bTest.event(95, List.of()); t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w));
        int i = M2bReviewTest.indexOf(t.log, GameEvent.PlayerMoved.class, 1);
        var e = (GameEvent.PlayerMoved)t.log.get(i);
        var log = new ArrayList<>(t.log);
        log.set(i, new GameEvent.PlayerMoved(e.playerId(), e.from(), 9, e.steps(), e.chainId(), e.segmentNo(), e.kind(), e.plannedDistance(), e.stoppedBy()));
        M2bReviewTest.rejectsAt(t, log, i);
    }
    @Test void everyEventDrawPointChecksItsBoundAtTheCorrespondingResult() {
        for (var t : List.of(reward(), move())) {
            for (int i = 0; i < t.log.size(); i++) {
                if (!(t.log.get(i) instanceof KernelEvent.RandomDrawn e) || !e.point().name().startsWith("EVENT_")) { continue; }
                var log = new ArrayList<>(t.log);
                log.set(i, new KernelEvent.RandomDrawn(e.protocol(), e.point(), e.bound() + 1, e.value(), e.after()));
                M2bReviewTest.rejectsAt(t, log, M2bReviewTest.indexOf(log, GameEvent.EventDrawn.class, 0));
            }
        }
        var card = M3bTest.event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, 999));
        card.rollOnly(); card.act(w -> new GameCommand.DrawEventCard("p1", w));
        int i = -1;
        for (int j = 0; j < card.log.size(); j++) {
            if (card.log.get(j) instanceof KernelEvent.RandomDrawn e && e.point() == DrawPoint.EVENT_CARD) { i = j; }
        }
        var e = (KernelEvent.RandomDrawn) card.log.get(i);
        var log = new ArrayList<>(card.log);
        log.set(i, new KernelEvent.RandomDrawn(e.protocol(), e.point(), 1001, e.value(), e.after()));
        M2bReviewTest.rejectsAt(card, log, M2bReviewTest.indexOf(log, GameEvent.EventCardReceived.class, 0));
    }
}
