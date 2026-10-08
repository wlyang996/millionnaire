package com.millionnaire.engine.config;

/**
 * 租金随轮数上涨（2026-10-08，用户确认，防止破产模式无限拖局）：只在破产模式生效。
 * 前 {@link #FREE_ROUNDS} 轮按原价；之后每 {@link #EVERY_ROUNDS} 轮倍率 +{@link #STEP_PERCENT}%，封顶 {@link #CAP_PERCENT}%。
 * 第 11～15 轮 ×1.2，16～20 轮 ×1.4 …… 第 56 轮起 ×3。地产与车站租金都按基础租金乘倍率，向下取整到 10。
 */
public final class RentInflation {
    public static final int FREE_ROUNDS = 10;
    public static final int EVERY_ROUNDS = 5;
    public static final int STEP_PERCENT = 20;
    public static final int CAP_PERCENT = 300;

    private RentInflation() {
    }

    /** 第 round 轮的租金倍率（百分比）；限时模式恒为 100。 */
    public static int percent(EndMode mode, long round) {
        if (mode != EndMode.BANKRUPTCY || round <= FREE_ROUNDS) {
            return 100;
        }
        long steps = (round - FREE_ROUNDS + EVERY_ROUNDS - 1) / EVERY_ROUNDS;
        return (int) Math.min(CAP_PERCENT, 100 + steps * STEP_PERCENT);
    }

    public static long apply(long baseRent, int percent) {
        if (percent == 100) {
            return baseRent;
        }
        return Math.multiplyExact(baseRent, percent) / 100 / 10 * 10;
    }
}
