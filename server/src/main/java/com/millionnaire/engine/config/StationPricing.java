package com.millionnaire.engine.config;

import com.millionnaire.engine.money.Ratio;

/** 车站：原价（即标准价值）、每站租金、应急抵押比例。 */
public record StationPricing(long price, long rentPerStation, Ratio emergencyMortgage) {
}
