package com.millionnaire.engine.config;

/**
 * 幸运格奖池里的一项（用户 2026-10-08：固定事件格改为"幸运"格，停下时从本棋盘的奖池按权重随机抽一项，自动生效）。
 * amount 只用于奖励（须在事件金额区间内），其余为 0；label 是卡面显示的短名（如"好人好事"）；weight 为抽取权重（正整数）。
 */
public record FixedEvent(EventKind kind, long amount, String label, int weight) {
}
