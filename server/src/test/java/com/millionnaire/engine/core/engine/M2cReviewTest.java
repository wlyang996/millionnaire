package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 第 15 轮复核 N1～N3 的反例（修复前失败，见 m2b-report 的 M2c 一节）。脚本骰点与明确动作序列；篡改反例断言拒绝位置。
 */
class M2cReviewTest {

    /** 标准 20 秒拍卖占位流程（参与者：全体存活者，仅占位测试用），三人都在 100ms 认输（被接受、延后），流程到期后整批清算。 */
    private static Table batchAfterAuction() {
        Table t = M2bReviewTest.table(3);
        DecisionContext<SessionState> c = M2bReviewTest.ctx(t);
        GameModule.openOverlay(c, FlowKind.AUCTION, "p2", 0, OverlayModule.durationMs(t.config, FlowKind.AUCTION), "x");
        M2bReviewTest.commit(t, c);
        long opened = t.now;
        long end = t.window().window().deadline();
        for (String p : List.of("p1", "p2", "p3")) {
            t.send(opened + 100, new GameCommand.Surrender(p, 1));
        }
        assertEquals(3, t.game().pendingSurrenders().size());
        t.tick(end);
        assertEquals("ALL_ELIMINATED", M2bReviewTest.ended(t).reason());
        return t;
    }

    private static int first(List<Event> log, Class<? extends Event> type, int from) {
        for (int i = from; i < log.size(); i++) {
            if (type.isInstance(log.get(i))) {
                return i;
            }
        }
        throw new AssertionError("no " + type.getSimpleName());
    }

    // ------------------------------------------------------------ N1 批次只能在流程结束后开始

    @Test
    void n1ABatchMovedBeforeTheFlowEndsIsRejectedAtItsStart() {
        Table t = batchAfterAuction();
        List<Event> log = new ArrayList<>(t.log);
        int start = first(log, GameEvent.SurrenderBatchStarted.class, 0);
        int end = first(log, GameEvent.SurrenderBatchEnded.class, start);
        List<Event> batch = new ArrayList<>(log.subList(start, end + 1));
        log.subList(start, end + 1).clear();
        int lastDeferred = M2bReviewTest.lastIndexOf(log, GameEvent.SurrenderDeferred.class);
        log.addAll(lastDeferred + 1, batch);
        M2bReviewTest.rejectsAt(t, log, lastDeferred + 1);
    }

    @Test
    void n1TheBatchStartsOnlyAfterTheFlowWindowHasClosed() {
        Table t = batchAfterAuction();
        int start = first(t.log, GameEvent.SurrenderBatchStarted.class, 0);
        long auction = t.log.stream().filter(e -> e instanceof GameEvent.WindowOpened o && o.frame().kind() == FlowKind.AUCTION)
                .map(e -> ((GameEvent.WindowOpened) e).frame().windowId()).findFirst().orElseThrow();
        int closed = first(t.log, GameEvent.WindowClosed.class, 0);
        while (((GameEvent.WindowClosed) t.log.get(closed)).windowId() != auction) {
            closed = first(t.log, GameEvent.WindowClosed.class, closed + 1);
        }
        assertTrue(closed < start, "the batch starts after the auction window closed");
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    // ------------------------------------------------------------ N2 正常终局要求批次已关闭

    @Test
    void n2DeletingTheBatchEndIsRejectedAtTheGameEnd() {
        Table t = batchAfterAuction();
        List<Event> log = new ArrayList<>(t.log);
        log.remove(first(log, GameEvent.SurrenderBatchEnded.class, 0));
        M2bReviewTest.rejectsAt(t, log, first(log, GameEvent.GameEnded.class, 0));
    }

    @Test
    void n2ATamperedDuplicatedOrEarlyBatchEndIsRejectedWhereItAppears() {
        Table t = batchAfterAuction();
        int start = first(t.log, GameEvent.SurrenderBatchStarted.class, 0);
        int end = first(t.log, GameEvent.SurrenderBatchEnded.class, start);
        GameEvent.SurrenderBatchEnded e = (GameEvent.SurrenderBatchEnded) t.log.get(end);
        List<Event> tampered = new ArrayList<>(t.log);
        tampered.set(end, new GameEvent.SurrenderBatchEnded(e.batch() + 1));
        M2bReviewTest.rejectsAt(t, tampered, end);
        List<Event> twice = new ArrayList<>(t.log);
        twice.add(end + 1, e);
        M2bReviewTest.rejectsAt(t, twice, end + 1);
        List<Event> early = new ArrayList<>(t.log);
        early.remove(end);
        early.add(start + 1, e);
        M2bReviewTest.rejectsAt(t, early, start + 1);
    }

    @Test
    void n2AnAdminAbortRemainsAnExplicitExceptionDuringADebtOrAPendingSurrender() {
        Table debt = M2bReviewTest.table(3, 2, 1);
        M2bReviewTest.debtOf(debt);
        assertEquals(Outcome.ACCEPTED, debt.send(debt.now + 10, new SessionCommand.EndGame(1, "ADMIN")).outcome());
        assertFalse(debt.session().inGame());
        Table pending = M2bReviewTest.table(3);
        DecisionContext<SessionState> c = M2bReviewTest.ctx(pending);
        GameModule.openOverlay(c, FlowKind.TRADE, "p2", 0, 15_000, "x");
        M2bReviewTest.commit(pending, c);
        pending.send(pending.now + 5, new GameCommand.Surrender("p2", 1));
        assertEquals(Outcome.ACCEPTED, pending.send(pending.now + 10, new SessionCommand.EndGame(1, "ADMIN")).outcome());
        assertFalse(pending.session().inGame());
        assertEquals(pending.state, pending.engine.rebuild(pending.log));
    }

    // ------------------------------------------------------------ N3 覆盖流程入口门禁

    @Test
    void n3AnOverlayCannotPreemptAnUpgradeWindow() {
        Table t = M2bReviewTest.table(3, 1);
        t.rollOnly();
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        assertEquals(LandingStep.UPGRADE, t.game().turn().landing().step());
        DecisionContext<SessionState> c = M2bReviewTest.ctx(t);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GameModule.openOverlay(c, FlowKind.TRADE, "p2", 0, 15_000, "x"));
        assertTrue(e.getMessage().contains("cannot be preempted"), e.getMessage());
        assertTrue(c.events().isEmpty(), "rejected at the entry: nothing was emitted");
    }

    @Test
    void n3NoForeignOverlayInsideALiquidation() {
        Table t = M2bReviewTest.table(3);
        DecisionContext<SessionState> c = M2bReviewTest.ctx(t);
        c.emit(new GameEvent.LiquidationStarted("p3", 0, com.millionnaire.engine.core.state.LifeState.SURRENDERED));
        int before = c.events().size();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GameModule.openOverlay(c, FlowKind.TRADE, "p2", 0, 15_000, "x"));
        assertTrue(e.getMessage().contains("liquidation"), e.getMessage());
        assertEquals(before, c.events().size(), "rejected at the entry: nothing more was emitted");
    }

    @Test
    void n3NoForeignOverlayDuringADebt() {
        Table t = M2bReviewTest.table(3, 2, 1);
        M2bReviewTest.debtOf(t);
        DecisionContext<SessionState> c = M2bReviewTest.ctx(t);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GameModule.openOverlay(c, FlowKind.TRADE, "p3", 0, 15_000, "x"));
        assertTrue(e.getMessage().contains("debt"), e.getMessage());
        assertTrue(c.events().isEmpty());
    }
}
