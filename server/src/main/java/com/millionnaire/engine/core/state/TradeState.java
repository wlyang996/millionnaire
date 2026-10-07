package com.millionnaire.engine.core.state;

/**
 * 进行中的交易（交易卡，requirements 第 14 节、open-decisions #12）：卖家把自己一块未抵押的地产或车站以自定价卖给指定买家；
 * 价格在标准价值的 50%（向上取整）～ 2.5 倍（向下取整）之间。交易期间该资产锁定；买家 15 秒内同意且现金足额才成交，
 * 拒绝或超时视为拒绝（交易卡保留，主动用卡机会已在申请时消耗）。
 */
public record TradeState(String seller, String buyer, int tile, long price) {
}
