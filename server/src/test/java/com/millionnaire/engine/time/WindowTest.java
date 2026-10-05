package com.millionnaire.engine.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WindowTest {

    private static Window reopened(Window.Resumption r) {
        return assertInstanceOf(Window.Resumption.Reopened.class, r).window();
    }

    @Test
    void opensAtAndDeadlineAreSeparate() {
        Window w = Window.open(1, 1000, 1500, 15_000);
        assertEquals(2500, w.opensAt());
        assertEquals(17_500, w.deadline());
        assertEquals(Window.Status.NOT_OPEN, w.status(1000));
        assertEquals(Window.Status.NOT_OPEN, w.status(2499));
        assertTrue(w.acceptsAt(2500), "opensAt is inclusive");
        assertTrue(w.acceptsAt(17_499));
        assertFalse(w.acceptsAt(17_500), "deadline is exclusive");
        assertEquals(Window.Status.EXPIRED, w.status(17_500));
    }

    @Test
    void pauseBeforeOpensAtKeepsBufferAndFullWindow() {
        Window w = Window.open(1, 1000, 1500, 15_000).pause(2000);
        assertEquals(Window.Status.PAUSED, w.status(2600));
        assertEquals(500, w.pausedLeadMs());
        assertEquals(15_000, w.pausedRemainingMs(), "unstarted buffer must not count as player time");
        Window r = reopened(w.resume(10_000));
        assertEquals(10_500, r.opensAt());
        assertEquals(25_500, r.deadline());
        assertFalse(r.paused());
    }

    @Test
    void pauseExactlyAtOpensAtKeepsFullWindowWithoutBuffer() {
        Window w = Window.open(1, 1000, 1500, 15_000).pause(2500);
        assertEquals(0, w.pausedLeadMs());
        assertEquals(15_000, w.pausedRemainingMs());
        Window r = reopened(w.resume(5000));
        assertEquals(5000, r.opensAt());
        assertEquals(20_000, r.deadline());
    }

    @Test
    void pauseAfterOpensAtSavesRemaining() {
        Window w = Window.open(7, 0, 0, 15_000).pause(4000);
        assertEquals(0, w.pausedLeadMs());
        assertEquals(11_000, w.pausedRemainingMs());
        Window r = reopened(w.resume(30_000));
        assertEquals(30_000, r.opensAt());
        assertEquals(41_000, r.deadline());
        assertTrue(r.acceptsAt(30_000));
    }

    @Test
    void pauseOneMillisecondBeforeDeadlineThenResumeLeavesOneMillisecond() {
        Window r = reopened(Window.open(1, 0, 0, 1000).pause(999).resume(5000));
        assertEquals(1, r.deadline() - r.opensAt());
    }

    @Test
    void zeroRemainingResumeIsExhausted() {
        Window exhausted = new Window(3, 0, 100, true, 0, 0);
        assertEquals(Window.Status.PAUSED, exhausted.status(50));
        assertEquals(new Window.Resumption.Exhausted(3), exhausted.resume(200));
    }

    @Test
    void repeatedPauseResumeConservesRemainingTime() {
        Window w = Window.open(1, 0, 0, 10_000);
        w = reopened(w.pause(3000).resume(5000));   // 已用 3000
        w = reopened(w.pause(6000).resume(9000));   // 再用 1000
        assertEquals(6000, w.deadline() - w.opensAt());
        assertEquals(15_000, w.deadline());
    }

    @Test
    void illegalTransitionsAndInconsistentFields() {
        Window w = Window.open(1, 0, 0, 1000);
        assertThrows(IllegalStateException.class, () -> w.resume(10));
        assertThrows(IllegalStateException.class, () -> w.pause(1000), "deadline first: expired windows cannot pause");
        assertThrows(IllegalStateException.class, () -> w.pause(10).pause(20));
        assertThrows(IllegalArgumentException.class, () -> Window.open(1, 0, 0, 0), "no zero-length windows");
        assertThrows(IllegalArgumentException.class, () -> Window.open(1, 0, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new Window(1, 0, 100, false, 0, 5), "running with pause fields");
        assertThrows(IllegalArgumentException.class, () -> new Window(1, 0, 100, true, 0, 101), "remaining beyond window");
        assertThrows(IllegalArgumentException.class, () -> new Window(1, 0, 100, true, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> new Window(1, 0, 100, true, 10, 50), "buffer left but window used");
    }
}
