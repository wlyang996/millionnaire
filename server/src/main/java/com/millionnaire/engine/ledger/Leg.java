package com.millionnaire.engine.ledger;

/** 分录的一条腿：账户余额变动（正为收入，负为支出），不允许为 0。 */
public record Leg(String account, long delta) {
}
