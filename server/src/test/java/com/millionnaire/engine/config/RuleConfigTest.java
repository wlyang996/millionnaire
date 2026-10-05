package com.millionnaire.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.serialize.Canonical;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleConfigTest {
    private final RuleConfig config = RuleConfigs.defaultV1();

    @Test
    void defaultConfigIsValid() {
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test
    void board30MatchesRequirementCounts() {
        BoardTemplate b = config.board(RuleConfigs.BOARD_30).orElseThrow();
        assertEquals(30, b.size());
        assertEquals(4, b.maxPlayers());
        assertCounts(b, 1, 16, 5, 1, 1, 1, 1, 4);
        assertTiers(b, 6, 6, 4);
        assertDesignated(b, 1, 1, 1);
    }

    @Test
    void board50MatchesRequirementCounts() {
        BoardTemplate b = config.board(RuleConfigs.BOARD_50).orElseThrow();
        assertEquals(50, b.size());
        assertEquals(8, b.maxPlayers());
        assertCounts(b, 1, 28, 9, 2, 1, 1, 2, 6);
        assertTiers(b, 10, 10, 8);
        assertDesignated(b, 2, 2, 1);
    }

    @Test
    void weightsSumToTotals() {
        assertEquals(1000, config.cardWeights().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(14, config.cardWeights().size());
        assertEquals(100, config.eventWeights().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void hashIsStableHexAndIndependentOfConstructionOrder() {
        String h = config.contentHash();
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
        assertEquals(h, RuleConfigs.defaultV1().contentHash());

        // 同内容、不同插入顺序的 Map → 同哈希
        List<Map.Entry<CardType, Integer>> entries = new ArrayList<>(config.cardWeights().entrySet());
        Collections.reverse(entries);
        Map<CardType, Integer> reversed = new LinkedHashMap<>();
        entries.forEach(e -> reversed.put(e.getKey(), e.getValue()));
        RuleConfig reordered = withCardWeights(reversed);
        assertEquals(h, reordered.contentHash());
        assertEquals(config, reordered);
    }

    @Test
    void hashChangesWhenAnyValueChanges() {
        String h = config.contentHash();
        Map<CardType, Integer> cards = new LinkedHashMap<>(config.cardWeights());
        cards.put(CardType.DEMOLISH, 16);
        cards.put(CardType.CLEAR_LAND, 14);
        assertNotEquals(h, withCardWeights(cards).contentHash());

        EconomyConfig e = config.economy();
        EconomyConfig e2 = new EconomyConfig(e.startReward() + 1, e.miniGameWinReward(), e.bailCost(),
                e.eventCashMin(), e.eventCashMax(), e.eventCashStep(), e.eventMoveMinSteps(), e.eventMoveMaxSteps(),
                e.dieFaces(), e.maxLevel(), e.handLimit(), e.initialHandSize(), e.orderNumberMax());
        RuleConfig changed = new RuleConfig(config.ruleVersion(), config.boards(), config.tiers(), config.station(),
                e2, config.ratios(), config.cardWeights(), config.eventWeights(), config.timing(), config.room());
        assertNotEquals(h, changed.contentHash());

        RuleConfig otherVersion = new RuleConfig("rules-v2", config.boards(), config.tiers(), config.station(),
                config.economy(), config.ratios(), config.cardWeights(), config.eventWeights(), config.timing(),
                config.room());
        assertNotEquals(h, otherVersion.contentHash());
    }

    @Test
    void configRoundTripsThroughCanonicalForm() {
        String text = Canonical.encode(config);
        RuleConfig back = Canonical.decode(text, RuleConfig.class);
        assertEquals(config, back);
        assertEquals(text, Canonical.encode(back));
        assertEquals(config.contentHash(), back.contentHash());
    }

    private RuleConfig withCardWeights(Map<CardType, Integer> cards) {
        return new RuleConfig(config.ruleVersion(), config.boards(), config.tiers(), config.station(),
                config.economy(), config.ratios(), cards, config.eventWeights(), config.timing(), config.room());
    }

    private static void assertCounts(BoardTemplate b, int start, int property, int event, int bank, int jail,
                                     int rest, int game, int station) {
        assertEquals(start, b.count(TileType.START));
        assertEquals(property, b.count(TileType.PROPERTY));
        assertEquals(event, b.count(TileType.EVENT));
        assertEquals(bank, b.count(TileType.BANK));
        assertEquals(jail, b.count(TileType.JAIL));
        assertEquals(rest, b.count(TileType.REST));
        assertEquals(game, b.count(TileType.GAME_ZONE));
        assertEquals(station, b.count(TileType.STATION));
    }

    private static void assertTiers(BoardTemplate b, int low, int mid, int high) {
        assertEquals(low, b.tiles().stream().filter(t -> t.tier() == Tier.LOW).count());
        assertEquals(mid, b.tiles().stream().filter(t -> t.tier() == Tier.MID).count());
        assertEquals(high, b.tiles().stream().filter(t -> t.tier() == Tier.HIGH).count());
    }

    private static void assertDesignated(BoardTemplate b, int low, int mid, int high) {
        assertEquals(low, b.tiles().stream().filter(t -> t.auctionDesignated() && t.tier() == Tier.LOW).count());
        assertEquals(mid, b.tiles().stream().filter(t -> t.auctionDesignated() && t.tier() == Tier.MID).count());
        assertEquals(high, b.tiles().stream().filter(t -> t.auctionDesignated() && t.tier() == Tier.HIGH).count());
    }
}
