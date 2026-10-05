package com.millionnaire.engine.core.engine;

/** 状态或事件日志不一致（损坏、伪造、版本错配）：恢复、重建、创世与 step 入口统一使用，原因保留在 cause。 */
public final class StateValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StateValidationException(String message) {
        super(message);
    }

    public StateValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
