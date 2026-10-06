package com.millionnaire.engine.time;

/**
 * 操作窗口：opensAt（动画缓冲结束、允许操作）与 deadline 分离，有效区间为 [opensAt, deadline)。
 * 暂停时分别保存"距开启的剩余缓冲"与"窗口剩余时长"，避免把未开始的缓冲算成玩家剩余时间。
 * <p>字段一致性：运行中 deadline &gt; opensAt 且暂停字段为 0；暂停中 0 ≤ 剩余 ≤ 原窗口长度，
 * 且剩余缓冲 &gt; 0 时窗口必须完整未用。剩余为 0 的暂停窗口合法（例如沿用已耗尽的投骰时间），
 * 恢复时返回 {@link Resumption.Exhausted}，由调用方立即执行自动动作，不创建零长度窗口。
 */
public record Window(long windowId, long opensAt, long deadline, boolean paused, long pausedLeadMs, long pausedRemainingMs) {

    public Window {
        if (deadline <= opensAt) {
            // 暂停窗口同样必须有正长度；"剩余为 0"只能表达为正长度窗口上的 pausedRemainingMs = 0（恢复时即耗尽）
            throw new IllegalArgumentException("window must have positive length");
        }
        if (!paused && (pausedLeadMs != 0 || pausedRemainingMs != 0)) {
            throw new IllegalArgumentException("running window must not carry pause fields");
        }
        if (paused && (pausedLeadMs < 0 || pausedRemainingMs < 0 || pausedRemainingMs > deadline - opensAt
                || pausedLeadMs > 0 && pausedRemainingMs != deadline - opensAt)) {
            throw new IllegalArgumentException("inconsistent pause fields");
        }
    }

    /** 状态。 */
    public enum Status {
        NOT_OPEN, OPEN, EXPIRED, PAUSED
    }

    /** 在 at 时刻开启：先经过 leadMs 缓冲，再开放 durationMs。 */
    public static Window open(long windowId, long at, long leadMs, long durationMs) {
        if (leadMs < 0 || durationMs <= 0) {
            throw new IllegalArgumentException("lead must be >= 0 and duration > 0");
        }
        long opensAt = Math.addExact(at, leadMs);
        return new Window(windowId, opensAt, Math.addExact(opensAt, durationMs), false, 0, 0);
    }

    public Status status(long at) {
        if (paused) {
            return Status.PAUSED;
        }
        if (at < opensAt) {
            return Status.NOT_OPEN;
        }
        return at < deadline ? Status.OPEN : Status.EXPIRED;
    }

    public boolean acceptsAt(long at) {
        return status(at) == Status.OPEN;
    }

    /** 在截止前暂停（at ≥ deadline 时应先处理到期任务）。 */
    public Window pause(long at) {
        if (paused || at >= deadline) {
            throw new IllegalStateException("cannot pause window " + windowId + " at " + at);
        }
        long lead = Math.max(0, opensAt - at);
        long remaining = deadline - Math.max(at, opensAt);
        return new Window(windowId, opensAt, deadline, true, lead, remaining);
    }

    /** 恢复：剩余时长为 0 时返回已耗尽，否则重新开放（先走完剩余缓冲）。 */
    public Resumption resume(long at) {
        if (!paused) {
            throw new IllegalStateException("window " + windowId + " is not paused");
        }
        if (pausedRemainingMs == 0) {
            return new Resumption.Exhausted(windowId);
        }
        long opens = Math.addExact(at, pausedLeadMs);
        return new Resumption.Reopened(new Window(windowId, opens, Math.addExact(opens, pausedRemainingMs), false, 0, 0));
    }

    /** 恢复结果。 */
    public sealed interface Resumption {
        /** 重新开放的窗口。 */
        record Reopened(Window window) implements Resumption {
        }

        /** 剩余时间为 0：不创建窗口，调用方应立即执行该窗口的超时自动动作。 */
        record Exhausted(long windowId) implements Resumption {
        }
    }
}
