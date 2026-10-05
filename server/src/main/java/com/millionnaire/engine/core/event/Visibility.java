package com.millionnaire.engine.core.event;

/** 事件可见性：决定投影下发范围，不影响服务端回放。 */
public enum Visibility {
    /** 房间内所有人。 */
    PUBLIC,
    /** 仅 {@link Event#recipient()} 指定的玩家。 */
    PRIVATE,
    /** 仅服务端（随机状态、计时内部细节、输入游标）。 */
    SERVER_ONLY
}
