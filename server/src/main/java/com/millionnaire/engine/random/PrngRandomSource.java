package com.millionnaire.engine.random;

/** 协议 {@link XoshiroLemireV1} 的随机源：xoshiro256** + Lemire 无偏抽取。 */
public final class PrngRandomSource implements RandomSource {
    private final Xoshiro256StarStar prng;

    public PrngRandomSource(RngState state) {
        this.prng = new Xoshiro256StarStar(state);
    }

    @Override
    public int nextInt(DrawPoint point, int bound) {
        return BoundedInt.next(prng::nextLong, bound);
    }

    @Override
    public RngState state() {
        return prng.state();
    }
}
