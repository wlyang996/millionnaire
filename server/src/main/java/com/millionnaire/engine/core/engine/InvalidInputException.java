package com.millionnaire.engine.core.engine;

/**
 * 输入无法规范编码（孤立代理字符、未登记的命令类型等）。外层应在分配接收序号之前用 {@code Engine.admit} 拒收；
 * 若仍送入 step，引擎抛出本异常、不消耗序号、不改状态，外层可把同一序号分配给下一条合法输入。
 */
public final class InvalidInputException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public InvalidInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
