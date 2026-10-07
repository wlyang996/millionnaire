package com.millionnaire.engine.random;

/**
 * 随机抽取点（opus-analysis 4.7）。每次抽取都标注调用点，写入事件日志，便于审计与回放比对。
 */
public enum DrawPoint {
    /** R1 开局数字 1..100（同分组内重抽）。 */
    ORDER_NUMBER("R1"),
    /** R2 开局手牌（加权）。 */
    INITIAL_CARD("R2"),
    /** R3 移动骰。 */
    MOVE_DIE("R3"),
    /** R4 出狱判定骰。 */
    JAIL_DIE("R4"),
    /** R5 事件类型（加权）。 */
    EVENT_KIND("R5"),
    /** R6 事件金额。 */
    EVENT_CASH("R6"),
    /** R7 事件道具。 */
    EVENT_CARD("R7"),
    /** R8 事件位移方向。 */
    EVENT_MOVE_DIRECTION("R8a"),
    /** R8 事件位移距离。 */
    EVENT_MOVE_DISTANCE("R8b"),
    /** R8c 事件"去车站"的目标车站（2026-10-08）。 */
    EVENT_STATION("R8c"),
    /** R7b 事件"加盖 / 降级"的目标地产（2026-10-08）。 */
    EVENT_TARGET("R7b"),
    /** R10 危险牙。 */
    DANGER_TOOTH("R10"),
    /** R11 代选牙。 */
    AUTO_TOOTH("R11");

    private final String code;

    DrawPoint(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
