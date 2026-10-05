package com.millionnaire.engine.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.money.Ratio;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * 属性式检查（N1）：对一批随机变异的配置，"校验通过 ⇒ 内容哈希与创建引擎都不抛异常"；
 * 并且校验本身对任何变异都不抛异常（只返回错误列表）。
 */
class ConfigPropertyTest {
    private static final long[] EXTREMES = {Long.MIN_VALUE, -1, 0, 1, 2, 3, 7, 50, 100, 999, 1000,
            1_000_000_000_000L, 1_000_000_000_001L, Long.MAX_VALUE};

    @Test
    void validatedConfigsAlwaysHashAndBuildAnEngine() {
        SplittableRandom rnd = new SplittableRandom(20261005);
        RuleConfig base = RuleConfigs.defaultV1();
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < 3000; i++) {
            RuleConfig c = mutate(base, rnd);
            List<String> errors = assertDoesNotThrow(() -> ConfigValidator.validate(c), "validate must not throw");
            if (errors.isEmpty()) {
                passed++;
                assertDoesNotThrow(c::contentHash, "valid config must encode: " + c);
                assertDoesNotThrow(() -> new Engine<>(c, SessionDomain.INSTANCE).create("r", 1, 0));
            } else {
                failed++;
            }
        }
        assertTrue(passed > 100 && failed > 100, "mutations must exercise both outcomes: " + passed + "/" + failed);
    }

    private static RuleConfig mutate(RuleConfig b, SplittableRandom rnd) {
        RoomOptions room = b.room();
        RatioConfig ratios = b.ratios();
        EconomyConfig eco = b.economy();
        List<TierPricing> tiers = b.tiers();
        StationPricing station = b.station();
        switch (rnd.nextInt(6)) {
            case 0 -> {
                List<Long> opts = new ArrayList<>(Arrays.asList(2000L, 3000L, 5000L));
                int k = rnd.nextInt(4);
                if (k == 0) {
                    opts.add(null);
                } else if (k == 1) {
                    opts.add(opts.get(rnd.nextInt(opts.size())));
                } else if (k == 2) {
                    opts.add(pick(rnd));
                } else {
                    opts.add(4000L);
                }
                room = new RoomOptions(room.minPlayersToStart(), opts, room.defaultBoardId(), room.defaultInitialCash(),
                        room.defaultEndMode(), room.defaultTimeLimitMinutes(), room.defaultRollSeconds());
            }
            case 1 -> {
                Ratio r = rnd.nextBoolean() ? Ratio.of(pick(rnd), pick(rnd)) : Ratio.of(1 + rnd.nextInt(400), 100);
                ratios = switch (rnd.nextInt(4)) {
                    case 0 -> new RatioConfig(ratios.upgradeValueShare(), ratios.bankMortgage(), ratios.redeemFeeOffBank(),
                            r, ratios.auctionMinRaise(), ratios.auctionCap(), ratios.forcedPurchase(), ratios.tradeMin(),
                            ratios.tradeMax(), ratios.systemAuctionCommission());
                    case 1 -> new RatioConfig(ratios.upgradeValueShare(), ratios.bankMortgage(), ratios.redeemFeeOffBank(),
                            ratios.auctionStart(), ratios.auctionMinRaise(), r, ratios.forcedPurchase(), ratios.tradeMin(),
                            ratios.tradeMax(), ratios.systemAuctionCommission());
                    case 2 -> new RatioConfig(ratios.upgradeValueShare(), ratios.bankMortgage(), ratios.redeemFeeOffBank(),
                            ratios.auctionStart(), ratios.auctionMinRaise(), ratios.auctionCap(), ratios.forcedPurchase(),
                            r, ratios.tradeMax(), ratios.systemAuctionCommission());
                    default -> new RatioConfig(ratios.upgradeValueShare(), ratios.bankMortgage(), ratios.redeemFeeOffBank(),
                            ratios.auctionStart(), ratios.auctionMinRaise(), ratios.auctionCap(), ratios.forcedPurchase(),
                            ratios.tradeMin(), ratios.tradeMax(), r);
                };
            }
            case 2 -> eco = new EconomyConfig(eco.startReward(), eco.miniGameWinReward(), eco.bailCost(),
                    rnd.nextBoolean() ? pick(rnd) : 100, rnd.nextBoolean() ? pick(rnd) : 500,
                    rnd.nextBoolean() ? pick(rnd) : 50, eco.eventMoveMinSteps(), eco.eventMoveMaxSteps(), eco.dieFaces(),
                    eco.maxLevel(), eco.handLimit(), eco.initialHandSize(), eco.orderNumberMax());
            case 3 -> {
                List<TierPricing> t = new ArrayList<>(tiers);
                TierPricing old = t.get(rnd.nextInt(t.size()));
                TierPricing neu = new TierPricing(old.tier(), rnd.nextBoolean() ? pick(rnd) : old.basePrice(),
                        rnd.nextBoolean() ? pick(rnd) : old.upgradeCost(), old.rents(),
                        rnd.nextBoolean() ? Ratio.of(pick(rnd), pick(rnd)) : old.emergencyMortgage());
                t.set(t.indexOf(old), neu);
                tiers = t;
            }
            case 4 -> station = new StationPricing(rnd.nextBoolean() ? pick(rnd) : 1000, rnd.nextBoolean() ? pick(rnd) : 200,
                    rnd.nextBoolean() ? Ratio.of(1 + rnd.nextInt(150), 100) : station.emergencyMortgage());
            default -> {
                // 不变异：基线配置必须通过
            }
        }
        return new RuleConfig(b.ruleVersion(), b.boards(), tiers, station, eco, ratios, b.cardWeights(),
                b.eventWeights(), b.timing(), room);
    }

    private static long pick(SplittableRandom rnd) {
        return rnd.nextInt(3) == 0 ? rnd.nextLong(1, 100_000) : EXTREMES[rnd.nextInt(EXTREMES.length)];
    }
}
