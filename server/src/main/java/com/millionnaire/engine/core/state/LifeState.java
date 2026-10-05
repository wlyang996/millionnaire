package com.millionnaire.engine.core.state;

/** 玩家存续状态；轮转跳过非 ALIVE 玩家（破产与认输在 M2 实现）。 */
public enum LifeState {
    ALIVE, BANKRUPT, SURRENDERED
}
