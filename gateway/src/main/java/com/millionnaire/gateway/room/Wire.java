package com.millionnaire.gateway.room;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.SystemCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.SessionView;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** WebSocket / HTTP 的 JSON 形状（协议见 gateway/README.md）。 */
@Component
public class Wire {
    private static final Logger log = LoggerFactory.getLogger(Wire.class);

    /** 客户端可发的对局命令：GameCommand 中除系统命令以外的全部记录类型，按简单类名索引。 */
    private static final Map<String, Class<?>> GAME_COMMANDS;

    static {
        Map<String, Class<?>> m = new TreeMap<>();
        for (Class<?> c : GameCommand.class.getPermittedSubclasses()) {
            if (!SystemCommand.class.isAssignableFrom(c)) {
                m.put(c.getSimpleName(), c);
            }
        }
        GAME_COMMANDS = Collections.unmodifiableMap(m);
    }

    private final ObjectMapper json;

    public Wire(ObjectMapper json) {
        this.json = json;
    }

    public static java.util.Set<String> gameCommandNames() {
        return GAME_COMMANDS.keySet();
    }

    public ObjectNode object() {
        return json.createObjectNode();
    }

    public String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new ClientException("BAD_JSON", "message is not valid JSON");
        }
    }

    /** 一步之后发给某个观察者的消息：本步对他可见的事件 + 他的最新视图。 */
    public String update(String roomCode, long version, long serverTime, List<Event> events, SessionView view) {
        return update(roomCode, version, serverTime, events, view, java.util.Map.of(), 0);
    }

    /**
     * @param avatars  玩家所选头像（玩家 ID → 序号 0～7）；没选的不在表里。
     * @param configId 房间绑定的游戏参数版本（客户端据此拉取 /api/configs/{id}/client）。
     */
    public String update(String roomCode, long version, long serverTime, List<Event> events, SessionView view,
                         java.util.Map<String, Integer> avatars, long configId) {
        ObjectNode n = object();
        n.put("type", "UPDATE");
        n.put("roomCode", roomCode);
        n.put("version", version);
        n.put("serverTime", serverTime);
        n.put("configId", configId);
        ArrayNode ev = n.putArray("events");
        for (Event e : events) {
            ObjectNode o = ev.addObject();
            o.put("kind", e.getClass().getSimpleName());
            try {
                o.set("data", json.valueToTree(e));
            } catch (IllegalArgumentException ex) {
                log.warn("cannot serialize event {}", e.getClass().getName(), ex);
                o.putNull("data");
            }
        }
        n.set("view", json.valueToTree(view));
        ObjectNode av = n.putObject("avatars");
        avatars.forEach(av::put);
        return write(n);
    }

    public String closed(String roomCode, String reason) {
        ObjectNode n = object();
        n.put("type", "ROOM_CLOSED");
        n.put("roomCode", roomCode);
        n.put("reason", reason);
        return write(n);
    }

    /** 客户端对局命令：actor 一律由服务端写入，客户端传来的同名字段被覆盖。 */
    public GameCommand gameCommand(String name, JsonNode args, String actor) {
        Class<?> type = name == null ? null : GAME_COMMANDS.get(name);
        if (type == null) {
            throw new ClientException("UNKNOWN_COMMAND", "unknown game command: " + name);
        }
        ObjectNode a = args instanceof ObjectNode o ? o.deepCopy() : object();
        a.put("actor", actor);
        try {
            return (GameCommand) json.treeToValue(a, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ClientException("BAD_REQUEST", "invalid arguments for " + name);
        }
    }

    public RoomSettings settings(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ClientException("BAD_REQUEST", "settings must be an object");
        }
        try {
            return json.treeToValue(node, RoomSettings.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ClientException("BAD_REQUEST", "invalid settings");
        }
    }
}
