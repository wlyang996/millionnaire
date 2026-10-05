package com.millionnaire.engine.random;

/** xoshiro256** 的 256 位状态；可序列化，进入快照，任何视图都不可见。 */
public record RngState(long s0, long s1, long s2, long s3) {
    public RngState {
        if ((s0 | s1 | s2 | s3) == 0) {
            throw new IllegalArgumentException("xoshiro state must not be all zero");
        }
    }

    /** 由 64 位种子经 SplitMix64 扩展为初始状态（xoshiro 作者推荐的播种方式）。 */
    public static RngState fromSeed(long seed) {
        SplitMix64 sm = new SplitMix64(seed);
        return new RngState(sm.next(), sm.next(), sm.next(), sm.next());
    }
}
