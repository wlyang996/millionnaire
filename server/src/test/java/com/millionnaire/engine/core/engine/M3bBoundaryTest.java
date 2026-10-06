package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3bBoundaryTest {
    @Test void theSecondEventTileInTheSameChainCompletesWithoutAnotherDraw() {
        var script = Table.script(Table.order(90, 10), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 6, 2, 6, 2, 6),
                Table.steps(DrawPoint.EVENT_KIND, 100, 80),
                Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, 0), Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 2));
        var t = new Table(new ScriptedRandom(script), 1).start(2, RuleConfigs.BOARD_50, EndMode.TIME_LIMIT, 30);
        for (int i = 0; i < 4; i++) { t.roll(); }
        t.rollOnly(); assertEquals(18, t.position("p1"));
        long chain = t.game().turn().chain().chainId();
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals(21, t.position("p1")); assertEquals("p2", t.current());
        assertEquals(1, t.log.stream().filter(GameEvent.EventDrawn.class::isInstance).count());
        var second = t.log.stream().filter(GameEvent.LandingStarted.class::isInstance).map(GameEvent.LandingStarted.class::cast)
                .filter(e -> e.chainId() == chain && e.tile() == 21).findFirst().orElseThrow();
        assertFalse(t.log.stream().anyMatch(e -> e instanceof GameEvent.LandingStepEntered s
                && s.landingId() == second.landingId()));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void eachNonManualControlDrawsAtTheExistingAutoDelayWithoutWaitingFifteenSeconds() {
        for (String mode : List.of("AWAY", "HOSTED", "OFFLINE")) {
            var t = M3bTest.event(0, Table.steps(DrawPoint.EVENT_CASH, 9, 0)); t.rollOnly();
            if (mode.equals("OFFLINE")) {
                t.send(new GameCommand.ConnectionSuspected(1, "p1", 1));
                t.send(new GameCommand.ConnectionConfirmed(1, "p1", 2));
            } else {
                t.send(new GameCommand.SetControl(1, "p1", ControlMode.valueOf(mode)));
            }
            long due = t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt();
            assertEquals(Math.max(t.now, t.window().window().opensAt()) + t.config.timing().autoActDelayMs(), due);
            assertTrue(due < t.window().window().deadline());
            t.tick(due - 1); assertEquals(0, t.log.stream().filter(GameEvent.EventDrawn.class::isInstance).count());
            t.tick(due); assertEquals(3100, t.cash("p1"));
            assertTrue(t.log.stream().filter(GameEvent.EventDrawn.class::isInstance).map(GameEvent.EventDrawn.class::cast).findFirst().orElseThrow().auto());
            assertEquals(t.state, t.engine.rebuild(t.log));
        }
    }
    @Test void globalExpiryPreservesAnOpenedDrawWindowAndDrainsAllItsSuccessors() {
        boolean found = false;
        for (long seed = 1; seed <= 20 && !found; seed++) {
            var t = Table.production(seed).start(2, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 15);
            long end = t.game().clock().endsAt();
            while (t.session().inGame()) {
                var l = t.game().turn().landing(); var w = t.window();
                if (l != null && l.step() == LandingStep.EVENT && w.window().deadline() > end) {
                    found = true; long id = w.windowId(); var rng = t.state.rng();
                    t.tick(end); assertEquals(GamePhase.DRAINING, t.game().phase());
                    assertEquals(id, t.windowId()); assertEquals(rng, t.state.rng());
                    var snapshot = t.engine.snapshot(t.state);
                    var in = new Input(t.seq + 1, w.window().deadline(), new Tick());
                    assertEquals(t.engine.step(t.state, in), t.engine.step(t.engine.restore(snapshot), in));
                    int start = t.log.size(); t.tick(in.serverTime());
                    while (t.session().inGame()) { t.tick(t.window().window().deadline()); }
                    var tail = t.log.subList(start, t.log.size());
                    assertEquals(1, tail.stream().filter(GameEvent.EventDrawn.class::isInstance).count());
                    assertFalse(tail.stream().anyMatch(GameEvent.TurnStarted.class::isInstance));
                    assertEquals(1, tail.stream().filter(GameEvent.GameEnded.class::isInstance).count());
                    assertEquals(t.state, t.engine.rebuild(t.log)); break;
                }
                if (w.window().deadline() >= end) { t.tick(end); if (t.session().inGame()) { t.tick(t.window().window().deadline()); } continue; }
                Command command = t.game().debt() != null ? new Tick() : l == null
                        ? new GameCommand.RollDice(t.current(), w.windowId()) : switch (l.step()) {
                            case EVENT -> new GameCommand.DrawEventCard(t.current(), w.windowId());
                            case DISCARD -> new GameCommand.DiscardCard(t.current(), w.windowId(), l.event().newCardIndex());
                            case BUY -> new GameCommand.DeclinePurchase(t.current(), w.windowId());
                            case UPGRADE -> new GameCommand.SkipUpgrade(t.current(), w.windowId());
                            case BANK -> new GameCommand.FinishBank(t.current(), w.windowId());
                            default -> new Tick();
                        };
                t.send(w.window().deadline() - (command instanceof Tick ? 0 : 1), command);
            }
        }
        assertTrue(found, "fixed production seeds must include a draw window spanning global expiry");
    }
    @Test void fineWithNoAssetsUsesTheExistingDirectBankruptcyPath() {
        var t = M3bTest.event(30, Table.steps(DrawPoint.EVENT_CASH, 9, 8));
        M3bTest.craft(t, g -> g.withLedger(g.ledger().transfer("p1", com.millionnaire.engine.ledger.Ledger.SYSTEM, 2900, "TEST", null)));
        t.rollOnly(); var r = t.act(w -> new GameCommand.DrawEventCard("p1", w));
        var created = r.events().stream().filter(GameEvent.DebtCreated.class::isInstance).map(GameEvent.DebtCreated.class::cast).findFirst().orElseThrow();
        assertEquals(DebtPath.DIRECT_BANKRUPTCY, created.debt().path()); assertNull(created.debt().creditor());
        assertFalse(r.events().stream().anyMatch(e -> e instanceof GameEvent.WindowOpened w && w.frame().kind() == FlowKind.DEBT));
        assertFalse(t.session().inGame());
    }
}
