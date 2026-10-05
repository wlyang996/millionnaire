package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.random.Draw;
import com.millionnaire.engine.random.DrawPoint;
import java.util.ArrayDeque;
import java.util.List;

/**
 * 领域演化时消费随机结果的入口：按先后顺序取出本步待消费的抽取，并核对抽取点与上界。
 * 领域事件里携带的随机派生值（如骰子点数）必须与取出的结果一致，否则视为日志不一致。
 */
public final class Draws {
    private final ArrayDeque<Draw> pending;

    Draws(List<Draw> pending) {
        this.pending = new ArrayDeque<>(pending);
    }

    /** 取出下一个待消费抽取的值；没有、抽取点或上界不符时抛 {@link IllegalStateException}。 */
    public int take(DrawPoint point, int bound) {
        Draw d = pending.pollFirst();
        if (d == null || d.point() != point || d.bound() != bound) {
            throw new IllegalStateException("expected pending draw " + point + "/" + bound + " but found " + d);
        }
        return d.value();
    }

    List<Draw> remaining() {
        return List.copyOf(pending);
    }
}
