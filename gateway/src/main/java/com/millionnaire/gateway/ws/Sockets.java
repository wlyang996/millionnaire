package com.millionnaire.gateway.ws;

import com.millionnaire.gateway.room.Outbox;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** 玩家 → 当前连接。每个玩家只保留最新的一条连接（重复连接不能产生双重控制）。 */
@Component
public class Sockets implements Outbox {
    private static final Logger log = LoggerFactory.getLogger(Sockets.class);
    private static final String OUT = "millionnaire.out";

    private final Map<String, WebSocketSession> byPlayer = new ConcurrentHashMap<>();

    /** 绑定玩家的新连接，返回被取代的旧连接（没有则 null）。 */
    WebSocketSession bind(String playerId, WebSocketSession session) {
        WebSocketSession previous = byPlayer.put(playerId, out(session));
        return previous == null || previous.getId().equals(session.getId()) ? null : previous;
    }

    void unbind(String playerId, WebSocketSession session) {
        byPlayer.computeIfPresent(playerId, (k, v) -> v.getId().equals(session.getId()) ? null : v);
    }

    @Override
    public void send(String playerId, String json) {
        WebSocketSession s = byPlayer.get(playerId);
        if (s != null) {
            sendTo(s, json);
        }
    }

    void sendTo(WebSocketSession session, String json) {
        WebSocketSession s = out(session);
        if (!s.isOpen()) {
            return;
        }
        try {
            s.sendMessage(new TextMessage(json));
        } catch (IOException | RuntimeException e) {
            // 包括慢连接超出缓冲上限（装饰器会关闭该连接）；发送失败不能影响房间推进
            log.debug("send to {} failed: {}", s.getId(), e.getMessage());
        }
    }

    /** 同一连接上的并发发送须经装饰器串行化（房间推送、命令回复可能来自不同线程）。 */
    private static WebSocketSession out(WebSocketSession session) {
        if (session instanceof ConcurrentWebSocketSessionDecorator) {
            return session;
        }
        return (WebSocketSession) session.getAttributes().computeIfAbsent(OUT,
                k -> new ConcurrentWebSocketSessionDecorator(session, 10_000, 1 << 20));
    }
}
