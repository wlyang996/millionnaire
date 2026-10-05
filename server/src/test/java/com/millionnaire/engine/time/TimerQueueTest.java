package com.millionnaire.engine.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimerQueueTest {

    @Test
    void totalOrderByDeadlineThenPriorityThenCreation() {
        List<ScheduledTask> tasks = List.of(
                new ScheduledTask(5, 1000, TaskKind.AUTO_ACT, 0),
                new ScheduledTask(4, 1000, TaskKind.TURN_WINDOW, 0),
                new ScheduledTask(3, 1000, TaskKind.FLOW, 0),
                new ScheduledTask(7, 1000, TaskKind.FLOW, 0),
                new ScheduledTask(6, 1000, TaskKind.GLOBAL_END, 0),
                new ScheduledTask(1, 2000, TaskKind.GLOBAL_END, 0),
                new ScheduledTask(2, 999, TaskKind.AUTO_ACT, 0));
        List<Long> expected = List.of(2L, 6L, 3L, 7L, 4L, 5L, 1L);
        // 任意插入顺序得到同一序列
        for (int seed = 0; seed < 50; seed++) {
            List<ScheduledTask> shuffled = new ArrayList<>(tasks);
            Collections.shuffle(shuffled, new java.util.Random(seed));
            TimerQueue q = TimerQueue.empty();
            for (ScheduledTask t : shuffled) {
                q = q.schedule(t);
            }
            assertEquals(expected, q.tasks().stream().map(ScheduledTask::taskId).toList());
        }
    }

    @Test
    void sameInstantSamePriorityIsStableByCreationOrder() {
        TimerQueue q = TimerQueue.empty()
                .schedule(new ScheduledTask(12, 500, TaskKind.FLOW, 1))
                .schedule(new ScheduledTask(10, 500, TaskKind.FLOW, 2))
                .schedule(new ScheduledTask(11, 500, TaskKind.FLOW, 3));
        assertEquals(List.of(10L, 11L, 12L), q.tasks().stream().map(ScheduledTask::taskId).toList());
    }

    @Test
    void firstDueIsInclusiveOfDeadline() {
        TimerQueue q = TimerQueue.empty().schedule(new ScheduledTask(1, 1000, TaskKind.TURN_WINDOW, 0));
        assertTrue(q.firstDue(999).isEmpty());
        assertEquals(1L, q.firstDue(1000).orElseThrow().taskId());
        assertEquals(1L, q.firstDue(5000).orElseThrow().taskId());
    }

    @Test
    void cancelAndDuplicates() {
        TimerQueue q = TimerQueue.empty()
                .schedule(new ScheduledTask(1, 100, TaskKind.FLOW, 0))
                .schedule(new ScheduledTask(2, 50, TaskKind.FLOW, 0));
        assertEquals(2L, q.peek().orElseThrow().taskId());
        TimerQueue q2 = q.cancel(2);
        assertEquals(1L, q2.peek().orElseThrow().taskId());
        assertEquals(2, q.tasks().size(), "queue is immutable");
        assertThrows(IllegalArgumentException.class, () -> q2.cancel(2));
        assertThrows(IllegalArgumentException.class, () -> q.schedule(new ScheduledTask(1, 300, TaskKind.FLOW, 0)));
        assertTrue(q2.cancel(1).isEmpty());
    }
}
