package com.millionnaire.engine.config;

import com.millionnaire.engine.testkit.TestBoards;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** opus-analysis 4.11 价格与取整表逐项核对。 */
class PricingTest {
    private final Pricing p = new Pricing(TestBoards.legacyV1());

    @Test
    void standardValues() {
        assertRow(Tier.LOW, 500, 650, 800, 950);
        assertRow(Tier.MID, 1000, 1300, 1600, 1900);
        assertRow(Tier.HIGH, 1500, 1950, 2400, 2850);
        assertEquals(1000, p.stationValue());
    }

    @Test
    void auctionStartStepCapForcedPurchaseAndTradeRange() {
        long[][] table = {
                // std, start, step, cap, forced, tradeMin, tradeMax
                {500, 250, 50, 1250, 750, 250, 1250}, {650, 325, 65, 1625, 975, 325, 1625},
                {800, 400, 80, 2000, 1200, 400, 2000}, {950, 475, 95, 2375, 1425, 475, 2375},
                {1000, 500, 100, 2500, 1500, 500, 2500}, {1300, 650, 130, 3250, 1950, 650, 3250},
                {1600, 800, 160, 4000, 2400, 800, 4000}, {1900, 950, 190, 4750, 2850, 950, 4750},
                {1500, 750, 150, 3750, 2250, 750, 3750}, {1950, 975, 195, 4875, 2925, 975, 4875},
                {2400, 1200, 240, 6000, 3600, 1200, 6000}, {2850, 1425, 285, 7125, 4275, 1425, 7125},
        };
        for (long[] row : table) {
            long std = row[0];
            assertEquals(row[1], p.auctionStart(std), "start " + std);
            assertEquals(row[2], p.auctionMinRaise(std), "step " + std);
            assertEquals(row[3], p.auctionCap(std), "cap " + std);
            assertEquals(row[4], p.forcedPurchasePrice(std), "forced " + std);
            assertEquals(row[5], p.tradeMin(std), "tradeMin " + std);
            assertEquals(row[6], p.tradeMax(std), "tradeMax " + std);
        }
    }

    @Test
    void roundingDirectionsOnNonIntegerBases() {
        // 非整数结果时：起拍/最低加价/交易下限向上，封顶/强购/交易上限向下
        assertEquals(26, p.auctionStart(51));
        assertEquals(6, p.auctionMinRaise(51));
        assertEquals(127, p.auctionCap(51));
        assertEquals(76, p.forcedPurchasePrice(51));
        assertEquals(26, p.tradeMin(51));
        assertEquals(127, p.tradeMax(51));
    }

    @Test
    void systemAuctionCommissionFloorsRemainderToSystem() {
        assertEquals(123, p.systemAuctionCommission(1239));
        assertEquals(0, p.systemAuctionCommission(9));
        assertEquals(250, p.systemAuctionCommission(2500));
    }

    @Test
    void mortgageRedeemAndLiquidation() {
        assertEquals(500, p.bankMortgage(500));
        assertEquals(1000, p.bankMortgage(1000));
        assertEquals(1500, p.bankMortgage(1500));
        assertEquals(400, p.emergencyMortgage(Tier.LOW));
        assertEquals(700, p.emergencyMortgage(Tier.MID));
        assertEquals(900, p.emergencyMortgage(Tier.HIGH));
        assertEquals(700, p.stationEmergencyMortgage());
        assertEquals(50, p.redeemFee(500, false));
        assertEquals(150, p.redeemFee(1500, false));
        assertEquals(40, p.redeemFee(400, false));
        assertEquals(90, p.redeemFee(900, false));
        assertEquals(70, p.redeemFee(700, false));
        assertEquals(0, p.redeemFee(900, true));
        // 破产变现改按应急比例（O5+O6）
        assertEquals(400, p.liquidationValue(Tier.LOW));
        assertEquals(700, p.stationLiquidationValue());
    }

    @Test
    void rents() {
        assertEquals(100, p.rent(Tier.LOW, 0));
        assertEquals(1400, p.rent(Tier.MID, 3));
        assertEquals(2100, p.rent(Tier.HIGH, 3));
        assertEquals(1200, p.stationRent(6));
        assertEquals(0, p.stationRent(0));
    }

    private void assertRow(Tier tier, long... values) {
        for (int level = 0; level < values.length; level++) {
            assertEquals(values[level], p.standardValue(tier, level), tier + " level " + level);
        }
    }
}
