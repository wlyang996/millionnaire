package com.millionnaire.engine.core.engine;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.Table;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.millionnaire.engine.core.engine.TeamEconomyTest.*;

class TeamAidTest {
    @Test void aidCannotOverspendAvailableCashOrForgeAnAcceptedTransfer() {
        var t=table(false,true); long w=t.windowId();t.tick(t.window().window().opensAt());
        craft(t,g->g.withLedger(g.ledger().transfer("p1",Ledger.SYSTEM,2800,"TEST",null)));
        assertEquals(RejectionCode.INSUFFICIENT_CASH,t.send(new GameCommand.TeamAid("p1",1,w,"p2",300,0)).rejection());
        assertEquals(200,t.cash("p1"));assertEquals(3000,t.cash("p2"));
        var forged=new GameEvent.TeamAidTransferred("p1",1,w,"p2",100,0,t.now);
        assertThrows(RuntimeException.class,()->TeamAidModule.evolve(t.session(),forged,t.config));
    }
    @Test void activeTransferIsAtomicBoundedPersonalAndReplayable() {
        var t = table(false, true); long w=t.windowId(); t.tick(t.window().window().opensAt());
        assertEquals(RejectionCode.NOT_ALLOWED,t.send(new GameCommand.TeamAid("p1",1,w,"p3",100,0)).rejection());
        assertEquals(RejectionCode.INVALID_ARGUMENT,t.send(new GameCommand.TeamAid("p1",1,w,"p2",0,0)).rejection());
        assertNull(t.send(new GameCommand.TeamAid("p1",1,w,"p2",300,0)).rejection());
        assertEquals(2700,t.cash("p1")); assertEquals(3300,t.cash("p2")); assertEquals(w,t.windowId());
        assertEquals(RejectionCode.AID_LIMIT,t.send(new GameCommand.TeamAid("p1",1,w,"p2",100,0)).rejection());
        assertTrue(t.game().progress().metrics().getOrDefault("p2",GameProgressState.Metrics.EMPTY).income().isEmpty());
        assertEquals(t.state,t.engine.restore(t.engine.snapshot(t.state)));
        assertEquals(t.state,t.engine.rebuild(t.log));
    }
    private static Table debt() {
        var t=table(false,true,Table.dice(DrawPoint.MOVE_DIE,1));
        craft(t,g->own(g,1,"p3",3).withLedger(g.ledger().transfer("p1",Ledger.SYSTEM,2900,"TEST",null)));
        t.rollOnly(); assertNotNull(t.game().debt()); assertEquals(0,t.game().debt().segment());
        t.tick(t.window().window().opensAt()); return t;
    }
    @Test void requestedDebtRejectsOverpaymentAndPaysExactlyOnce() {
        var t=debt(); var d=t.game().debt(); long deadline=t.window().window().deadline();
        assertEquals(RejectionCode.NOT_ALLOWED,t.send(new GameCommand.TeamAid("p2",1,d.windowId(),"p1",100,d.debtId())).rejection());
        assertNull(t.send(new GameCommand.RequestTeamAid("p1",1,d.windowId(),d.debtId())).rejection());
        assertEquals(RejectionCode.ALREADY_REQUESTED,t.send(new GameCommand.RequestTeamAid("p1",1,d.windowId(),d.debtId())).rejection());
        assertEquals(deadline,t.window().window().deadline());
        assertEquals(RejectionCode.AID_EXCEEDS_GAP,t.send(new GameCommand.TeamAid("p2",1,d.windowId(),"p1",601,d.debtId())).rejection());
        assertNull(t.send(new GameCommand.TeamAid("p2",1,d.windowId(),"p1",300,d.debtId())).rejection());
        assertNotNull(t.game().debt()); assertEquals(400,t.cash("p1"));
        assertNull(t.send(new GameCommand.TeamAid("p2",1,d.windowId(),"p1",300,d.debtId())).rejection());
        assertNull(t.game().debt()); assertEquals(0,t.cash("p1")); assertEquals(2400,t.cash("p2")); assertEquals(3700,t.cash("p3"));
        assertEquals(1,t.log.stream().filter(GameEvent.DebtPaid.class::isInstance).count());
        assertEquals(t.state,t.engine.restore(t.engine.snapshot(t.state)));
    }
    @Test void directBankruptcyWaitsOnlyConfiguredRescueWindow() {
        var t=debt(); long deadline=t.window().window().deadline();
        assertEquals(DebtPath.DIRECT_BANKRUPTCY,t.game().debt().path());
        t.tick(deadline-1); assertTrue(t.game().player("p1").orElseThrow().alive());
        t.tick(deadline); assertFalse(t.game().player("p1").orElseThrow().alive()); assertNull(t.game().debt());
    }
    @Test void manualDebtReturnsToMortgageAfterRescueAndStaleAidCannotTransfer() {
        var t=table(false,true,Table.dice(DrawPoint.MOVE_DIE,1));
        craft(t,g->own(own(g,1,"p3",3),5,"p1",0).withLedger(g.ledger().transfer("p1",Ledger.SYSTEM,2900,"TEST",null)));
        t.rollOnly(); var d=t.game().debt(); t.tick(t.window().window().deadline());
        assertEquals(1,t.game().debt().segment());
        assertNotNull(t.send(new GameCommand.TeamAid("p2",1,d.windowId(),"p1",100,d.debtId())).rejection());
        assertEquals(3000,t.cash("p2"));
    }
    @Test void allyAttacksAreRejectedAndRoadblocksRemainForEnemies() {
        var t=table(false,true);
        craft(t,g->own(g,1,"p2",2));
        for(var card:java.util.List.of(CardType.DOWNGRADE,CardType.DEMOLISH,CardType.CLEAR_LAND,CardType.FORCED_PURCHASE))
            assertEquals(RejectionCode.NO_TARGET,CardModule.tileCondition(t.config,t.game(),"p1",card,1));
        assertEquals(RejectionCode.NO_TARGET,CardModule.queryTarget(t.game(),"p1","p2"));
        var g=t.game().withBoard(t.game().board().placed(new Roadblock(1,1,"p2",1)));
        var plan=MovementRules.segment(1,MoveKind.DICE,0,3,30);
        assertEquals(3,MovementRules.resolve(plan,g,t.config,"p1",30).to());
        assertEquals(1,MovementRules.resolve(plan,g,t.config,"p3",30).to());
        assertEquals(1,g.board().roadblocks().size());
    }
}
