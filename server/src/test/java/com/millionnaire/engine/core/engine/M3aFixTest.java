package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import static com.millionnaire.engine.core.engine.M2bReviewTest.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class M3aFixTest {
    @Test
    void manualLiquidationNeedsAnOpenedSegmentEvenWithAcceptedSurrender() {
        Table t = M3aTest.realDebt();
        int created = indexOf(t.log, GameEvent.DebtCreated.class, 0);
        var log = new ArrayList<>(t.log.subList(0, created + 1));
        var prefix = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, log);
        var d = ((SessionState) prefix.domain()).game().debt();
        // Supply a genuine, digest-bound source so bankruptcyDebtId is not the rejecting layer.
        // The forged history starts a new input before opening MANUAL segment 1.
        var command = new GameCommand.Surrender(d.debtor(), t.game().gameNo());
        var input = new com.millionnaire.engine.core.command.Input(prefix.lastSeq() + 1, prefix.now(), command);
        log.add(new com.millionnaire.engine.core.event.KernelEvent.InputAccepted(input.seq(), input.serverTime(),
                com.millionnaire.engine.serialize.Canonical.sha256Hex(t.engine.codec().bytes(input)), null, command));
        var sourced = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, log);
        var g = ((SessionState) sourced.domain()).game();
        assertEquals(d.debtId(), g.turn().track().bankruptcyDebtId());
        assertTrue(g.flow().frames().isEmpty());
        assertEquals(0, g.debt().segment());
        assertEquals(0, g.debt().windowId());
        t.send(Math.max(t.now + 1, t.window().window().opensAt()), command);
        int liquidation = indexOf(t.log, GameEvent.LiquidationStarted.class, 0);
        int target = log.size();
        log.addAll(t.log.subList(liquidation, t.log.size()));
        rejectsAt(t, log, target);
    }

    @Test
    void consecutiveSafePointsNeedDistinctTurnStarts() {
        Table t = table(3, 2);
        int safe = indexOf(t.log, GameEvent.SafePointEntered.class, 0);
        var e = (GameEvent.SafePointEntered) t.log.get(safe);
        var log = new ArrayList<>(t.log);
        // Empty stack, NONE stage, no chain/landing, and sequential number all remain valid.
        log.add(safe + 1, new GameEvent.SafePointEntered(e.safePointNo() + 1));
        rejectsAt(t, log, safe + 1);
    }

    @Test
    void dequeueBeforeTheNextTurnStartCannotReuseThePreviousSafePoint() {
        Table t = M3aTamperTest.queuedRun();
        int started = lastIndexOf(t.log, GameEvent.TurnStarted.class);
        int dequeued = indexOf(t.log, GameEvent.FlowDequeued.class, 0);
        var log = new ArrayList<>(t.log.subList(0, started));
        var prefix = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, log);
        var g = ((SessionState) prefix.domain()).game();
        assertEquals(TurnStage.NONE, g.turn().stage());
        assertNull(g.turn().chain());
        assertNull(g.turn().landing());
        assertEquals(0, g.turn().track().safePointPhase());
        assertTrue(g.flow().safePointNo() > g.flow().startedAtSafePoint());
        // Skip the new TurnStarted/SafePointEntered pair. Low-level FIFO and one-flow checks pass.
        // Rebind the subsequent window to the old, otherwise internally consistent safe point.
        for (var event : t.log.subList(dequeued, t.log.size())) {
            if (event instanceof GameEvent.TurnStageEntered e) {
                event = new GameEvent.TurnStageEntered(e.stage(), e.windowId(),
                        new Continuation.BeginTurn(g.turn().turnNo()), e.notBefore());
            } else if (event instanceof GameEvent.WindowOpened e) {
                var w = e.frame();
                var o = w.origin();
                event = new GameEvent.WindowOpened(new FlowFrame(w.windowId(), w.kind(), w.owner(), w.window(),
                        w.deadlineTaskId(), w.resumeTag(), new FlowOrigin(o.kind(), g.flow().safePointNo(),
                        o.ref(), o.cursor(), o.actor())));
            }
            log.add(event);
        }
        rejectsAt(t, log, started);
    }

    @Test
    void manualDebtCannotLiquidateInItsCreationStep() {
        Table t = M3aTest.realDebt();
        int created = indexOf(t.log, GameEvent.DebtCreated.class, 0);
        var log = new ArrayList<>(t.log.subList(0, created + 1));
        t.tick(t.window().window().deadline());
        t.tick(t.window().window().deadline());
        int liquidation = indexOf(t.log, GameEvent.LiquidationStarted.class, 0);
        int ended = indexOf(t.log, GameEvent.GameEnded.class, 0);
        log.addAll(t.log.subList(liquidation, ended + 1));
        rejectsAt(t, log, created + 1);
    }

    @Test
    void aSecondSafePointAtTheSameTurnBoundaryIsRejected() {
        Table t = table(3, 2);
        var c = ctx(t);
        FlowCoordinator.request(c, GameModule.FLOW, FlowKind.TRADE, "p2");
        FlowCoordinator.request(c, GameModule.FLOW, FlowKind.AUCTION, "p3");
        commit(t, c);
        t.rollThenResolveEvent();
        t.tick(t.window().window().deadline());
        int closed = lastIndexOf(t.log, GameEvent.WindowClosed.class);
        var log = new ArrayList<>(t.log.subList(0, closed + 1));
        t.state = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, log);
        var f = t.game().flow();
        var r = f.queue().getFirst();
        long safe = f.safePointNo() + 1, id = f.nextWindowId(), task = t.state.nextTaskId();
        var w = com.millionnaire.engine.time.Window.open(id, t.state.now(), 0, 20_000);
        log.add(new GameEvent.SafePointEntered(safe));
        log.add(new GameEvent.FlowDequeued(r.requestId()));
        log.add(new com.millionnaire.engine.core.event.KernelEvent.TaskScheduled(new com.millionnaire.engine.time.ScheduledTask(task, w.deadline(), com.millionnaire.engine.time.TaskKind.FLOW, id)));
        log.add(new GameEvent.WindowOpened(new FlowFrame(id, r.kind(), r.applicant(), w, task, "x", new FlowOrigin(FlowOrigin.Kind.QUEUED, safe, r.requestId(), -1, r.applicant()))));
        rejectsAt(t, log, closed + 1);
    }

    @Test
    void anAcceptedRollCannotBeReplacedByBeginTurn() {
        Table t = table(3, 2);
        var request = ctx(t);
        FlowCoordinator.request(request, GameModule.FLOW, FlowKind.TRADE, "p2");
        commit(t, request);
        t.rollThenResolveEvent();
        int dice = indexOf(t.log, GameEvent.DiceRolled.class, 0) - 1; // before RandomDrawn, after the roll window closed
        int start = indexOf(t.log, GameEvent.TurnStarted.class, 0);
        long turn = ((GameEvent.TurnStarted) t.log.get(start)).turnNo();
        var log = new ArrayList<>(t.log.subList(0, dice));
        t.state = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, log);
        var f = t.game().flow();
        var r = f.queue().getFirst();
        long id = f.nextWindowId(), task = t.state.nextTaskId();
        var w = com.millionnaire.engine.time.Window.open(id, t.state.now(), 0, 15_000);
        log.add(new GameEvent.TurnStageEntered(TurnStage.AWAITING_FLOW, 0, new Continuation.BeginTurn(turn), t.state.now()));
        log.add(new GameEvent.FlowDequeued(r.requestId()));
        log.add(new com.millionnaire.engine.core.event.KernelEvent.TaskScheduled(new com.millionnaire.engine.time.ScheduledTask(task, w.deadline(), com.millionnaire.engine.time.TaskKind.FLOW, id)));
        log.add(new GameEvent.WindowOpened(new FlowFrame(id, r.kind(), r.applicant(), w, task, "x", new FlowOrigin(FlowOrigin.Kind.QUEUED, f.safePointNo(), r.requestId(), -1, r.applicant()))));
        rejectsAt(t, log, dice);
    }

    @Test
    void disconnectAndHostingDoNotReclassifyTheSecondManualSegment() {
        Table t = M3aTest.realDebt();
        String debtor = t.game().debt().debtor();
        long first = t.window().window().deadline();
        t.send(t.now + 1, new GameCommand.ConnectionSuspected(1, debtor, 1));
        t.send(t.now + 1, new GameCommand.ConnectionConfirmed(1, debtor, 2));
        t.send(t.now + 1, new GameCommand.SetControl(1, debtor, ControlMode.HOSTED));
        t.tick(first);
        assertTrue(t.game().player(debtor).orElseThrow().alive());
        assertEquals(DebtPath.MANUAL, t.game().debt().path());
        assertEquals(2, t.game().debt().segment());
        assertEquals(first + 30_000, t.window().window().deadline());
        t.tick(first + 29_999);
        assertTrue(t.game().player(debtor).orElseThrow().alive());
        t.tick(first + 30_000);
        assertFalse(t.session().inGame());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void dequeuedRequestStillNeedsAwaitingBeginTurnAtTheOnlineGate() {
        Table t = M3aTamperTest.queuedRun();
        int dequeued = indexOf(t.log, GameEvent.FlowDequeued.class, 0);
        t.state = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, t.log.subList(0, dequeued + 1));
        assertEquals(TurnStage.NONE, t.game().turn().stage());
        var c = ctx(t);
        var r = t.game().flow().pendingStart();
        assertThrows(IllegalStateException.class, () -> GameModule.openQueuedOverlay(c, r, 0, 15_000, "x"));
        assertTrue(c.events().isEmpty());
    }

    @Test
    void onlyTheDiceValueIsChanged() {
        Table t = table(2, 1);
        t.rollThenResolveEvent();
        int i = indexOf(t.log, GameEvent.DiceRolled.class, 0);
        var e = (GameEvent.DiceRolled) t.log.get(i);
        var log = new ArrayList<>(t.log);
        log.set(i, new GameEvent.DiceRolled(e.playerId(), e.value() + 1, e.auto()));
        rejectsAt(t, log, i);
    }

    @Test
    void onlyTheMovementStepsAreChanged() {
        Table t = table(2, 1);
        t.rollThenResolveEvent();
        int i = indexOf(t.log, GameEvent.PlayerMoved.class, 0);
        var e = (GameEvent.PlayerMoved) t.log.get(i);
        var log = new ArrayList<>(t.log);
        log.set(i, new GameEvent.PlayerMoved(e.playerId(), e.from(), e.to(), e.steps() + 1, e.chainId(), e.segmentNo(), e.kind(), e.plannedDistance(), e.stoppedBy()));
        rejectsAt(t, log, i);
        // Isolate the committed-die check: endpoint geometry would also reject this field, but for a different reason.
        var ex = assertThrows(StateValidationException.class, () -> t.engine.rebuild(log));
        assertTrue(ex.getMessage().contains("move does not follow the committed source/chain"), ex.getMessage());
    }
}
