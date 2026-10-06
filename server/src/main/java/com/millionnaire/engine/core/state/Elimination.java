package com.millionnaire.engine.core.state;

/**
 * 淘汰记录：淘汰序号（单调递增，越晚越大）、批次（一般每次淘汰一批；延后认输在流程结束时同批处理）、
 * 批次开始前的净资产快照（只在"处理完一批后零存活"时用于该批内部排名，O7/O8）。
 */
public record Elimination(long seq, long batch, long netWorthBefore) {
}
