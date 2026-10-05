package com.millionnaire.engine.random;

/** 生产随机协议 v1：SplitMix64 播种的 xoshiro256**，Lemire 无偏抽取取 64 位输出的高 32 位。 */
public final class XoshiroLemireV1 implements RandomSourceFactory {
    public static final String PROTOCOL_ID = "xoshiro256ss-lemire32-v1";
    public static final XoshiroLemireV1 INSTANCE = new XoshiroLemireV1();

    private XoshiroLemireV1() {
    }

    @Override
    public String protocolId() {
        return PROTOCOL_ID;
    }

    @Override
    public RngState seed(long seed) {
        return RngState.fromSeed(seed);
    }

    @Override
    public RandomSource open(RngState state) {
        return new PrngRandomSource(state);
    }
}
