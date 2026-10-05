package com.millionnaire.engine.core.engine;

/**
 * 内核故障诊断：故障输入、引擎版本、配置哈希、最后已提交状态哈希与原因。外层据此停房并记录，
 * 修复后从最后已提交状态恢复；同一故障输入不得自动重试。
 */
public record FaultReport(long seq, long serverTime, String commandType, String inputDigest, String engineVersion,
                          String configHash, String lastCommittedStateHash, String causeType, String causeMessage) {
}
