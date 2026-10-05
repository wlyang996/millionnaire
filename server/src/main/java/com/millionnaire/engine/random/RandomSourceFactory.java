package com.millionnaire.engine.random;

/**
 * 随机协议：协议 ID + 播种 + 从状态打开随机源。引擎通过它注入随机性（生产用 {@link XoshiroLemireV1}，测试可用脚本源）。
 * 协议 ID 写入创世事件与每个 RandomDrawn 事件；旧局只能用同一协议续跑与审计。
 * 实现必须无可变全局进度：全部进度都在 {@link RngState} 中，从而可由快照恢复。
 */
public interface RandomSourceFactory {
    String protocolId();

    /** 由外层生成的种子得到初始状态。 */
    RngState seed(long seed);

    RandomSource open(RngState state);
}
