package com.millionnaire.engine.core.engine;

/** 房间因内核故障而停止推进；在人工处理（{@link RoomRunner#clearFault}）之前拒绝一切输入。 */
public final class RoomHaltedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final transient FaultReport report;

    public RoomHaltedException(String message, FaultReport report) {
        super(message);
        this.report = report;
    }

    public FaultReport report() {
        return report;
    }
}
