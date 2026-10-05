package com.millionnaire.engine.time;

import com.millionnaire.engine.serialize.Immutable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 不可变定时队列，内部始终按 {@link TaskOrder#ORDER} 排序；可直接规范序列化。
 * 构造器（含反序列化入口）检查全体 taskId 唯一且为正。
 */
public record TimerQueue(List<ScheduledTask> tasks) {
    public TimerQueue {
        if (tasks == null) {
            throw new IllegalArgumentException("tasks missing");
        }
        TreeSet<Long> ids = new TreeSet<>();
        for (ScheduledTask t : tasks) {
            if (t == null) {
                throw new IllegalArgumentException("null task");
            }
            if (t.taskId() <= 0) {
                throw new IllegalArgumentException("taskId must be positive: " + t.taskId());
            }
            if (!ids.add(t.taskId())) {
                throw new IllegalArgumentException("duplicate taskId " + t.taskId());
            }
        }
        List<ScheduledTask> sorted = new ArrayList<>(tasks);
        sorted.sort(TaskOrder.ORDER);
        tasks = Immutable.list(sorted);
    }

    public static TimerQueue empty() {
        return new TimerQueue(List.of());
    }

    public TimerQueue schedule(ScheduledTask task) {
        return new TimerQueue(Immutable.append(tasks, task));
    }

    public TimerQueue cancel(long taskId) {
        List<ScheduledTask> rest = tasks.stream().filter(t -> t.taskId() != taskId).toList();
        if (rest.size() == tasks.size()) {
            throw new IllegalArgumentException("no task " + taskId);
        }
        return new TimerQueue(rest);
    }

    public Optional<ScheduledTask> find(long taskId) {
        return tasks.stream().filter(t -> t.taskId() == taskId).findFirst();
    }

    public Optional<ScheduledTask> peek() {
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    /** 队首任务若在 now 时刻已到期（dueAt ≤ now）则返回。 */
    public Optional<ScheduledTask> firstDue(long now) {
        return peek().filter(t -> t.dueAt() <= now);
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }
}
