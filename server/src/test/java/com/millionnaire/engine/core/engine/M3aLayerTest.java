package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import static com.millionnaire.engine.core.engine.M2bReviewTest.*;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.time.Window;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3aLayerTest {
    @Test void lowLevelSafePointNumbersMustBeSequential() {
        var f = FlowState.initial(1).withSafePoint(1, 0);
        var ex = assertThrows(IllegalStateException.class, () -> FlowCoordinator.evolve(f, new GameEvent.SafePointEntered(3)));
        assertEquals("safe points are numbered in order", ex.getMessage());
        assertEquals(2, FlowCoordinator.evolve(f, new GameEvent.SafePointEntered(2)).safePointNo());
    }
    @Test void lowLevelCannotDequeueTwiceAtOneSafePointEvenAfterClosingTheFirstFlow() {
        var r1 = new FlowRequest(1, FlowKind.TRADE, "p2", 0);
        var r2 = new FlowRequest(2, FlowKind.AUCTION, "p3", 0);
        var f = FlowState.initial(1).withSafePoint(1, 0).withQueue(List.of(r1, r2)).withNextRequestId(3);
        f = FlowCoordinator.evolve(f, new GameEvent.FlowDequeued(1));
        var w = new FlowFrame(1, FlowKind.TRADE, "p2", Window.open(1, 0, 0, 1000), 1, "x", new FlowOrigin(FlowOrigin.Kind.QUEUED, 1, 1, -1, "p2"));
        f = FlowCoordinator.evolve(f, new GameEvent.WindowOpened(w));
        f = FlowCoordinator.evolve(f, new GameEvent.WindowClosed(1, GameEvent.CloseReason.EXPIRED));
        var closed = f;
        var ex = assertThrows(IllegalStateException.class, () -> FlowCoordinator.evolve(closed, new GameEvent.FlowDequeued(2)));
        assertEquals("at most one flow may start per safe point", ex.getMessage());
    }
    @Test void lowLevelQueuedOriginIsCheckedWithoutTheGameModule() {
        var r = new FlowRequest(1, FlowKind.TRADE, "p2", 0);
        var f = FlowState.initial(1).withSafePoint(1, 1).withPendingStart(r);
        var w = new FlowFrame(1, FlowKind.TRADE, "p2", Window.open(1, 0, 0, 1000), 1, "x", new FlowOrigin(FlowOrigin.Kind.QUEUED, 1, 2, -1, "p2"));
        var ex = assertThrows(IllegalStateException.class, () -> FlowCoordinator.evolve(f, new GameEvent.WindowOpened(w)));
        assertEquals("queued window without matching dequeued request/safe point", ex.getMessage());
    }
    @Test void gameLayerSynchronousOriginIsCheckedAtTheWindowEvent() {
        var t = table(3);
        t.tick(t.window().window().opensAt());
        var c = ctx(t);
        GameModule.openOverlay(c, FlowKind.ATTACK, "p1", 0, 15000, "x");
        commit(t, c);
        int i = lastIndexOf(t.log, GameEvent.WindowOpened.class);
        var w = ((GameEvent.WindowOpened)t.log.get(i)).frame();
        var o = w.origin();
        var log = new ArrayList<>(t.log);
        log.set(i, new GameEvent.WindowOpened(new FlowFrame(w.windowId(), w.kind(), w.owner(), w.window(), w.deadlineTaskId(), w.resumeTag(), new FlowOrigin(o.kind(), o.scopeId(), o.ref() + 1, o.cursor(), o.actor()))));
        rejectsAt(t, log, i);
    }
    @Test void attackResponseAllowsAnotherLiveResponderAndBindsItsActor() {
        var t = table(3);
        t.tick(t.window().window().opensAt());
        var c = ctx(t);
        GameModule.openOverlay(c, FlowKind.ATTACK, "p1", 0, 15000, "x");
        commit(t, c);
        c = ctx(t);
        GameModule.openSourcedOverlay(c, FlowKind.RESPONSE, "p2", 0, 5000, "x", new FlowOrigin(FlowOrigin.Kind.ATTACK_RESPONSE, t.game().turn().turnNo(), t.window().windowId(), -1, "p2"));
        commit(t, c);
        assertEquals("p2", t.window().owner());
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void jailHasNoAttackPermission() { assertFalse(StageTable.rule(StageTable.Point.JAIL).preemptible(FlowKind.ATTACK)); }
    @Test void debtCapacityUsesAvailableCash() {
        var t = table(3);
        var g = t.game().withLedger(t.game().ledger().freeze("p1", 2990));
        assertEquals(DebtPath.DIRECT_BANKRUPTCY, EconomyModule.debtPath(t.config, g, "p1", 20));
    }
}
