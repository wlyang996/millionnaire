package com.millionnaire.engine.config;

import com.millionnaire.engine.testkit.TestBoards;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.money.Ratio;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {
    private final RuleConfig base = TestBoards.legacyV1();

    @Test
    void acceptsDefault() {
        assertEquals(List.of(), ConfigValidator.validate(base));
        assertEquals(base, ConfigValidator.validateOrThrow(base));
    }

    @Test
    void rejectsNonIntegerStandardValue() {
        // 升级费 301 × 50% 不是整数，而标准价值没有规定取整方向 → 拒绝
        RuleConfig c = withTier(Tier.LOW, t -> new TierPricing(t.tier(), t.basePrice(), 301, t.rents(), t.emergencyMortgage()));
        assertError(c, "level 1 standard value is not an integer");
    }

    @Test
    void rejectsNonIntegerEmergencyMortgage() {
        RuleConfig c = withTier(Tier.LOW, t -> new TierPricing(t.tier(), 501, t.upgradeCost(), t.rents(), t.emergencyMortgage()));
        assertError(c, "emergency mortgage is not an integer");
    }

    @Test
    void rejectsNonIntegerRedeemFee() {
        // 原价 505：银行抵押 505，10% 手续费 50.5 → 拒绝
        RuleConfig c = withTier(Tier.LOW, t -> new TierPricing(t.tier(), 505, t.upgradeCost(), t.rents(), Ratio.percent(100)));
        assertError(c, "redeem fee on bank principal");
    }

    @Test
    void rejectsNegativeAndZeroValues() {
        assertError(withTier(Tier.MID, t -> new TierPricing(t.tier(), -1000, t.upgradeCost(), t.rents(), t.emergencyMortgage())),
                "basePrice must be in 1..");
        assertError(withStation(new StationPricing(0, 200, Ratio.percent(70))), "station.price must be in 1..");
        RatioConfig r = base.ratios();
        RatioConfig badRatio = new RatioConfig(r.upgradeValueShare(), r.bankMortgage(), r.redeemFeeOffBank(),
                r.auctionStart(), new Ratio(-10, 100), r.auctionCap(), r.forcedPurchase(), r.tradeMin(), r.tradeMax(),
                r.systemAuctionCommission());
        assertError(withRatios(badRatio), "ratios.auctionMinRaise numerator must be > 0");
    }

    @Test
    void rejectsMissingParts() {
        assertError(new RuleConfig(base.ruleVersion(), base.boards(), null, base.station(), base.economy(),
                base.ratios(), base.cardWeights(), base.eventWeights(), base.timing(), base.room()), "tiers missing");
        assertError(new RuleConfig(" ", base.boards(), base.tiers(), base.station(), base.economy(),
                base.ratios(), base.cardWeights(), base.eventWeights(), base.timing(), null), "ruleVersion missing");
        List<TierPricing> twoTiers = base.tiers().subList(0, 2);
        assertError(new RuleConfig(base.ruleVersion(), base.boards(), twoTiers, base.station(), base.economy(),
                base.ratios(), base.cardWeights(), base.eventWeights(), base.timing(), base.room()), "tier[HIGH] missing");
        assertTrue(ConfigValidator.validate(null).contains("config missing"));
    }

    @Test
    void rejectsInconsistentRents() {
        assertError(withTier(Tier.HIGH, t -> new TierPricing(t.tier(), t.basePrice(), t.upgradeCost(),
                List.of(300L, 750L, 1350L), t.emergencyMortgage())), "rents must have maxLevel+1 entries");
        assertError(withTier(Tier.HIGH, t -> new TierPricing(t.tier(), t.basePrice(), t.upgradeCost(),
                List.of(300L, 750L, 700L, 2100L), t.emergencyMortgage())), "strictly increasing");
    }

    @Test
    void rejectsBadWeights() {
        Map<CardType, Integer> cards = new TreeMap<>(base.cardWeights());
        cards.put(CardType.QUERY, 51);
        assertError(withCards(cards), "cardWeights must sum to 1000");
        cards.remove(CardType.QUERY);
        assertError(withCards(cards), "cardWeights must define every kind");
        Map<EventKind, Integer> events = new TreeMap<>(base.eventWeights());
        events.put(EventKind.JAIL, 6);
        assertError(new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), base.station(), base.economy(),
                base.ratios(), base.cardWeights(), events, base.timing(), base.room()), "eventWeights must sum to 100");
    }

    @Test
    void rejectsBrokenBoards() {
        String noJail = TestBoards.LEGACY_30.replace(" J ", " R ");
        assertError(withBoards(RuleConfigs.board("x", 2, 4, noJail)), "exactly one JAIL");
        String startMoved = "L S" + TestBoards.LEGACY_30.substring(3);
        assertError(withBoards(RuleConfigs.board("x", 2, 4, startMoved)), "exactly one START at index 0");
        assertError(withBoards(RuleConfigs.board("x", 2, 9, TestBoards.LEGACY_30)), "player capacity");
        assertError(withBoards(RuleConfigs.board("x", 2, 4, "S L E J T M L R B")), "too small for max forward chain");

        List<Tile> tiles = new ArrayList<>(base.board(RuleConfigs.BOARD_30).orElseThrow().tiles());
        tiles.set(2, new Tile(2, TileType.EVENT, Tier.LOW, true));
        assertError(withBoards(new BoardTemplate("x", 2, 4, tiles)), "tier must be set iff PROPERTY");
        tiles.set(2, new Tile(7, TileType.EVENT, null, false));
        assertError(withBoards(new BoardTemplate("x", 2, 4, tiles)), "tile index 7 at position 2");
    }

    @Test
    void rejectsRoomDefaultsOutsideOptions() {
        RoomOptions r = base.room();
        assertError(withRoom(new RoomOptions(2, r.initialCashOptions(), r.defaultBoardId(), 2500,
                r.defaultEndMode(), r.defaultTimeLimitMinutes(), r.defaultRollSeconds())), "defaultInitialCash");
        assertError(withRoom(new RoomOptions(2, r.initialCashOptions(), "nope", r.defaultInitialCash(),
                r.defaultEndMode(), r.defaultTimeLimitMinutes(), r.defaultRollSeconds())), "defaultBoardId unknown");
        assertError(withRoom(new RoomOptions(2, r.initialCashOptions(), r.defaultBoardId(), r.defaultInitialCash(),
                r.defaultEndMode(), 45, r.defaultRollSeconds())), "defaultTimeLimitMinutes");
    }

    @Test
    void validateOrThrowCarriesAllErrors() {
        Map<CardType, Integer> cards = new TreeMap<>(base.cardWeights());
        cards.put(CardType.QUERY, 51);
        RuleConfig bad = new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), new StationPricing(0, 200,
                Ratio.percent(70)), base.economy(), base.ratios(), cards, base.eventWeights(), base.timing(), base.room());
        ConfigValidationException e = assertThrows(ConfigValidationException.class, () -> ConfigValidator.validateOrThrow(bad));
        assertEquals(2, e.errors().size(), e.errors().toString());
    }

    @Test
    void ratioLegalityIsCheckedUniformly() {
        assertError(withTier(Tier.LOW, t -> new TierPricing(t.tier(), t.basePrice(), t.upgradeCost(), t.rents(),
                new Ratio(-80, 100))), "tier[LOW].emergencyMortgage numerator must be > 0");
        assertError(withTier(Tier.LOW, t -> new TierPricing(t.tier(), t.basePrice(), t.upgradeCost(), t.rents(),
                new Ratio(80, 0))), "tier[LOW].emergencyMortgage denominator must be > 0");
        assertError(withStation(new StationPricing(1000, 200, new Ratio(0, 100))), "station.emergencyMortgage numerator");
        RatioConfig r = base.ratios();
        RatioConfig greedy = new RatioConfig(r.upgradeValueShare(), r.bankMortgage(), r.redeemFeeOffBank(),
                r.auctionStart(), r.auctionMinRaise(), r.auctionCap(), r.forcedPurchase(), r.tradeMin(), r.tradeMax(),
                Ratio.percent(110));
        assertError(withRatios(greedy), "ratios.systemAuctionCommission must be <= 100%");
        RatioConfig zeroDen = new RatioConfig(new Ratio(1, 0), r.bankMortgage(), r.redeemFeeOffBank(),
                r.auctionStart(), r.auctionMinRaise(), r.auctionCap(), r.forcedPurchase(), r.tradeMin(), r.tradeMax(),
                r.systemAuctionCommission());
        assertError(withRatios(zeroDen), "ratios.upgradeValueShare denominator must be > 0");
    }

    @Test
    void derivedAmountsAreComputedWithCheckedArithmetic() {
        RatioConfig r = base.ratios();
        RatioConfig hugeCap = new RatioConfig(r.upgradeValueShare(), r.bankMortgage(), r.redeemFeeOffBank(),
                r.auctionStart(), r.auctionMinRaise(), new Ratio(Long.MAX_VALUE, 1), r.forcedPurchase(), r.tradeMin(),
                r.tradeMax(), r.systemAuctionCommission());
        List<String> errors = ConfigValidator.validate(withRatios(hugeCap));
        assertTrue(errors.stream().anyMatch(e -> e.contains("auction cap overflows")), errors.toString());
        RatioConfig bigForced = new RatioConfig(r.upgradeValueShare(), r.bankMortgage(), r.redeemFeeOffBank(),
                r.auctionStart(), r.auctionMinRaise(), r.auctionCap(), new Ratio(1_000_000_000_000L, 1), r.tradeMin(),
                r.tradeMax(), r.systemAuctionCommission());
        assertError(withRatios(bigForced), "forced purchase out of range");
        assertError(withStation(new StationPricing(1000, ConfigValidator.MAX_AMOUNT + 1, Ratio.percent(70))),
                "station.rentPerStation must be in");
        RoomOptions o = base.room();
        assertError(withRoom(new RoomOptions(2, List.of(ConfigValidator.MAX_AMOUNT + 1), o.defaultBoardId(),
                ConfigValidator.MAX_AMOUNT + 1, o.defaultEndMode(), 30, 15)), "room.initialCashOptions must be in");
    }

    @Test
    void largeEquivalentRatiosCompareWithoutOverflow() {
        Ratio a = new Ratio(Long.MAX_VALUE / 2, Long.MAX_VALUE);
        Ratio b = new Ratio(Long.MAX_VALUE - 1, Long.MAX_VALUE / 2);
        assertTrue(ConfigValidator.compare(a, b) < 0);
        assertEquals(0, ConfigValidator.compare(new Ratio(1, 2), new Ratio(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2 * 2)));
        RatioConfig r = base.ratios();
        RatioConfig big = new RatioConfig(r.upgradeValueShare(), r.bankMortgage(), r.redeemFeeOffBank(),
                r.auctionStart(), r.auctionMinRaise(), r.auctionCap(), r.forcedPurchase(), a, b,
                r.systemAuctionCommission());
        List<String> errors = ConfigValidator.validate(withRatios(big));
        assertTrue(errors.stream().noneMatch(e -> e.contains("tradeMin > tradeMax")), errors.toString());
    }

    @Test
    void countsAreRangeCheckedBeforeArithmetic() {
        EconomyConfig e = base.economy();
        EconomyConfig huge = new EconomyConfig(e.startReward(), e.miniGameWinReward(), e.bailCost(), e.eventCashMin(),
                e.eventCashMax(), e.eventCashStep(), e.eventMoveMinSteps(), 3, Integer.MAX_VALUE, e.maxLevel(),
                e.handLimit(), e.initialHandSize(), e.orderNumberMax(), e.offerUnaffordablePurchase(), e.upgradeAfterPurchase());
        List<String> errors = ConfigValidator.validate(new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(),
                base.station(), huge, base.ratios(), base.cardWeights(), base.eventWeights(), base.timing(), base.room()));
        assertTrue(errors.stream().anyMatch(x -> x.contains("economy.dieFaces must be in 1..100")), errors.toString());
        TimingConfig t = base.timing();
        TimingConfig longRoll = new TimingConfig(List.of(15, Integer.MAX_VALUE), t.timeLimitMinutesOptions(),
                t.bankruptcyModeCapMinutes(), t.decisionWindowMs(), t.responseWindowMs(), t.discardWindowMs(),
                t.tradeResponseMs(), t.toothPickMs(), t.auctionDurationMs(), t.auctionExtendMs(), t.auctionMaxMs(),
                t.debtSegmentMs(), t.heartbeatMs(), t.suspectAfterMs(), t.offlineAfterMs(), t.allOfflineCloseMs(),
                t.downtimeBudgetMs(), t.recoveryPrepMs(), t.animDiceMs(), t.animPerStepMs(), t.autoActDelayMs(), t.endWhenAllAway());
        assertError(new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), base.station(), base.economy(),
                base.ratios(), base.cardWeights(), base.eventWeights(), longRoll, base.room()),
                "timing.rollSecondsOptions must be strictly increasing within 1..3600");
    }

    @Test
    void roomMustBeAbleToStartOnEveryBoard() {
        RoomOptions o = base.room();
        assertError(withRoom(new RoomOptions(9, o.initialCashOptions(), o.defaultBoardId(), o.defaultInitialCash(),
                o.defaultEndMode(), o.defaultTimeLimitMinutes(), o.defaultRollSeconds())), "room.minPlayersToStart must be in 2..4");
        assertError(withRoom(new RoomOptions(3, o.initialCashOptions(), o.defaultBoardId(), o.defaultInitialCash(),
                o.defaultEndMode(), o.defaultTimeLimitMinutes(), o.defaultRollSeconds())), "rules-v1 room.minPlayersToStart must be 2");
    }

    @Test
    void rulesV1SpecChecksBoardComposition() {
        String moreStations = TestBoards.LEGACY_30.replaceFirst(" E ", " T ");
        RuleConfig c = new RuleConfig(base.ruleVersion(),
                List.of(RuleConfigs.board(RuleConfigs.BOARD_30, 2, 4, moreStations), base.boards().get(1)),
                base.tiers(), base.station(), base.economy(), base.ratios(), base.cardWeights(), base.eventWeights(),
                base.timing(), base.room());
        assertError(c, "rules-v1 board classic-30 must have 4 STATION, got 5");
        String noDesignation = TestBoards.LEGACY_30.replace("M*", "M");
        assertError(new RuleConfig(base.ruleVersion(),
                List.of(RuleConfigs.board(RuleConfigs.BOARD_30, 2, 4, noDesignation), base.boards().get(1)),
                base.tiers(), base.station(), base.economy(), base.ratios(), base.cardWeights(), base.eventWeights(),
                base.timing(), base.room()), "designated MID auction properties, got 0");
        assertError(new RuleConfig(base.ruleVersion(),
                List.of(RuleConfigs.board(RuleConfigs.BOARD_30, 2, 3, TestBoards.LEGACY_30), base.boards().get(1)),
                base.tiers(), base.station(), base.economy(), base.ratios(), base.cardWeights(), base.eventWeights(),
                base.timing(), base.room()), "rules-v1 board classic-30 capacity must be 2..4");
        assertError(new RuleConfig("rules-v9", base.boards(), base.tiers(), base.station(), base.economy(),
                base.ratios(), base.cardWeights(), base.eventWeights(), base.timing(), base.room()),
                "no rule specification for ruleVersion rules-v9");
    }

    // ---------------------------------------------------------------- helpers

    private static void assertError(RuleConfig c, String fragment) {
        List<String> errors = ConfigValidator.validate(c);
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)), "expected '" + fragment + "' in " + errors);
    }

    private RuleConfig withTier(Tier tier, UnaryOperator<TierPricing> change) {
        List<TierPricing> tiers = base.tiers().stream().map(t -> t.tier() == tier ? change.apply(t) : t).toList();
        return new RuleConfig(base.ruleVersion(), base.boards(), tiers, base.station(), base.economy(), base.ratios(),
                base.cardWeights(), base.eventWeights(), base.timing(), base.room());
    }

    private RuleConfig withStation(StationPricing s) {
        return new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), s, base.economy(), base.ratios(),
                base.cardWeights(), base.eventWeights(), base.timing(), base.room());
    }

    private RuleConfig withRatios(RatioConfig r) {
        return new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), base.station(), base.economy(), r,
                base.cardWeights(), base.eventWeights(), base.timing(), base.room());
    }

    private RuleConfig withCards(Map<CardType, Integer> cards) {
        return new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), base.station(), base.economy(),
                base.ratios(), cards, base.eventWeights(), base.timing(), base.room());
    }

    private RuleConfig withBoards(BoardTemplate extra) {
        List<BoardTemplate> boards = new ArrayList<>(base.boards());
        boards.add(extra);
        return new RuleConfig(base.ruleVersion(), boards, base.tiers(), base.station(), base.economy(), base.ratios(),
                base.cardWeights(), base.eventWeights(), base.timing(), base.room());
    }

    private RuleConfig withRoom(RoomOptions r) {
        return new RuleConfig(base.ruleVersion(), base.boards(), base.tiers(), base.station(), base.economy(),
                base.ratios(), base.cardWeights(), base.eventWeights(), base.timing(), r);
    }
}
