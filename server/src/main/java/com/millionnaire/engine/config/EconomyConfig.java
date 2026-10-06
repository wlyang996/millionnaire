package com.millionnaire.engine.config;

/**
 * 与地产无关的固定金额与计数。
 * offerUnaffordablePurchase：落到无主地产但现金不足时，是否仍开购买窗口（界面显示价格、按钮置灰，只能放弃）。
 * upgradeAfterPurchase：买下地产的同一次落点里，是否紧接着开升级窗口（正式规则为否：下次落到自己的地才能升级）。
 */
public record EconomyConfig(
        long startReward,
        long miniGameWinReward,
        long bailCost,
        long eventCashMin,
        long eventCashMax,
        long eventCashStep,
        int eventMoveMinSteps,
        int eventMoveMaxSteps,
        int dieFaces,
        int maxLevel,
        int handLimit,
        int initialHandSize,
        int orderNumberMax,
        boolean offerUnaffordablePurchase,
        boolean upgradeAfterPurchase) {
}
