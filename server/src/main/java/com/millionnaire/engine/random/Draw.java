package com.millionnaire.engine.random;

/** 一次已抽取、尚待领域事件消费的随机结果。 */
public record Draw(DrawPoint point, int bound, int value) {
    public Draw {
        if (point == null || bound < 1 || value < 0 || value >= bound) {
            throw new IllegalArgumentException("invalid draw " + point + " " + value + "/" + bound);
        }
    }
}
