package com.millionnaire.engine.testkit;

import com.millionnaire.engine.time.Window;

/** 演示窗口；deadlineTaskId 为 0 表示暂停中、没有挂起的截止任务。 */
public record DemoRound(String playerId, Window window, long deadlineTaskId) {
}
