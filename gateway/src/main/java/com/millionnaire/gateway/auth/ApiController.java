package com.millionnaire.gateway.auth;

import com.millionnaire.gateway.auth.UserStore.User;
import com.millionnaire.gateway.room.LiveRoom;
import com.millionnaire.gateway.room.RoomService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP 接口：登录、我的信息、当前房间快照。联机命令走 WebSocket /ws。 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final UserStore users;
    private final SessionTokens tokens;
    private final RoomService rooms;
    private final boolean testLoginEnabled;

    public ApiController(UserStore users, SessionTokens tokens, RoomService rooms,
                         @Value("${millionnaire.auth.test-login-enabled:true}") boolean testLoginEnabled) {
        this.users = users;
        this.tokens = tokens;
        this.rooms = rooms;
        this.testLoginEnabled = testLoginEnabled;
    }

    public record TestLogin(String nickname) {
    }

    /** 测试身份登录：每次创建一个新用户（不依赖微信）。上线前用 TEST_LOGIN_ENABLED=false 关闭。 */
    @PostMapping("/auth/test-login")
    public ResponseEntity<Map<String, Object>> testLogin(@RequestBody(required = false) TestLogin body) {
        if (!testLoginEnabled) {
            return error(HttpStatus.NOT_FOUND, "TEST_LOGIN_DISABLED");
        }
        String nickname = body == null || body.nickname() == null ? null : body.nickname().strip();
        if (nickname == null || !rooms.validNickname(nickname)) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_NICKNAME");
        }
        User user = users.create(nickname);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", tokens.issue(user.id()));
        out.put("userId", user.playerId());
        out.put("nickname", user.nickname());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> user = user(auth);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.get().playerId());
        out.put("nickname", user.get().nickname());
        out.put("roomCode", rooms.roomOf(user.get().playerId()).map(LiveRoom::code).orElse(null));
        return ResponseEntity.ok(out);
    }

    /** 当前房间的快照（与 WebSocket 的 UPDATE 消息同形，events 为空）。 */
    @GetMapping("/room")
    public ResponseEntity<?> room(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> user = user(auth);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        Optional<LiveRoom> room = rooms.roomOf(user.get().playerId());
        if (room.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "NOT_IN_ROOM");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(room.get().snapshot(user.get().playerId()));
    }

    private Optional<User> user(String authorization) {
        return tokens.fromAuthorization(authorization).flatMap(users::find);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        return ResponseEntity.status(status).body(out);
    }
}
