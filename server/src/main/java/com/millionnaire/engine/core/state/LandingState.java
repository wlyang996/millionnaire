package com.millionnaire.engine.core.state;

/**
 * 一次落点结算的推进状态（M2 P1，M2b E1 加强）：落点编号（整局单调递增，绑定续接）、格子、当前等待的子阶段、
 * 待付款金额（DEBT 时为欠款）、效果标识 bought（本次落点买下，允许买后立即升级；M4 建造卡亦据此判断），
 * 以及<b>下一项必须处理的步骤</b> next（由规则从落点当时的状态推出：缴租、买 / 放弃、升级、银行；null = 无）
 * 与<b>当前决策是否仍待消费</b> decisionOpen。落点只有在 next 为空、决策已消费、费用已结清时才能结束；
 * 每个决策（买 / 放弃、升级 / 跳过、结束银行）只能消费一次。
 */
public record LandingState(long landingId, int tile, LandingStep step, long pendingPayment, boolean bought,
                           LandingStep next, boolean decisionOpen) {
    public LandingState withStep(LandingStep value, long payment) {
        return new LandingState(landingId, tile, value, payment, bought, next, decisionOpen);
    }

    public LandingState markBought() {
        return new LandingState(landingId, tile, step, pendingPayment, true, next, decisionOpen);
    }

    public LandingState withNext(LandingStep value) {
        return new LandingState(landingId, tile, step, pendingPayment, bought, value, decisionOpen);
    }

    public LandingState decision(boolean open) {
        return new LandingState(landingId, tile, step, pendingPayment, bought, next, open);
    }
}
