package com.millionnaire.engine.core.state;
/** 费用凭据，种类、来源任务、资产、债权人及金额共同核对。 */
public record FeeSource(Kind kind, long landingId, int cursor, int tile, String creditor, long amount) {
    public enum Kind { RENT, FINE, SYSTEM }
}
