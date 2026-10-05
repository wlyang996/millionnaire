package com.millionnaire.engine.core.state;

/**
 * 一步之内的事件衔接记录，用于在演化时核对规则派生值（T3）：已掷未走的点数、待落点的格子、是否应发起点奖励、
 * 是否刚落到监狱、待处理的判定点数、是否刚付保释金。步与步之间必须为空（{@link #NONE}）。
 */
public record TurnTrack(int pendingDie, int pendingLanding, boolean rewardDue, boolean jailLanding, int jailRoll,
                        boolean bailPaid) {
    public static final TurnTrack NONE = new TurnTrack(0, -1, false, false, 0, false);

    public TurnTrack die(int value) {
        return new TurnTrack(value, pendingLanding, rewardDue, jailLanding, jailRoll, bailPaid);
    }

    public TurnTrack moved(int landing, boolean reward) {
        return new TurnTrack(0, landing, reward, jailLanding, jailRoll, bailPaid);
    }

    public TurnTrack rewarded() {
        return new TurnTrack(pendingDie, pendingLanding, false, jailLanding, jailRoll, bailPaid);
    }

    public TurnTrack landed(boolean jail) {
        return new TurnTrack(pendingDie, -1, rewardDue, jail, jailRoll, bailPaid);
    }

    public TurnTrack jailed() {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, false, jailRoll, bailPaid);
    }

    public TurnTrack judged(int value) {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, value, bailPaid);
    }

    public TurnTrack bail(boolean value) {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, jailRoll, value);
    }
}
