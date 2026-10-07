package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.testkit.Table;
import java.util.*;
import org.junit.jupiter.api.Test;

class ControlSourceTest {
    static Table resumed() {
        var t=M2bReviewTest.table(2,1); t.send(new GameCommand.SetControl(1,"p1",ControlMode.HOSTED));
        t.rollOnly(); assertEquals(t.state,t.engine.rebuild(t.log)); return t;
    }
    static int manual(Table t) {
        for(int i=0;i<t.log.size();i++) { if(t.log.get(i) instanceof GameEvent.ControlChanged c && c.mode()==ControlMode.MANUAL) { return i; } }
        throw new AssertionError("no manual control event");
    }
    @Test void onlyChangingTheControlPlayerFailsAtThatEvent() {
        var t=resumed(); int i=manual(t); var log=new ArrayList<>(t.log);
        log.set(i,new GameEvent.ControlChanged("p2",ControlMode.MANUAL)); M2bReviewTest.rejectsAt(t,log,i);
    }
    @Test void onlyChangingTheControlModeFailsAtThatEvent() {
        var t=resumed(); int i=manual(t); var log=new ArrayList<>(t.log);
        log.set(i,new GameEvent.ControlChanged("p1",ControlMode.AWAY)); M2bReviewTest.rejectsAt(t,log,i);
    }
    @Test void duplicateControlChangeCannotReuseTheConsumedInputSource() {
        var t=resumed(); int i=manual(t); var log=new ArrayList<>(t.log);
        log.add(i+1,log.get(i)); M2bReviewTest.rejectsAt(t,log,i+1);
    }
    @Test void anInsertedManualChangeHasNoSourceInAnOrdinaryManualBusinessInput() {
        var t=M2bReviewTest.table(2,1); t.rollOnly();
        int i=M2bReviewTest.indexOf(t.log,GameEvent.DiceRolled.class,0); var log=new ArrayList<>(t.log);
        log.add(i,new GameEvent.ControlChanged("p1",ControlMode.MANUAL)); M2bReviewTest.rejectsAt(t,log,i);
    }
    @Test void removingOnlyTheClientSourceFailsAtItsControlEvent() {
        var t=resumed(); int i=manual(t), accepted=i-1;
        while(!(t.log.get(accepted) instanceof KernelEvent.InputAccepted)) { accepted--; }
        var e=(KernelEvent.InputAccepted)t.log.get(accepted); var log=new ArrayList<>(t.log);
        log.set(accepted,new KernelEvent.InputAccepted(e.seq(),e.at(),e.digest(),e.systemCommand(),null));
        M2bReviewTest.rejectsAt(t,log,i);
    }
    @Test void changingOnlyTheSourceActorFailsAtTheAcceptedInputDigest() {
        var t=resumed(); int i=manual(t)-1;
        while(!(t.log.get(i) instanceof KernelEvent.InputAccepted)) { i--; }
        var e=(KernelEvent.InputAccepted)t.log.get(i); var c=(GameCommand.RollDice)e.clientCommand(); var log=new ArrayList<>(t.log);
        log.set(i,new KernelEvent.InputAccepted(e.seq(),e.at(),e.digest(),null,new GameCommand.RollDice("p2",c.windowId())));
        M2bReviewTest.rejectsAt(t,log,i);
    }
    @Test void explicitResumeHasItsOwnOneUseSource() {
        var t=M2bReviewTest.table(2,1); t.send(new GameCommand.SetControl(1,"p1",ControlMode.AWAY));
        var r=t.send(new GameCommand.ResumeControl("p1",1)); assertEquals(StepResult.Outcome.ACCEPTED,r.outcome());
        assertEquals(t.state,t.engine.rebuild(t.log));
        assertTrue(r.events().getFirst() instanceof KernelEvent.InputAccepted e && e.clientCommand() instanceof GameCommand.ResumeControl);
    }
    @Test void aTrustedSetControlSourceCannotChangeAnotherPlayerOrMode() {
        var t=M2bReviewTest.table(2,1); t.send(new GameCommand.SetControl(1,"p1",ControlMode.HOSTED));
        int i=M2bReviewTest.indexOf(t.log,GameEvent.ControlChanged.class,0); var log=new ArrayList<>(t.log);
        log.set(i,new GameEvent.ControlChanged("p2",ControlMode.HOSTED)); M2bReviewTest.rejectsAt(t,log,i);
    }
    @Test void deletingTheRequiredControlChangeFailsAtItsImmediateSuccessor() {
        var t=resumed(); int i=manual(t); var log=new ArrayList<>(t.log); log.remove(i);
        M2bReviewTest.rejectsAt(t,log,i);
    }
    @Test void delayingControlUntilAfterTaskCancellationFailsBeforeBusinessExecution() {
        var t=resumed(); int i=manual(t); var log=new ArrayList<>(t.log); var event=log.remove(i);
        assertTrue(log.get(i) instanceof KernelEvent.TaskCancelled);
        log.add(i+1,event); M2bReviewTest.rejectsAt(t,log,i);
    }
    @Test void restoreRejectsAStolenUnconsumedControlCredential() {
        var t=M2bReviewTest.table(2,1);
        var state=t.state.withDomain(t.session().withControlSource(new GameEvent.ControlChanged("p1",ControlMode.MANUAL)));
        assertThrows(StateValidationException.class,()->t.engine.restore(t.engine.snapshot(state)));
    }
    @Test void controlOriginGateRejectsAnotherPlayerWithoutRelyingOnTimersOrBoundaryChecks() {
        var t=M2bReviewTest.table(2,1); t.send(new GameCommand.SetControl(1,"p1",ControlMode.HOSTED));
        var source=new Input(t.seq+1,t.now+1,new GameCommand.RollDice("p1",t.windowId()));
        var state=SessionDomain.INSTANCE.acceptSystemInput(t.session(),source);
        var wrong=new GameEvent.ControlChanged("p2",ControlMode.MANUAL);
        var ex=assertThrows(IllegalStateException.class,()->SessionDomain.INSTANCE.evolve(state,wrong,new Draws(List.of()),t.config));
        assertEquals("control change requires its matching accepted input source",ex.getMessage());
        var accepted=SessionDomain.INSTANCE.evolve(state,new GameEvent.ControlChanged("p1",ControlMode.MANUAL),new Draws(List.of()),t.config);
        assertNull(accepted.controlSource()); assertEquals(ControlMode.MANUAL,accepted.game().player("p1").orElseThrow().control());
    }
    @Test void everyGameCommandIsExplicitlyClassifiedAndNewOnesRequireAClassification() throws ReflectiveOperationException {
        Set<String> business=Set.of("RollDice","PayBail","BuyProperty","DeclinePurchase","StartLandAuction","UpgradeProperty",
                "SkipUpgrade","BankMortgage","Redeem","FinishBank","EmergencyMortgage","ContinueDebt","DeclareBankruptcy",
                "Surrender","DrawEventCard","DiscardCard","PickTooth");
        Set<String> control=Set.of("ResumeControl");
        Set<String> system=Set.of("SetControl","ConnectionSuspected","ConnectionConfirmed","Reconnected");
        Set<String> actual=new TreeSet<>();
        for(var type:GameCommand.class.getPermittedSubclasses()) {
            actual.add(type.getSimpleName()); var fields=type.getRecordComponents(); var args=new Object[fields.length]; var types=new Class<?>[fields.length];
            for(int i=0;i<fields.length;i++) { types[i]=fields[i].getType(); args[i]=types[i]==String.class?"p1":types[i]==long.class?1L:types[i]==int.class?0:ControlMode.HOSTED; }
            var command=(GameCommand)type.getDeclaredConstructor(types).newInstance(args);
            var expected=business.contains(type.getSimpleName())?BusinessCommands.Kind.BUSINESS:control.contains(type.getSimpleName())?BusinessCommands.Kind.CONTROL:BusinessCommands.Kind.SYSTEM;
            assertEquals(expected,BusinessCommands.kind(command),type.getSimpleName());
        }
        var expected=new TreeSet<>(business); expected.addAll(control); expected.addAll(system); assertEquals(expected,actual);
    }
    record ReadDetails(String actor) implements Command { }
    record Chat(String actor) implements Command { }
    record Voice(String actor) implements Command { }
    @Test void externalReadAndSocialActivitiesAreExcludedFromBusinessRecovery() {
        var t=HostedBusinessTest.property(); HostedBusinessTest.hosted(t,ControlMode.AWAY);
        for(Command activity:List.of(new ReadDetails("p1"),new Chat("p1"),new Voice("p1"))) {
            assertEquals(BusinessCommands.Kind.EXTERNAL,BusinessCommands.kind(activity));
            var ctx=M2bReviewTest.ctx(t); var before=ctx.engineState();
            assertNull(BusinessCommands.beforeCommand(ctx,activity)); assertEquals(before,ctx.engineState()); assertTrue(ctx.events().isEmpty());
        }
    }
}
