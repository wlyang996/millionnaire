package com.millionnaire.engine.core.engine;

/** 恢复或重建得到的状态不一致（损坏、伪造或版本错配）。 */
public final class StateValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StateValidationException(String message) {
        super(message);
    }
}
