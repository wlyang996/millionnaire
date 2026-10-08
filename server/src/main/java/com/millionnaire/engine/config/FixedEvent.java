package com.millionnaire.engine.config;

/**
 * 幸运 / 不幸格奖池里的一项（用户 2026-10-08：停下时从本棋盘对应的奖池按权重随机抽一项，自动生效）。
 * amount 只用于奖励 / 罚款（须在事件金额区间内），其余为 0；label 是卡面显示的短名（如"好人好事"）；weight 为抽取权重（正整数）；
 * unlucky 为 true 的项属于不幸格（UNLUCKY_EVENT）的奖池，否则属于幸运格（FIXED_EVENT）。不幸奖池里的 MOVE 表示后退（格数按事件位移区间抽）。
 */
public record FixedEvent(EventKind kind, long amount, String label, int weight, boolean unlucky) {
    public FixedEvent(EventKind kind, long amount, String label, int weight) {
        this(kind, amount, label, weight, false);
    }
}
