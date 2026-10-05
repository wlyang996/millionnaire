package com.millionnaire.engine.config;

import com.millionnaire.engine.money.Ratio;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 普通地产档位：原价、每级升级费、各等级租金（下标 = 等级）、应急抵押比例（亦用于破产变现）。 */
public record TierPricing(Tier tier, long basePrice, long upgradeCost, List<Long> rents, Ratio emergencyMortgage) {
    public TierPricing {
        rents = Immutable.list(rents);
    }
}
