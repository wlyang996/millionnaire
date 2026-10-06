package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Optional;

/**
 * 流程协调状态：窗口栈（栈顶唯一可运行，其余均暂停）、FIFO 申请队列、会话内单调的窗口 ID 与申请 ID 分配器，
 * 以及安全点标记：safePointNo 为已进入的安全点编号，startedAtSafePoint 为最近一次从队列启动流程时所在的安全点编号
 * （相等表示本安全点已启动过一个流程，不能再启动第二个）。
 */
public record FlowState(List<FlowFrame> frames, List<FlowRequest> queue, long nextWindowId, long nextRequestId,
                        long safePointNo, long startedAtSafePoint, FlowRequest pendingStart) {
    public FlowState(List<FlowFrame> frames, List<FlowRequest> queue, long nextWindowId, long nextRequestId,
                     long safePointNo, long startedAtSafePoint) {
        this(frames, queue, nextWindowId, nextRequestId, safePointNo, startedAtSafePoint, null);
    }
    public FlowState {
        frames = Immutable.list(frames);
        queue = Immutable.list(queue);
    }

    public static FlowState initial(long nextWindowId) {
        return new FlowState(List.of(), List.of(), nextWindowId, 1, 0, 0);
    }

    public Optional<FlowFrame> top() {
        return frames.isEmpty() ? Optional.empty() : Optional.of(frames.get(frames.size() - 1));
    }

    public Optional<FlowFrame> frame(long windowId) {
        return frames.stream().filter(f -> f.windowId() == windowId).findFirst();
    }

    public FlowState withFrames(List<FlowFrame> value) {
        return new FlowState(value, queue, nextWindowId, nextRequestId, safePointNo, startedAtSafePoint, pendingStart);
    }

    public FlowState withQueue(List<FlowRequest> value) {
        return new FlowState(frames, value, nextWindowId, nextRequestId, safePointNo, startedAtSafePoint, pendingStart);
    }

    public FlowState withNextWindowId(long value) {
        return new FlowState(frames, queue, value, nextRequestId, safePointNo, startedAtSafePoint, pendingStart);
    }

    public FlowState withNextRequestId(long value) {
        return new FlowState(frames, queue, nextWindowId, value, safePointNo, startedAtSafePoint, pendingStart);
    }

    public FlowState withSafePoint(long no, long startedAt) {
        return new FlowState(frames, queue, nextWindowId, nextRequestId, no, startedAt, pendingStart);
    }
    public FlowState withPendingStart(FlowRequest value) {
        return new FlowState(frames, queue, nextWindowId, nextRequestId, safePointNo, startedAtSafePoint, value);
    }
}
