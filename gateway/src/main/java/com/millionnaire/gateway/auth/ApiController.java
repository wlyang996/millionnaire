package com.millionnaire.gateway.auth;

import com.millionnaire.gateway.auth.UserStore.User;
import com.millionnaire.gateway.record.GameRecords;
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
    private final GameRecords records;
    private final WechatAuth wechat;

    public ApiController(UserStore users, SessionTokens tokens, RoomService rooms, GameRecords records, WechatAuth wechat,
                         @Value("${millionnaire.auth.test-login-enabled:true}") boolean testLoginEnabled) {
        this.wechat = wechat;
        this.records = records;
        this.users = users;
        this.tokens = tokens;
        this.rooms = rooms;
        this.testLoginEnabled = testLoginEnabled;
    }

    /** avatar：所选头像序号 0～7（可省略，省略时按玩家 ID 取默认头像）。 */
    public record TestLogin(String nickname, Integer avatar) {
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
        User user = users.create(nickname, body.avatar() == null ? -1 : body.avatar());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", tokens.issue(user.id()));
        out.put("userId", user.playerId());
        out.put("nickname", user.nickname());
        out.put("avatar", user.avatar());
        return ResponseEntity.ok(out);
    }

    /** code：wx.login 的一次性 code；nickname / avatar：资料页填写的昵称与头像（老用户可省略，省略时沿用已存的）。 */
    public record WxLogin(String code, String nickname, Integer avatar) {
    }

    /**
     * 微信登录：code 换 openid，按 openid 找用户。老用户直接返回（带了昵称 / 头像就顺便更新）；
     * 新用户必须带合法昵称，否则返回 needProfile=true（code 已用掉，客户端填完资料后重新 wx.login 再调）。
     */
    @PostMapping("/auth/wx-login")
    public ResponseEntity<Map<String, Object>> wxLogin(@RequestBody(required = false) WxLogin body) {
        if (!wechat.configured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_NOT_CONFIGURED");
        }
        if (body == null || body.code() == null || body.code().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        }
        Optional<String> openid = wechat.openid(body.code());
        if (openid.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "WECHAT_LOGIN_FAILED");
        }
        String nickname = body.nickname() == null ? null : body.nickname().strip();
        if (nickname != null && !nickname.isEmpty() && !rooms.validNickname(nickname)) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_NICKNAME");
        }
        boolean hasNick = nickname != null && !nickname.isEmpty();
        int avatar = body.avatar() == null ? -1 : body.avatar();
        Optional<User> found = users.findByOpenid(wechat.appId(), openid.get());
        User user;
        if (found.isPresent()) {
            user = hasNick || avatar >= 0 ? users.updateProfile(found.get(), hasNick ? nickname : found.get().nickname(), avatar) : found.get();
        } else if (!hasNick) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("needProfile", true);
            return ResponseEntity.ok(out);
        } else {
            user = users.createWithOpenid(wechat.appId(), openid.get(), nickname, avatar);
        }
        rooms.rememberAvatar(user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", tokens.issue(user.id()));
        out.put("userId", user.playerId());
        out.put("nickname", user.nickname());
        out.put("avatar", user.avatar());
        out.put("roomCode", rooms.roomOf(user.playerId()).map(LiveRoom::code).orElse(null));
        return ResponseEntity.ok(out);
    }

    /** 客户端据此决定走微信登录还是测试登录。 */
    @GetMapping("/auth/methods")
    public Map<String, Object> methods() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wechat", wechat.configured());
        out.put("test", testLoginEnabled);
        return out;
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
        out.put("avatar", user.get().avatar());
        out.put("roomCode", rooms.roomOf(user.get().playerId()).map(LiveRoom::code).orElse(null));
        return ResponseEntity.ok(out);
    }

    /** 我的最近 20 局（新的在前）：[{gameNo, endMode, timeLimitMinutes, boardId, playerCount, startedAt, endedAt, endReason, rank, netWorth, cash, life}]。 */
    @GetMapping("/me/history")
    public ResponseEntity<?> history(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> user = user(auth);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        return ResponseEntity.ok(records.recent(user.get().id()));
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

    /** 地图模板：[{id, minPlayers, maxPlayers, tiles:[{index, type, tier, auctionDesignated}]}]，无需登录。 */
    @GetMapping("/boards")
    public java.util.List<com.millionnaire.engine.config.BoardTemplate> boards() {
        return rooms.boards();
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
