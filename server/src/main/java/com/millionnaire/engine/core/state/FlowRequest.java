package com.millionnaire.engine.core.state;

/**
 * 排队中的流程申请（拍卖、交易）；每位申请者至多一个。标的：tile 为地块（无则 -1），counterparty 为交易买家，price 为交易价。
 */
public record FlowRequest(long requestId, FlowKind kind, String applicant, long submittedAt, int tile, String counterparty, long price) {
    public FlowRequest(long requestId, FlowKind kind, String applicant, long submittedAt) {
        this(requestId, kind, applicant, submittedAt, -1, null, 0);
    }
}
