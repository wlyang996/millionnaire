package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.Table;
import org.junit.jupiter.api.Test;

/** Invoke the fee origin itself so another debt/ledger check cannot mask a weakened origin. */
class M3bFeeRulesTest {
    static Table fine() {
        var t = M3bTest.event(30, Table.steps(DrawPoint.EVENT_CASH, 9, 8));
        M3bTest.craft(t, g -> g.withLedger(g.ledger().transfer("p1", Ledger.SYSTEM, 2900, "TEST", null))
                .withBoard(g.board().with(OwnableState.unowned(1).owned("p1"))));
        t.rollOnly(); t.act(w -> new GameCommand.DrawEventCard("p1", w)); return t;
    }
    @Test void fineOriginRejectsOnlyChangingTheCreditor() {
        var t = fine(); var l = t.game().turn().landing(); var s = t.game().debt().source();
        assertTrue(FeeRules.valid(t.config, t.game(), l, s));
        assertFalse(FeeRules.valid(t.config, t.game(), l, new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile(), "p2", s.amount())));
    }
    @Test void fineOriginRejectsOnlyChangingTheAmount() {
        var t = fine(); var l = t.game().turn().landing(); var s = t.game().debt().source();
        assertFalse(FeeRules.valid(t.config, t.game(), l, new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile(), s.creditor(), 450)));
    }
    @Test void fineOriginRejectsOnlyChangingTheTile() {
        var t = fine(); var l = t.game().turn().landing(); var s = t.game().debt().source();
        assertFalse(FeeRules.valid(t.config, t.game(), l, new FeeSource(s.kind(), s.landingId(), s.cursor(), 3, s.creditor(), s.amount())));
    }
    @Test void fineOriginRejectsOnlyChangingTheCursor() {
        var t = fine(); var l = t.game().turn().landing(); var s = t.game().debt().source();
        assertFalse(FeeRules.valid(t.config, t.game(), l, new FeeSource(s.kind(), s.landingId(), 0, s.tile(), s.creditor(), s.amount())));
    }
    @Test void fineSnapshotRejectsOnlyChangingTheSourceCreditor() {
        var t = fine(); var d = t.game().debt(); var s = d.source();
        var altered = new DebtState(d.debtId(), d.debtor(), d.creditor(), d.amount(), d.cause(), d.segment(), d.continued(), d.windowId(),
                new FeeSource(s.kind(), s.landingId(), s.cursor(), s.tile(), "p2", s.amount()), d.path());
        M3bRestoreTest.rejects(t, g -> g.withDebt(altered));
    }
    @Test void losingManualControlCannotChangeAnAlreadyLockedFineDebtRoute() {
        var t = fine(); var d = t.game().debt();
        t.send(new GameCommand.SetControl(1, "p1", ControlMode.AWAY));
        assertEquals(d.debtId(), t.game().debt().debtId()); assertEquals(DebtPath.MANUAL, t.game().debt().path());
        t.tick(t.window().window().deadline()); assertEquals(2, t.game().debt().segment());
        assertEquals(d.source(), t.game().debt().source());
    }
}
