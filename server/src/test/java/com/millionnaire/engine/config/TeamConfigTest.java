package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Canonical;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamConfigTest {
    @Test void disabledDefaultsAndOldConstructorsDoNotActivateTeams() {
        var c = RuleConfigs.defaultV1();
        assertFalse(c.team().enabled());
        assertEquals(List.of(), ConfigValidator.validate(c));
        var old = new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing(), c.room(), c.rentInflation(), c.setBonus(),
                c.startPick(), c.roundReward(), c.funTitles(), c.cityEvents(), c.series());
        assertEquals(c, old);
        assertEquals(c.contentHash(), old.contentHash());
        assertEquals(c, Canonical.decode(Canonical.encode(c), RuleConfig.class));
    }

    @Test void badListsAndAmountsReturnChineseErrorsRatherThanThrowing() {
        var bad = new TeamConfig(true, Arrays.asList(2, null), 3, true, true, true,
                true, 0, 0, Arrays.asList(100L, null), true, 999);
        var errors = assertDoesNotThrow(() -> TeamConfig.validate(bad));
        assertTrue(errors.stream().anyMatch(e -> e.contains("组队形式")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("单笔")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("次数")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("快捷金额")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("等待时长")));
        assertFalse(TeamConfig.validate(new TeamConfig(false, List.of(2,2), 2, true, true, true,
                true, 300, 1, List.of(300L,500L), true, 120001)).isEmpty());
    }

    @Test void configurationIsImmutableAndHashIncludesAidRules() {
        var sizes = new ArrayList<>(List.of(2,3));
        var quick = new ArrayList<>(List.of(100L,300L));
        var t = new TeamConfig(true, sizes, 3, false, true, true, true, 1000, 2, quick, true, 15000);
        sizes.clear(); quick.clear();
        assertEquals(List.of(2,3), t.teamSizeOptions());
        assertThrows(UnsupportedOperationException.class, () -> t.aidQuickAmounts().add(1L));
        var c = withTeam(RuleConfigs.defaultV1(), t);
        assertEquals(List.of(), ConfigValidator.validate(c));
        assertNotEquals(RuleConfigs.defaultV1().contentHash(), c.contentHash());
        assertEquals(c, Canonical.decode(Canonical.encode(c), RuleConfig.class));
    }

    @Test void enabledFormatsRequireAtLeastOneMapWithEnoughSeats() {
        var base = RuleConfigs.defaultV1();
        var t = new TeamConfig(true, List.of(3), 3, true, true, true,
                true, 5000, 1, List.of(100L), true, 20000);
        var c = new RuleConfig(base.ruleVersion(), List.of(base.board(RuleConfigs.BOARD_30).orElseThrow()),
                base.tiers(), base.station(), base.economy(), base.ratios(), base.cardWeights(), base.eventWeights(),
                base.timing(), base.room(), base.rentInflation(), base.setBonus(), base.startPick(),
                base.roundReward(), base.funTitles(), base.cityEvents(), base.series(), t);
        assertTrue(ConfigValidator.validate(c).stream().anyMatch(e -> e.contains("3v3没有")));
    }

    private RuleConfig withTeam(RuleConfig c, TeamConfig t) {
        return new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing(), c.room(), c.rentInflation(), c.setBonus(),
                c.startPick(), c.roundReward(), c.funTitles(), c.cityEvents(), c.series(), t);
    }
}
