package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.event.Visibility;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.core.state.TurnState;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.Window;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * M1c 边界：T2 账本、T4/T6 生产覆盖流程闭环（落点等待 → 覆盖流程 → 恢复 → 全局到时收尾）、T5 自动任务、
 * T7 连接与控制、T8 发牌、T10 动画累计。全部经过生产 SessionDomain 的任务分派与出口校验。
 */
class M1cBoundaryTest {

    private static DecisionContext<SessionState> ctx(Table t) {
        return new DecisionContext<>(t.state, new Evolver<>(SessionDomain.INSTANCE, t.config), SessionState.class,
                XoshiroLemireV1.INSTANCE, t.config);
    }

    /** 把 DecisionContext 中的工作状态作为已提交状态（先经引擎完整校验）。 */
    private static void commit(Table t, DecisionContext<SessionState> c) {
        t.engine.validate(c.engineState());
        t.state = c.engineState();
        t.log.addAll(c.events());
    }

    /** 截点：快照文本 + 截点时已发送的输入数与事件数。 */
    private record Cut(String name, String snapshot, int inputs, int events) {
    }

    private static Cut cut(Table t, String name) {
        return new Cut(name, t.engine.snapshot(t.state), t.inputs.size(), t.log.size());
    }

    /** 从每个截点的快照恢复，经生产入口 Engine.step 续跑余下输入，结果（状态与事件）与不中断运行完全一致。 */
    private static void assertResumable(Table t, Cut... cuts) {
        for (Cut c : cuts) {
            EngineState s = t.engine.restore(c.snapshot());
            List<Event> tail = new java.util.ArrayList<>();
            for (Input in : t.inputs.subList(c.inputs(), t.inputs.size())) {
                StepResult r = t.engine.step(s, in);
                s = r.state();
                tail.addAll(r.events());
            }
            assertEquals(t.state, s, "state after resuming at " + c.name());
            assertEquals(t.log.subList(c.events(), t.log.size()), tail, "events after resuming at " + c.name());
        }
    }

    private static long count(List<Event> events, Class<?> type) {
        return events.stream().filter(type::isInstance).count();
    }

    // ------------------------------------------------------------ T2

    @Test
    void t2ForgedBalancesAreRejectedAtTheStepBoundary() {
        Table t = Table.production(1).start(2);
        GameState g = t.game();
        Ledger l = g.ledger();
        Map<String, Long> cash = new TreeMap<>(l.cash());
        String a = g.players().get(0).playerId();
        cash.put(a, cash.get(a) + 1);
        Ledger forged = new Ledger(l.baseline(), l.opening(), cash, l.frozen(), l.systemNet() - 1, l.journal());
        EngineState bad = t.state.withDomain(t.session().withGame(g.withLedger(forged)));
        assertThrows(StateValidationException.class, () -> t.engine.step(bad, new Input(t.seq + 1, t.now + 1, new Tick())));
        Map<String, Long> skewed = new TreeMap<>();
        skewed.put(a, 6000L);
        skewed.put(g.players().get(1).playerId(), 0L);
        Ledger skewedOpen = Ledger.open(skewed);
        EngineState badOpening = t.state.withDomain(t.session().withGame(g.withLedger(skewedOpen)));
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(badOpening)));
    }

    // ------------------------------------------------------------ T4 / T6：生产覆盖流程闭环

    @Test
    void t4OverlayTimeoutIsDispatchedToItsModuleAndTheTurnWindowResumes() {
        Table t = Table.production(2).start(2);
        long turnWindow = t.windowId();
        long deadline = t.window().window().deadline();
        long pausedAt = t.state.now();
        DecisionContext<SessionState> c = ctx(t);
        GameModule.openOverlay(c, FlowKind.ATTACK, t.current(), 0, 10_000, "after-attack");
        commit(t, c);
        long overlayDeadline = t.window().window().deadline();
        StepResult r = t.tick(overlayDeadline);
        assertEquals(Outcome.ACCEPTED, r.outcome());
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.WindowClosed w && w.reason() == CloseReason.EXPIRED));
        FlowFrame resumed = t.window();
        assertEquals(turnWindow, resumed.windowId());
        assertEquals(overlayDeadline, resumed.window().opensAt());
        assertEquals(overlayDeadline + (deadline - pausedAt), resumed.window().deadline(), "resumed with the remaining roll time");
        assertFalse(resumed.window().paused());
    }

    @Test
    void t6QueuedFlowsStartOnePerSafePointAndTheTurnWaitsForThem() {
        Table t = Table.production(3).start(3);
        DecisionContext<SessionState> c = ctx(t);
        String[] others = t.game().players().stream().map(p -> p.playerId()).filter(id -> !id.equals(t.current()))
                .toArray(String[]::new);
        assertNull(FlowCoordinator.request(c, GameModule.FLOW, FlowKind.AUCTION, others[0]));
        assertNull(FlowCoordinator.request(c, GameModule.FLOW, FlowKind.TRADE, others[1]));
        commit(t, c);
        t.roll();                                           // 回合交界 = 安全点：只启动第一个申请
        GameState g = t.game();
        assertEquals(TurnStage.AWAITING_FLOW, g.turn().stage());
        assertEquals(new com.millionnaire.engine.core.state.Continuation.BeginTurn(g.turn().turnNo()), g.turn().continuation());
        Cut awaiting = cut(t, "AWAITING_FLOW with a queued request");
        assertEquals(1, g.flow().frames().size());
        assertEquals(FlowKind.AUCTION, g.flow().frames().get(0).kind());
        assertEquals(1, g.flow().queue().size(), "the second request waits for the next safe point");
        String next = g.turn().currentPlayer();
        t.tick(t.window().window().deadline());             // 拍卖占位流程到期 → 返回 → 开始该回合
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        assertEquals(next, t.current());
        assertEquals(1, t.game().flow().queue().size(), "no second start at the same safe point");
        Cut returned = cut(t, "after the overlay returned");
        t.roll();                                           // 下一个安全点启动第二个申请
        assertEquals(FlowKind.TRADE, t.game().flow().frames().get(0).kind());
        assertTrue(t.game().flow().queue().isEmpty());
        assertResumable(t, awaiting, returned);
        assertThrows(IllegalArgumentException.class,
                () -> GameModule.openOverlay(ctx(t), FlowKind.TURN, t.current(), 0, 1000, "x"), "TURN uses openDecision");
    }

    /** 用真实随机与真实回合推进，直到当前窗口开放时刻进入全局到时前 marginMs 之内（每回合推进不超过 3 秒）。 */
    private static void advanceNearEnd(Table t, long marginMs) {
        long endsAt = t.game().clock().endsAt();
        while (t.window().window().opensAt() < endsAt - marginMs) {
            t.roll();
        }
    }

    @Test
    void t4t6LandingWaitOverlayResumeAndGlobalEndDrainAcrossSteps() {
        Table t = Table.production(4).start(2, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 15);
        advanceNearEnd(t, 40_000);
        long endsAt = t.game().clock().endsAt();
        // 落点等待：以阶段决策窗口入口开 LANDING（栈为空时），覆盖流程在其上暂停它
        t.send(Math.max(t.now, t.window().window().opensAt()) + 1, new Tick());
        DecisionContext<SessionState> c = ctx(t);
        long rollWindow = c.state().game().turn().windowId();
        FlowCoordinator.close(c, GameModule.FLOW, rollWindow, CloseReason.CANCELLED);
        TurnModule.openDecision(c, new com.millionnaire.engine.core.state.Continuation.EndTurn(c.state().game().turn().turnNo()), 0, 15_000);
        GameModule.openOverlay(c, FlowKind.ATTACK, c.state().game().turn().currentPlayer(), 0, 50_000, "after-attack");
        commit(t, c);
        assertEquals(TurnStage.LANDING, t.game().turn().stage());
        assertTrue(t.game().flow().frames().get(0).window().paused());
        Cut landing = cut(t, "LANDING paused under an overlay");
        long overlayEnd = t.window().window().deadline();
        assertTrue(overlayEnd > endsAt, "the overlay crosses the global end");
        StepResult atEnd = t.tick(endsAt);                  // 全局到时：进入 DRAINING，已启动的流程继续
        assertTrue(atEnd.events().stream().anyMatch(e -> e instanceof GameEvent.DrainingStarted));
        assertEquals(GamePhase.DRAINING, t.game().phase(), "draining persists across steps");
        t.engine.validate(t.state);
        Cut draining = cut(t, "DRAINING with an overlay running");
        t.tick(overlayEnd);                                 // 覆盖流程到期 → LANDING 恢复（剩余时间）
        assertEquals(GamePhase.DRAINING, t.game().phase());
        assertEquals(TurnStage.LANDING, t.game().turn().stage());
        assertFalse(t.window().window().paused());
        Cut drainingLanding = cut(t, "DRAINING with LANDING resumed");
        StepResult done = t.tick(t.window().window().deadline());   // LANDING 到期 → END_TURN → 不开新回合，结算
        assertTrue(done.events().stream().anyMatch(e -> e instanceof GameEvent.GameEnded g && g.reason().equals("TIME_UP")));
        assertEquals(0, count(done.events(), GameEvent.TurnStarted.class), "no new turn after the global end");
        assertNull(t.session().game());
        assertTrue(t.state.timers().isEmpty());
        // 整个过程（含测试中直接驱动的流程事件）可从事件重建
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertResumable(t, landing, draining, drainingLanding);
    }

    @Test
    void t6GlobalEndWhileAwaitingAFlowFinishesWhenTheFlowReturns() {
        Table t = Table.production(5).start(2, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 15);
        advanceNearEnd(t, 15_000);
        long endsAt = t.game().clock().endsAt();
        DecisionContext<SessionState> c = ctx(t);
        String other = c.state().game().players().stream().map(p -> p.playerId())
                .filter(id -> !id.equals(c.state().game().turn().currentPlayer())).findFirst().orElseThrow();
        FlowCoordinator.request(c, GameModule.FLOW, FlowKind.AUCTION, other);
        commit(t, c);
        t.roll();
        assertEquals(TurnStage.AWAITING_FLOW, t.game().turn().stage());
        long flowEnd = t.window().window().deadline();
        assertTrue(flowEnd > endsAt);
        Cut awaiting = cut(t, "AWAITING_FLOW across the global end");
        t.tick(endsAt);
        assertEquals(GamePhase.DRAINING, t.game().phase());
        Cut draining = cut(t, "DRAINING while awaiting a flow");
        StepResult done = t.tick(flowEnd);
        assertTrue(done.events().stream().anyMatch(e -> e instanceof GameEvent.GameEnded));
        assertEquals(0, count(done.events(), GameEvent.TurnStageEntered.class), "BEGIN_TURN does not start a turn while draining");
        assertResumable(t, awaiting, draining);
    }

    // ------------------------------------------------------------ T5

    @Test
    void t5ZeroLengthOverlayKeepsTheAutoTaskAndAutoTasksMustNotPrecedeTheWindow() {
        Table t = Table.production(6).start(2);
        t.send(t.now + 100, new GameCommand.SetControl(1, t.current(), ControlMode.AWAY));
        long auto = t.game().turn().autoTaskId();
        DecisionContext<SessionState> c = ctx(t);
        assertInstanceOf(FlowCoordinator.Opened.Exhausted.class,
                GameModule.openOverlay(c, FlowKind.ATTACK, t.current(), 0, 0, "x"));
        assertEquals(auto, c.state().game().turn().autoTaskId(), "the parent's auto task is kept");
        t.engine.validate(c.engineState());
        // 自动任务早于窗口开放 + 延时：恢复被拒
        ScheduledTask task = t.state.timers().find(auto).orElseThrow();
        EngineState early = t.state.withTimers(t.state.timers().cancel(auto)
                .schedule(new ScheduledTask(auto, t.window().window().opensAt() + 10, task.kind(), task.ref())), t.state.nextTaskId());
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(early)));
        // 应有的自动任务缺失：恢复被拒
        GameState g = t.game();
        EngineState missing = t.state.withTimers(t.state.timers().cancel(auto), t.state.nextTaskId())
                .withDomain(t.session().withGame(g.withTurn(g.turn().withAutoTask(0))));
        assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(missing)));
    }

    // ------------------------------------------------------------ T7

    @Test
    void t7ConnectionTransitionsObservationsGameBindingAndManualControl() {
        Table t = Table.production(7).start(2);
        String p = t.current();
        String q = t.game().players().stream().map(x -> x.playerId()).filter(id -> !id.equals(p)).findFirst().orElseThrow();
        assertEquals(RejectionCode.INVALID_TRANSITION, t.send(new GameCommand.ConnectionConfirmed(1, q, 1)).rejection(),
                "confirmed offline only after suspect");
        t.send(new GameCommand.ConnectionSuspected(1, q, 2));
        t.send(new GameCommand.ConnectionConfirmed(1, q, 3));
        assertEquals(RejectionCode.STALE_OBSERVATION, t.send(new GameCommand.ConnectionSuspected(1, q, 2)).rejection());
        assertEquals(RejectionCode.INVALID_TRANSITION, t.send(new GameCommand.ConnectionSuspected(1, q, 4)).rejection(),
                "a late suspect cannot downgrade a confirmed offline");
        assertEquals(RejectionCode.GAME_MISMATCH, t.send(new GameCommand.Reconnected(2, q, 5)).rejection(), "wrong game");
        assertEquals(RejectionCode.INVALID_ARGUMENT, t.send(new GameCommand.SetControl(1, p, null)).rejection(),
                "null mode is a normal rejection, not a kernel fault");
        assertEquals(RejectionCode.GAME_MISMATCH, t.send(new GameCommand.SetControl(9, p, ControlMode.AWAY)).rejection());
        // 托管期间本人投骰 = 恢复并投骰（产品决定，M2 P0）；ResumeControl 也可单独恢复
        t.send(new GameCommand.SetControl(1, p, ControlMode.AWAY));
        long v1 = t.game().turn().autoTaskId();
        t.send(new GameCommand.SetControl(1, p, ControlMode.HOSTED));
        long v2 = t.game().turn().autoTaskId();
        assertTrue(v1 != 0 && v2 != 0 && v1 != v2, "a real policy change re-versions the auto task");
        assertTrue(t.state.timers().find(v1).isEmpty());
        long w = t.windowId();
        assertEquals(RejectionCode.GAME_MISMATCH, t.send(new GameCommand.ResumeControl(p, 3)).rejection());
        t.send(new GameCommand.ResumeControl(p, 1));
        assertEquals(0, t.game().turn().autoTaskId());
        assertEquals(RejectionCode.UNCHANGED, t.send(new GameCommand.ResumeControl(p, 1)).rejection());
        t.send(new GameCommand.SetControl(1, p, ControlMode.AWAY));
        assertEquals(Outcome.ACCEPTED, t.send(Math.max(t.now + 1, t.window().window().opensAt()),
                new GameCommand.RollDice(p, w)).outcome());
        assertEquals(ControlMode.MANUAL, t.game().player(p).orElseThrow().control());
    }

    // ------------------------------------------------------------ T8

    @Test
    void t8OpeningCardsAreWeightedPrivateAndProjectedOnlyToTheirOwner() {
        ScriptedRandom r = new ScriptedRandom(script(order(90, 10), List.of(
                ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 0), ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 999),
                ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 120), ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 120))));
        Table t = new Table(r, 1).start(2);
        GameState g = t.game();
        assertEquals(List.of(CardType.ROADBLOCK, CardType.CLEAR_LAND), g.player("p1").orElseThrow().hand());
        assertEquals(List.of(CardType.RENT_WAIVER, CardType.RENT_WAIVER), g.player("p2").orElseThrow().hand(),
                "the same type can repeat");
        List<Event> dealt = t.log.stream().filter(e -> e instanceof GameEvent.CardDealt).toList();
        assertEquals(4, dealt.size());
        assertTrue(dealt.stream().allMatch(e -> e.visibility() == Visibility.PRIVATE && e.recipient() != null));
        List<Event> forP1 = EventProjector.project(t.log, "p1");
        assertEquals(2, count(forP1, GameEvent.CardDealt.class));
        assertTrue(forP1.stream().filter(e -> e instanceof GameEvent.CardDealt).allMatch(e -> e.recipient().equals("p1")));
        assertEquals(2, count(forP1, GameEvent.CardsDealt.class), "both public hand counts are visible");
        assertEquals(0, count(EventProjector.project(t.log, null), GameEvent.CardDealt.class), "spectators see no faces");
        assertEquals(List.of(), SessionDomain.INSTANCE.project(t.state, null).game().myHand());
        assertFalse(t.engine.codec().encode(SessionDomain.INSTANCE.project(t.state, "p2")).contains("CLEAR_LAND"));
        // 篡改牌面：重建被拒
        List<Event> forged = t.log.stream().map(e -> e instanceof GameEvent.CardDealt d && d.card() == CardType.CLEAR_LAND
                ? new GameEvent.CardDealt(d.recipient(), CardType.FORCED_PURCHASE) : e).toList();
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(forged));
    }

    @Test
    void t8DealingFollowsTurnOrderNotJoinOrder() {
        // 加入顺序 p1、p2；抽数 p1=10、p2=90 → p2 先行动，先拿前两张
        ScriptedRandom r = new ScriptedRandom(script(order(10, 90), List.of(
                ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 0), ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 999),
                ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 120), ScriptedRandom.step(DrawPoint.INITIAL_CARD, 1000, 120))));
        Table t = new Table(r, 1).start(2);
        GameState g = t.game();
        assertEquals("p2", g.turn().currentPlayer());
        assertEquals(List.of(CardType.ROADBLOCK, CardType.CLEAR_LAND), g.player("p2").orElseThrow().hand());
        assertEquals(List.of(CardType.RENT_WAIVER, CardType.RENT_WAIVER), g.player("p1").orElseThrow().hand());
        assertEquals(List.of(new GameEvent.CardDealt("p2", CardType.ROADBLOCK), new GameEvent.CardDealt("p2", CardType.CLEAR_LAND),
                new GameEvent.CardDealt("p1", CardType.RENT_WAIVER), new GameEvent.CardDealt("p1", CardType.RENT_WAIVER)),
                t.log.stream().filter(e -> e instanceof GameEvent.CardDealt).toList());
        // 按加入顺序发牌的篡改日志被拒
        List<Event> joinOrder = new java.util.ArrayList<>(t.log);
        int first = -1;
        for (int i = 0; i < joinOrder.size(); i++) {
            if (joinOrder.get(i) instanceof GameEvent.CardDealt) {
                first = i;
                break;
            }
        }
        joinOrder.set(first, new GameEvent.CardDealt("p1", CardType.ROADBLOCK));
        joinOrder.set(first + 1, new GameEvent.CardDealt("p1", CardType.CLEAR_LAND));
        joinOrder.set(first + 2, new GameEvent.CardDealt("p2", CardType.RENT_WAIVER));
        joinOrder.set(first + 3, new GameEvent.CardDealt("p2", CardType.RENT_WAIVER));
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(joinOrder));
    }

    // ------------------------------------------------------------ T10

    @Test
    void t10ZeroRemainingReleaseAccumulatesBothDiceAnimations() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 1, 6, 1),
                dice(DrawPoint.JAIL_DIE, 2), dice(DrawPoint.MOVE_DIE, 2))), 1).start(2);
        for (int i = 0; i < 4; i++) {
            t.roll();
        }
        long deadline = t.window().window().deadline();
        t.tick(deadline);                                   // 超时判定 2（偶数）→ 剩余 0 → 立即移动 2（事件格，无落点窗口）
        assertEquals("p2", t.current());
        assertEquals(deadline + 1500 + 1500 + 2 * 250, t.window().window().opensAt(),
                "judgment animation + move animation are shown one after the other");
    }

    @Test
    void t10BailHasNoDiceAnimation() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 1, 6, 1))), 1)
                .start(2);
        for (int i = 0; i < 4; i++) {
            t.roll();
        }
        long at = t.window().window().opensAt() + 300;
        t.send(at, new GameCommand.PayBail("p1", t.windowId()));
        assertEquals(at, t.window().window().opensAt(), "no animation buffer after paying bail");
        assertNotEquals(0, t.windowId());
    }

    @Test
    void zeroRemainingTurnWindowAfterAnOverlayRollsImmediately() {
        Table t = Table.production(8).start(2);
        DecisionContext<SessionState> c = ctx(t);
        GameModule.openOverlay(c, FlowKind.ATTACK, c.state().game().turn().currentPlayer(), 0, 10_000, "x");
        // 构造：回合窗口暂停时剩余为 0
        GameState g = c.state().game();
        FlowFrame turn = g.flow().frames().get(0);
        FlowFrame exhausted = new FlowFrame(turn.windowId(), turn.kind(), turn.owner(),
                new Window(turn.windowId(), turn.window().opensAt(), turn.window().deadline(), true, 0, 0), 0, turn.resumeTag());
        EngineState crafted = c.engineState().withDomain(c.state().withGame(g.withFlow(
                g.flow().withFrames(List.of(exhausted, g.flow().frames().get(1))))));
        t.state = t.engine.restore(t.engine.snapshot(crafted));
        String mover = t.current();
        StepResult r = t.tick(t.window().window().deadline());
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.DiceRolled d && d.auto() && d.playerId().equals(mover)));
        assertTrue(!mover.equals(t.current()) || t.game().turn().stage() == TurnStage.LANDING,
                "the mover moved (M2: a landing decision window may follow)");
    }
}
