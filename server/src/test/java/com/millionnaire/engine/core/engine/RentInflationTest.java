package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RentInflation;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import org.junit.jupiter.api.Test;

/** 租金随轮数上涨（用户 2026-10-08）：破产模式前 10 轮原价，之后每 5 轮 +20%，封顶 ×3；限时模式不涨。 */
class RentInflationTest {
    private static final RuleConfig RULES = RuleConfigs.defaultV1();

    @Test
    void percentSchedule() {
        assertEquals(100, RentInflation.DEFAULT.percent(EndMode.BANKRUPTCY, 1));
        assertEquals(100, RentInflation.DEFAULT.percent(EndMode.BANKRUPTCY, 10));
        assertEquals(120, RentInflation.DEFAULT.percent(EndMode.BANKRUPTCY, 11));
        assertEquals(120, RentInflation.DEFAULT.percent(EndMode.BANKRUPTCY, 15));
        assertEquals(140, RentInflation.DEFAULT.percent(EndMode.BANKRUPTCY, 16));
        assertEquals(280, RentInflation.DEFAULT.percent(EndMode.BANKRUPTCY, 55));
        assertEquals(300, RentInflation.DEFAULT.percent(EndMode.BANKRUPTCY, 56));
        assertEquals(300, RentInflation.DEFAULT.percent(EndMode.BANKRUPTCY, 500));
        assertEquals(100, RentInflation.DEFAULT.percent(EndMode.TIME_LIMIT, 500));
        assertEquals(350, RentInflation.apply(250, 140));
        assertEquals(130, RentInflation.apply(100, 130), "floored to a multiple of 10");
        var custom = new RentInflation(4, 2, 50, 250);
        assertEquals(100, custom.percent(EndMode.BANKRUPTCY, 4));
        assertEquals(150, custom.percent(EndMode.BANKRUPTCY, 5));
        assertEquals(250, custom.percent(EndMode.BANKRUPTCY, 9));
        assertEquals(100, new RentInflation(4, 2, 0, 300).percent(EndMode.BANKRUPTCY, 99), "step 0 turns it off");
    }

    private static Table table(EndMode mode) {
        var script = Table.script(Table.order(90, 10), Table.deal(2), Table.dice(DrawPoint.MOVE_DIE, 3, 1, 3));
        return new Table(RULES, new ScriptedRandom(script), 1).start(2, RuleConfigs.BOARD_30, mode, 30);
    }

    private static void turn(Table t) {
        String who = t.current();
        t.rollOnly();
        while (t.session().inGame() && who.equals(t.current()) && t.game().turn().landing() != null) {
            t.pass();
        }
    }

    @Test
    void roundAdvancesWhenTheOrderWrapsAround() {
        var t = table(EndMode.BANKRUPTCY);
        assertEquals(1, t.game().turn().round());
        assertEquals(100, t.game().turn().rentPercent());
        turn(t);
        assertEquals("p2", t.current());
        assertEquals(1, t.game().turn().round());
        turn(t);
        assertEquals("p1", t.current());
        assertEquals(2, t.game().turn().round());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test
    void rentUsesTheMultiplierOnlyInBankruptcyMode() {
        for (EndMode mode : EndMode.values()) {
            GameState g = table(mode).game();
            var board = LobbyModule.board(RULES, g.settings());
            int tile = 1; // 低价地产，未升级租金 100
            var o = g.board().ownable(tile).orElseThrow().owned("p2");
            g = g.withBoard(g.board().with(o));
            assertEquals(100, EconomyModule.rent(RULES, board, g, o));
            int pct = RULES.rentInflation().percent(mode, 16);
            assertEquals(mode == EndMode.BANKRUPTCY ? 140 : 100, pct);
            GameState late = g.withTurn(g.turn().next(g.turn().turnNo(), g.turn().currentPlayer(), 16, pct));
            assertEquals(pct == 140 ? 140 : 100, EconomyModule.rent(RULES, board, late, o));
        }
    }
}
