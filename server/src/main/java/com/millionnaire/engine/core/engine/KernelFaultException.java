package com.millionnaire.engine.core.engine;

/**
 * 内核故障：领域或内核的程序错误（随机纪律被破坏、演化一致性断言失败、提交前边界校验失败等）。
 * 整步不提交（调用方持有的原状态不变），不得自动重试；外层应暂停该房间推进并记录诊断。
 */
public final class KernelFaultException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public KernelFaultException(String message) {
        super(message);
    }

    public KernelFaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
