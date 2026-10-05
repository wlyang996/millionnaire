package com.millionnaire.engine.core.engine;

/** 外层违反输入契约（序号跳跃、同序号不同内容）；引擎不改变状态，由外层修复投递顺序。 */
public final class InputOrderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InputOrderException(String message) {
        super(message);
    }
}
