package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.state.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3bPreparationTest {
    @Test void arbitrarySuccessorPredicatesCanExpressTasksOutsideTheInitialRule() {
        var t = M2bReviewTest.table(2, 1);
        t.rollOnly();
        var l = t.game().turn().landing();
        var successor = new LandingRules.Successor(LandingStep.BUILD_CARD,
                (c, g, landing) -> g.player(g.turn().currentPlayer()).orElseThrow().hand().size() == 2);
        assertTrue(successor.gate().allows(t.config, t.game(), l));
        assertNotEquals(successor.step(), EconomyModule.requiredStep(t.config, t.game(), "p1", l.tile()));
        assertFalse(new LandingRules.Successor(LandingStep.BUILD_CARD, (c, g, landing) -> false)
                .gate().allows(t.config, t.game(), l));
    }
    @Test void feeTableRejectsAnUnregisteredSystemProducerAndMapsNullCreditorToSystem() {
        var t = M2bReviewTest.table(2, 1);
        t.rollOnly();
        var l = t.game().turn().landing();
        for (var kind : List.of(FeeSource.Kind.FINE, FeeSource.Kind.SYSTEM)) {
            var s = new FeeSource(kind, l.landingId(), l.cursor(), l.tile(), null, 100);
            assertFalse(FeeRules.valid(t.config, t.game(), l, s));
            assertEquals(com.millionnaire.engine.ledger.Ledger.SYSTEM, FeeRules.account(s));
        }
    }
    @Test void stepDescriptionsOwnWindowPermissionsAndPreserveRentAsAnImmediateTask() {
        for (var r : LandingRules.RULES) {
            if (r.execution() == LandingRules.Execution.WINDOW) {
                assertNotNull(r.point());
                assertNotNull(StageTable.rule(r.point()).duration());
                assertNotNull(StageTable.rule(r.point()).onTimeout());
            }
        }
        assertNull(LandingRules.rule(LandingStep.RENT).point());
        assertEquals(List.of(), LandingRules.rule(LandingStep.RENT).outcome(LandingResult.PAID).successors());
    }
}
