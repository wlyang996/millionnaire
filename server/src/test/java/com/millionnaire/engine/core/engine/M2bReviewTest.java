package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.RoomCommand.Join;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameClock;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.GameView;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.LifeState;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.Standing;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 第 14 轮评审 E1～E10 的反例（修复前失败，见 m2b-report）。关键反例都用脚本骰点与明确的动作序列；
 * 篡改反例断言<b>在目标事件处</b>被拒（重建异常信息中的事件下标）。30 格地图：1 L、3 L、4 车站、5 M、11 银行。
 */
class M2bReviewTest {

    // ------------------------------------------------------------ 工具

    static Table table(int players, int... moves) {
        if (moves.length == 0) { moves = new int[] {2}; }
        int[] order = new int[players];
        for (int i = 0; i < players; i++) {
            order[i] = 90 - i * 10;
        }
        return new Table(ScriptedRandom.withEventCards(script(order(order), Table.deal(players), dice(DrawPoint.MOVE_DIE, moves))), 1)
                .start(players);
    }

    private static void craft(Table t, UnaryOperator<GameState> f) {
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(f.apply(t.game())))));
    }

    private static UnaryOperator<GameState> own(int tile, String owner, int level) {
        return g -> g.withBoard(g.board().with(new OwnableState(tile, owner, level, false, 0, null)));
    }

    private static UnaryOperator<GameState> mortgaged(int tile, String owner, long principal) {
        return g -> g.withBoard(g.board().with(new OwnableState(tile, owner, 0, true, principal, null)));
    }

    private static UnaryOperator<GameState> cash(String player, long cash) {
        return g -> g.withLedger(g.ledger().transfer(player, Ledger.SYSTEM, g.ledger().cash(player) - cash, "TEST", null));
    }

    /** 把全局到时改到 at（重排 GLOBAL_END 任务并相应平移开局时刻，经恢复入口完整校验）。 */
    private static void endAt(Table t, long at) {
        GameState g = t.game();
        long duration = g.clock().endsAt() - g.startedAt();
        long task = g.clock().taskId();
        GameState moved = new GameState(g.gameNo(), at - duration, g.settings(), g.phase(), g.players(), g.orderDraws(),
                g.board(), g.turn(), g.flow(), g.ledger(), new GameClock(at, task), g.debt(), g.pendingSurrenders());
        EngineState s = t.state.withTimers(t.state.timers().cancel(task)
                .schedule(new ScheduledTask(task, at, TaskKind.GLOBAL_END, g.gameNo())), t.state.nextTaskId());
        t.state = t.engine.restore(t.engine.snapshot(s.withDomain(t.session().withGame(moved))));
    }

    static DecisionContext<SessionState> ctx(Table t) {
        return new DecisionContext<>(t.state, new Evolver<>(SessionDomain.INSTANCE, t.config), SessionState.class,
                XoshiroLemireV1.INSTANCE, t.config);
    }

    static void commit(Table t, DecisionContext<SessionState> c) {
        t.engine.validate(c.engineState());
        t.state = c.engineState();
        t.log.addAll(c.events());
    }

    /** 在上一回合的投骰窗提交申请，走完无费用落点，到下一回合安全点启动；保留认输/清算测试意图。 */
    static DecisionContext<SessionState> queuedAtInitialSafePoint(Table t, FlowKind kind, String owner,
                                                                  long duration, String tag) {
        DecisionContext<SessionState> c = ctx(t);
        assertEquals(null, FlowCoordinator.request(c, GameModule.FLOW, kind, owner));
        commit(t, c);
        t.rollThenResolveEvent();
        assertEquals(kind, t.window().kind());
        int opened = lastIndexOf(t.log, GameEvent.WindowOpened.class);
        assertEquals(t.log.size() - 1, opened);
        // 仅重新生成最后的开窗，以保留调用方指定的占位时限与标签；出队/阶段来自正常回合路径。
        t.log.subList(opened - 1, t.log.size()).clear();
        t.state = new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(null, t.log);
        c = ctx(t);
        var r = t.game().flow().pendingStart();
        GameModule.openQueuedOverlay(c, r, Math.max(0, t.game().turn().notBefore() - t.state.now()), duration, tag);
        return c;
    }

    static <T extends Event> int indexOf(List<Event> log, Class<T> type, int nth) {
        int seen = 0;
        for (int i = 0; i < log.size(); i++) {
            if (type.isInstance(log.get(i)) && seen++ == nth) {
                return i;
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " #" + nth);
    }

    static <T extends Event> int lastIndexOf(List<Event> log, Class<T> type) {
        for (int i = log.size() - 1; i >= 0; i--) {
            if (type.isInstance(log.get(i))) {
                return i;
            }
        }
        throw new AssertionError("no " + type.getSimpleName());
    }

    /** 重建必须在第 index 个事件处被拒。 */
    static void rejectsAt(Table t, List<Event> log, int index) {
        StateValidationException e = assertThrows(StateValidationException.class, () -> t.engine.rebuild(log));
        assertTrue(e.getMessage().contains("at event " + index + ":"), "expected rejection at event " + index + " ("
                + log.get(index).getClass().getSimpleName() + ") but got: " + e.getMessage());
    }

    static GameEvent.GameEnded ended(Table t) {
        return t.log.stream().filter(e -> e instanceof GameEvent.GameEnded).map(e -> (GameEvent.GameEnded) e).findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------ E1 落点必要步骤与决策消费

    @Test
    void e1DeletingTheRentIsRejectedAtTheLandingFinish() {
        Table t = table(2, 1, 1);
        t.rollThenResolveEvent();
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        t.act(w -> new GameCommand.UpgradeProperty("p1", w));
        t.rollThenResolveEvent();                                               // p2 → 1：缴租 250
        assertEquals(2450, t.cash("p1"));
        List<Event> log = new ArrayList<>(t.log);
        log.remove(indexOf(log, GameEvent.RentPaid.class, 0));
        log.remove(indexOf(log, GameEvent.RentCharged.class, 0));
        rejectsAt(t, log, indexOf(log, GameEvent.LandingFinished.class, 1));
    }

    @Test
    void e1ASecondUpgradeInTheSameLandingIsRejectedWhereItIsInserted() {
        Table t = table(2, 1);
        t.rollThenResolveEvent();
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        t.act(w -> new GameCommand.UpgradeProperty("p1", w));
        List<Event> log = new ArrayList<>(t.log);
        int up = indexOf(log, GameEvent.PropertyUpgraded.class, 0);
        log.add(up + 1, new GameEvent.PropertyUpgraded("p1", 1, 2, 300));
        rejectsAt(t, log, up + 1);
    }

    @Test
    void e1ASkippedOrDuplicatedDecisionIsRejected() {
        Table t = table(2, 1);
        t.rollThenResolveEvent();
        t.act(w -> new GameCommand.DeclinePurchase("p1", w));
        List<Event> skipped = new ArrayList<>(t.log);
        skipped.remove(indexOf(skipped, GameEvent.PurchaseDeclined.class, 0));
        rejectsAt(t, skipped, indexOf(skipped, GameEvent.LandingFinished.class, 0));
        List<Event> twice = new ArrayList<>(t.log);
        int d = indexOf(twice, GameEvent.PurchaseDeclined.class, 0);
        twice.add(d + 1, twice.get(d));
        rejectsAt(t, twice, d + 1);
    }

    // ------------------------------------------------------------ E2 延后认输不被提前判胜吞掉

    @Test
    void e2AnAcceptedDeferredSurrenderIsNotSwallowedByAnEarlyWin() {
        Table t = table(3);
        DecisionContext<SessionState> c = queuedAtInitialSafePoint(t, FlowKind.TRADE, "p2", 15_000, "x");    // p2 的交易流程（参与者：p2）
        commit(t, c);
        long end = t.window().window().deadline();
        t.send(t.now + 5, new GameCommand.Surrender("p2", 1));
        assertEquals(List.of("p2"), t.game().pendingSurrenders());
        t.send(t.now + 5, new GameCommand.Surrender("p1", 1));            // 当前玩家，但不是该流程的参与者：立即
        t.send(t.now + 5, new GameCommand.Surrender("p3", 1));
        assertTrue(t.session().inGame(), "no winner while p2's accepted surrender is still pending");
        t.tick(end);                                                  // 流程结束 → 批处理 p2 → 零存活
        GameEvent.GameEnded e = ended(t);
        assertEquals("ALL_ELIMINATED", e.reason());
        assertEquals(List.of(new Standing("p2", 1, 3000, 0), new Standing("p3", 2, 0, 0), new Standing("p1", 3, 0, 0)),
                e.result().standings());
    }

    private static List<String> twoDeferred(String first, String second) {
        Table t = table(3);
        DecisionContext<SessionState> c = queuedAtInitialSafePoint(t, FlowKind.AUCTION, "p2", 20_000, "x");  // 拍卖占位：全体存活者都是参与者
        commit(t, c);
        long end = t.window().window().deadline();
        t.send(t.now + 5, new GameCommand.Surrender(first, 1));
        t.send(t.now + 5, new GameCommand.Surrender(second, 1));
        assertEquals(List.of(first, second), t.game().pendingSurrenders());
        t.tick(end);
        GameEvent.GameEnded e = ended(t);
        assertEquals("LAST_SURVIVOR", e.reason());
        return e.result().standings().stream().map(Standing::playerId).toList();
    }

    @Test
    void e2TwoDeferredSurrendersAreProcessedInReceiptOrder() {
        assertEquals(List.of("p1", "p2", "p3"), twoDeferred("p3", "p2"), "p2 went out later, so it ranks higher");
        assertEquals(List.of("p1", "p3", "p2"), twoDeferred("p2", "p3"), "swapping the requests swaps the ranks");
    }

    @Test
    void e2AWholeTableDeferredBatchEndsWithEveryoneTiedOnPreBatchNetWorth() {
        Table t = table(3);
        DecisionContext<SessionState> c = queuedAtInitialSafePoint(t, FlowKind.AUCTION, "p2", 20_000, "x");
        commit(t, c);
        long end = t.window().window().deadline();
        for (String p : List.of("p2", "p3", "p1")) {
            t.send(t.now + 5, new GameCommand.Surrender(p, 1));
        }
        assertEquals(3, t.game().pendingSurrenders().size());
        t.tick(end);
        GameEvent.GameEnded e = ended(t);
        assertEquals("ALL_ELIMINATED", e.reason());
        assertEquals(List.of(1, 1, 1), e.result().standings().stream().map(Standing::rank).toList());
        assertTrue(e.result().standings().stream().allMatch(s -> s.netWorth() == 3000));
    }

    /** 正常结算事件（带结算摘要）在给定会话状态上演化。 */
    private static void evolveEnd(Table t, SessionState s) {
        GameState g = s.game();
        GameEvent.GameEnded end = new GameEvent.GameEnded(1, "TIME_UP", new com.millionnaire.engine.core.state.GameResult(1,
                "TIME_UP", TurnModule.standings(g, t.config)));
        GameModule.evolve(s, end, null, t.config);
    }

    private static SessionState withoutWindows(Table t) {
        GameState g = t.game();
        return t.session().withGame(g.withFlow(g.flow().withFrames(List.of())));
    }

    @Test
    void e2GameEndedIsRejectedWhileAnAcceptedSurrenderIsPending() {
        Table t = table(3);
        SessionState s = withoutWindows(t);
        evolveEnd(t, s);                                            // 对照：干净状态可以结束
        SessionState pending = s.withGame(s.game().withPendingSurrenders(List.of("p2")));
        assertThrows(IllegalStateException.class, () -> evolveEnd(t, pending));
    }

    @Test
    void e2GameEndedIsRejectedWhileADebtIsOpen() {
        Table t = table(3);
        SessionState s = withoutWindows(t);
        SessionState debt = s.withGame(s.game().withDebt(new com.millionnaire.engine.core.state.DebtState(1, "p1", "p2", 100,
                EconomyModule.RENT, 1, false, 0, null, com.millionnaire.engine.core.state.DebtPath.MANUAL)));
        assertThrows(IllegalStateException.class, () -> evolveEnd(t, debt));
    }

    @Test
    void e2GameEndedIsRejectedInsideALiquidation() {
        Table t = table(3);
        SessionState s = withoutWindows(t);
        GameState g = s.game();
        SessionState liquidating = s.withGame(g.withTurn(g.turn().withTrack(g.turn().track().liquidating(
                new com.millionnaire.engine.core.state.Liquidation("p3", LifeState.SURRENDERED, 0)))));
        assertThrows(IllegalStateException.class, () -> evolveEnd(t, liquidating));
    }

    // ------------------------------------------------------------ E3 清算原因贯穿

    @Test
    void e3AnEliminationMustMatchTheLiquidationOutcome() {
        Table t = table(3);
        t.send(t.now + 5, new GameCommand.Surrender("p3", 1));
        List<Event> log = new ArrayList<>(t.log);
        int i = indexOf(log, GameEvent.PlayerEliminated.class, 0);
        GameEvent.PlayerEliminated e = (GameEvent.PlayerEliminated) log.get(i);
        assertEquals(LifeState.SURRENDERED, e.life());
        log.set(i, new GameEvent.PlayerEliminated(e.playerId(), LifeState.BANKRUPT, e.elimination()));
        rejectsAt(t, log, i);
    }

    // ------------------------------------------------------------ E4 冻结归属

    @Test
    void e4FrozenCashWithoutAnyHoldingFlowIsRejectedOnRestore() {
        Table t = table(3);
        GameState g = t.game();
        EngineState frozen = t.state.withDomain(t.session().withGame(g.withLedger(g.ledger().freeze("p3", 100))));
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(frozen)));
    }

    // ------------------------------------------------------------ E5 到时原因固定

    /** p2 欠 p1 100（现金 50，持有 3 号低价地，应急 400）；返回债务第一段开启时刻。 */
    static long debtOf(Table t) {
        t.rollThenResolveEvent();                                               // p1 → 2
        craft(t, own(1, "p1", 0).andThen(own(3, "p2", 0)).andThen(cash("p2", 50))::apply);
        t.rollThenResolveEvent();                                               // p2 → 1
        assertEquals(FlowKind.DEBT, t.window().kind());
        return t.window().window().opensAt();
    }

    @Test
    void e5GlobalEndAtTheSameInstantAsTheSecondDebtSegmentExpiryStaysTimeUp() {
        Table t = table(2, 2, 1);
        long s = debtOf(t);
        endAt(t, s + 60_000);
        t.tick(s + 30_000);
        assertEquals(2, t.game().debt().segment());
        t.tick(s + 60_000);                                         // 同刻：全局到时先于流程到期，随后第二段到期破产
        assertEquals("TIME_UP", ended(t).reason());
    }

    @Test
    void e5BankruptcyAfterTheGlobalEndStaysTimeUp() {
        Table t = table(2, 2, 1);
        long s = debtOf(t);
        endAt(t, s + 5_000);
        t.tick(s + 5_000);
        assertEquals(GamePhase.DRAINING, t.game().phase());
        t.send(t.now + 10, new GameCommand.DeclareBankruptcy("p2", t.window().windowId()));
        assertEquals("TIME_UP", ended(t).reason());
    }

    @Test
    void e5ADeferredSurrenderBatchAfterTheGlobalEndStaysTimeUp() {
        Table t = table(2, 2, 1);
        long s = debtOf(t);
        t.send(s + 1, new GameCommand.Surrender("p1", 1));          // 债权人：延后
        endAt(t, s + 5_000);
        t.tick(s + 5_000);
        t.send(t.now + 10, new GameCommand.EmergencyMortgage("p2", t.window().windowId(), 3));
        GameEvent.GameEnded e = ended(t);
        assertEquals("TIME_UP", e.reason());
        assertEquals("p2", e.result().standings().get(0).playerId());
    }

    // ------------------------------------------------------------ E6 公共视图足以恢复经济窗口

    private static GameView view(Table t, String viewer) {
        return SessionDomain.INSTANCE.project(t.state, viewer).game();
    }

    @Test
    void e6TheLatestProjectionAloneRestoresBothDebtSegmentsAndDraining() {
        Table t = table(3, 2, 1);
        long s = debtOf(t);
        GameView v = view(t, "p3");
        assertNotNull(v.debt());
        assertEquals(new GameView.PublicDebt(1, "p2", "p1", 100, 1, false, false, t.window().windowId()), v.debt());
        assertEquals(LandingStep.DEBT, v.landing().step());
        assertEquals(50, v.players().stream().filter(p -> p.playerId().equals("p2")).findFirst().orElseThrow().cash());
        t.tick(s + 30_000);
        GameView second = view(t, "p2");
        assertEquals(2, second.debt().segment());
        assertTrue(second.debt().continueAvailable(), "the continue popup is live in the second segment");
        t.send(t.now + 10, new GameCommand.ContinueDebt("p2", t.window().windowId()));
        assertFalse(view(t, "p2").debt().continueAvailable(), "already continued: the button is gone");
        assertTrue(view(t, "p2").debt().continued());
        endAt(t, t.now + 1_000);
        t.tick(t.now + 1_000);
        GameView draining = view(t, null);
        assertEquals(GamePhase.DRAINING, draining.phase());
        assertEquals(2, draining.debt().segment());
        assertTrue(draining.myHand().isEmpty(), "spectators never see card faces");
    }

    @Test
    void e6ADebtorReconnectingInTheSecondSegmentRecoversThePopupFromTheProjection() {
        Table t = table(3, 2, 1);
        long s = debtOf(t);
        t.tick(s + 30_000);
        t.send(t.now + 10, new GameCommand.ConnectionSuspected(1, "p2", 1));
        t.send(t.now + 10, new GameCommand.ConnectionConfirmed(1, "p2", 2));
        t.send(t.now + 10, new GameCommand.Reconnected(1, "p2", 3));
        GameView v = view(t, "p2");                                 // 重连后只拿到最新投影
        assertEquals(2, v.debt().segment());
        assertTrue(v.debt().continueAvailable());
        assertEquals(100, v.debt().amount());
        assertEquals("p1", v.debt().creditor());
        GameView.OpenWindow w = v.windows().get(v.windows().size() - 1);
        assertEquals(v.debt().windowId(), w.windowId());
        assertEquals(s + 60_000, w.deadline(), "the deadline is the original 60 s total");
        assertEquals(Outcome.ACCEPTED, t.send(t.now + 10, new GameCommand.ContinueDebt("p2", v.debt().windowId())).outcome());
    }

    @Test
    void e6TheProjectionShowsThePendingLandingDecision() {
        Table t = table(2, 1);
        t.rollThenResolveEvent();
        GameView v = view(t, "p2");
        assertEquals(new GameView.PublicLanding(1, 1, LandingStep.BUY, true), v.landing());
    }

    // ------------------------------------------------------------ E8 昵称

    private static RejectionCode join(String nickname) {
        Table t = Table.production(5);
        t.send(new Join("p1", "Host"));
        return t.send(new Join("p2", nickname)).rejection();
    }

    @Test
    void e8DefaultIgnorableAndBareCombiningNicknamesAreRejected() {
        for (String bad : new String[] {"͏", "́", "͏͏", " ́ ", "឴", "᠋", "ᅟ͏"}) {
            assertEquals(RejectionCode.INVALID_NICKNAME, join(bad), "reject " + bad.codePoints().boxed().toList());
        }
        for (String ok : new String[] {"é", "Amélie", "👨‍👩‍👧",
                "👍🏽", "가"}) {
            assertEquals(null, join(ok), "accept " + ok.codePoints().boxed().toList());
        }
    }

    // ------------------------------------------------------------ E9 银行窗口跨到时

    @Test
    void e9TheGlobalEndClosesAnOpenBankWindowAtOnce() {
        Table t = table(2, 1);
        craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().at(10)));
        t.rollThenResolveEvent();                                               // p1 → 11 银行
        assertEquals(LandingStep.BANK, t.game().turn().landing().step());
        long bankDeadline = t.window().window().deadline();
        long end = t.window().window().opensAt() + 3_000;
        endAt(t, end);
        t.tick(end);
        assertFalse(t.session().inGame(), "the bank window is closed by the global end instead of running to " + bankDeadline);
        assertEquals("TIME_UP", ended(t).reason());
    }

    // ------------------------------------------------------------ 固化的默认行为 4：本人普通窗口均可赎回

    @Test
    void redeemingInsideABuyWindowIsAllowedAndDoesNotRefreshIt() {
        Table t = table(2, 1);
        craft(t, mortgaged(3, "p1", 500));
        t.rollThenResolveEvent();                                               // p1 → 1：买 / 放弃窗口
        long deadline = t.window().window().deadline();
        assertEquals(Outcome.ACCEPTED, t.act(w -> new GameCommand.Redeem("p1", w, 3)).outcome());
        assertEquals(3000 - 550, t.cash("p1"), "off the bank: 500 + 10%");
        assertEquals(deadline, t.window().window().deadline());
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        assertEquals("p1", t.game().board().ownable(1).orElseThrow().owner());
    }

    // ------------------------------------------------------------ 债务成立前后切换控制模式

    @Test
    void switchingControlAfterTheDebtIsSetDoesNotChangeItsPath() {
        Table t = table(3, 2, 1);
        long s = debtOf(t);
        t.send(s + 1, new GameCommand.SetControl(1, "p2", ControlMode.HOSTED));
        assertEquals(Outcome.ACCEPTED, t.send(s + 10, new GameCommand.EmergencyMortgage("p2", t.window().windowId(), 3)).outcome());
        assertEquals(350, t.cash("p2"));
        assertTrue(t.game().player("p2").orElseThrow().alive());
    }

    // ------------------------------------------------------------ E10 严格测试台

    @Test
    void e10StrictRollFailsInsteadOfSkippingALandingWindow() {
        Table t = table(2, 1, 1);
        t.rollThenResolveEvent();
        assertEquals(TurnStage.LANDING, t.game().turn().stage());
        assertThrows(IllegalStateException.class, t::rollOnly);
        StepResult r = t.roll();
        assertEquals(t.state, r.state(), "the combined result ends in the table state");
    }
}
