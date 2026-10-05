package com.millionnaire.engine.testkit;

import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.RandomSource;
import com.millionnaire.engine.random.RandomSourceFactory;
import com.millionnaire.engine.random.RngState;
import java.util.List;

/**
 * 脚本随机源：按预设顺序返回结果，并核对每次调用的抽取点与上界。
 * <b>无可变全局进度</b>：游标保存在 {@link RngState} 中（s0 为标记、s1 为已消费条数），
 * 因此快照恢复后从原位置继续，被丢弃的决策也不会"偷走"脚本条目。
 * 协议 ID 与生产不同，生产引擎与审计模式都会拒绝它。
 */
public final class ScriptedRandom implements RandomSourceFactory {
    public static final String PROTOCOL_ID = "scripted-test";
    private static final long MARK = 0x5C21_0000_0000_0001L;

    /** 一次预期抽取。 */
    public record Step(DrawPoint point, int bound, int value) {
    }

    private final List<Step> script;

    public ScriptedRandom(List<Step> script) {
        this.script = List.copyOf(script);
    }

    public static Step step(DrawPoint point, int bound, int value) {
        return new Step(point, bound, value);
    }

    @Override
    public String protocolId() {
        return PROTOCOL_ID;
    }

    @Override
    public RngState seed(long seed) {
        return new RngState(MARK, 0, 0, 0);
    }

    @Override
    public RandomSource open(RngState state) {
        int start = cursor(state);
        return new RandomSource() {
            private int position = start;

            @Override
            public int nextInt(DrawPoint point, int bound) {
                if (position >= script.size()) {
                    throw new AssertionError("script exhausted at draw " + (position + 1) + " (" + point + "/" + bound + ")");
                }
                Step s = script.get(position);
                if (s.point() != point || s.bound() != bound) {
                    throw new AssertionError("draw " + (position + 1) + " expected " + s.point() + "/" + s.bound()
                            + " but engine asked " + point + "/" + bound);
                }
                position++;
                return s.value();
            }

            @Override
            public RngState state() {
                return new RngState(MARK, position, 0, 0);
            }
        };
    }

    /** 状态中记录的已消费条数。 */
    public static int cursor(RngState state) {
        if (state.s0() != MARK || state.s2() != 0 || state.s3() != 0) {
            throw new IllegalArgumentException("not a scripted random state: " + state);
        }
        return Math.toIntExact(state.s1());
    }

    public void assertExhausted(EngineState state) {
        int consumed = cursor(state.rng());
        if (consumed != script.size()) {
            throw new AssertionError("consumed " + consumed + " of " + script.size() + " scripted draws");
        }
    }
}
