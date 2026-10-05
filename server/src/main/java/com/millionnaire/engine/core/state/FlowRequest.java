package com.millionnaire.engine.core.state;

/** 排队中的流程申请（拍卖、交易）；每位申请者至多一个。 */
public record FlowRequest(long requestId, FlowKind kind, String applicant, long submittedAt) {
}
