package com.millionnaire.gateway.auth;

import com.millionnaire.gateway.auth.UserStore.User;
import com.millionnaire.gateway.record.EventLogs;
import com.millionnaire.gateway.record.GameRecords;
import com.millionnaire.gateway.room.Wire;
import com.millionnaire.gateway.room.LiveRoom;
import com.millionnaire.gateway.room.RoomService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP 接口：登录、我的信息、当前房间快照。联机命令走 WebSocket /ws。 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiController.class);
    private final UserStore users;
    private final SessionTokens tokens;
    private final RoomService rooms;
    private final boolean testLoginEnabled;
    private final GameRecords records;
    private final WechatAuth wechat;
    private final Wire wire;

    public ApiController(UserStore users, SessionTokens tokens, RoomService rooms, GameRecords records, WechatAuth wechat,
                         Wire wire, @Value("${millionnaire.auth.test-login-enabled:true}") boolean testLoginEnabled) {
        this.wire = wire;
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
        WechatAuth.Exchange ex = wechat.exchange(body.code());
        if (ex.openid() == null) {
            // 带回微信错误码，便于排查（40029 code 无效 / AppID 不一致，40125 AppSecret 错，40013 AppID 错，-1 连不上微信）
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", "WECHAT_LOGIN_FAILED");
            err.put("wxErrcode", ex.errcode());
            err.put("message", ex.errmsg());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }
        Optional<String> openid = Optional.of(ex.openid());
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

    /**
     * 战绩详情（用户 2026-10-09）：我参加过的一局的表头、全部玩家的名次与资产（按名次），以及本局公开事件（与 UPDATE 消息里的
     * events 同形，客户端按对局记录的格式翻译；没存日志时为 null）。没参加过的局返回 404。
     */
    @GetMapping("/me/games/{roomId}/{gameNo}")
    public ResponseEntity<?> game(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @PathVariable("roomId") long roomId, @PathVariable("gameNo") long gameNo) {
        Optional<User> user = user(auth);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        Optional<GameRecords.Detail> found = records.detail(user.get().id(), roomId, gameNo);
        if (found.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        GameRecords.Detail d = found.get();
        GameRecords.Row h = d.header();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", h.roomId());
        out.put("gameNo", h.gameNo());
        out.put("endMode", h.endMode());
        out.put("timeLimitMinutes", h.timeLimitMinutes());
        out.put("boardId", h.boardId());
        out.put("playerCount", h.playerCount());
        out.put("startedAt", h.startedAt());
        out.put("endedAt", h.endedAt());
        out.put("endReason", h.endReason());
        out.put("initialCash", d.initialCash());
        List<Map<String, Object>> players = new ArrayList<>();
        d.seats().stream()
                .sorted(Comparator.comparing((GameRecords.Seat s) -> s.rank() == null ? Integer.MAX_VALUE : s.rank())
                        .thenComparingInt(GameRecords.Seat::seatNo))
                .forEach(s -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    Optional<User> u = users.find(s.userId());
                    p.put("playerId", String.valueOf(s.userId()));
                    p.put("nickname", u.map(User::nickname).orElse("玩家"));
                    p.put("avatar", u.map(User::avatar).orElse(0));
                    p.put("rank", s.rank());
                    p.put("netWorth", s.netWorth());
                    p.put("cash", s.cash());
                    p.put("life", s.life());
                    p.put("me", s.userId() == user.get().id());
                    players.add(p);
                });
        out.put("players", players);
        var result = com.millionnaire.gateway.record.ResultSnapshot.decode(d.resultJson());
        out.put("titles", result.titles());
        out.put("metrics", result.metrics());
        Object events = null;
        if (d.events() != null) {
            try {
                events = wire.events(EventLogs.decode(d.events()));
            } catch (RuntimeException e) {
                log.warn("cannot decode game log room {} game {}: {}", roomId, gameNo, e.toString());
            }
        }
        out.put("events", events);
        return ResponseEntity.ok(out);
    }

    /** 我的数据（全部已记录对局的汇总）：{games, finished, wins, top3, bankrupt, avgRank, bestNetWorth}。 */
    @GetMapping("/me/stats")
    public ResponseEntity<?> stats(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> user = user(auth);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        return ResponseEntity.ok(records.stats(user.get().id()));
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
