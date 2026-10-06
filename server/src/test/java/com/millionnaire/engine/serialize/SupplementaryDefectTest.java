package com.millionnaire.engine.serialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.millionnaire.engine.time.Window;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 另一独立试验报告的疑似缺陷复现（修复前失败，见 m1d-report 末节）。 */
class SupplementaryDefectTest {

    /** 测试用 record。 */
    public record IntHolder(int i) {
    }

    /** 测试用 record。 */
    public record MapHolder(Map<String, Integer> m) {
        public MapHolder {
            m = Immutable.sortedMap(m);
        }
    }

    @Test
    void outOfRangeIntIsAnIllegalInputNotAnArithmeticError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Canonical.decode("{\"i\":2147483648}", IntHolder.class));
        assertInstanceOf(ArithmeticException.class, e.getCause(), "the original cause is kept");
        assertEquals(new IntHolder(7), Canonical.decode("{\"i\":7}", IntHolder.class));
    }

    @Test
    void nullMapKeyIsAnIllegalInput() {
        assertThrows(IllegalArgumentException.class, () -> Canonical.decode("{\"m\":[[null,1]]}", MapHolder.class));
        assertEquals(new MapHolder(Map.of("a", 1)), Canonical.decode("{\"m\":[[\"a\",1]]}", MapHolder.class));
    }

    @Test
    void aZeroLengthPausedWindowWithAPositiveBufferIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Window(1, 100, 100, true, 50, 0));
        assertThrows(IllegalArgumentException.class, () -> new Window(1, 100, 100, true, 0, 0),
                "a window always has positive length; zero remaining is expressed on a positive-length window");
        // 合法的"已耗尽"暂停窗口仍可表达
        assertInstanceOf(Window.Resumption.Exhausted.class, new Window(1, 0, 100, true, 0, 0).resume(200));
    }

    @Test
    void deeplyNestedInputIsRejectedInsteadOfOverflowingTheStack() {
        int depth = 200_000;
        String nested = "[".repeat(depth) + "]".repeat(depth);
        assertThrows(IllegalArgumentException.class,
                () -> Codec.DEFAULT.decode(nested, (java.lang.reflect.Type) List.class));
    }
}
