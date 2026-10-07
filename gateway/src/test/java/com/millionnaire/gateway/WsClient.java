package com.millionnaire.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 测试用 WebSocket 客户端：收到的消息按顺序记下，await 依次向后查找匹配的消息。 */
final class WsClient extends TextWebSocketHandler implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    volatile CloseStatus closedWith;
    private final WebSocketSession session;
    private int cursor;

    WsClient(int port, String token) throws Exception {
        String uri = "ws://localhost:" + port + "/ws" + (token == null ? "" : "?token=" + token);
        session = new StandardWebSocketClient().execute(this, new WebSocketHttpHeaders(), URI.create(uri))
                .get(5, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
        received.add(JSON.readTree(message.getPayload()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        closedWith = status;
    }

    ObjectNode msg(String type, String requestId) {
        ObjectNode n = JSON.createObjectNode().put("type", type);
        if (requestId != null) {
            n.put("requestId", requestId);
        }
        return n;
    }

    void send(JsonNode n) throws Exception {
        synchronized (session) {
            session.sendMessage(new TextMessage(JSON.writeValueAsString(n)));
        }
    }

    /** 发送并等待对应 requestId 的 RESULT。 */
    JsonNode call(ObjectNode n) throws Exception {
        String rid = n.path("requestId").asText(null);
        send(n);
        return await(m -> "RESULT".equals(m.path("type").asText()) && (rid == null || rid.equals(m.path("requestId").asText(null))));
    }

    JsonNode await(Predicate<JsonNode> p) throws InterruptedException {
        return await(p, 10_000);
    }

    JsonNode await(Predicate<JsonNode> p, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (int i = cursor; i < received.size(); i++) {
                JsonNode m = received.get(i);
                if (p.test(m)) {
                    cursor = i + 1;
                    return m;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out; received: " + received);
    }

    /** 等待某个类型的消息。 */
    JsonNode awaitType(String type) throws InterruptedException {
        return await(m -> type.equals(m.path("type").asText()));
    }

    /** 最后收到的 UPDATE。 */
    JsonNode lastUpdate() {
        for (int i = received.size() - 1; i >= 0; i--) {
            if ("UPDATE".equals(received.get(i).path("type").asText())) {
                return received.get(i);
            }
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        if (session.isOpen()) {
            session.close();
        }
    }
}
