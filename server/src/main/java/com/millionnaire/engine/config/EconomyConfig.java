package com.millionnaire.engine.config;

/** 与地产无关的固定金额与计数。 */
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
        int orderNumberMax) {
}
