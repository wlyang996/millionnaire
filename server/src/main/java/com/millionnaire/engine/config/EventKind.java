package com.millionnaire.engine.config;

/**
 * 事件结果类型（requirements 第 12 节）。BUILD 起为 2026-10-08 新增（用户）：
 * BUILD 随机给自己一块未抵押、未满级的普通地产免费升一级；DOWNGRADE 随机让自己一块有等级的未抵押地产降一级（没有合适的地就无事发生）；
 * TO_STATION 前进到随机一个车站；TO_START 前进回到起点（经过 / 落在起点照常领奖励，路障照常拦）。
 * 新类型排在最后、旧配置权重为 0，旧对局的抽取结果不变。
 */
public enum EventKind {
    CASH_REWARD, CASH_FINE, CARD, MOVE, JAIL, BUILD, DOWNGRADE, TO_STATION, TO_START
}
