package com.millionnaire.engine.core.state;

import com.millionnaire.engine.time.Window;

/**
 * 流程栈中的一层：一个窗口及其截止任务。windowId 在会话内单调分配；
 * resumeTag 为该层关闭后所属模块应继续的位置（"恢复位置"，由模块自定义）。
 * deadlineTaskId 为 0 表示窗口暂停中、没有挂起任务。
 */
public record FlowFrame(long windowId, FlowKind kind, String owner, Window window, long deadlineTaskId, String resumeTag, FlowOrigin origin) {
    public FlowFrame(long windowId, FlowKind kind, String owner, Window window, long deadlineTaskId, String resumeTag) {
        this(windowId, kind, owner, window, deadlineTaskId, resumeTag, null);
    }
}
