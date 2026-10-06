package com.millionnaire.engine.core.engine;
import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.state.*;
import org.junit.jupiter.api.Test;

/** Call each gate/table directly, bypassing downstream invariant checks. */
class M3bIndependentTest {
    @Test void eventWindowUsesDecisionDurationAndAutomaticDrawAtEveryPolicy() {
        var rule=StageTable.rule(StageTable.Point.EVENT_DRAW);
        assertEquals(StageTable.Duration.DECISION,rule.duration());
        assertEquals(StageTable.Action.DRAW_EVENT,rule.onTimeout());
        assertEquals(StageTable.Action.DRAW_EVENT,rule.hostedPolicy());
        assertEquals(StageTable.Action.DRAW_EVENT,rule.offlinePolicy());
    }
    @Test void discardWindowUsesDiscardDurationAndAbandonsNewCardAtEveryPolicy() {
        var rule=StageTable.rule(StageTable.Point.DISCARD);
        assertEquals(StageTable.Duration.DISCARD,rule.duration());
        assertEquals(StageTable.Action.DISCARD_NEW,rule.onTimeout());
        assertEquals(StageTable.Action.DISCARD_NEW,rule.hostedPolicy());
        assertEquals(StageTable.Action.DISCARD_NEW,rule.offlinePolicy());
    }
    @Test void successorGenerationActuallyEvaluatesItsGateWithThePostResultState() {
        var t=M3bTest.event(55,com.millionnaire.engine.testkit.Table.steps(com.millionnaire.engine.random.DrawPoint.EVENT_CARD,1000,0));
        t.rollOnly(); var g=t.game();
        var l=new LandingState(1,1,2,null,0,false,java.util.List.of(LandingStep.CARD),java.util.List.of(-1),java.util.List.of(),0,false);
        assertEquals(java.util.List.of(LandingStep.CARD),LandingRules.consume(t.config,g,l,LandingResult.CARD_RECEIVED).tasks());
        var overflow=g.withPlayer(g.player("p1").orElseThrow().withHand(java.util.Collections.nCopies(7,com.millionnaire.engine.config.CardType.ROADBLOCK)));
        assertEquals(java.util.List.of(LandingStep.CARD,LandingStep.DISCARD),LandingRules.consume(t.config,overflow,l,LandingResult.CARD_RECEIVED).tasks());
    }
    @Test void eventInitialTaskIsSuppressedByOnlyTheChainDrawnMarker() {
        var t=M3bTest.event(0,com.millionnaire.engine.testkit.Table.steps(com.millionnaire.engine.random.DrawPoint.EVENT_CASH,9,0));
        t.rollOnly(); var g=t.game();
        assertEquals(LandingStep.EVENT,EconomyModule.requiredStep(t.config,g,"p1",2));
        var changed=g.withTurn(g.turn().withChain(g.turn().chain().drewEvent()));
        assertNull(EconomyModule.requiredStep(t.config,changed,"p1",2));
    }
    @Test void duplicateDrawClicksAndDuplicateDeliveryNeverDrawAnotherResult() {
        var t=M3bTest.event(0,com.millionnaire.engine.testkit.Table.steps(com.millionnaire.engine.random.DrawPoint.EVENT_CASH,9,0));
        t.rollOnly(); long w=t.windowId();
        t.act(id->new com.millionnaire.engine.core.command.GameCommand.DrawEventCard("p1",id));
        var before=t.state; var input=t.inputs.getLast();
        var duplicate=t.engine.step(before,input);
        assertEquals(StepResult.Outcome.DUPLICATE,duplicate.outcome());
        assertEquals(before,duplicate.state()); assertTrue(duplicate.events().isEmpty());
        var rejected=t.send(new com.millionnaire.engine.core.command.GameCommand.DrawEventCard("p1",w));
        assertEquals(StepResult.Outcome.REJECTED,rejected.outcome()); assertEquals(before.rng(),t.state.rng());
        assertEquals(1,t.log.stream().filter(com.millionnaire.engine.core.event.GameEvent.EventDrawn.class::isInstance).count());
        assertEquals(t.state,t.engine.rebuild(t.log));
    }
}
