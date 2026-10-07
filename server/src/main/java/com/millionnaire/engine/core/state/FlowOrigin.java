package com.millionnaire.engine.core.state;
/** 权威流程来源：申请编号与安全点，或当前行动/债务/父攻击窗口；恢复不能仅根据窗口种类猜测。 */
public record FlowOrigin(Kind kind, long scopeId, long ref, int cursor, String actor) {
    public enum Kind { QUEUED, ACTIVE_CARD, DEBT, ATTACK_RESPONSE, RENT_RESPONSE, MINIGAME }
}
