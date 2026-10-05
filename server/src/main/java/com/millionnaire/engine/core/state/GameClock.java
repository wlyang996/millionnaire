package com.millionnaire.engine.core.state;

/** 全局时钟：到时时刻与对应 GLOBAL_END 任务（永不暂停）；taskId 为 0 表示已触发。 */
public record GameClock(long endsAt, long taskId) {
}
