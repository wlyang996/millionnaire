package com.millionnaire.engine.time;

import java.util.Comparator;

/** 任务总排序：dueAt 升序 → 优先级升序 → 创建序号（taskId）升序。taskId 唯一，故为全序。 */
public final class TaskOrder {
    public static final Comparator<ScheduledTask> ORDER = Comparator
            .comparingLong(ScheduledTask::dueAt)
            .thenComparingInt(t -> t.kind().priority())
            .thenComparingLong(ScheduledTask::taskId);

    private TaskOrder() {
    }
}
