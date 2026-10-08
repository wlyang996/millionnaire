package com.millionnaire.engine.config;

/** 格子类型（requirements 第 3 节）。 */
public enum TileType {
    START, PROPERTY, STATION, EVENT, BANK, JAIL, REST, GAME_ZONE,
    /** 幸运格（用户 2026-10-08）：停下时从本棋盘奖池随机抽一项并自动生效，不需点卡。奖池见 {@link BoardTemplate#fixedEvents()}。 */
    FIXED_EVENT
}
