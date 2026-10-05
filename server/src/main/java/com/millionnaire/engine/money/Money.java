package com.millionnaire.engine.money;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 金币运算：金额一律为 {@code long} 整数，任何溢出立即抛 {@link ArithmeticException}，不做静默截断。
 * <p>选择静态工具而非包装类型，使状态 record 与规范序列化保持为纯整数字段。
 */
public final class Money {
    private Money() {
    }

    public static long add(long a, long b) {
        return Math.addExact(a, b);
    }

    public static long sub(long a, long b) {
        return Math.subtractExact(a, b);
    }

    public static long mul(long a, long b) {
        return Math.multiplyExact(a, b);
    }

    /** a / den，按指定方向舍入；den 必须为正。 */
    public static long div(long a, long den, Rounding rounding) {
        requirePositiveDen(den);
        Objects.requireNonNull(rounding, "rounding");
        return rounding == Rounding.FLOOR ? Math.floorDiv(a, den) : Math.ceilDiv(a, den);
    }

    /**
     * a × num / den，中间积按 128 位计算，避免 64 位乘法溢出；结果超出 long 范围时抛异常。
     */
    public static long mulDiv(long a, long num, long den, Rounding rounding) {
        requirePositiveDen(den);
        Objects.requireNonNull(rounding, "rounding");
        long hi = Math.multiplyHigh(a, num);
        long lo = a * num;
        if (hi == (lo >> 63)) {
            return div(lo, den, rounding);
        }
        BigInteger[] qr = BigInteger.valueOf(a).multiply(BigInteger.valueOf(num))
                .divideAndRemainder(BigInteger.valueOf(den));
        BigInteger q = qr[0];
        int r = qr[1].signum();
        if (rounding == Rounding.FLOOR && r < 0) {
            q = q.subtract(BigInteger.ONE);
        } else if (rounding == Rounding.CEIL && r > 0) {
            q = q.add(BigInteger.ONE);
        }
        return q.longValueExact();
    }

    /** a × num / den，要求整除，否则抛异常（用于规则未定义取整方向的字段）。 */
    public static long mulDivExact(long a, long num, long den) {
        long floor = mulDiv(a, num, den, Rounding.FLOOR);
        long ceil = mulDiv(a, num, den, Rounding.CEIL);
        if (floor != ceil) {
            throw new ArithmeticException(a + "*" + num + "/" + den + " is not an integer");
        }
        return floor;
    }

    /** a × num / den 是否为整数。 */
    public static boolean isExact(long a, long num, long den) {
        return mulDiv(a, num, den, Rounding.FLOOR) == mulDiv(a, num, den, Rounding.CEIL);
    }

    public static long requireNonNegative(long amount, String what) {
        if (amount < 0) {
            throw new IllegalArgumentException(what + " must be >= 0: " + amount);
        }
        return amount;
    }

    public static long requirePositive(long amount, String what) {
        if (amount <= 0) {
            throw new IllegalArgumentException(what + " must be > 0: " + amount);
        }
        return amount;
    }

    private static void requirePositiveDen(long den) {
        if (den <= 0) {
            throw new ArithmeticException("denominator must be > 0: " + den);
        }
    }
}
