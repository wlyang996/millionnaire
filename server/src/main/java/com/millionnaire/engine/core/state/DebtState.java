package com.millionnaire.engine.core.state;

/**
 * 进行中的债务（M2 P5，至多一个）：债务编号、债务人、债权人（null = 系统）、欠款、原因、当前段（1 或 2）、
 * 第二段弹窗是否已确认"继续"、对应的 DEBT 覆盖窗口。债务成立即锁定处理路径（手动抵押），之后的控制 / 连接变化不改判。
 */
public record DebtState(long debtId, String debtor, String creditor, long amount, String cause, int segment,
                        boolean continued, long windowId) {
    public DebtState segment(int value, long window) {
        return new DebtState(debtId, debtor, creditor, amount, cause, value, false, window);
    }

    public DebtState continuedNow() {
        return new DebtState(debtId, debtor, creditor, amount, cause, segment, true, windowId);
    }
}
