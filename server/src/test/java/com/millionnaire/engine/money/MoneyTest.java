package com.millionnaire.engine.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MoneyTest {
    private static final long MAX = Long.MAX_VALUE;
    private static final long MIN = Long.MIN_VALUE;

    @Test
    void addSubMulThrowOnOverflow() {
        assertEquals(MAX, Money.add(MAX - 1, 1));
        assertThrows(ArithmeticException.class, () -> Money.add(MAX, 1));
        assertThrows(ArithmeticException.class, () -> Money.sub(MIN, 1));
        assertThrows(ArithmeticException.class, () -> Money.mul(MAX / 2 + 1, 2));
        assertThrows(ArithmeticException.class, () -> Money.mul(MIN, -1));
        assertEquals(MIN, Money.mul(MIN / 2, 2));
    }

    @Test
    void divRoundsMathematicallyForBothSigns() {
        assertEquals(3, Money.div(7, 2, Rounding.FLOOR));
        assertEquals(4, Money.div(7, 2, Rounding.CEIL));
        assertEquals(-4, Money.div(-7, 2, Rounding.FLOOR));
        assertEquals(-3, Money.div(-7, 2, Rounding.CEIL));
        assertEquals(5, Money.div(10, 2, Rounding.CEIL));
        assertEquals(MIN, Money.div(MIN, 1, Rounding.FLOOR));
        assertThrows(ArithmeticException.class, () -> Money.div(1, 0, Rounding.FLOOR));
        assertThrows(ArithmeticException.class, () -> Money.div(1, -2, Rounding.FLOOR));
    }

    @Test
    void mulDivSurvivesIntermediateOverflow() {
        assertEquals(MAX, Money.mulDiv(MAX, MAX, MAX, Rounding.FLOOR));
        assertEquals(MAX / 2, Money.mulDiv(MAX, 2, 4, Rounding.FLOOR));
        assertEquals(MAX / 2 + 1, Money.mulDiv(MAX, 2, 4, Rounding.CEIL));
        assertEquals(922337203685477580L, Money.mulDiv(MAX, 10, 100, Rounding.FLOOR));
        assertEquals(922337203685477581L, Money.mulDiv(MAX, 10, 100, Rounding.CEIL));
        assertEquals(MIN, Money.mulDiv(MIN, 3, 3, Rounding.CEIL));
        assertEquals(-(1L << 62), Money.mulDiv(MIN, 4, 8, Rounding.FLOOR));
        assertEquals(MAX, Money.mulDiv(MIN + 1, -(MAX - 1), MAX - 1, Rounding.FLOOR));
    }

    @Test
    void mulDivThrowsWhenResultDoesNotFit() {
        assertThrows(ArithmeticException.class, () -> Money.mulDiv(MAX, 3, 2, Rounding.FLOOR));
        assertThrows(ArithmeticException.class, () -> Money.mulDiv(MIN, -1, 1, Rounding.FLOOR));
        assertThrows(ArithmeticException.class, () -> Money.mulDiv(MIN, MIN, MAX, Rounding.CEIL));
        assertThrows(ArithmeticException.class, () -> Money.mulDiv(1, 1, 0, Rounding.CEIL));
    }

    @Test
    void mulDivMatchesBigIntegerReferenceOnEdgeGrid() {
        long[] values = {MIN, MIN + 1, -(1L << 32) - 1, -7, -1, 0, 1, 7, 1L << 32, (1L << 62) + 3, MAX - 1, MAX};
        long[] dens = {1, 2, 3, 7, 10, 100, 1L << 31, (1L << 32) + 1, MAX - 1, MAX};
        int checked = 0;
        for (long a : values) {
            for (long num : values) {
                for (long den : dens) {
                    for (Rounding r : Rounding.values()) {
                        BigInteger exact = BigInteger.valueOf(a).multiply(BigInteger.valueOf(num));
                        BigInteger expected = new java.math.BigDecimal(exact)
                                .divide(new java.math.BigDecimal(BigInteger.valueOf(den)), 0,
                                        r == Rounding.FLOOR ? RoundingMode.FLOOR : RoundingMode.CEILING)
                                .toBigIntegerExact();
                        if (expected.bitLength() > 63) {
                            assertThrows(ArithmeticException.class, () -> Money.mulDiv(a, num, den, r));
                        } else {
                            assertEquals(expected.longValueExact(), Money.mulDiv(a, num, den, r),
                                    a + "*" + num + "/" + den + " " + r);
                        }
                        checked++;
                    }
                }
            }
        }
        assertEquals(values.length * values.length * dens.length * 2, checked);
    }

    @Test
    void mulDivExactRejectsFractions() {
        assertEquals(400, Money.mulDivExact(500, 80, 100));
        assertThrows(ArithmeticException.class, () -> Money.mulDivExact(501, 80, 100));
        assertTrue(Money.isExact(1300, 50, 100));
        assertFalse(Money.isExact(301, 50, 100));
    }

    @Test
    void ratioAppliesWithDirection() {
        Ratio tenPercent = Ratio.percent(10);
        assertEquals(123, tenPercent.apply(1239, Rounding.FLOOR));
        assertEquals(124, tenPercent.apply(1231, Rounding.CEIL));
        assertEquals(124, tenPercent.applyExact(1240));
        assertThrows(ArithmeticException.class, () -> tenPercent.applyExact(1239));
    }

    @Test
    void nonNegativeGuards() {
        assertEquals(0, Money.requireNonNegative(0, "x"));
        assertThrows(IllegalArgumentException.class, () -> Money.requireNonNegative(-1, "x"));
        assertThrows(IllegalArgumentException.class, () -> Money.requirePositive(0, "x"));
    }
}
