package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import static com.millionnaire.engine.core.engine.M2bReviewTest.indexOf;
import static com.millionnaire.engine.core.engine.M2bReviewTest.rejectsAt;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class M3aTamperTest {
    private static <T extends Event> void changed(Table t, Class<T> type, UnaryOperator<T> f) {
        int i = indexOf(t.log, type, 0);
        var log = new ArrayList<>(t.log);
        T old = type.cast(log.get(i));
        T next = f.apply(old);
        assertNotEquals(old, next);
        log.set(i, next);
        rejectsAt(t, log, i);
    }

    private static void duplicate(Table t, Class<? extends Event> type) {
        int i = indexOf(t.log, type, 0);
        var log = new ArrayList<>(t.log);
        log.add(i + 1, log.get(i));
        rejectsAt(t, log, i + 1);
    }

    @Test
    void chainStartDeletionDuplicationInsertionAndEarlyExecutionHaveExactIndices() {
        Table t = M2bReviewTest.table(2, 1);
        t.rollThenResolveEvent();
        assertEquals(t.state, t.engine.rebuild(t.log));
        int start = indexOf(t.log, GameEvent.MoveChainStarted.class, 0);
        int dice = indexOf(t.log, GameEvent.DiceRolled.class, 0);
        var deleted = new ArrayList<>(t.log);
        deleted.remove(start);
        rejectsAt(t, deleted, indexOf(deleted, GameEvent.PlayerMoved.class, 0));
        duplicate(t, GameEvent.MoveChainStarted.class);
        var inserted = new ArrayList<>(t.log);
        inserted.add(dice, t.log.get(start));
        rejectsAt(t, inserted, dice);
        var early = new ArrayList<>(t.log);
        Event e = early.remove(start);
        early.add(dice, e);
        rejectsAt(t, early, dice);
    }

    @Test
    void chainIdentityTurnOwnerOriginAndMovementSegmentCannotBeForged() {
        Table t = M2bReviewTest.table(2, 1);
        t.rollThenResolveEvent();
        changed(t, GameEvent.MoveChainStarted.class, e -> new GameEvent.MoveChainStarted(e.chainId() + 1, e.turnNo(), e.playerId(), e.origin()));
        changed(t, GameEvent.MoveChainStarted.class, e -> new GameEvent.MoveChainStarted(e.chainId(), e.turnNo() + 1, e.playerId(), e.origin()));
        changed(t, GameEvent.MoveChainStarted.class, e -> new GameEvent.MoveChainStarted(e.chainId(), e.turnNo(), "p2", e.origin()));
        changed(t, GameEvent.MoveChainStarted.class, e -> new GameEvent.MoveChainStarted(e.chainId(), e.turnNo(), e.playerId(), e.origin() + 1));
        changed(t, GameEvent.PlayerMoved.class, e -> new GameEvent.PlayerMoved(e.playerId(), e.from(), e.to(), e.steps(), e.chainId() + 1, e.segmentNo(), e.kind(), e.plannedDistance(), e.stoppedBy()));
        changed(t, GameEvent.PlayerMoved.class, e -> new GameEvent.PlayerMoved(e.playerId(), e.from(), e.to(), e.steps(), e.chainId(), e.segmentNo() + 1, e.kind(), e.plannedDistance(), e.stoppedBy()));
        changed(t, GameEvent.PlayerMoved.class, e -> new GameEvent.PlayerMoved(e.playerId(), e.from(), e.to(), e.steps(), e.chainId(), e.segmentNo(), MoveKind.EVENT_FORWARD, e.plannedDistance(), e.stoppedBy()));
        changed(t, GameEvent.LandingStarted.class, e -> new GameEvent.LandingStarted(e.landingId(), e.playerId(), e.tile(), e.chainId() + 1));
        changed(t, GameEvent.LandingStepEntered.class, e -> new GameEvent.LandingStepEntered(e.landingId(), e.step(), e.payment(), e.cursor() + 1));
    }

    private static DebtState source(DebtState d, FeeSource s) {
        return new DebtState(d.debtId(), d.debtor(), d.creditor(), d.amount(), d.cause(), d.segment(), d.continued(), d.windowId(), s, d.path());
    }

    @Test
    void debtSourceFieldsIndependentNumberAndLockedPathAreCheckedAtCreation() {
        Table t = M3aTest.realDebt();
        changed(t, GameEvent.DebtCreated.class, e -> {
            var d = e.debt();
            return new GameEvent.DebtCreated(new DebtState(d.debtId() + 1, d.debtor(), d.creditor(), d.amount(), d.cause(), d.segment(), d.continued(), d.windowId(), d.source(), d.path()));
        });
        changed(t, GameEvent.DebtCreated.class, e -> {
            var d = e.debt();
            return new GameEvent.DebtCreated(new DebtState(d.debtId(), d.debtor(), d.creditor(), d.amount(), d.cause(), d.segment(), d.continued(), d.windowId(), d.source(), DebtPath.DIRECT_BANKRUPTCY));
        });
        changed(t, GameEvent.DebtCreated.class, e -> {
            var s = e.debt().source();
            return new GameEvent.DebtCreated(source(e.debt(), new FeeSource(FeeSource.Kind.FINE, s.landingId(), s.cursor(), s.tile(), s.creditor(), s.amount())));
        });
        changed(t, GameEvent.DebtCreated.class, e -> {
            var s = e.debt().source();
            return new GameEvent.DebtCreated(source(e.debt(), new FeeSource(s.kind(), s.landingId() + 1, s.cursor(), s.tile(), s.creditor(), s.amount())));
        });
        changed(t, GameEvent.DebtCreated.class, e -> {
            var s = e.debt().source();
            return new GameEvent.DebtCreated(source(e.debt(), new FeeSource(s.kind(), s.landingId(), s.cursor() + 1, s.tile(), s.creditor(), s.amount())));
        });
        changed(t, GameEvent.DebtCreated.class, e -> {
            var s = e.debt().source();
            return new GameEvent.DebtCreated(source(e.debt(), new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile() + 1, s.creditor(), s.amount())));
        });
        changed(t, GameEvent.DebtCreated.class, e -> {
            var s = e.debt().source();
            return new GameEvent.DebtCreated(source(e.debt(), new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile(), "p1", s.amount())));
        });
        changed(t, GameEvent.DebtCreated.class, e -> {
            var s = e.debt().source();
            return new GameEvent.DebtCreated(source(e.debt(), new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile(), s.creditor(), s.amount() + 1)));
        });
        duplicate(t, GameEvent.DebtCreated.class);
        int debt = indexOf(t.log, GameEvent.DebtCreated.class, 0);
        int charge = M2bReviewTest.lastIndexOf(t.log, GameEvent.RentCharged.class);
        var deleted = new ArrayList<>(t.log);
        deleted.remove(debt);
        rejectsAt(t, deleted, debt); // 紧随的 LandingStepEntered(DEBT)
        var early = new ArrayList<>(t.log);
        Event e = early.remove(debt);
        early.add(charge, e);
        rejectsAt(t, early, charge);
        var inserted = new ArrayList<>(t.log);
        inserted.add(charge, t.log.get(debt));
        rejectsAt(t, inserted, charge);
    }

    static Table queuedRun() {
        Table t = M2bReviewTest.table(3, 2);
        var c = M2bReviewTest.ctx(t);
        assertNull(FlowCoordinator.request(c, GameModule.FLOW, FlowKind.TRADE, "p2"));
        M2bReviewTest.commit(t, c);
        t.rollThenResolveEvent(); // 普通占位落点结束 → 下一个回合安全点启动
        assertEquals(FlowKind.TRADE, t.window().kind());
        assertEquals(t.state, t.engine.rebuild(t.log));
        return t;
    }

    private static int queuedWindow(Table t) {
        return M2bReviewTest.lastIndexOf(t.log, GameEvent.WindowOpened.class);
    }

    private static void originChanged(Table t, UnaryOperator<FlowOrigin> f) {
        int i = queuedWindow(t);
        var e = (GameEvent.WindowOpened) t.log.get(i);
        var w = e.frame();
        var log = new ArrayList<>(t.log);
        log.set(i, new GameEvent.WindowOpened(new FlowFrame(w.windowId(), w.kind(), w.owner(), w.window(), w.deadlineTaskId(), w.resumeTag(), f.apply(w.origin()))));
        rejectsAt(t, log, i);
    }

    @Test
    void queuedSourceDeletionDuplicateInsertionEarlyStartAndAllAssociationsAreChecked() {
        Table t = queuedRun();
        int dequeued = indexOf(t.log, GameEvent.FlowDequeued.class, 0);
        int opened = queuedWindow(t);
        var deleted = new ArrayList<>(t.log);
        deleted.remove(dequeued);
        rejectsAt(t, deleted, opened - 1);
        duplicate(t, GameEvent.FlowDequeued.class);
        originChanged(t, o -> new FlowOrigin(o.kind(), o.scopeId() + 1, o.ref(), o.cursor(), o.actor()));
        originChanged(t, o -> new FlowOrigin(o.kind(), o.scopeId(), o.ref() + 1, o.cursor(), o.actor()));
        originChanged(t, o -> new FlowOrigin(o.kind(), o.scopeId(), o.ref(), o.cursor() + 1, o.actor()));
        originChanged(t, o -> new FlowOrigin(o.kind(), o.scopeId(), o.ref(), o.cursor(), "p1"));
        originChanged(t, o -> new FlowOrigin(FlowOrigin.Kind.ACTIVE_CARD, o.scopeId(), o.ref(), o.cursor(), o.actor()));
        var early = new ArrayList<>(t.log);
        int request = indexOf(t.log, GameEvent.FlowRequested.class, 0);
        early.add(request + 1, t.log.get(opened));
        rejectsAt(t, early, request + 1);
        var duplicateWindow = new ArrayList<>(t.log);
        duplicateWindow.add(opened + 1, t.log.get(opened));
        rejectsAt(t, duplicateWindow, opened + 1);
    }

    @Test
    void aWindowTaskAssociationIsRejectedAtTheWindowEvent() {
        Table t = queuedRun();
        int i = queuedWindow(t);
        var w = ((GameEvent.WindowOpened) t.log.get(i)).frame();
        var log = new ArrayList<>(t.log);
        log.set(i, new GameEvent.WindowOpened(new FlowFrame(w.windowId(), w.kind(), w.owner(), w.window(), w.deadlineTaskId() + 1, w.resumeTag(), w.origin())));
        rejectsAt(t, log, i);
    }
    @Test
    void aCursorCannotConsumeAResultTwiceOrSkipItsPrecedingPayment() {
        Table t = M2bReviewTest.table(2, 1);
        t.rollThenResolveEvent();
        t.act(w -> new com.millionnaire.engine.core.command.GameCommand.BuyProperty("p1", w));
        duplicate(t, GameEvent.PropertyBought.class);
        int bought = indexOf(t.log, GameEvent.PropertyBought.class, 0);
        var deleted = new ArrayList<>(t.log);
        deleted.remove(bought);
        rejectsAt(t, deleted, bought); // 下一项 UPGRADE 不可凭空生成
        changed(t, GameEvent.PropertyBought.class, e -> new GameEvent.PropertyBought(e.playerId(), e.tile(), e.price() + 1));
        changed(t, GameEvent.PropertyBought.class, e -> new GameEvent.PropertyBought("p2", e.tile(), e.price()));
        changed(t, GameEvent.PropertyBought.class, e -> new GameEvent.PropertyBought(e.playerId(), e.tile() + 1, e.price()));
    }

}
