package com.millionnaire.engine.core.state;

/**
 * 回合阶段（与窗口栈分开）：NONE 为回合之间；JAIL_DECISION、PRE_ROLL、LANDING 各绑定一个栈底 TURN 窗口；
 * AWAITING_FLOW 表示回合存在、没有 TURN 窗口、正在等待覆盖流程返回，返回后按 continuation 继续。
 */
public enum TurnStage {
    NONE, JAIL_DECISION, PRE_ROLL, LANDING, AWAITING_FLOW;

    /** 是否绑定栈底 TURN 窗口。 */
    public boolean windowed() {
        return this == JAIL_DECISION || this == PRE_ROLL || this == LANDING;
    }

    /** 是否尚未投骰（全局到时时直接结束）。 */
    public boolean beforeRoll() {
        return this == JAIL_DECISION || this == PRE_ROLL;
    }
}
