package com.millionnaire.engine.core.command;

/**
 * 可信系统命令（定时唤醒、管理端操作等），只能由服务端内部产生，<b>绝不能由客户端消息构造</b>；
 * actor 恒为 null。客户端命令（其余 {@link Command}）的 actor 只能取自认证上下文。
 * 外层分别用 {@code Engine.admitClient} / {@code Engine.admitSystem} 把关来源。
 */
public interface SystemCommand extends Command {
    @Override
    default String actor() {
        return null;
    }
}
