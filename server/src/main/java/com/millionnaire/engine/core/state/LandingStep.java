package com.millionnaire.engine.core.state;

/**
 * 落点推进器的子阶段（opus-analysis 4.5 LandingStage 的 M2 子集）。只有需要等待的子阶段会跨步保存：
 * BUY / UPGRADE / BANK 绑定 LANDING 决策窗口，DEBT 表示回合处于 AWAITING_FLOW、等待债务流程返回。
 * BUILD_CARD 为 M4 建造卡保留（买后付费升级之后、同回合免费升一级），M2 不可达。
 */
public enum LandingStep {
    /** 无主地产或车站：买 / 放弃（指定拍卖地的"发起拍卖"留给 M5）。 */
    BUY,
    /** 自己的未抵押普通地产：付费升一级 / 跳过（含买后立即升级）。 */
    UPGRADE,
    /** M4 保留：建造卡免费升一级。 */
    BUILD_CARD,
    /** 银行格：100% 抵押、免费赎回，可多次操作，结束或到时离开。 */
    BANK,
    /** 必须缴租（只作为 {@link LandingState#next()} 出现：费用成立后清除；不跨步保存为当前步骤）。 */
    RENT,
    /** 租金未能付清：等待债务流程（应急抵押 / 破产）。 */
    DEBT,
    /** M4 响应完成后消费当前任务，按结果表生成后继费用任务。 */
    RESPONSE,
    /** M3b 抽取/奖励/罚款/弃牌来源任务。 */
    EVENT,
    /** M3b/M3c 消费位移来源，接续同一 MoveChain 中的下一落点。 */
    MOVE,
    REWARD, FINE, CARD, DISCARD, TO_JAIL
}
