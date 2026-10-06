package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.SessionCommand.EndGame;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameResult;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnTrack;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 第十六轮遗留问题：篡改必须在目标事件下标被拒。 */
class M2dReviewTest {
    private static Table batchAfterAuction() {
        Table t = M2bReviewTest.table(3);
        DecisionContext<SessionState> c = M2bReviewTest.queuedAtInitialSafePoint(t, FlowKind.AUCTION, "p2",
                OverlayModule.durationMs(t.config, FlowKind.AUCTION), "x");
        M2bReviewTest.commit(t, c);
        long opened = t.now;
        long deadline = t.window().window().deadline();
        for (String player : List.of("p1", "p2", "p3")) {
            t.send(opened + 100, new GameCommand.Surrender(player, 1));
        }
        assertEquals(3, t.game().pendingSurrenders().size());
        t.tick(deadline);
        assertEquals(t.state, t.engine.rebuild(t.log));
        return t;
    }

    @Test
    void deletingTheBatchEndAndNullingTheResultIsRejectedAtGameEnded() {
        Table t = batchAfterAuction();
        List<Event> log = new ArrayList<>(t.log);
        int batchEnd = M2bReviewTest.indexOf(log, GameEvent.SurrenderBatchEnded.class, 0);
        assertEquals(94, batchEnd, "M3b fixture also completes the event card before the queued flow");
        log.remove(batchEnd);
        int end = M2bReviewTest.indexOf(log, GameEvent.GameEnded.class, 0);
        assertEquals(95, end, "M3b index after deleting SurrenderBatchEnded");
        GameEvent.GameEnded event = (GameEvent.GameEnded) log.get(end);
        assertTrue(t.inputs.stream().noneMatch(i -> i.command() instanceof EndGame));
        log.set(end, new GameEvent.GameEnded(event.gameNo(), event.reason(), null));
        M2bReviewTest.rejectsAt(t, log, end);
    }

    @Test
    void aNormalEndDisguisedAsAnAbortIsRejectedAtGameAborted() {
        Table t = batchAfterAuction();
        List<Event> log = new ArrayList<>(t.log);
        int end = M2bReviewTest.indexOf(log, GameEvent.GameEnded.class, 0);
        GameEvent.GameEnded event = (GameEvent.GameEnded) log.get(end);
        log.set(end, new GameEvent.GameAborted(event.gameNo(), event.reason(), t.state.lastSeq()));
        M2bReviewTest.rejectsAt(t, log, end);
    }

    private static Table abortedTable() {
        Table t = M2bReviewTest.table(3);
        EndGame command = new EndGame(1, "ADMIN");
        t.engine.admitSystem(command);
        assertEquals(Outcome.ACCEPTED, t.send(t.now + 10, command).outcome());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        assertFalse(t.session().inGame());
        assertNull(t.session().abortSource());
        assertNull(t.session().lastResult());
        return t;
    }

    private static int abortIndex(Table t) {
        return M2bReviewTest.indexOf(t.log, GameEvent.GameAborted.class, 0);
    }

    private static int sourceIndex(Table t) {
        int abort = abortIndex(t);
        for (int i = abort - 1; i >= 0; i--) {
            if (t.log.get(i) instanceof KernelEvent.InputAccepted) {
                return i;
            }
        }
        throw new AssertionError("no accepted abort input");
    }

    @Test
    void anAbortWithoutTheAcceptedSystemCommandIsRejectedAtGameAborted() {
        Table t = abortedTable();
        int source = sourceIndex(t);
        KernelEvent.InputAccepted accepted = (KernelEvent.InputAccepted) t.log.get(source);
        assertEquals(new EndGame(1, "ADMIN"), accepted.systemCommand());
        List<Event> log = new ArrayList<>(t.log);
        // 保留游标和摘要，只删掉来源内容，避免序号错误在目标终局之前遮蔽本反例。
        log.set(source, new KernelEvent.InputAccepted(accepted.seq(), accepted.at(), accepted.digest()));
        M2bReviewTest.rejectsAt(t, log, abortIndex(t));
    }

    @Test
    void anAbortAfterARejectedEndGameIsRejectedAtGameAborted() {
        Table t = abortedTable();
        int source = sourceIndex(t);
        KernelEvent.InputAccepted accepted = (KernelEvent.InputAccepted) t.log.get(source);
        List<Event> log = new ArrayList<>(t.log);
        log.set(source, new KernelEvent.InputRejected(accepted.seq(), accepted.at(), accepted.digest(),
                RejectionCode.INVALID_ARGUMENT, null));
        M2bReviewTest.rejectsAt(t, log, abortIndex(t));
    }

    @Test
    void aClientCommandCannotBeRecordedAsTheSystemSource() {
        Table t = abortedTable();
        int source = sourceIndex(t);
        KernelEvent.InputAccepted accepted = (KernelEvent.InputAccepted) t.log.get(source);
        GameCommand.Surrender client = new GameCommand.Surrender("p1", 1);
        List<Event> log = new ArrayList<>(t.log);
        log.set(source, new KernelEvent.InputAccepted(accepted.seq(), accepted.at(),
                t.engine.digest(new Input(accepted.seq(), accepted.at(), client)), client));
        M2bReviewTest.rejectsAt(t, log, source);
        assertThrows(InvalidInputException.class, () -> t.engine.admitClient(new EndGame(1, "ADMIN")));
    }

    @Test
    void anUnrelatedAcceptedInputDigestCannotAuthorizeAnEndGame() {
        Table t = abortedTable();
        int source = sourceIndex(t);
        KernelEvent.InputAccepted accepted = (KernelEvent.InputAccepted) t.log.get(source);
        List<Event> log = new ArrayList<>(t.log);
        log.set(source, new KernelEvent.InputAccepted(accepted.seq(), accepted.at(),
                t.engine.digest(new Input(accepted.seq(), accepted.at(), new Tick())), accepted.systemCommand()));
        M2bReviewTest.rejectsAt(t, log, source);
    }

    @Test
    void aChangedSystemCommandMustMatchTheAcceptedInputDigest() {
        Table t = abortedTable();
        int source = sourceIndex(t);
        KernelEvent.InputAccepted accepted = (KernelEvent.InputAccepted) t.log.get(source);
        for (EndGame changed : List.of(new EndGame(2, "ADMIN"), new EndGame(1, "OTHER"))) {
            List<Event> log = new ArrayList<>(t.log);
            log.set(source, new KernelEvent.InputAccepted(accepted.seq(), accepted.at(), accepted.digest(), changed));
            M2bReviewTest.rejectsAt(t, log, source);
        }
    }

    @Test
    void abortFieldsMustMatchTheAcceptedSystemCommand() {
        Table t = abortedTable();
        int abort = abortIndex(t);
        GameEvent.GameAborted event = (GameEvent.GameAborted) t.log.get(abort);
        for (GameEvent.GameAborted changed : List.of(
                new GameEvent.GameAborted(event.gameNo() + 1, event.reason(), event.inputSeq()),
                new GameEvent.GameAborted(event.gameNo(), "OTHER", event.inputSeq()),
                new GameEvent.GameAborted(event.gameNo(), event.reason(), event.inputSeq() - 1))) {
            List<Event> log = new ArrayList<>(t.log);
            log.set(abort, changed);
            M2bReviewTest.rejectsAt(t, log, abort);
        }
    }

    @Test
    void aNormalEndAfterAnAdminAbortIsRejectedAtTheSecondEnd() {
        Table t = M2bReviewTest.table(3);
        GameResult result = new GameResult(1, "TIME_UP", TurnModule.standings(t.game(), t.config));
        t.send(t.now + 10, new EndGame(1, "ADMIN"));
        List<Event> log = new ArrayList<>(t.log);
        int second = log.size();
        log.add(new GameEvent.GameEnded(1, "TIME_UP", result));
        M2bReviewTest.rejectsAt(t, log, second);
        List<Event> duplicate = new ArrayList<>(t.log);
        duplicate.add(t.log.get(abortIndex(t)));
        M2bReviewTest.rejectsAt(t, duplicate, second);
    }

    @Test
    void aSystemAbortCannotBeReplacedByANormalResult() {
        Table t = abortedTable();
        int abort = abortIndex(t);
        // 恢复终止事件之前的演化状态，只用来生成与该状态匹配的合法排名。
        EngineState before = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, t.log.subList(0, abort));
        SessionState session = (SessionState) before.domain();
        GameResult result = new GameResult(1, "TIME_UP", TurnModule.standings(session.game(), t.config));
        List<Event> log = new ArrayList<>(t.log);
        log.set(abort, new GameEvent.GameEnded(1, "TIME_UP", result));
        M2bReviewTest.rejectsAt(t, log, abort);
    }

    @Test
    void aSourceCannotEscapeToTheNextInputOrBeRestoredFromASnapshot() {
        Table t = abortedTable();
        int abort = abortIndex(t);
        List<Event> prefix = new ArrayList<>(t.log.subList(0, abort));
        EngineState unfinished = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, prefix);
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(unfinished)));
        Input next = new Input(t.seq + 1, t.now + 1, new Tick());
        prefix.add(new KernelEvent.InputAccepted(next.seq(), next.serverTime(), t.engine.digest(next)));
        M2bReviewTest.rejectsAt(t, prefix, abort);
    }

    @Test
    void realSystemAbortsStillWorkDuringDebtAndDeferredSurrender() {
        Table debt = M2bReviewTest.table(2, 1, 5, 2, 1, 1, 1, 1);
        for (String player : List.of("p1", "p2", "p1", "p2")) {
            debt.rollThenResolveEvent();
            debt.act(w -> new GameCommand.BuyProperty(player, w));
            debt.act(w -> new GameCommand.UpgradeProperty(player, w));
        }
        debt.rollThenResolveEvent();                                            // p1 → 4，买车站后剩 400
        debt.act(w -> new GameCommand.BuyProperty("p1", w));
        debt.rollThenResolveEvent();                                            // p2 → 7，无力购买中价地产
        debt.rollThenResolveEvent();                                            // p1 → 5，须付 500；资产足够，进入债务窗口
        assertEquals(400, debt.cash("p1"));
        assertEquals(FlowKind.DEBT, debt.window().kind());
        assertEquals(debt.state, debt.engine.rebuild(debt.log));
        assertTrue(debt.game().debt() != null);
        assertEquals(Outcome.ACCEPTED, debt.send(debt.now + 10, new EndGame(1, "DEBT_ABORT")).outcome());
        assertFalse(debt.session().inGame());
        assertEquals(debt.state, debt.engine.rebuild(debt.engine.decodeEvents(debt.engine.encodeEvents(debt.log))));
        Table pending = M2bReviewTest.table(3);
        DecisionContext<SessionState> c = M2bReviewTest.queuedAtInitialSafePoint(pending, FlowKind.TRADE, "p2", 15_000, "x");
        M2bReviewTest.commit(pending, c);
        pending.send(pending.now + 5, new GameCommand.Surrender("p2", 1));
        assertEquals(List.of("p2"), pending.game().pendingSurrenders());
        assertEquals(Outcome.ACCEPTED, pending.send(pending.now + 10, new EndGame(1, "PENDING_ABORT")).outcome());
        assertFalse(pending.session().inGame());
        assertEquals(pending.state, pending.engine.rebuild(pending.log));
        assertEquals(pending.state, pending.engine.restore(pending.engine.snapshot(pending.state)));
    }

    @Test
    void aNormalEndCannotDiscardDiceMovementLandingOrOrderDraws() {
        Table t = M2bReviewTest.table(3, 2);
        t.rollThenResolveEvent();
        assertEquals(t.state, t.engine.rebuild(t.log));
        for (Class<? extends Event> type : List.of(GameEvent.OrderRoundStarted.class, GameEvent.DiceRolled.class,
                GameEvent.PlayerMoved.class, GameEvent.Landed.class)) {
            int cut = M2bReviewTest.indexOf(t.log, type, 0);
            List<Event> prefix = new ArrayList<>(t.log.subList(0, cut + 1));
            EngineState state = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, prefix);
            SessionState session = (SessionState) state.domain();
            TurnTrack track = session.game().turn().track();
            assertFalse(TurnTrack.NONE.equals(track), type.getSimpleName());
            assertNull(session.game().debt());
            assertTrue(session.game().pendingSurrenders().isEmpty());
            assertNull(track.liquidating());
            assertEquals(0, track.openBatch());
            assertEquals(0, track.pendingCharge());
            // 保留真实前缀，仅执行合法的终止清理；旧的五项经济残留检查均会放行这些步内状态。
            DecisionContext<SessionState> c = new DecisionContext<>(state, new Evolver<>(SessionDomain.INSTANCE, t.config),
                    SessionState.class, XoshiroLemireV1.INSTANCE, t.config);
            TurnModule.terminate(c);
            prefix.addAll(c.events());
            var game = c.state().game();
            GameResult result = new GameResult(1, "TIME_UP", TurnModule.standings(game, t.config));
            int end = prefix.size();
            prefix.add(new GameEvent.GameEnded(1, "TIME_UP", result));
            M2bReviewTest.rejectsAt(t, prefix, end);
        }
        // 干净步边界对照：同样清理窗口、任务后，正常非空结果可完整重建。
        DecisionContext<SessionState> clean = M2bReviewTest.ctx(t);
        TurnModule.finish(clean, "TIME_UP");
        M2bReviewTest.commit(t, clean);
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void aForgedGenesisCannotSeedAbortAuthority() {
        Table t = abortedTable();
        int abort = abortIndex(t);
        EngineState before = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, t.log.subList(0, abort));
        SessionState forged = (SessionState) before.domain();
        KernelEvent.Genesis original = (KernelEvent.Genesis) t.log.get(0);
        KernelEvent.Genesis genesis = new KernelEvent.Genesis(original.roomId(), original.configHash(),
                original.engineVersion(), original.domainId(), original.rngProtocol(), original.rng(), original.at(), forged);
        M2bReviewTest.rejectsAt(t, List.of(genesis, t.log.get(abort)), 0);
    }

    @Test
    void aNormalEndWithANullResultIsRejectedEvenWithACleanTrack() {
        Table t = batchAfterAuction();
        int end = M2bReviewTest.indexOf(t.log, GameEvent.GameEnded.class, 0);
        GameEvent.GameEnded event = (GameEvent.GameEnded) t.log.get(end);
        List<Event> log = new ArrayList<>(t.log);
        log.set(end, new GameEvent.GameEnded(event.gameNo(), event.reason(), null));
        M2bReviewTest.rejectsAt(t, log, end);
    }
}
