package com.millionnaire.engine.random;

/** xoshiro256**（Blackman & Vigna），算法固定、与 JDK 版本无关。可变工作副本，状态通过 {@link #state()} 导出。 */
public final class Xoshiro256StarStar {
    private long s0;
    private long s1;
    private long s2;
    private long s3;

    public Xoshiro256StarStar(RngState state) {
        this.s0 = state.s0();
        this.s1 = state.s1();
        this.s2 = state.s2();
        this.s3 = state.s3();
    }

    public long nextLong() {
        long result = Long.rotateLeft(s1 * 5, 7) * 9;
        long t = s1 << 17;
        s2 ^= s0;
        s3 ^= s1;
        s1 ^= s2;
        s0 ^= s3;
        s2 ^= t;
        s3 = Long.rotateLeft(s3, 45);
        return result;
    }

    public RngState state() {
        return new RngState(s0, s1, s2, s3);
    }
}
