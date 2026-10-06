package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.ledger.Ledger;
import java.util.List;
import java.util.Objects;

/** Fee identity is checked here; debt route is fixed once at charge time, never recomputed at repayment. */
final class FeeRules {
    private FeeRules() { }
    @FunctionalInterface interface Origin { boolean matches(RuleConfig c, GameState g, LandingState l, FeeSource s); }
    record Rule(FeeSource.Kind kind, String cause, LandingStep task, Origin origin) { }
    static final List<Rule> RULES = List.of(
        new Rule(FeeSource.Kind.RENT, EconomyModule.RENT, LandingStep.RENT, (c, g, l, s) ->
            g.board().ownable(l.tile()).map(o -> o.owner() != null && !o.mortgaged()
                    && !o.owner().equals(g.turn().currentPlayer()) && Objects.equals(s.creditor(), o.owner())
                    && s.amount() == EconomyModule.rent(c, LobbyModule.board(c, g.settings()), g, o)).orElse(false)),
        new Rule(FeeSource.Kind.FINE, "FINE", LandingStep.FINE, (c, g, l, s) ->
                s.creditor() == null && EventModule.hasKind(l, com.millionnaire.engine.config.EventKind.CASH_FINE)
                        && s.amount() == l.event().amount()),
        // SYSTEM is reserved for future concrete producers; arbitrary system charges remain forbidden.
        new Rule(FeeSource.Kind.SYSTEM, "SYSTEM", null, (c, g, l, s) -> false));
    static Rule rule(FeeSource.Kind kind) {
        return RULES.stream().filter(r -> r.kind() == kind).findFirst().orElseThrow();
    }
    static boolean valid(RuleConfig c, GameState g, LandingState l, FeeSource s) {
        if (s == null || l == null || s.kind() == null) { return false; }
        Rule r = rule(s.kind());
        return s.landingId() == l.landingId() && s.cursor() == l.cursor() && s.tile() == l.tile()
                && s.amount() > 0 && l.currentTask() == r.task() && r.origin().matches(c, g, l, s);
    }
    static String account(FeeSource s) { return s.creditor() == null ? Ledger.SYSTEM : s.creditor(); }
}
