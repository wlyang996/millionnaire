package com.millionnaire.engine.core.engine;

/**
 * 内核故障：领域或内核的程序错误（随机纪律被破坏、演化一致性断言失败、提交前边界校验失败等）。
 * 整步不提交（包括本步内先到期任务产生的临时事件），不得自动重试；外层应停房并保存 {@link #report()}。
 */
public final class KernelFaultException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient FaultReport report;

    public KernelFaultException(String message) {
        this(message, null, null);
    }

    public KernelFaultException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public KernelFaultException(String message, Throwable cause, FaultReport report) {
        super(message, cause);
        this.report = report;
    }

    /** 引擎在 step 边界补全的诊断；领域内部抛出时为 null，引擎会重新包装。 */
    public FaultReport report() {
        return report;
    }
}
