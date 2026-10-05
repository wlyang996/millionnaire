package com.millionnaire.engine.random;

import java.util.function.LongSupplier;

/**
 * 无偏有界整数：Lemire 乘法-拒绝法。取原始 64 位输出的高 {@code bits} 位作为 x，
 * m = x × bound，低 bits 位小于阈值 t = 2^bits mod bound 时拒绝重抽；结果为 m 的高位。
 * 被接受的 x 恰好在每个结果上各有 ⌊2^bits / bound⌋ 个，故严格均匀（见单测的穷举验证）。
 */
public final class BoundedInt {
    /** 生产使用的位宽。 */
    public static final int BITS = 32;

    private BoundedInt() {
    }

    public static int next(LongSupplier raw64, int bound) {
        return next(raw64, bound, BITS);
    }

    static int next(LongSupplier raw64, int bound, int bits) {
        checkBound(bound, bits);
        while (true) {
            int r = reduceOnce(raw64.getAsLong() >>> (64 - bits), bound, bits);
            if (r >= 0) {
                return r;
            }
        }
    }

    /** 对单个 bits 位样本 x 做一次归约；被拒绝时返回 -1。 */
    static int reduceOnce(long x, int bound, int bits) {
        long mask = (1L << bits) - 1;
        long m = x * bound;
        long low = m & mask;
        long threshold = (1L << bits) % bound;
        return low < threshold ? -1 : (int) (m >>> bits);
    }

    private static void checkBound(int bound, int bits) {
        if (bits < 1 || bits > BITS) {
            throw new IllegalArgumentException("bits must be in 1..32");
        }
        if (bound < 1 || bound > (1L << bits)) {
            throw new IllegalArgumentException("bound out of range: " + bound);
        }
    }
}
