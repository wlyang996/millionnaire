package com.millionnaire.engine.core.event;

/**
 * 完整事件（服务端权威，开放接口：内核事件 {@link KernelEvent} + 领域事件）。事件日志足以重建全部状态。
 * <p><b>默认保密</b>：未声明可见性的事件为 SERVER_ONLY，公开事件必须显式声明 PUBLIC，
 * 私有事件声明 PRIVATE 并给出 {@link #recipient()}。可见性只决定投影，不影响回放。
 */
public interface Event {

    default Visibility visibility() {
        return Visibility.SERVER_ONLY;
    }

    /** PRIVATE 事件的唯一接收者；其他可见性为 null。 */
    default String recipient() {
        return null;
    }
}
