package com.millionnaire.engine.core.state;

/**
 * 类型化续接（M2 P1，取代 M1 的字符串 continuation）：回合等待（LANDING 决策窗口或 AWAITING_FLOW 覆盖流程）结束后从哪里继续。
 * 每个续接都绑定回合编号；继续落点的续接还绑定落点编号。演化与校验逐项核对（{@link #allowedFor}）。
 */
public sealed interface Continuation {
    /** 绑定的回合编号。 */
    long turnNo();

    /** 覆盖流程返回后开始当前玩家的回合阶段窗口（安全点启动的排队流程）。 */
    record BeginTurn(long turnNo) implements Continuation {
    }

    /** 结束回合（无落点推进器的普通落点等待，保留给测试与 M3 之前的占位落点）。 */
    record EndTurn(long turnNo) implements Continuation {
    }

    /** 继续第 landingId 次落点的推进器（落点决策窗口结束、或落点触发的债务流程返回后）。 */
    record ResumeLanding(long turnNo, long landingId) implements Continuation {
    }

    /**
     * 各阶段允许的续接：AWAITING_FLOW 可为 BeginTurn 或 ResumeLanding；LANDING 可为 ResumeLanding 或 EndTurn；
     * 其他阶段必须为 null。另须绑定当前回合，ResumeLanding 还须指向当前落点。
     */
    static boolean allowedFor(TurnStage stage, Continuation c, TurnState turn) {
        if (stage == TurnStage.AWAITING_FLOW || stage == TurnStage.LANDING) {
            if (c == null || c.turnNo() != turn.turnNo()) {
                return false;
            }
            return switch (c) {
                case BeginTurn b -> stage == TurnStage.AWAITING_FLOW && turn.landing() == null;
                case EndTurn e -> stage == TurnStage.LANDING && turn.landing() == null;
                case ResumeLanding r -> turn.landing() != null && turn.landing().landingId() == r.landingId();
            };
        }
        return c == null;
    }
}
