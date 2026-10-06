package com.millionnaire.engine.core.state;

/**
 * 步内清算事务（M2b E3）：被清算的玩家、清算结果（破产 / 认输）与关联债务（0 = 无欠款的认输清算）。
 * 其后的淘汰、资产变现 / 回收、偿债与现金回收都必须与这三项一致。
 */
public record Liquidation(String playerId, LifeState outcome, long debtId) {
}
