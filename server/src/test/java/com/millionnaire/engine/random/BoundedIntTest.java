package com.millionnaire.engine.random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 无偏性以算法层面的穷举为证明：在缩小位宽下枚举全部样本，验证每个结果被接受的样本数完全相同。
 * 生产位宽 32 与测试位宽共用同一段代码（{@link BoundedInt#reduceOnce}）。
 */
class BoundedIntTest {

    @Test
    void exhaustiveUniformityAtReducedWidth() {
        for (int bits : new int[] {1, 4, 8, 10}) {
            long space = 1L << bits;
            for (int bound = 1; bound <= space; bound++) {
                long[] hits = new long[bound];
                long rejected = 0;
                for (long x = 0; x < space; x++) {
                    int r = BoundedInt.reduceOnce(x, bound, bits);
                    if (r < 0) {
                        rejected++;
                    } else {
                        hits[r]++;
                    }
                }
                long perValue = space / bound;
                for (int v = 0; v < bound; v++) {
                    assertEquals(perValue, hits[v], "bits " + bits + " bound " + bound + " value " + v);
                }
                assertEquals(space % bound, rejected, "bits " + bits + " bound " + bound);
            }
        }
    }

    @Test
    void rejectionConsumesAnotherRawDraw() {
        // bound 3、32 位：阈值 t = 2^32 mod 3 = 1，x = 0 时低位为 0 < 1 被拒
        Deque<Long> raw = new ArrayDeque<>(List.of(0L, 0xFFFF_FFFF_0000_0000L));
        int v = BoundedInt.next(raw::removeFirst, 3);
        assertEquals(2, v);
        assertTrue(raw.isEmpty(), "the rejected sample must be followed by exactly one more draw");
    }

    @Test
    void usesHighBitsAndBoundOneAlwaysZero() {
        assertEquals(0, BoundedInt.next(() -> 0xFFFF_FFFF_FFFF_FFFFL, 1));
        assertEquals(5, BoundedInt.next(() -> 0xFFFF_FFFF_0000_0000L, 6));
        assertEquals(0, BoundedInt.next(() -> 0x0000_0001_FFFF_FFFFL, 6));
    }

    @Test
    void invalidBoundRejected() {
        assertThrows(IllegalArgumentException.class, () -> BoundedInt.next(() -> 1L, 0));
        assertThrows(IllegalArgumentException.class, () -> BoundedInt.next(() -> 1L, -5));
    }

    @Test
    void smallRangeDistributionIsPlausible() {
        // 合理性检查（不是无偏性证明）：60000 次六面骰，每面计数偏离 10000 不超过 3%
        PrngRandomSource r = new PrngRandomSource(RngState.fromSeed(2026));
        int[] counts = new int[6];
        for (int i = 0; i < 60_000; i++) {
            counts[r.nextInt(DrawPoint.MOVE_DIE, 6)]++;
        }
        for (int c : counts) {
            assertTrue(Math.abs(c - 10_000) < 300, "count " + c);
        }
    }
}
