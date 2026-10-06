package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.Table;
import java.util.Collections;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class M3bRestoreTest {
    static void rejects(Table t, java.util.function.UnaryOperator<GameState> change) {
        var s = t.state.withDomain(t.session().withGame(change.apply(t.game())));
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(s)));
    }
    @Test void restoreRejectsAnUnconsumedEventMoveCredentialAtAStableWindow() {
        var t = M3bTest.event(0, Table.steps(DrawPoint.EVENT_CASH, 9, 0)); t.rollOnly();
        rejects(t, g -> g.withTurn(g.turn().withTrack(g.turn().track().redirect(new EventMove(1, 1, "p1", MoveKind.EVENT_FORWARD, 1)))));
    }
    @Test void restoreRejectsOnlyChangingTheDrawnMarkerOfAnOpenEventWindow() {
        var t = M3bTest.event(0, Table.steps(DrawPoint.EVENT_CASH, 9, 0)); t.rollOnly();
        rejects(t, g -> g.withTurn(g.turn().withChain(g.turn().chain().drewEvent())));
    }
    @Test void restoreChecksEveryNewResolutionFieldAndItsTaskRelationship() {
        var t = overflow(); var r = t.game().turn().landing().event();
        for (var changed : java.util.List.of(
                new EventResolution(r.kind(), 1, r.moveKind(), r.distance(), r.newCardIndex(), false, r.cardDraw()),
                new EventResolution(r.kind(), r.amount(), MoveKind.EVENT_FORWARD, r.distance(), r.newCardIndex(), false, r.cardDraw()),
                new EventResolution(r.kind(), r.amount(), r.moveKind(), 1, r.newCardIndex(), false, r.cardDraw()),
                new EventResolution(r.kind(), r.amount(), r.moveKind(), r.distance(), 5, false, r.cardDraw()),
                new EventResolution(r.kind(), r.amount(), r.moveKind(), r.distance(), r.newCardIndex(), true, r.cardDraw()),
                new EventResolution(r.kind(), r.amount(), r.moveKind(), r.distance(), r.newCardIndex(), false, 1000),
                new EventResolution(com.millionnaire.engine.config.EventKind.MOVE, r.amount(), r.moveKind(), r.distance(), r.newCardIndex(), false, r.cardDraw()))) {
            rejects(t, g -> g.withTurn(g.turn().withLanding(g.turn().landing().withEvent(changed))));
        }
        rejects(t, g -> g.withTurn(g.turn().withLanding(g.turn().landing().withEvent(null))));
    }
    static Table overflow() {
        var t = M3bTest.event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, 999));
        M3bTest.craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().withHand(Collections.nCopies(6, CardType.ROADBLOCK))));
        t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w)); return t;
    }
    @Test void restoreRejectsChangingOnlyTheNewCardFaceInsideTheOverflowHand() {
        var t = overflow();
        var g = t.game(); var p = g.player("p1").orElseThrow();
        var hand = new ArrayList<>(p.hand()); hand.set(6, CardType.ROADBLOCK);
        var state = t.state.withDomain(t.session().withGame(g.withPlayer(p.withHand(hand))));
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(state)));
    }
    @Test void restoreRejectsAnOverflowHandOutsideItsDiscardWindow() {
        var t = M3bTest.event(0, Table.steps(DrawPoint.EVENT_CASH, 9, 0));
        var g = t.game();
        var state = t.state.withDomain(t.session().withGame(g.withPlayer(g.player("p1").orElseThrow().withHand(Collections.nCopies(7, CardType.ROADBLOCK)))));
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(state)));
    }
}
