package com.millionnaire.engine.config;

/** 格子类型（requirements 第 3 节）。 */
public enum TileType {
    START, PROPERTY, STATION, EVENT, BANK, JAIL, REST, GAME_ZONE,
    /** 固定事件格（用户 2026-10-08）：每格效果固定、写在格子上，停下立即生效，不抽卡。效果见 {@link BoardTemplate#fixedEvents()}。 */
    FIXED_EVENT
}
