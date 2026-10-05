package com.millionnaire.engine.core.state;

/**
 * 回合状态：回合编号（0 = 尚未开始）、当前玩家、阶段、阶段绑定的 TURN 窗口 ID（AWAITING_FLOW 时为 0）、
 * 本回合是否已领起点奖励（O1）、自动动作任务 ID（0 = 未安排）、continuation（LANDING / AWAITING_FLOW 结束后
 * 的继续位置，如 END_TURN、BEGIN_TURN）以及步内事件衔接记录。
 */
public record TurnState(long turnNo, String currentPlayer, TurnStage stage, long windowId, boolean startRewardGiven,
                        long autoTaskId, String continuation, TurnTrack track) {
    /** 结束落点等待后结束回合。 */
    public static final String END_TURN = "END_TURN";
    /** 覆盖流程返回后开始当前玩家的回合阶段窗口。 */
    public static final String BEGIN_TURN = "BEGIN_TURN";

    public static TurnState notStarted() {
        return new TurnState(0, null, TurnStage.NONE, 0, false, 0, null, TurnTrack.NONE);
    }

    public TurnState withStage(TurnStage value, long window, String next) {
        return new TurnState(turnNo, currentPlayer, value, window, startRewardGiven, autoTaskId, next, track);
    }

    public TurnState withAutoTask(long value) {
        return new TurnState(turnNo, currentPlayer, stage, windowId, startRewardGiven, value, continuation, track);
    }

    public TurnState rewardGiven() {
        return new TurnState(turnNo, currentPlayer, stage, windowId, true, autoTaskId, continuation, track);
    }

    public TurnState withTrack(TurnTrack value) {
        return new TurnState(turnNo, currentPlayer, stage, windowId, startRewardGiven, autoTaskId, continuation, value);
    }
}
