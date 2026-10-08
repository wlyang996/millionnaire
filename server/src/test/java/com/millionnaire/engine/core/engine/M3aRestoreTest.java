package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class M3aRestoreTest {
    private static void rejected(Table t, UnaryOperator<GameState> change, String reason) {
        var crafted = t.state.withDomain(t.session().withGame(change.apply(t.game())));
        var ex = assertThrows(StateValidationException.class, () -> t.engine.restore(t.engine.snapshot(crafted)));
        assertTrue(ex.getMessage().contains(reason), ex.getMessage());
    }
    private static Table buy() { Table t = M2bReviewTest.table(2, 1); t.rollThenResolveEvent(); return t; }
    private static void chain(Table t, UnaryOperator<MoveChain> f, String reason) {
        rejected(t, g -> g.withTurn(g.turn().withChain(f.apply(g.turn().chain()))), reason);
    }
    private static MoveChain segment(MoveChain c, MoveSegment s) {
        return new MoveChain(c.chainId(), c.turnNo(), c.playerId(), c.origin(), c.eventDrawn(), c.startRewardGiven(), List.of(s), c.walkedSteps());
    }
    @Test void chainFlagsOriginAndWalkCountAreCheckedOnRestore() {
        Table t = buy();
        chain(t, c -> new MoveChain(c.chainId(), c.turnNo(), c.playerId(), c.origin(), true, c.startRewardGiven(), c.segments(), c.walkedSteps()), "move chain invalid");
        chain(t, c -> new MoveChain(c.chainId(), c.turnNo(), c.playerId(), c.origin(), c.eventDrawn(), true, c.segments(), c.walkedSteps()), "move chain invalid");
        chain(t, c -> new MoveChain(c.chainId(), c.turnNo(), c.playerId(), c.origin() + 1, c.eventDrawn(), c.startRewardGiven(), c.segments(), c.walkedSteps()), "move chain segment/flags invalid");
        chain(t, c -> new MoveChain(c.chainId(), c.turnNo(), c.playerId(), c.origin(), c.eventDrawn(), c.startRewardGiven(), c.segments(), c.walkedSteps() + 1), "move chain segment/flags invalid");
    }
    @Test void segmentGeometryAndSourceAreCheckedOnRestore() {
        Table t = buy();
        chain(t, c -> { var s = c.segments().getFirst(); return segment(c, new MoveSegment(s.number(), s.kind(), s.from(), s.to(), s.distance(), List.of(2), s.startEligible())); }, "move chain segment/flags invalid");
        chain(t, c -> { var s = c.segments().getFirst(); return segment(c, new MoveSegment(s.number() + 1, s.kind(), s.from(), s.to(), s.distance(), s.walked(), s.startEligible())); }, "move chain segment/flags invalid");
        chain(t, c -> { var s = c.segments().getFirst(); return segment(c, new MoveSegment(s.number(), MoveKind.EVENT_FORWARD, s.from(), s.to(), s.distance(), s.walked(), s.startEligible())); }, "move chain segment/flags invalid");
    }
    private static void landing(Table t, UnaryOperator<LandingState> f) {
        rejected(t, g -> g.withTurn(g.turn().withLanding(f.apply(g.turn().landing()))), "landing invalid");
    }
    @Test void landingTaskParentsResultsCursorAndBoughtAreCheckedOnRestore() {
        Table t = buy();
        landing(t, l -> new LandingState(l.landingId(), l.chainId(), l.tile(), l.step(), l.pendingPayment(), l.bought(), List.of(LandingStep.BUY, LandingStep.BUY), l.generatedBy(), l.results(), l.cursor(), l.decisionOpen()));
        landing(t, l -> new LandingState(l.landingId(), l.chainId(), l.tile(), l.step(), l.pendingPayment(), l.bought(), l.tasks(), List.of(0), l.results(), l.cursor(), l.decisionOpen()));
        landing(t, l -> new LandingState(l.landingId(), l.chainId(), l.tile(), l.step(), l.pendingPayment(), l.bought(), l.tasks(), l.generatedBy(), List.of(LandingResult.DECLINED), l.cursor(), l.decisionOpen()));
        landing(t, l -> new LandingState(l.landingId(), l.chainId(), l.tile(), l.step(), l.pendingPayment(), l.bought(), l.tasks(), l.generatedBy(), l.results(), l.cursor() + 1, l.decisionOpen()));
        landing(t, l -> new LandingState(l.landingId(), l.chainId(), l.tile(), l.step(), l.pendingPayment(), true, l.tasks(), l.generatedBy(), l.results(), l.cursor(), l.decisionOpen()));
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        landing(t, l -> new LandingState(l.landingId(), l.chainId(), l.tile(), l.step(), l.pendingPayment(), l.bought(), l.tasks(), List.of(-1, -1), l.results(), l.cursor(), l.decisionOpen()));
    }
    private static void debt(Table t, UnaryOperator<DebtState> f, String reason) { rejected(t, g -> g.withDebt(f.apply(g.debt())), reason); }
    private static DebtState source(DebtState d, FeeSource s) { return new DebtState(d.debtId(), d.debtor(), d.creditor(), d.amount(), d.cause(), d.segment(), d.continued(), d.windowId(), s, d.path()); }
    @Test void debtPathIdAndCounterAreCheckedOnRestore() {
        Table t = M3aTest.realDebt();
        debt(t, d -> new DebtState(d.debtId(), d.debtor(), d.creditor(), d.amount(), d.cause(), d.segment(), d.continued(), d.windowId(), d.source(), DebtPath.DIRECT_BANKRUPTCY), "debt invalid");
        debt(t, d -> new DebtState(d.debtId() + 1, d.debtor(), d.creditor(), d.amount(), d.cause(), d.segment(), d.continued(), d.windowId(), d.source(), d.path()), "debt invalid");
        rejected(t, g -> { var a = g.turn(); return g.withTurn(new TurnState(a.turnNo(), a.currentPlayer(), a.stage(), a.windowId(), a.startRewardGiven(), a.autoTaskId(), a.continuation(), a.notBefore(), a.landing(), a.lastLandingId(), a.track(), a.chain(), a.lastChainId(), a.lastDebtId() - 1, a.round())); }, "debt invalid");
    }
    @Test void everyDebtSourceFieldIsCheckedOnRestore() {
        Table t = M3aTest.realDebt();
        debt(t, d -> { var s = d.source(); return source(d, new FeeSource(FeeSource.Kind.FINE, s.landingId(), s.cursor(), s.tile(), s.creditor(), s.amount())); }, "debt invalid");
        debt(t, d -> { var s = d.source(); return source(d, new FeeSource(s.kind(), s.landingId() + 1, s.cursor(), s.tile(), s.creditor(), s.amount())); }, "debt step invalid");
        debt(t, d -> { var s = d.source(); return source(d, new FeeSource(s.kind(), s.landingId(), s.cursor() + 1, s.tile(), s.creditor(), s.amount())); }, "debt step invalid");
        debt(t, d -> { var s = d.source(); return source(d, new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile() + 1, s.creditor(), s.amount())); }, "debt invalid");
        debt(t, d -> { var s = d.source(); return source(d, new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile(), "p1", s.amount())); }, "debt invalid");
        debt(t, d -> { var s = d.source(); return source(d, new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile(), s.creditor(), s.amount() + 1)); }, "debt invalid");
    }
    private static void origin(Table t, UnaryOperator<FlowOrigin> f, String reason) {
        rejected(t, g -> { var w = g.flow().top().orElseThrow(); return g.withFlow(g.flow().withFrames(List.of(new FlowFrame(w.windowId(), w.kind(), w.owner(), w.window(), w.deadlineTaskId(), w.resumeTag(), f.apply(w.origin()))))); }, reason);
    }
    @Test void everyQueuedOriginFieldIsCheckedOnRestore() {
        Table t = M3aTamperTest.queuedRun();
        origin(t, o -> new FlowOrigin(FlowOrigin.Kind.ACTIVE_CARD, o.scopeId(), o.ref(), o.cursor(), o.actor()), "queued flow origin invalid");
        origin(t, o -> new FlowOrigin(o.kind(), o.scopeId(), t.game().flow().nextRequestId(), o.cursor(), o.actor()), "queued flow origin invalid");
        origin(t, o -> new FlowOrigin(o.kind(), o.scopeId() + 1, o.ref(), o.cursor(), o.actor()), "queued flow origin invalid");
        origin(t, o -> new FlowOrigin(o.kind(), o.scopeId(), o.ref(), o.cursor() + 1, o.actor()), "queued flow origin invalid");
        origin(t, o -> new FlowOrigin(o.kind(), o.scopeId(), o.ref(), o.cursor(), "p1"), "overlay source missing");
    }
}
