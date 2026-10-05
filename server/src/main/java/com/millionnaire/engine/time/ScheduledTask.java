package com.millionnaire.engine.time;

/**
 * 定时任务。taskId 由引擎单调分配（即创建序号）；ref 指向所属窗口/流程的标识。
 */
public record ScheduledTask(long taskId, long dueAt, TaskKind kind, long ref) {
    public ScheduledTask {
        if (kind == null) {
            throw new IllegalArgumentException("kind missing");
        }
    }
}
