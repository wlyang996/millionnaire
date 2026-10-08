package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.ConfigValidator;
import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.SetBonus;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 同组地产加成（用户 2026-10-08）：每条边上的普通地产两两一组，同组全归一人且未抵押时租金 ×150%。 */
class SetBonusTest {
    private static final RuleConfig RULES = RuleConfigs.defaultV1();

    @Test
    void defaultGroupsPairPropertiesAlongEachSide() {
        var g30 = RULES.setBonus().groups().get(RuleConfigs.BOARD_30);
        assertEquals(List.of(1, 3), RULES.setBonus().members(RuleConfigs.BOARD_30, 1));
        assertEquals(List.of(5, 6), RULES.setBonus().members(RuleConfigs.BOARD_30, 6));
        assertEquals(List.of(27, 28), RULES.setBonus().members(RuleConfigs.BOARD_30, 27));
        assertEquals(8, g30.stream().filter(x -> x > 0).distinct().count());
        assertEquals(14, RULES.setBonus().groups().get(RuleConfigs.BOARD_50).stream().filter(x -> x > 0).distinct().count());
        assertEquals(0, RULES.setBonus().groupOf(RuleConfigs.BOARD_30, 4), "stations are not grouped");
        assertTrue(ConfigValidator.validate(RULES).isEmpty());
    }

    @Test
    void rentRisesOnlyWhenTheWholeGroupIsOwnedAndUnmortgaged() {
        var script = Table.script(Table.order(90, 10), Table.deal(2), Table.dice(DrawPoint.MOVE_DIE, 3));
        GameState g = new Table(RULES, new ScriptedRandom(script), 1).start(2, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 30).game();
        var board = LobbyModule.board(RULES, g.settings());
        var a = g.board().ownable(1).orElseThrow().owned("p2"); // 低价，租金 100
        g = g.withBoard(g.board().with(a));
        assertEquals(100, EconomyModule.rent(RULES, board, g, a), "half a group: no bonus");
        var b = g.board().ownable(3).orElseThrow().owned("p2"); // 中价，租金 200
        g = g.withBoard(g.board().with(b));
        assertEquals(150, EconomyModule.rent(RULES, board, g, a));
        assertEquals(300, EconomyModule.rent(RULES, board, g, b));
        GameState mortgaged = g.withBoard(g.board().with(b.mortgage(500)));
        assertEquals(100, EconomyModule.rent(RULES, board, mortgaged, a), "a mortgaged member breaks the set");
    }

    @Test
    void validatorRejectsBadGroups() {
        List<Integer> g30 = new ArrayList<>(RULES.setBonus().groups().get(RuleConfigs.BOARD_30));
        g30.set(4, 1); // 车站
        g30.set(28, 99); // 单块成组
        var bad = new RuleConfig(RULES.ruleVersion(), RULES.boards(), RULES.tiers(), RULES.station(), RULES.economy(),
                RULES.ratios(), RULES.cardWeights(), RULES.eventWeights(), RULES.timing(), RULES.room(), RULES.rentInflation(),
                new SetBonus(90, Map.of(RuleConfigs.BOARD_30, g30)));
        List<String> errors = ConfigValidator.validate(bad);
        assertTrue(errors.stream().anyMatch(e -> e.contains("rentPercent")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("not an ordinary property")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("at least 2")), errors::toString);
    }
}
