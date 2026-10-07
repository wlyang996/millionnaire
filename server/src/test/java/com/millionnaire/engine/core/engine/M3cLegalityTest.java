package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3cLegalityTest {
    static RejectionCode gate(Table t, GameState g, MovementEffect.Kind kind, int distance) {
        return MovementEffects.rejection(g, t.config, t.now, "p1", t.windowId(), kind, distance);
    }
    static Table ready() { var t = M2bReviewTest.table(2); M3cTest.ready(t); return t; }
    @Test void targetedDistanceBoundsAreIndependentOfDiceAndOtherChecks() {
        var t = ready();
        for (int d : new int[] {0, -1, 7, Integer.MAX_VALUE}) { assertEquals(RejectionCode.INVALID_ARGUMENT, gate(t, t.game(), MovementEffect.Kind.TARGETED, d)); }
        for (int d = 1; d <= 6; d++) { assertNull(gate(t, t.game(), MovementEffect.Kind.TARGETED, d)); }
    }
    @Test void placementOnAJailTileIsRejectedEvenWithAValidPreRollWindow() {
        var t = ready(); var g = t.game().withPlayer(t.game().player("p1").orElseThrow().at(8));
        assertEquals(RejectionCode.NOT_ALLOWED, gate(t, g, MovementEffect.Kind.ROADBLOCK, 0));
    }
    @Test void targetedCannotEscapeJailEvenWhenItsWindowAndStageAreOtherwiseValid() {
        var t = ready(); var g = t.game().withPlayer(t.game().player("p1").orElseThrow().at(8).jail(true, 0));
        assertEquals(RejectionCode.NOT_ALLOWED, gate(t, g, MovementEffect.Kind.TARGETED, 1));
    }
    @Test void drainingRejectsBothPrimitivesBeforeTheyEmit() {
        var t = ready(); var g = (GameState)M3cTamperTest.field(t.game(), "phase", GamePhase.DRAINING);
        assertEquals(RejectionCode.DRAINING, gate(t, g, MovementEffect.Kind.ROADBLOCK, 0));
        assertEquals(RejectionCode.DRAINING, gate(t, g, MovementEffect.Kind.TARGETED, 1));
    }
    @Test void duplicatePlacementGateRejectsWithoutDependingOnBoardMutationGuards() {
        var t = ready(); var g = t.game().withBoard(t.game().board().placed(new Roadblock(1, 0, "p2", 1)));
        assertEquals(RejectionCode.ALREADY_OWNED, gate(t, g, MovementEffect.Kind.ROADBLOCK, 0));
    }
    @Test void automatedOrOfflinePlayersCannotBypassTheM4BusinessEntry() {
        var t = ready(); var p = t.game().player("p1").orElseThrow();
        for (var mode : List.of(ControlMode.AWAY, ControlMode.HOSTED)) {
            assertEquals(RejectionCode.CONTROL_NOT_MANUAL, gate(t, t.game().withPlayer(p.control(mode)), MovementEffect.Kind.TARGETED, 1));
        }
        assertEquals(RejectionCode.CONTROL_NOT_MANUAL, gate(t, t.game().withPlayer(p.conn(ConnState.OFFLINE, 2)), MovementEffect.Kind.ROADBLOCK, 0));
    }
    @Test void invalidWindowsTimesActorsAndUnsettledStagesEmitNothing() {
        var t = ready(); var initial = t.state;
        for (var kind : MovementEffect.Kind.values()) {
            int distance = kind == MovementEffect.Kind.TARGETED ? 1 : 0;
            assertEquals(RejectionCode.NOT_MEMBER, MovementEffects.rejection(t.game(), t.config, t.now, "absent", t.windowId(), kind, distance));
            assertEquals(RejectionCode.NOT_YOUR_TURN, MovementEffects.rejection(t.game(), t.config, t.now, "p2", t.windowId(), kind, distance));
            assertEquals(RejectionCode.WRONG_STAGE, MovementEffects.rejection(t.game(), t.config, t.now, "p1", t.windowId() + 1, kind, distance));
            assertEquals(RejectionCode.WINDOW_NOT_OPEN, MovementEffects.rejection(t.game(), t.config,
                    t.window().window().opensAt() - 1, "p1", t.windowId(), kind, distance));
            assertNotNull(MovementEffects.rejection(t.game(), t.config, t.window().window().deadline(), "p1", t.windowId(), kind, distance));
        }
        var ctx = M2bReviewTest.ctx(t);
        assertEquals(RejectionCode.INVALID_ARGUMENT, MovementEffects.targeted(ctx, "p1", t.windowId(), 7));
        assertTrue(ctx.events().isEmpty()); assertEquals(initial, ctx.engineState());
        M3cTest.target(t, 1); var atBuy = M2bReviewTest.ctx(t);
        assertEquals(RejectionCode.WRONG_STAGE, MovementEffects.placeRoadblock(atBuy, "p1", t.windowId()));
        assertEquals(RejectionCode.WRONG_STAGE, MovementEffects.targeted(atBuy, "p1", t.windowId(), 1));
        assertTrue(atBuy.events().isEmpty()); assertEquals(t.state, atBuy.engineState());
    }
    @Test void targetedWorksAfterPaidJailReleaseAndDoesNotRefreshTheRollWindow() {
        var t = M2bReviewTest.table(2, 4, 1, 4, 1);
        t.roll(); t.roll(); t.roll(); t.roll();
        assertEquals(TurnStage.JAIL_DECISION, t.game().turn().stage());
        t.act(w -> new GameCommand.PayBail("p1", w));
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        var rng = t.state.rng(); M3cTest.target(t, 1);
        assertEquals(9, t.position("p1")); assertEquals(rng, t.state.rng());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void surrenderedOwnerRemainsInTheRoadblockAndItCanStillTrigger() {
        var t = M2bReviewTest.table(3); M3cTest.place(t); var block = t.game().board().roadblocks().getFirst();
        t.send(new GameCommand.Surrender("p1", 1));
        assertFalse(t.game().player("p1").orElseThrow().alive()); assertEquals(List.of(block), t.game().board().roadblocks());
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state))); assertEquals(t.state, t.engine.rebuild(t.log));
        var segment = MovementRules.resolve(MovementRules.segment(1, MoveKind.DICE, 29, 2, 30), t.game().board(), "p2", 30);
        assertEquals(block, segment.stoppedBy());
    }
    @Test void bankruptcyLiquidationPreservesTheBoardObjectWhileClearingAssetsAndHand() {
        var t = M3aTest.realDebt();
        // Insert a legal placement into the earlier PRE_ROLL history, then replay the unchanged economic inputs.
        var start = M2bReviewTest.table(2, 1, 5, 2, 1, 1, 1, 1); M3cTest.place(start);
        for (String p : List.of("p1", "p2", "p1", "p2")) {
            start.rollThenResolveEvent(); start.act(w -> new GameCommand.BuyProperty(p, w)); start.act(w -> new GameCommand.UpgradeProperty(p, w));
        }
        start.rollThenResolveEvent(); start.act(w -> new GameCommand.BuyProperty("p1", w));
        start.rollThenResolveEvent(); start.rollThenResolveEvent();
        var d = start.game().debt(); assertEquals(t.game().debt().amount(), d.amount());
        int last = M2bReviewTest.indexOf(start.log, GameEvent.DebtCreated.class, 0);
        // During liquidation the surviving creditor is still a member; inspect the event prefix before GameEnded discards the board.
        start.act(w -> new GameCommand.DeclareBankruptcy("p1", w));
        int bankrupt = M2bReviewTest.indexOf(start.log, GameEvent.PlayerEliminated.class, 0);
        int reclaimed = M2bReviewTest.indexOf(start.log, GameEvent.CashReclaimed.class, 0);
        var prefix = new Evolver<>(SessionDomain.INSTANCE, start.config).evolveAll(null, start.log.subList(0, reclaimed + 1));
        var g = ((SessionState)prefix.domain()).game();
        assertEquals("p1", g.board().roadblocks().getFirst().owner());
        assertTrue(g.player("p1").orElseThrow().hand().isEmpty()); assertTrue(g.board().ownedBy("p1").isEmpty());
        assertFalse(g.player("p1").orElseThrow().alive()); assertTrue(last < bankrupt); assertTrue(bankrupt < reclaimed);
        assertEquals(start.state, start.engine.rebuild(start.log));
    }
    @Test void noRoadblockOrTargetedClientCommandIsAdded() {
        for (var type : GameCommand.class.getPermittedSubclasses()) {
            assertFalse(type.getSimpleName().contains("Roadblock")); assertFalse(type.getSimpleName().contains("Targeted"));
        }
    }
}
