package com.millionnaire.engine.config;

import com.millionnaire.engine.money.Money;
import com.millionnaire.engine.money.Rounding;

/**
 * 字段级价格与取整表（O25）。已定方向：起拍、最低加价、交易下限向上；封顶、强购、交易上限、系统拍卖分成向下。
 * 规则未定义方向的字段（标准价值、抵押额、赎回手续费）要求整除，由 {@link ConfigValidator} 在加载时保证。
 */
public final class Pricing {
    private final RuleConfig config;

    public Pricing(RuleConfig config) {
        this.config = config;
    }

    /** 标准价值 = 原价 + 等级 × 每级升级费 × 50%（D#2：按标准升级费，与是否付费无关）。 */
    public long standardValue(Tier tier, int level) {
        TierPricing p = config.tier(tier);
        if (level < 0 || level > config.economy().maxLevel()) {
            throw new IllegalArgumentException("level out of range: " + level);
        }
        long upgrades = Money.mul(level, p.upgradeCost());
        return Money.add(p.basePrice(), config.ratios().upgradeValueShare().applyExact(upgrades));
    }

    public long stationValue() {
        return config.station().price();
    }

    public long rent(Tier tier, int level) {
        return config.tier(tier).rents().get(level);
    }

    /** 车站租金 = 所有者计租车站数 × 每站租金（已抵押车站不计数，D#3）。 */
    public long stationRent(int countedStations) {
        return Money.mul(countedStations, config.station().rentPerStation());
    }

    public long auctionStart(long basis) {
        return config.ratios().auctionStart().apply(basis, Rounding.CEIL);
    }

    public long auctionMinRaise(long basis) {
        return config.ratios().auctionMinRaise().apply(basis, Rounding.CEIL);
    }

    public long auctionCap(long basis) {
        return config.ratios().auctionCap().apply(basis, Rounding.FLOOR);
    }

    public long forcedPurchasePrice(long standardValue) {
        return config.ratios().forcedPurchase().apply(standardValue, Rounding.FLOOR);
    }

    public long tradeMin(long standardValue) {
        return config.ratios().tradeMin().apply(standardValue, Rounding.CEIL);
    }

    public long tradeMax(long standardValue) {
        return config.ratios().tradeMax().apply(standardValue, Rounding.FLOOR);
    }

    /** 系统拍卖发起人分成，向下取整，余量归系统。 */
    public long systemAuctionCommission(long price) {
        return config.ratios().systemAuctionCommission().apply(price, Rounding.FLOOR);
    }

    public long bankMortgage(long basePrice) {
        return config.ratios().bankMortgage().applyExact(basePrice);
    }

    public long emergencyMortgage(Tier tier) {
        TierPricing p = config.tier(tier);
        return p.emergencyMortgage().applyExact(p.basePrice());
    }

    public long stationEmergencyMortgage() {
        return config.station().emergencyMortgage().applyExact(config.station().price());
    }

    /** 破产变现按应急抵押比例（O5+O6 产品决定）。 */
    public long liquidationValue(Tier tier) {
        return emergencyMortgage(tier);
    }

    public long stationLiquidationValue() {
        return stationEmergencyMortgage();
    }

    /** 赎回手续费：银行内免费，其他位置按本金 10%。 */
    public long redeemFee(long principal, boolean atBank) {
        return atBank ? 0 : config.ratios().redeemFeeOffBank().applyExact(principal);
    }
}
