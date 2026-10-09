package com.millionnaire.engine.core.state;

/**
 * 回合状态：回合编号（0 = 尚未开始）、当前玩家、阶段、阶段绑定的 TURN 窗口 ID（AWAITING_FLOW 时为 0）、
 * 本回合是否已领起点奖励（O1）、自动动作任务 ID（0 = 未安排）、类型化续接（LANDING / AWAITING_FLOW 结束后的继续位置，
 * 见 {@link Continuation}）、notBefore（AWAITING_FLOW 时继续位置的窗口最早开放时刻，含尚未播完的移动动画；其他阶段为 0）、
 * 进行中的落点（{@link LandingState}，无则 null）、已分配的最后一个落点编号（整局单调）、步内事件衔接记录，
 * 以及当前轮数 round（行动顺序绕回一圈记一轮，第一个回合为第 1 轮）与本轮租金倍率 rentPercent（百分比，回合开始时按配置算好）。
 */
public record TurnState(long turnNo, String currentPlayer, TurnStage stage, long windowId, boolean startRewardGiven,
                        long autoTaskId, Continuation continuation, long notBefore, LandingState landing,
                        long lastLandingId, TurnTrack track, MoveChain chain, long lastChainId, long lastDebtId,
                        long round, int rentPercent, int allAwayTurns) {

    public TurnState(long turnNo, String currentPlayer, TurnStage stage, long windowId, boolean startRewardGiven,
                     long autoTaskId, Continuation continuation, long notBefore, LandingState landing, long lastLandingId,
                     TurnTrack track, MoveChain chain, long lastChainId, long lastDebtId, long round, int rentPercent) {
        this(turnNo, currentPlayer, stage, windowId, startRewardGiven, autoTaskId, continuation, notBefore, landing,
                lastLandingId, track, chain, lastChainId, lastDebtId, round, rentPercent, 0);
    }

    public TurnState withAllAwayTurns(int value) {
        return new TurnState(turnNo, currentPlayer, stage, windowId, startRewardGiven, autoTaskId, continuation, notBefore,
                landing, lastLandingId, track, chain, lastChainId, lastDebtId, round, rentPercent, value);
    }

    public static TurnState notStarted() {
        return new TurnState(0, null, TurnStage.NONE, 0, false, 0, null, 0, null, 0, TurnTrack.NONE, null, 0, 0, 0, 100);
    }

    /** 新回合（落点编号计数延续）。 */
    public TurnState next(long no, String player, long newRound, int newRentPercent) {
        return new TurnState(no, player, TurnStage.NONE, 0, false, 0, null, 0, null, lastLandingId, TurnTrack.NONE, null, lastChainId, lastDebtId,
                newRound, newRentPercent, allAwayTurns);
    }

    /** 回合结束：回到 NONE，清空窗口、续接与落点。 */
    public TurnState ended() {
        return new TurnState(turnNo, currentPlayer, TurnStage.NONE, 0, startRewardGiven, 0, null, 0, null, lastLandingId,
                TurnTrack.NONE, null, lastChainId, lastDebtId, round, rentPercent, allAwayTurns);
    }

    public TurnState withStage(TurnStage value, long window, Continuation next, long earliest) {
        return new TurnState(turnNo, currentPlayer, value, window, startRewardGiven, autoTaskId, next, earliest, landing,
                lastLandingId, track, chain, lastChainId, lastDebtId, round, rentPercent, allAwayTurns);
    }

    public TurnState withAutoTask(long value) {
        return new TurnState(turnNo, currentPlayer, stage, windowId, startRewardGiven, value, continuation, notBefore, landing,
                lastLandingId, track, chain, lastChainId, lastDebtId, round, rentPercent, allAwayTurns);
    }

    public TurnState rewardGiven() {
        return new TurnState(turnNo, currentPlayer, stage, windowId, true, autoTaskId, continuation, notBefore, landing,
                lastLandingId, track, chain, lastChainId, lastDebtId, round, rentPercent, allAwayTurns);
    }

    public TurnState withTrack(TurnTrack value) {
        return new TurnState(turnNo, currentPlayer, stage, windowId, startRewardGiven, autoTaskId, continuation, notBefore,
                landing, lastLandingId, value, chain, lastChainId, lastDebtId, round, rentPercent, allAwayTurns);
    }

    /** 设置或清除当前落点；新落点的编号成为"最后分配的落点编号"。 */
    public TurnState withLanding(LandingState value) {
        long last = value != null && value.landingId() > lastLandingId ? value.landingId() : lastLandingId;
        return new TurnState(turnNo, currentPlayer, stage, windowId, startRewardGiven, autoTaskId, continuation, notBefore,
                value, last, track, chain, lastChainId, lastDebtId, round, rentPercent, allAwayTurns);
    }
    public TurnState withChain(MoveChain value) {
        return new TurnState(turnNo, currentPlayer, stage, windowId, startRewardGiven, autoTaskId, continuation,
                notBefore, landing, lastLandingId, track, value,
                value == null ? lastChainId : Math.max(lastChainId, value.chainId()), lastDebtId, round, rentPercent, allAwayTurns);
    }
    public TurnState debtAllocated(long id) {
        return new TurnState(turnNo, currentPlayer, stage, windowId, startRewardGiven, autoTaskId, continuation,
                notBefore, landing, lastLandingId, track, chain, lastChainId, id, round, rentPercent, allAwayTurns);
    }
}
