package com.millionnaire.engine.random;

import java.util.Optional;

/** 已知随机协议的查找表（审计按事件中的协议 ID 选择实现）。 */
public final class RandomProtocols {
    private RandomProtocols() {
    }

    public static Optional<RandomSourceFactory> forId(String protocolId) {
        return XoshiroLemireV1.PROTOCOL_ID.equals(protocolId) ? Optional.of(XoshiroLemireV1.INSTANCE) : Optional.empty();
    }
}
