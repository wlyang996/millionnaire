package com.millionnaire.engine.time;

/**
 * 定时任务类别与同刻优先级（数值越小越先处理，opus-analysis 4.8）：
 * 全局到时 &gt; 流程计时 &gt; 回合窗口计时 &gt; 自动动作。优先级显式给出，不依赖枚举声明顺序。
 */
public enum TaskKind {
    GLOBAL_END(0),
    FLOW(1),
    TURN_WINDOW(2),
    AUTO_ACT(3);

    private final int priority;

    TaskKind(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
