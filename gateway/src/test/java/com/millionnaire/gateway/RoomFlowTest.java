package com.millionnaire.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 端到端：测试登录 → WebSocket 建房、加入、准备、开局、投骰 → 数据库里的用户与房间行。
 * 数据库用 H2（MySQL 模式）代替，只证明接线与协议，不证明 MySQL 5.7 的行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("db")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.placeholders.table_options="
})
class RoomFlowTest {
    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;

    /**
     * H2 2.x 的 MySQL 模式下 LENGTH(VARBINARY) 按 UTF-8 解码后的字符数计算，随机字节常不等于 16；
     * 真实 MySQL 的 LENGTH 是字节数（5.7 还会忽略 CHECK）。测试里把这条约束换成 H2 下按字节计的 OCTET_LENGTH。
     */
    @BeforeEach
    void h2ByteLengthCheck() {
        jdbc.execute("ALTER TABLE room DROP CONSTRAINT IF EXISTS ck_room_ids");
        jdbc.execute("ALTER TABLE room ADD CONSTRAINT ck_room_ids CHECK (OCTET_LENGTH(create_request_id) = 16"
                + " AND max_saved_game_no >= 0)");
        jdbc.execute("ALTER TABLE game_record DROP CONSTRAINT IF EXISTS ck_game_record_values");
        jdbc.execute("ALTER TABLE game_record ADD CONSTRAINT ck_game_record_values CHECK (game_no >= 1 AND player_count >= 1"
                + " AND player_count <= 8 AND initial_cash >= 0 AND ended_at >= started_at AND OCTET_LENGTH(draft_sha256) = 32)");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String nickname) {
        return http.postForObject("/api/auth/test-login", Map.of("nickname", nickname), Map.class);
    }

    @Test
    void createJoinStartAndRoll() throws Exception {
        Map<String, Object> a = login("阿杰");
        Map<String, Object> b = login("糖糖");
        String aid = (String) a.get("userId");
        String bid = (String) b.get("userId");
        try (WsClient wa = new WsClient(port, (String) a.get("token"));
             WsClient wb = new WsClient(port, (String) b.get("token"))) {
            assertThat(wa.awaitType("HELLO").path("userId").asText()).isEqualTo(aid);
            wb.awaitType("HELLO");

            JsonNode created = wa.call(wa.msg("CREATE_ROOM", "c1"));
            assertThat(created.path("ok").asBoolean()).as(created.toString()).isTrue();
            String code = created.path("roomCode").asText();
            assertThat(code).matches("\\d{6}");
            // 同一 requestId 重发：同一个房间
            assertThat(wa.call(wa.msg("CREATE_ROOM", "c1")).path("roomCode").asText()).isEqualTo(code);
            // 已在房间里不能再建
            assertThat(wa.call(wa.msg("CREATE_ROOM", "c2")).path("code").asText()).isEqualTo("ALREADY_IN_ROOM");

            JsonNode joined = wb.call(wb.msg("JOIN_ROOM", "j1").put("roomCode", code));
            assertThat(joined.path("ok").asBoolean()).as(joined.toString()).isTrue();
            JsonNode lobby = wa.await(m -> "UPDATE".equals(m.path("type").asText())
                    && m.path("view").path("members").size() == 2);
            assertThat(lobby.path("view").path("hostId").asText()).isEqualTo(aid);

            // 聊天：房间里每个人都收到最近聊天的完整列表；控制字符被清掉；空消息、发得太快被拒
            JsonNode said = wb.call(wb.msg("CHAT", "chat-1").put("text", " 大家好\u0007 "));
            assertThat(said.path("ok").asBoolean()).as(said.toString()).isTrue();
            JsonNode chat = wa.await(m -> "CHAT".equals(m.path("type").asText()) && m.path("lines").size() == 1);
            assertThat(chat.path("lines").get(0).path("text").asText()).isEqualTo("大家好");
            assertThat(chat.path("lines").get(0).path("from").asText()).isEqualTo(bid);
            assertThat(chat.path("lines").get(0).path("nickname").asText()).isEqualTo("糖糖");
            assertThat(wb.call(wb.msg("CHAT", "chat-2").put("text", "再来")).path("code").asText()).isEqualTo("TOO_FAST");
            assertThat(wa.call(wa.msg("CHAT", "chat-3").put("text", "   ")).path("code").asText()).isEqualTo("BAD_REQUEST");

            // 系统命令不能由客户端发；开局前没有对局
            assertThat(wa.call(wa.msg("GAME", "g0").put("command", "SetControl")).path("code").asText())
                    .isEqualTo("UNKNOWN_COMMAND");
            assertThat(wa.call(wa.msg("SET_CONTROL", "s0").put("mode", "HOSTED")).path("code").asText())
                    .isEqualTo("NOT_IN_GAME");
            // 非房主不能开局
            assertThat(wb.call(wb.msg("START_GAME", "x1")).path("code").asText()).isEqualTo("NOT_HOST");

            assertThat(wa.call(wa.msg("READY", "r1").put("ready", true)).path("ok").asBoolean()).isTrue();
            assertThat(wb.call(wb.msg("READY", "r2").put("ready", true)).path("ok").asBoolean()).isTrue();
            JsonNode started = wa.call(wa.msg("START_GAME", "start"));
            assertThat(started.path("ok").asBoolean()).as(started.toString()).isTrue();

            JsonNode game = wb.await(m -> "UPDATE".equals(m.path("type").asText())
                    && !m.path("view").path("game").isNull() && m.path("view").path("game").isObject());
            assertThat(game.path("view").path("game").path("players").size()).isEqualTo(2);
            // 私有事件只发给接收者：B 收到的发牌事件全是 B 自己的
            for (JsonNode e : game.path("events")) {
                if ("CardDealt".equals(e.path("kind").asText())) {
                    assertThat(e.path("data").path("recipient").asText()).isEqualTo(bid);
                }
            }
            assertThat(game.path("events").toString()).contains("CardDealt");

            // 开局那一步的 UPDATE 先于 RESULT 到达：取最新视图里当前玩家的回合窗口，等它开启后由当前玩家投骰
            JsonNode turn = wa.lastUpdate();
            String current = turn.path("view").path("game").path("currentPlayer").asText();
            JsonNode window = ownWindow(turn, current);
            assertThat(window).as(turn.toString()).isNotNull();
            long wait = window.path("opensAt").asLong() - turn.path("serverTime").asLong();
            if (wait > 0) {
                Thread.sleep(wait + 50);
            }
            WsClient mover = current.equals(aid) ? wa : wb;
            WsClient other = current.equals(aid) ? wb : wa;
            JsonNode wrong = other.call(other.msg("GAME", "roll-x").put("command", "RollDice")
                    .set("args", other.msg("x", null).put("windowId", window.path("windowId").asLong())));
            assertThat(wrong.path("ok").asBoolean()).as(wrong.toString()).isFalse();
            JsonNode rolled = mover.call(mover.msg("GAME", "roll-1").put("command", "RollDice")
                    .set("args", mover.msg("x", null).put("windowId", window.path("windowId").asLong())));
            assertThat(rolled.path("ok").asBoolean()).as(rolled.toString()).isTrue();
            // 这一步的 UPDATE 先于 RESULT 到达
            JsonNode moved = mover.lastUpdate();
            assertThat(moved.path("events").toString()).contains("DiceRolled", "PlayerMoved", "Landed");
            JsonNode me = moved.path("view").path("game").path("players").get(0);
            assertThat(me.path("playerId").asText()).isEqualTo(current);
            assertThat(me.path("position").asInt()).isBetween(1, 6);

            // 数据库：两个用户、一个 OPEN 房间
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE user_id IN (?, ?)", Integer.class,
                    Long.parseLong(aid), Long.parseLong(bid))).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM room WHERE room_code = ? AND status = 'OPEN'",
                    String.class, Integer.parseInt(code))).isEqualTo("OPEN");

            // 同一用户的新连接取代旧连接，并拿到房间快照
            try (WsClient wa2 = new WsClient(port, (String) a.get("token"))) {
                JsonNode hello = wa2.awaitType("HELLO");
                assertThat(hello.path("roomCode").asText()).isEqualTo(code);
                assertThat(wa2.awaitType("UPDATE").path("view").path("game").isObject()).isTrue();
                wa.awaitType("REPLACED");
            }
        }
    }

    @Test
    void lastMemberLeavingClosesTheRoom() throws Exception {
        Map<String, Object> a = login("圆圆");
        Map<String, Object> b = login("豆豆");
        try (WsClient wa = new WsClient(port, (String) a.get("token"));
             WsClient wb = new WsClient(port, null)) {
            wa.awaitType("HELLO");
            // 未登录的连接先发 AUTH
            assertThat(wb.call(wb.msg("SYNC", "s")).path("code").asText()).isEqualTo("UNAUTHENTICATED");
            wb.send(wb.msg("AUTH", null).put("token", (String) b.get("token")));
            wb.awaitType("HELLO");

            String code = wa.call(wa.msg("CREATE_ROOM", "c")).path("roomCode").asText();
            assertThat(wa.call(wa.msg("LEAVE_ROOM", "l")).path("ok").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("SELECT close_reason FROM room WHERE room_code = ? AND status = 'CLOSED'",
                    String.class, Integer.parseInt(code))).isEqualTo("ENGINE");
            assertThat(wb.call(wb.msg("JOIN_ROOM", "j").put("roomCode", code)).path("code").asText())
                    .isEqualTo("ROOM_NOT_FOUND");
            assertThat(wa.call(wa.msg("READY", "r").put("ready", true)).path("code").asText()).isEqualTo("NOT_IN_ROOM");
        }
    }

    /** 测试机器人：一个人建房、加机器人（自动准备）、开局；机器人转为托管并由服务端自动投骰。 */
    @Test
    void soloPlayerStartsWithABot() throws Exception {
        Map<String, Object> a = login("毛毛");
        String aid = (String) a.get("userId");
        try (WsClient wa = new WsClient(port, (String) a.get("token"))) {
            wa.awaitType("HELLO");
            assertThat(wa.call(wa.msg("ADD_BOT", "b0")).path("code").asText()).isEqualTo("NOT_IN_ROOM");
            String code = wa.call(wa.msg("CREATE_ROOM", "c")).path("roomCode").asText();
            JsonNode added = wa.call(wa.msg("ADD_BOT", "b1"));
            assertThat(added.path("ok").asBoolean()).as(added.toString()).isTrue();
            assertThat(added.path("roomCode").asText()).isEqualTo(code);
            JsonNode lobby = wa.lastUpdate();
            JsonNode bot = lobby.path("view").path("members").get(1);
            assertThat(bot.path("nickname").asText()).isEqualTo("机器人1");
            assertThat(bot.path("ready").asBoolean()).as(lobby.toString()).isTrue();
            String botId = bot.path("playerId").asText();

            assertThat(wa.call(wa.msg("READY", "r").put("ready", true)).path("ok").asBoolean()).isTrue();
            JsonNode started = wa.call(wa.msg("START_GAME", "s"));
            assertThat(started.path("ok").asBoolean()).as(started.toString()).isTrue();
            assertThat(wa.call(wa.msg("ADD_BOT", "b2")).path("code").asText()).isEqualTo("IN_GAME");

            // 开局那一步之后机器人立即转为托管（UPDATE 先于开局的 RESULT 到达）
            JsonNode hosted = wa.lastUpdate();
            assertThat(controlOf(hosted, botId)).isEqualTo("HOSTED");
            assertThat(controlOf(hosted, aid)).isEqualTo("MANUAL");
            // 轮到人时自己投一次；之后机器人应在自动动作延时后自己投骰
            JsonNode turn = wa.lastUpdate();
            if (aid.equals(turn.path("view").path("game").path("currentPlayer").asText())) {
                JsonNode w = ownWindow(turn, aid);
                long wait = w.path("opensAt").asLong() - turn.path("serverTime").asLong();
                if (wait > 0) {
                    Thread.sleep(wait + 50);
                }
                assertThat(wa.call(wa.msg("GAME", "roll").put("command", "RollDice")
                        .set("args", wa.msg("x", null).put("windowId", w.path("windowId").asLong()))).path("ok").asBoolean()).isTrue();
            }
            // 人落地后的决策窗口直接处理（放弃买地、不用卡结束回合等），并保持心跳，直到机器人自己投骰
            int start = wa.received.size();
            JsonNode botRoll = null;
            int req = 0;
            for (long until = System.currentTimeMillis() + 60_000; botRoll == null && System.currentTimeMillis() < until; ) {
                for (int i = start; i < wa.received.size() && botRoll == null; i++) {
                    for (JsonNode e : wa.received.get(i).path("events")) {
                        if ("DiceRolled".equals(e.path("kind").asText()) && botId.equals(e.path("data").path("playerId").asText())) {
                            botRoll = wa.received.get(i);
                        }
                    }
                }
                if (botRoll != null) {
                    break;
                }
                wa.send(wa.msg("PING", null));
                JsonNode last = wa.lastUpdate();
                JsonNode game = last.path("view").path("game");
                JsonNode mine = ownWindow(last, aid);
                if (mine != null && "LANDING".equals(game.path("stage").asText())
                        && mine.path("opensAt").asLong() <= last.path("serverTime").asLong()) {
                    JsonNode landing = game.path("landing");
                    String command = landing.isNull() || landing.isMissingNode() ? "FinishTurn" : switch (landing.path("step").asText()) {
                        case "BUY" -> "DeclinePurchase";
                        case "UPGRADE" -> "SkipUpgrade";
                        case "BANK" -> "FinishBank";
                        case "EVENT" -> "DrawEventCard";
                        case "RESPONSE" -> "RespondCard";
                        default -> null;
                    };
                    if (command != null) {
                        wa.send(wa.msg("GAME", "land" + (++req)).put("command", command)
                                .set("args", wa.msg("x", null).put("windowId", mine.path("windowId").asLong())));
                    }
                }
                Thread.sleep(300);
            }
            assertThat(botRoll).as("the bot rolls by itself").isNotNull();
            assertThat(botRoll.path("events").toString()).contains("PlayerMoved");

            // 认输后存活的只剩托管的机器人，对局结束；人离开后只剩机器人：机器人随之离开，房间关闭
            JsonNode gave = wa.call(wa.msg("GAME", "sur").put("command", "Surrender")
                    .set("args", wa.msg("x", null).put("gameNo", 1)));
            assertThat(gave.path("ok").asBoolean()).as(gave.toString()).isTrue();
            JsonNode ended = wa.lastUpdate();
            assertThat(ended.path("view").path("game").isNull()).as(ended.toString()).isTrue();
            assertThat(ended.path("view").path("members").get(1).path("ready").asBoolean()).isTrue(); // 机器人重新准备
            // 战绩：这一局写进数据库（异步），我的最近战绩里有一行：2 人、已认输、排名第 2
            Map<?, ?>[] history = null;
            for (int i = 0; i < 50; i++) {
                history = http.exchange("/api/me/history", org.springframework.http.HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(bearer((String) a.get("token"))), Map[].class).getBody();
                if (history != null && history.length > 0) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(history).hasSize(1);
            assertThat(history[0].get("playerCount")).isEqualTo(2);
            assertThat(history[0].get("life")).isEqualTo("SURRENDERED");
            assertThat(history[0].get("rank")).isEqualTo(2);
            assertThat(history[0].get("endMode")).isEqualTo("TIME_LIMIT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_record_player p JOIN game_record r ON r.record_id = p.record_id"
                    + " WHERE r.room_id = (SELECT room_id FROM room WHERE room_code = ? AND status = 'OPEN')", Integer.class,
                    Integer.parseInt(code))).isEqualTo(2);
            assertThat(http.getForEntity("/api/me/history", Map.class).getStatusCode().value()).isEqualTo(401);

            JsonNode left = wa.call(wa.msg("LEAVE_ROOM", "l"));
            assertThat(left.path("ok").asBoolean()).as(left.toString()).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM room WHERE room_code = ?", String.class,
                    Integer.parseInt(code))).isEqualTo("CLOSED");
        }
    }

    private static org.springframework.http.HttpHeaders bearer(String token) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static String controlOf(JsonNode update, String player) {
        for (JsonNode p : update.path("view").path("game").path("players")) {
            if (player.equals(p.path("playerId").asText())) {
                return p.path("control").asText();
            }
        }
        return "";
    }

    private static JsonNode ownWindow(JsonNode update, String owner) {
        for (JsonNode w : update.path("view").path("game").path("windows")) {
            if (owner.equals(w.path("owner").asText(null)) && "TURN".equals(w.path("kind").asText())) {
                return w;
            }
        }
        return null;
    }
}
