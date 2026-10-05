package com.millionnaire.engine.ledger;

/** 账本拒绝操作（不平衡、透支、动用冻结资金）或不变量被破坏。 */
public final class LedgerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LedgerException(String message) {
        super(message);
    }
}
