package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.Table;
import java.util.*;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

/** Latest product decision: accepted business commands resume; rejected commands leave control unchanged. */
class HostedBusinessTest {
    static void hosted(Table t, ControlMode mode) {
        assertEquals(StepResult.Outcome.ACCEPTED, t.send(new GameCommand.SetControl(1, "p1", mode)).outcome());
    }
    static StepResult accepts(Table t, LongFunction<GameCommand> command, Class<? extends Event> effect) {
        long at = Math.max(t.now + 10, t.window().window().opensAt());
        var input = new Input(t.seq + 1, at, command.apply(t.window().windowId()));
        var before = t.state;
        var restored = t.engine.restore(t.engine.snapshot(before));
        var r = t.send(at, input.command());
        assertEquals(StepResult.Outcome.ACCEPTED, r.outcome(), input.command().toString());
        int control = M2bReviewTest.indexOf(r.events(), GameEvent.ControlChanged.class, 0);
        var change = (GameEvent.ControlChanged) r.events().get(control);
        assertEquals("p1", change.playerId()); assertEquals(ControlMode.MANUAL, change.mode());
        assertEquals(1, r.events().stream().filter(GameEvent.ControlChanged.class::isInstance).count());
        assertTrue(control < M2bReviewTest.indexOf(r.events(), effect, 0));
        assertEquals(r, t.engine.step(restored, input), "snapshot continuation");
        assertEquals(r.state(), new Evolver<>(SessionDomain.INSTANCE, t.config).evolveAll(before, r.events()), "event continuation");
        if (t.session().inGame()) { assertEquals(ControlMode.MANUAL, t.game().player("p1").orElseThrow().control()); }
        return r;
    }
    static Table property() { var t = M2bReviewTest.table(2, 1); t.rollOnly(); return t; }
    static Table upgrade() { var t = property(); t.act(w -> new GameCommand.BuyProperty("p1", w)); return t; }
    static Table bank() {
        var t = M2bReviewTest.table(2, 1);
        M3bTest.craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().at(10))
                .withBoard(g.board().with(OwnableState.unowned(1).owned("p1"))));
        t.rollOnly(); return t;
    }
    static Table redeem() {
        var t = M2bReviewTest.table(2, 1);
        M3bTest.craft(t, g -> g.withBoard(g.board().with(new OwnableState(1, "p1", 0, true, 500, null)))); return t;
    }
    static Table jail() {
        var script=Table.script(Table.order(90,10),Table.deal(2),Table.dice(DrawPoint.MOVE_DIE,2),
                Table.steps(DrawPoint.EVENT_KIND,100,95),Table.dice(DrawPoint.MOVE_DIE,1));
        var t=new Table(new com.millionnaire.engine.testkit.ScriptedRandom(script),1).start(2);
        t.rollOnly(); t.act(w->new GameCommand.DrawEventCard("p1",w)); t.roll(); return t;
    }
    @Test void hostedRollResumesAndRolls() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=M2bReviewTest.table(2,1); hosted(t,m); accepts(t,w->new GameCommand.RollDice("p1",w),GameEvent.DiceRolled.class); } }
    @Test void hostedBuyResumesAndBuys() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=property(); hosted(t,m); accepts(t,w->new GameCommand.BuyProperty("p1",w),GameEvent.PropertyBought.class); } }
    @Test void hostedDeclineResumesAndDeclines() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=property(); hosted(t,m); accepts(t,w->new GameCommand.DeclinePurchase("p1",w),GameEvent.PurchaseDeclined.class); } }
    @Test void hostedUpgradeResumesAndUpgrades() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=upgrade(); hosted(t,m); accepts(t,w->new GameCommand.UpgradeProperty("p1",w),GameEvent.PropertyUpgraded.class); } }
    @Test void hostedSkipResumesAndSkips() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=upgrade(); hosted(t,m); accepts(t,w->new GameCommand.SkipUpgrade("p1",w),GameEvent.UpgradeSkipped.class); } }
    @Test void hostedMortgageResumesWithoutRefreshingDeadline() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=bank(); long deadline=t.window().window().deadline(); hosted(t,m); accepts(t,w->new GameCommand.BankMortgage("p1",w,1),GameEvent.AssetMortgaged.class); assertEquals(deadline,t.window().window().deadline()); } }
    @Test void hostedRedeemResumesWithoutRefreshingDeadline() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=redeem(); long deadline=t.window().window().deadline(); hosted(t,m); accepts(t,w->new GameCommand.Redeem("p1",w,1),GameEvent.AssetRedeemed.class); assertEquals(deadline,t.window().window().deadline()); } }
    @Test void hostedFinishBankResumesAndFinishes() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=bank(); hosted(t,m); accepts(t,w->new GameCommand.FinishBank("p1",w),GameEvent.BankFinished.class); } }
    @Test void hostedDrawResumesAndDrawsOnce() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=M3bTest.event(0,Table.steps(DrawPoint.EVENT_CASH,9,0)); t.rollOnly(); hosted(t,m); long w=t.windowId(); accepts(t,id->new GameCommand.DrawEventCard("p1",id),GameEvent.EventDrawn.class); var rng=t.state.rng(); assertEquals(StepResult.Outcome.REJECTED,t.send(new GameCommand.DrawEventCard("p1",w)).outcome()); assertEquals(rng,t.state.rng()); } }
    @Test void hostedDiscardResumesAndDiscards() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=M3bRestoreTest.overflow(); hosted(t,m); accepts(t,w->new GameCommand.DiscardCard("p1",w,0),GameEvent.EventCardDiscarded.class); } }
    @Test void hostedPayBailResumesAndPays() { for (var m : List.of(ControlMode.AWAY, ControlMode.HOSTED)) { var t=jail(); hosted(t,m); accepts(t,w->new GameCommand.PayBail("p1",w),GameEvent.BailPaid.class); } }
    @Test void hostedEmergencyMortgageResumesButKeepsLockedDebtPath() { var t=M3bFeeRulesTest.fine(); var source=t.game().debt().source(); hosted(t,ControlMode.HOSTED); accepts(t,w->new GameCommand.EmergencyMortgage("p1",w,1),GameEvent.AssetMortgaged.class); assertNotNull(source); }
    @Test void hostedContinueDebtResumesAndContinues() { var t=M3bFeeRulesTest.fine(); t.tick(t.window().window().deadline()); hosted(t,ControlMode.AWAY); accepts(t,w->new GameCommand.ContinueDebt("p1",w),GameEvent.DebtContinued.class); }
    @Test void hostedBankruptcyResumesAndConfirms() { var t=M3bFeeRulesTest.fine(); hosted(t,ControlMode.AWAY); accepts(t,w->new GameCommand.DeclareBankruptcy("p1",w),GameEvent.LiquidationStarted.class); }
    @Test void hostedSurrenderResumesAndSurrenders() { var t=M2bReviewTest.table(3,1); hosted(t,ControlMode.HOSTED); accepts(t,w->new GameCommand.Surrender("p1",1),GameEvent.LiquidationStarted.class); }
    @Test void rejectedBusinessCommandsNeverResumeOrConsumeRandomness() {
        var t=property(); hosted(t,ControlMode.AWAY); var rng=t.state.rng();
        for (GameCommand c : List.of(new GameCommand.BuyProperty("p2",t.windowId()), new GameCommand.BuyProperty("p1",t.windowId()+1),
                new GameCommand.DiscardCard("p1",t.windowId(),-1),new GameCommand.Redeem("p1",t.windowId(),29),
                new GameCommand.StartLandAuction("p1",t.windowId()))) {
            var r=t.send(Math.max(t.now+1,t.window().window().opensAt()),c);
            assertEquals(StepResult.Outcome.REJECTED,r.outcome()); assertEquals(ControlMode.AWAY,t.game().player("p1").orElseThrow().control());
            assertFalse(r.events().stream().anyMatch(GameEvent.ControlChanged.class::isInstance)); assertEquals(rng,t.state.rng());
        }
    }
    @Test void offlineBusinessAndExplicitResumeRequireTrustedReconnect() {
        var t=M3bFeeRulesTest.fine(); hosted(t,ControlMode.HOSTED);
        t.send(new GameCommand.ConnectionSuspected(1,"p1",1)); t.send(new GameCommand.ConnectionConfirmed(1,"p1",2));
        for (GameCommand c : List.of(new GameCommand.EmergencyMortgage("p1",t.window().windowId(),1),
                new GameCommand.ContinueDebt("p1",t.window().windowId()),new GameCommand.DeclareBankruptcy("p1",t.window().windowId()),
                new GameCommand.Surrender("p1",1),new GameCommand.ResumeControl("p1",1))) {
            var r=t.send(c); assertEquals(RejectionCode.CONTROL_NOT_MANUAL,r.rejection());
            assertEquals(ControlMode.HOSTED,t.game().player("p1").orElseThrow().control());
        }
        t.send(new GameCommand.Reconnected(1,"p1",3));
        accepts(t,w->new GameCommand.EmergencyMortgage("p1",w,1),GameEvent.AssetMortgaged.class);
    }
    @Test void dueEventDrawRunsBeforeLateClickAndDoesNotResume() {
        var t=M3bTest.event(0,Table.steps(DrawPoint.EVENT_CASH,9,0)); t.rollOnly(); hosted(t,ControlMode.HOSTED);
        long w=t.windowId(), due=t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt();
        var r=t.send(due,new GameCommand.DrawEventCard("p1",w)); assertEquals(StepResult.Outcome.REJECTED,r.outcome());
        assertTrue(r.events().stream().anyMatch(e->e instanceof GameEvent.EventDrawn d && d.auto()));
        assertFalse(r.events().stream().anyMatch(GameEvent.ControlChanged.class::isInstance));
        assertEquals(ControlMode.HOSTED,t.game().player("p1").orElseThrow().control());
    }
    @Test void readOnlyViewsDoNotResumeOrChangeTimers() {
        var t=property(); hosted(t,ControlMode.HOSTED); var before=t.state;
        for(String viewer:List.of("p1","p2","spectator")) { SessionDomain.INSTANCE.project(t.state,viewer); }
        assertEquals(before,t.state); assertEquals(ControlMode.HOSTED,t.game().player("p1").orElseThrow().control());
    }
    @Test void everyBusinessClassIsRejectedWhenConfirmedOfflineWithoutChangingControl() {
        var t=property(); hosted(t,ControlMode.HOSTED);
        t.send(new GameCommand.ConnectionSuspected(1,"p1",1));
        t.send(new GameCommand.ConnectionConfirmed(1,"p1",2));
        long w=t.windowId(); var rng=t.state.rng();
        for(GameCommand c:List.of(new GameCommand.RollDice("p1",w),new GameCommand.PayBail("p1",w),
                new GameCommand.BuyProperty("p1",w),new GameCommand.DeclinePurchase("p1",w),new GameCommand.StartLandAuction("p1",w),
                new GameCommand.UpgradeProperty("p1",w),new GameCommand.SkipUpgrade("p1",w),new GameCommand.BankMortgage("p1",w,1),
                new GameCommand.Redeem("p1",w,1),new GameCommand.FinishBank("p1",w),new GameCommand.EmergencyMortgage("p1",w,1),
                new GameCommand.ContinueDebt("p1",w),new GameCommand.DeclareBankruptcy("p1",w),new GameCommand.Surrender("p1",1),
                new GameCommand.DrawEventCard("p1",w),new GameCommand.DiscardCard("p1",w,0))) {
            var r=t.send(c);
            assertEquals(RejectionCode.CONTROL_NOT_MANUAL,r.rejection(),c.toString());
            assertFalse(r.events().stream().anyMatch(GameEvent.ControlChanged.class::isInstance));
            assertEquals(ControlMode.HOSTED,t.game().player("p1").orElseThrow().control()); assertEquals(rng,t.state.rng());
        }
    }
    @Test void offlineBusinessGateRejectsEvenWithManualControlAndWithoutOtherChecks() {
        var t=M3bFeeRulesTest.fine();
        t.send(new GameCommand.ConnectionSuspected(1,"p1",1));
        t.send(new GameCommand.ConnectionConfirmed(1,"p1",2));
        assertEquals(ControlMode.MANUAL,t.game().player("p1").orElseThrow().control());
        var ctx=M2bReviewTest.ctx(t); var before=ctx.engineState();
        assertEquals(RejectionCode.CONTROL_NOT_MANUAL,BusinessCommands.beforeCommand(ctx,
                new GameCommand.EmergencyMortgage("p1",t.window().windowId(),1)));
        assertEquals(before,ctx.engineState()); assertTrue(ctx.events().isEmpty());
    }
    @Test void suspectConnectionAllowsBusinessRecoveryAndReconnectCannotResurrectOldAutoTasks() {
        var t=property(); hosted(t,ControlMode.AWAY);
        t.send(new GameCommand.ConnectionSuspected(1,"p1",1));
        long oldTask=t.game().turn().autoTaskId();
        long due=t.state.timers().find(oldTask).orElseThrow().dueAt();
        long deadline=t.window().window().deadline();
        accepts(t,w->new GameCommand.BuyProperty("p1",w),GameEvent.PropertyBought.class);
        assertTrue(t.state.timers().find(oldTask).isEmpty());
        assertEquals(0,t.game().turn().autoTaskId());
        var r=t.tick(Math.max(t.now+1,due));
        assertFalse(r.events().stream().anyMatch(GameEvent.PropertyUpgraded.class::isInstance));
        assertEquals(ControlMode.MANUAL,t.game().player("p1").orElseThrow().control());
        assertEquals(deadline,t.window().window().deadline());
    }
}
