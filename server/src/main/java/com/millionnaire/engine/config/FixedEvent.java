package com.millionnaire.engine.config;

/**
 * 固定事件格的效果（按棋盘上固定事件格的顺序对应）。amount 只用于奖励 / 罚款（须在事件金额区间内），其余为 0；
 * label 是格子上显示的短名（如"随地吐痰"），只用于显示。
 */
public record FixedEvent(EventKind kind, long amount, String label) {
}
