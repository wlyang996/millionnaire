package com.millionnaire.engine.core.state;

/**
 * 可拥有的格子（普通地产或车站）：所有者（null = 无主）、等级 0..3（车站恒 0）、是否抵押、抵押本金（赎回本金 =
 * 该次抵押实得金额，未抵押为 0）、占用流程（拍卖 / 交易锁定，M5；M2 恒为 null）。
 */
public record OwnableState(int tile, String owner, int level, boolean mortgaged, long principal, String lockedBy) {
    public static OwnableState unowned(int tile) {
        return new OwnableState(tile, null, 0, false, 0, null);
    }

    public OwnableState owned(String value) {
        return new OwnableState(tile, value, level, mortgaged, principal, lockedBy);
    }

    public OwnableState level(int value) {
        return new OwnableState(tile, owner, value, mortgaged, principal, lockedBy);
    }

    public OwnableState mortgage(long value) {
        return new OwnableState(tile, owner, level, true, value, lockedBy);
    }

    public OwnableState redeemed() {
        return new OwnableState(tile, owner, level, false, 0, lockedBy);
    }
}
