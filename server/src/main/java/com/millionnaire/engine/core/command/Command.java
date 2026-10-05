package com.millionnaire.engine.core.command;

/**
 * 引擎命令（开放接口：内核命令 {@link Tick} + 各领域命令，均须在引擎的类型登记表中注册）。
 * 身份由外层鉴权后注入，引擎只做规则判定。
 */
public interface Command {
    /** 命令发起者；拒绝结果只私发给它。系统输入（如 Tick）为 null。 */
    String actor();
}
