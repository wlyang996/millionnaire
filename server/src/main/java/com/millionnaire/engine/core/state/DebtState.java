package com.millionnaire.engine.core.state;
/** 独立债务编号、费用凭据、锁定路径及两段窗口，直接破产也记录成立事件。 */
public record DebtState(long debtId, String debtor, String creditor, long amount, String cause, int segment,
                        boolean continued, long windowId, FeeSource source, DebtPath path) {
    public DebtState segment(int value, long window) {
        return new DebtState(debtId, debtor, creditor, amount, cause, value, false, window, source, path);
    }
    public DebtState continuedNow() {
        return new DebtState(debtId, debtor, creditor, amount, cause, segment, true, windowId, source, path);
    }
}
