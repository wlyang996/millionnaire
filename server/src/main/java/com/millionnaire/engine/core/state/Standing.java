package com.millionnaire.engine.core.state;

/** 结算名次（O21：并列按 1、1、3 编号）。 */
public record Standing(String playerId, int rank, long netWorth, long cash) {
}
