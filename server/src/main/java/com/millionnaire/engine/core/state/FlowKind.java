package com.millionnaire.engine.core.state;

/** 流程/窗口类别（open-decisions "规则补充 v1"、opus-analysis 4.5）。 */
public enum FlowKind {
    /** 回合阶段窗口（投骰前、落点决策、落点后等）；只能位于栈底。 */
    TURN,
    /** 申请类流程：拍卖、交易（随时申请，进入 FIFO 队列，在安全点启动）。 */
    AUCTION, TRADE,
    /** 由落点或用卡同步触发的覆盖流程。 */
    ATTACK, DEBT, MINIGAME, DISCARD,
    /** 响应窗（免租、拒绝购买、房屋保护），可嵌套在回合窗口或覆盖流程之上。 */
    RESPONSE;

    /** 是否走申请队列。 */
    public boolean queued() {
        return this == AUCTION || this == TRADE;
    }

    /** 是否为覆盖流程（非 TURN、非 RESPONSE）。 */
    public boolean overlay() {
        return this != TURN && this != RESPONSE;
    }
}
