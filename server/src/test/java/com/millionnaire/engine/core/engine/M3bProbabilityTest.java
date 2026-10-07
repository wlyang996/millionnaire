package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.testkit.TestBoards;
import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.config.*;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.testkit.Table;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class M3bProbabilityTest {
    @Test void everyCardWeightBoundaryMapsToThePrivateReceiptField() {
        var c = TestBoards.legacyV1(); int begin = 0;
        for (var type : CardType.values()) {
            int end = begin + c.cardWeights().get(type);
            for (int value : new int[] {begin, end - 1}) {
                var t = M3bTest.event(55, Table.steps(DrawPoint.EVENT_CARD, 1000, value));
                t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w));
                var e = t.log.stream().filter(GameEvent.EventCardReceived.class::isInstance)
                        .map(GameEvent.EventCardReceived.class::cast).findFirst().orElseThrow();
                assertEquals(type, e.card()); assertEquals(t.state, t.engine.rebuild(t.log));
            }
            begin = end;
        }
    }
    @Test void exactEventAndCardIntervalsMatchEveryConfiguredWeight() {
        var c = TestBoards.legacyV1();
        var events = new EnumMap<EventKind, Integer>(EventKind.class);
        for (int i = 0; i < 100; i++) { events.merge(EventModule.pick(c, i), 1, Integer::sum); }
        var configured = new EnumMap<EventKind, Integer>(EventKind.class);
        c.eventWeights().forEach((k, w) -> { if (w > 0) { configured.put(k, w); } }); // 权重为 0 的种类抽不到
        assertEquals(configured, events);
        var cards = new EnumMap<CardType, Integer>(CardType.class);
        for (int i = 0; i < 1000; i++) { cards.merge(CardDeck.pick(c, i), 1, Integer::sum); }
        assertEquals(c.cardWeights(), cards);
        assertThrows(IllegalStateException.class, () -> EventModule.pick(c, -1));
        assertThrows(IllegalStateException.class, () -> EventModule.pick(c, 100));
        assertThrows(IllegalStateException.class, () -> CardDeck.pick(c, -1));
        assertThrows(IllegalStateException.class, () -> CardDeck.pick(c, 1000));
    }
    @Test void allNineCashTiersMapToBothKindsOfLoggedResult() {
        for (int i = 0; i < 9; i++) {
            for (int kind : new int[] {0, 30}) {
                var t = M3bTest.event(kind, Table.steps(DrawPoint.EVENT_CASH, 9, i));
                t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w));
                var e = t.log.stream().filter(GameEvent.EventDrawn.class::isInstance).map(GameEvent.EventDrawn.class::cast).findFirst().orElseThrow();
                assertEquals(100 + 50 * i, e.amount());
                assertEquals(3000 + (kind == 0 ? e.amount() : -e.amount()), t.cash("p1"));
                assertEquals(t.state, t.engine.rebuild(t.log));
            }
        }
    }
    @Test void allSixDirectionDistancePairsMapToCommittedSegments() {
        for (int direction = 0; direction < 2; direction++) {
            for (int distance = 0; distance < 3; distance++) {
                var t = M3bTest.event(80, Table.script(Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, direction),
                        Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, distance)));
                t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w));
                var e = t.log.stream().filter(GameEvent.EventMoveCommitted.class::isInstance).map(GameEvent.EventMoveCommitted.class::cast).findFirst().orElseThrow();
                assertEquals(distance + 1, e.distance());
                assertEquals(direction == 0 ? com.millionnaire.engine.core.state.MoveKind.EVENT_FORWARD : com.millionnaire.engine.core.state.MoveKind.EVENT_BACKWARD, e.kind());
                assertEquals(Math.floorMod(2 + (direction == 0 ? 1 : -1) * (distance + 1), 30), t.position("p1"));
                assertEquals(3000, t.cash("p1"), "these moves never earn a forward start crossing");
                assertEquals(t.state, t.engine.rebuild(t.log));
            }
        }
    }
}
