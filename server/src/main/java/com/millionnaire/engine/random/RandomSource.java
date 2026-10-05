package com.millionnaire.engine.random;

/** 引擎唯一的随机来源；每次抽取须声明抽取点。 */
public interface RandomSource {
    /** 返回 [0, bound) 内均匀分布的整数。 */
    int nextInt(DrawPoint point, int bound);

    /** 当前（抽取后）状态，写入 RandomDrawn 事件，供重建直接采用。 */
    RngState state();
}
