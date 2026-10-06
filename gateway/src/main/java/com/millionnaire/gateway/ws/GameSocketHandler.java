package com.millionnaire.gateway.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.millionnaire.engine.core.command.GameCommand.SetControl;
import com.millionnaire.engine.core.command.RoomCommand.Kick;
import com.millionnaire.engine.core.command.RoomCommand.Leave;
import com.millionnaire.engine.core.command.RoomCommand.SetReady;
import com.millionnaire.engine.core.command.RoomCommand.ChangeSettings;
import com.millionnaire.engine.core.command.SessionCommand.StartGame;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.gateway.auth.SessionTokens;
import com.millionnaire.gateway.auth.UserStore;
import com.millionnaire.gateway.auth.UserStore.User;
import com.millionnaire.gateway.room.ClientException;
import com.millionnaire.gateway.room.LiveRoom;
import com.millionnaire.gateway.room.RoomService;
import com.millionnaire.gateway.room.Wire;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 客户端协议（完整说明见 gateway/README.md）。每条客户端消息是一个 JSON 对象，type 决定动作；
 * 改变状态的消息必须带 requestId，服务端回 RESULT（同一 requestId 重发时原样返回上次结论）。
 * 身份只取自登录令牌：命令里的 actor / playerId 一律由服务端写入。
 */
@Component
public class GameSocketHandler extends TextWebSocketHandler {
    static final String USER = "millionnaire.userId";
    private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);
    private static final int MAX_REQUEST_ID = 64;

    private final RoomService rooms;
    private final UserStore users;
    private final SessionTokens tokens;
    private final Sockets sockets;
    private final Wire wire;
    private final Clock clock;

    public GameSocketHandler(RoomService rooms, UserStore users, SessionTokens tokens, Sockets sockets, Wire wire,
                             Clock clock) {
        this.rooms = rooms;
        this.users = users;
        this.tokens = tokens;
        this.sockets = sockets;
        this.wire = wire;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (session.getAttributes().get(USER) instanceof Long uid) {
            authenticate(session, uid);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getAttributes().get(USER) instanceof Long uid) {
            sockets.unbind(Long.toString(uid), session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String requestId = null;
        try {
            JsonNode msg = wire.read(message.getPayload());
            String type = text(msg, "type");
            requestId = text(msg, "requestId");
            if ("AUTH".equals(type)) {
                Long uid = tokens.resolve(text(msg, "token"))
                        .orElseThrow(() -> new ClientException("UNAUTHENTICATED", "invalid token"));
                authenticate(session, uid);
                return;
            }
            User user = currentUser(session);
            if (type == null) {
                throw new ClientException("BAD_REQUEST", "type missing");
            }
            switch (type) {
                case "PING" -> {
                    ObjectNode pong = wire.object().put("type", "PONG").put("serverTime", clock.millis());
                    sockets.sendTo(session, wire.write(pong));
                }
                case "SYNC" -> sync(session, user);
                case "CHAT" -> {
                    LiveRoom room = rooms.roomOf(user.playerId())
                            .orElseThrow(() -> new ClientException("NOT_IN_ROOM", "join or create a room first"));
                    room.chat(user.playerId(), user.nickname(), text(msg, "text"));
                    if (requestId != null) {
                        sockets.sendTo(session, wire.write(result(requestId, "ACCEPTED", null, room.code())));
                    }
                }
                default -> command(session, user, type, requireRequestId(requestId), msg);
            }
        } catch (ClientException e) {
            sockets.sendTo(session, wire.write(result(requestId, "ERROR", e.code(), null).put("message", e.getMessage())));
        } catch (RuntimeException e) {
            log.error("websocket message failed", e);
            sockets.sendTo(session, wire.write(result(requestId, "ERROR", "SERVER_ERROR", null)));
        }
    }

    private void command(WebSocketSession session, User user, String type, String requestId, JsonNode msg) {
        String pid = user.playerId();
        LiveRoom.Reply reply;
        String roomCode;
        switch (type) {
            case "CREATE_ROOM" -> {
                JsonNode s = msg.get("settings");
                RoomService.Created c = rooms.create(user, requestId, s == null || s.isNull() ? null : wire.settings(s));
                roomCode = c.room().code();
                ObjectNode r = result(requestId, "ACCEPTED", null, roomCode);
                if (c.settings() != null && !c.settings().ok()) {
                    r.put("settingsCode", c.settings().code());
                }
                sockets.sendTo(session, wire.write(r));
                return;
            }
            case "JOIN_ROOM" -> {
                int code = roomCode(msg);
                LiveRoom target = rooms.byCode(code).orElse(null);
                if (target != null && target.isMember(pid)) {
                    // 已在房间里：当作重连同步
                    sockets.sendTo(session, wire.write(result(requestId, "ACCEPTED", null, target.code())));
                    sockets.sendTo(session, target.snapshot(pid));
                    return;
                }
                reply = rooms.join(user, code, requestId);
                roomCode = String.format("%06d", code);
            }
            default -> {
                LiveRoom room = rooms.roomOf(pid)
                        .orElseThrow(() -> new ClientException("NOT_IN_ROOM", "join or create a room first"));
                roomCode = room.code();
                reply = switch (type) {
                    case "LEAVE_ROOM" -> room.submitClient(pid, requestId, new Leave(pid));
                    case "READY" -> room.submitClient(pid, requestId, new SetReady(pid, bool(msg, "ready")));
                    case "UPDATE_SETTINGS" -> room.submitClient(pid, requestId,
                            new ChangeSettings(pid, wire.settings(msg.get("settings"))));
                    case "KICK" -> room.submitClient(pid, requestId, new Kick(pid, text(msg, "target")));
                    case "START_GAME" -> room.submitClient(pid, requestId, new StartGame(pid));
                    case "GAME" -> room.submitClient(pid, requestId,
                            wire.gameCommand(text(msg, "command"), msg.get("args"), pid));
                    case "SET_CONTROL" -> setControl(room, pid, text(msg, "mode"));
                    default -> throw new ClientException("UNKNOWN_TYPE", "unknown message type: " + type);
                };
            }
        }
        sockets.sendTo(session, wire.write(result(requestId, reply.outcome(), reply.code(), roomCode)));
    }

    /** 暂离 / 托管 / 手动：玩家只能切换自己的控制模式，由服务端转成可信系统命令。 */
    private static LiveRoom.Reply setControl(LiveRoom room, String pid, String mode) {
        ControlMode m;
        try {
            m = ControlMode.valueOf(mode == null ? "" : mode);
        } catch (IllegalArgumentException e) {
            throw new ClientException("BAD_REQUEST", "mode must be MANUAL, AWAY or HOSTED");
        }
        OptionalLong gameNo = room.gameNo();
        if (gameNo.isEmpty()) {
            throw new ClientException("NOT_IN_GAME", "no game in progress");
        }
        return room.submitSystem(new SetControl(gameNo.getAsLong(), pid, m));
    }

    private void authenticate(WebSocketSession session, long uid) {
        User user = users.find(uid).orElse(null);
        if (user == null) {
            sockets.sendTo(session, wire.write(result(null, "ERROR", "UNAUTHENTICATED", null)));
            return;
        }
        // 同一连接换了账号：旧账号不再经这条连接收消息
        if (session.getAttributes().get(USER) instanceof Long previous && previous != uid) {
            sockets.unbind(Long.toString(previous), session);
        }
        session.getAttributes().put(USER, uid);
        WebSocketSession replaced = sockets.bind(user.playerId(), session);
        if (replaced != null) {
            sockets.sendTo(replaced, wire.write(wire.object().put("type", "REPLACED")));
            try {
                replaced.close(new CloseStatus(4001, "replaced by a newer connection"));
            } catch (IOException e) {
                log.debug("closing replaced session failed", e);
            }
        }
        Optional<LiveRoom> room = rooms.roomOf(user.playerId());
        ObjectNode hello = wire.object().put("type", "HELLO").put("userId", user.playerId())
                .put("nickname", user.nickname()).put("serverTime", clock.millis());
        hello.put("roomCode", room.map(LiveRoom::code).orElse(null));
        sockets.sendTo(session, wire.write(hello));
        room.ifPresent(r -> {
            sockets.sendTo(session, r.snapshot(user.playerId()));
            sockets.sendTo(session, r.chatMessage());
        });
    }

    private void sync(WebSocketSession session, User user) {
        Optional<LiveRoom> room = rooms.roomOf(user.playerId());
        if (room.isPresent()) {
            sockets.sendTo(session, room.get().snapshot(user.playerId()));
            sockets.sendTo(session, room.get().chatMessage());
        } else {
            sockets.sendTo(session, wire.write(wire.object().put("type", "NO_ROOM")));
        }
    }

    private User currentUser(WebSocketSession session) {
        if (session.getAttributes().get(USER) instanceof Long uid) {
            return users.find(uid).orElseThrow(() -> new ClientException("UNAUTHENTICATED", "unknown user"));
        }
        throw new ClientException("UNAUTHENTICATED", "send AUTH with a login token first");
    }

    private ObjectNode result(String requestId, String outcome, String code, String roomCode) {
        ObjectNode n = wire.object().put("type", "RESULT").put("requestId", requestId)
                .put("ok", "ACCEPTED".equals(outcome)).put("outcome", outcome).put("code", code);
        if (roomCode != null) {
            n.put("roomCode", roomCode);
        }
        return n;
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID) {
            throw new ClientException("REQUEST_ID_REQUIRED", "requestId (1-64 chars) is required");
        }
        return requestId;
    }

    private static int roomCode(JsonNode msg) {
        String raw = text(msg, "roomCode");
        if (raw == null || !raw.matches("\\d{1,6}")) {
            throw new ClientException("BAD_REQUEST", "roomCode must be 6 digits");
        }
        return Integer.parseInt(raw);
    }

    private static String text(JsonNode msg, String field) {
        JsonNode v = msg == null ? null : msg.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean bool(JsonNode msg, String field) {
        JsonNode v = msg.get(field);
        if (v == null || !v.isBoolean()) {
            throw new ClientException("BAD_REQUEST", field + " must be true or false");
        }
        return v.asBoolean();
    }
}
