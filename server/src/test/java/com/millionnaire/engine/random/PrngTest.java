package com.millionnaire.engine.random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.millionnaire.engine.serialize.Canonical;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PrngTest {

    @Test
    void splitMix64MatchesJdkReference() {
        // JDK SplittableRandom 的 nextLong 与 SplitMix64 同算法（相同 gamma 与 mix64），作为独立参照
        for (long seed : new long[] {0, 1, 42, -1, Long.MIN_VALUE}) {
            SplitMix64 ours = new SplitMix64(seed);
            SplittableRandom ref = new SplittableRandom(seed);
            for (int i = 0; i < 100; i++) {
                assertEquals(ref.nextLong(), ours.next(), "seed " + seed + " step " + i);
            }
        }
    }

    @Test
    void xoshiroMatchesReferenceVectors() {
        // 参考实现在状态 {1,2,3,4} 下的前四个输出
        Xoshiro256StarStar x = new Xoshiro256StarStar(new RngState(1, 2, 3, 4));
        assertEquals(11520L, x.nextLong());
        assertEquals(0L, x.nextLong());
        assertEquals(1509978240L, x.nextLong());
        assertEquals(1215971899390074240L, x.nextLong());
    }

    @Test
    void sameSeedSameSequenceDifferentSeedDiffers() {
        assertArrayEquals(draws(RngState.fromSeed(7), 1000), draws(RngState.fromSeed(7), 1000));
        assertNotEquals(java.util.Arrays.toString(draws(RngState.fromSeed(7), 20)),
                java.util.Arrays.toString(draws(RngState.fromSeed(8), 20)));
    }

    @Test
    void goldenSequenceForSeed42() {
        // 黄金值：锁定 PRNG 协议（算法 + 播种 + 有界抽取）。修改任一环节都会使旧局无法回放。
        int[] dice = new int[12];
        PrngRandomSource r = new PrngRandomSource(RngState.fromSeed(42));
        for (int i = 0; i < dice.length; i++) {
            dice[i] = r.nextInt(DrawPoint.MOVE_DIE, 6) + 1;
        }
        assertArrayEquals(GOLDEN_SEED42_DICE, dice);
    }

    static final int[] GOLDEN_SEED42_DICE = {1, 3, 5, 6, 6, 5, 5, 6, 5, 4, 5, 2};

    @Test
    void snapshotOfStateContinuesIdentically() {
        PrngRandomSource a = new PrngRandomSource(RngState.fromSeed(123));
        for (int i = 0; i < 37; i++) {
            a.nextInt(DrawPoint.EVENT_KIND, 100);
        }
        String saved = Canonical.encode(a.state());
        PrngRandomSource b = new PrngRandomSource(Canonical.decode(saved, RngState.class));
        for (int i = 0; i < 500; i++) {
            int bound = 1 + (i % 97);
            assertEquals(a.nextInt(DrawPoint.EVENT_CASH, bound), b.nextInt(DrawPoint.EVENT_CASH, bound));
        }
        assertEquals(a.state(), b.state());
    }

    @Test
    void allZeroStateRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RngState(0, 0, 0, 0));
    }

    private static int[] draws(RngState s, int n) {
        PrngRandomSource r = new PrngRandomSource(s);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = r.nextInt(DrawPoint.ORDER_NUMBER, 100);
        }
        return out;
    }
}
