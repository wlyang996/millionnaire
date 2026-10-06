package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 一步之内的事件衔接记录，用于在演化时核对规则派生值（T3）：已掷未走的点数、待落点的格子、是否应发起点奖励、
 * 是否刚落到监狱、待处理的判定点数、是否刚付保释金、开局抽数队列，以及 M2 经济衔接：本步刚落下的格子（供落点推进器开始）、
 * 已成立未付的租金、进行中的清算事务（玩家 + 结果 + 债务，E3）、正在处理的认输批次。步与步之间必须为空（{@link #NONE}）。
 */
public record TurnTrack(int pendingDie, int pendingLanding, boolean rewardDue, boolean jailLanding, int jailRoll,
                        boolean bailPaid, List<String> drawQueue, int landedTile, long pendingCharge, Liquidation liquidating,
                        long openBatch) {
    public static final TurnTrack NONE = new TurnTrack(0, -1, false, false, 0, false, List.of(), -1, 0, null, 0);

    public TurnTrack {
        drawQueue = Immutable.list(drawQueue);
    }

    /** 开局抽数轮次：本轮依次应抽数的玩家（首抽为全体座位顺序，重抽为各同分组按名次与座位顺序展开）。 */
    public TurnTrack draws(List<String> value) {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, jailRoll, bailPaid, value, landedTile,
                pendingCharge, liquidating, openBatch);
    }

    public TurnTrack die(int value) {
        return new TurnTrack(value, pendingLanding, rewardDue, jailLanding, jailRoll, bailPaid, drawQueue, landedTile,
                pendingCharge, liquidating, openBatch);
    }

    public TurnTrack moved(int landing, boolean reward) {
        return new TurnTrack(0, landing, reward, jailLanding, jailRoll, bailPaid, drawQueue, landedTile, pendingCharge,
                liquidating, openBatch);
    }

    public TurnTrack rewarded() {
        return new TurnTrack(pendingDie, pendingLanding, false, jailLanding, jailRoll, bailPaid, drawQueue, landedTile,
                pendingCharge, liquidating, openBatch);
    }

    /** 落下：jail 表示落在监狱（随后必须入狱）；tile 为落点（供落点推进器开始，监狱为 -1）。 */
    public TurnTrack landed(boolean jail, int tile) {
        return new TurnTrack(pendingDie, -1, rewardDue, jail, jailRoll, bailPaid, drawQueue, tile, pendingCharge,
                liquidating, openBatch);
    }

    public TurnTrack jailed() {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, false, jailRoll, bailPaid, drawQueue, landedTile,
                pendingCharge, liquidating, openBatch);
    }

    public TurnTrack judged(int value) {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, value, bailPaid, drawQueue, landedTile,
                pendingCharge, liquidating, openBatch);
    }

    public TurnTrack bail(boolean value) {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, jailRoll, value, drawQueue, landedTile,
                pendingCharge, liquidating, openBatch);
    }

    /** 落点推进器已接手本步落点。 */
    public TurnTrack landingTaken() {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, jailRoll, bailPaid, drawQueue, -1,
                pendingCharge, liquidating, openBatch);
    }

    public TurnTrack charge(long amount) {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, jailRoll, bailPaid, drawQueue, landedTile,
                amount, liquidating, openBatch);
    }

    public TurnTrack liquidating(Liquidation value) {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, jailRoll, bailPaid, drawQueue, landedTile,
                pendingCharge, value, openBatch);
    }

    public TurnTrack batch(long value) {
        return new TurnTrack(pendingDie, pendingLanding, rewardDue, jailLanding, jailRoll, bailPaid, drawQueue, landedTile,
                pendingCharge, liquidating, value);
    }
}
