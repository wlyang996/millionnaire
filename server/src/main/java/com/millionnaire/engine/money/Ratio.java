package com.millionnaire.engine.money;

/** 有理比例 num/den（如 50/100），用于价格倍率；避免任何浮点。 */
public record Ratio(long num, long den) {

    public static Ratio percent(long p) {
        return new Ratio(p, 100);
    }

    public static Ratio of(long num, long den) {
        return new Ratio(num, den);
    }

    public long apply(long amount, Rounding rounding) {
        return Money.mulDiv(amount, num, den, rounding);
    }

    /** 仅当结果为整数时返回，否则抛 {@link ArithmeticException}。 */
    public long applyExact(long amount) {
        return Money.mulDivExact(amount, num, den);
    }

    public boolean isExactFor(long amount) {
        return Money.isExact(amount, num, den);
    }
}
