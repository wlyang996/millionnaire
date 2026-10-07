package com.millionnaire.gateway.room;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 每位玩家最后一次收到有效消息的时刻（心跳 PING、命令、聊天、AUTH 都算）。
 * 房间据此判定连接状态（已裁决 2）：连续 15 秒无消息为疑似断线，30 秒为确认掉线，再次收到消息即重连。
 */
@Component
public class Presence {
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    /** 收到该玩家的一条有效消息。 */
    public void touch(String playerId, long now) {
        lastSeen.merge(playerId, now, Math::max);
    }

    /** 最后一次收到消息的时刻；从未见过的玩家从现在开始计时。 */
    long lastSeen(String playerId, long now) {
        return lastSeen.computeIfAbsent(playerId, k -> now);
    }
}
