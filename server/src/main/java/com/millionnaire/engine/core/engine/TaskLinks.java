package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import com.millionnaire.engine.time.Window;
import java.util.List;

/**
 * 窗口与定时任务的双向关联校验（恢复入口与提交边界使用，M1 的窗口模板）：
 * 运行中的窗口必须恰好对应一个任务，且该任务的 ID、kind、ref（= windowId）、dueAt（= deadline）全部一致；
 * 反向地，同 kind 同 ref 的任务只能是这一个；暂停中的窗口不得有任务。
 */
public final class TaskLinks {
    private TaskLinks() {
    }

    /** 校验一个窗口与其截止任务；taskId 为 0 表示暂停中、无任务。 */
    public static void requireWindowTask(EngineState engine, Window window, long taskId, TaskKind kind) {
        List<ScheduledTask> linked = engine.timers().tasks().stream()
                .filter(t -> t.kind() == kind && t.ref() == window.windowId()).toList();
        if (window.paused()) {
            expect(taskId == 0 && linked.isEmpty(), "paused window " + window.windowId() + " must not have a deadline task");
            return;
        }
        ScheduledTask task = engine.timers().find(taskId).orElse(null);
        expect(task != null, "window " + window.windowId() + " lost its deadline task " + taskId);
        expect(task.kind() == kind && task.ref() == window.windowId() && task.dueAt() == window.deadline(),
                "task " + taskId + " does not match window " + window.windowId() + ": " + task);
        expect(linked.size() == 1, "window " + window.windowId() + " has " + linked.size() + " linked tasks");
    }

    /** 反向校验：某 kind 的每个任务都必须被认领（ref 在给定集合中）。 */
    public static void requireNoOrphans(EngineState engine, TaskKind kind, List<Long> claimedRefs) {
        for (ScheduledTask t : engine.timers().tasks()) {
            if (t.kind() == kind) {
                expect(claimedRefs.contains(t.ref()), "orphan " + kind + " task " + t.taskId() + " ref " + t.ref());
            }
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new StateValidationException(message);
        }
    }
}
