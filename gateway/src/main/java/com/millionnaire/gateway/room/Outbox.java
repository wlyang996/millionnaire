package com.millionnaire.gateway.room;

/** 把消息推给某个玩家的当前连接（不在线就丢弃；重连后客户端用 SYNC 取最新快照）。 */
public interface Outbox {
    void send(String playerId, String json);
}
