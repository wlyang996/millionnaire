package com.millionnaire.engine.config;

import com.millionnaire.engine.money.Ratio;

/** 各类价格比例；取整方向见 {@link Pricing}。 */
public record RatioConfig(
        Ratio upgradeValueShare,
        Ratio bankMortgage,
        Ratio redeemFeeOffBank,
        Ratio auctionStart,
        Ratio auctionMinRaise,
        Ratio auctionCap,
        Ratio forcedPurchase,
        Ratio tradeMin,
        Ratio tradeMax,
        Ratio systemAuctionCommission) {
}
