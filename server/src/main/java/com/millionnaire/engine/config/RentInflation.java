package com.millionnaire.engine.config;

/**
 * 租金随轮数上涨（2026-10-08，防止破产模式无限拖局），可在管理后台配置：只在破产模式生效。
 * 前 freeRounds 轮按原价；之后每 everyRounds 轮倍率 +stepPercent%，封顶 capPercent%。stepPercent = 0 表示关闭。
 * 默认：第 11～15 轮 ×1.2，16～20 轮 ×1.4 …… 第 56 轮起 ×3。地产与车站租金都按基础租金乘倍率，向下取整到 10。
 */
public record RentInflation(int freeRounds, int everyRounds, int stepPercent, int capPercent) {
    public static final RentInflation DEFAULT = new RentInflation(10, 5, 20, 300);

    /** 第 round 轮的租金倍率（百分比）；限时模式恒为 100。 */
    public int percent(EndMode mode, long round) {
        if (mode != EndMode.BANKRUPTCY || stepPercent <= 0 || everyRounds <= 0 || round <= freeRounds) {
            return 100;
        }
        long steps = (round - freeRounds + everyRounds - 1) / everyRounds;
        return (int) Math.max(100, Math.min(capPercent, 100 + steps * stepPercent));
    }

    public static long apply(long baseRent, int percent) {
        if (percent == 100) {
            return baseRent;
        }
        return Math.multiplyExact(baseRent, percent) / 100 / 10 * 10;
    }
}
