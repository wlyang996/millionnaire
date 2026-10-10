package com.millionnaire.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.millionnaire.engine.config.TeamConfig;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.TeamState.TeamId;
import com.millionnaire.gateway.config.*;
import com.millionnaire.gateway.room.RoomService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("db")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:teamflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.placeholders.table_options="
})
class TeamRoomFlowTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired GameConfigs configs;
    @Autowired RoomService rooms;

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String name) {
        return http.postForObject("/api/auth/test-login", Map.of("nickname", name), Map.class);
    }
    @Test void authenticatedSelectionBotIdentityRetryAndReconnectKeepFrozenTeams() throws Exception {
        // H2 LENGTH(VARBINARY) differs from MySQL byte length; adjust only the test database constraint.
        jdbc.execute("ALTER TABLE room DROP CONSTRAINT IF EXISTS ck_room_ids");
        jdbc.execute("ALTER TABLE room ADD CONSTRAINT ck_room_ids CHECK (OCTET_LENGTH(create_request_id) = 16 AND max_saved_game_no >= 0)");
        var json = new ObjectMapper(); var settings = (ObjectNode) json.valueToTree(SettingsMapper.defaults());
        var d = TeamConfig.DEFAULT;
        ((ObjectNode) settings.get("timing")).put("autoActDelayMs", 10000);
        settings.set("team", json.valueToTree(new TeamConfig(true, d.teamSizeOptions(), d.defaultTeamSize(),
                d.allowBots(), d.allyRentWaiver(), d.allyRoadblockPass(), d.aidEnabled(), d.aidMaxAmount(), d.aidPerTurn(),
                d.aidQuickAmounts(), d.debtAidEnabled(), d.debtAidWindowMs())));
        configs.publish(json.treeToValue(settings, GameSettings.class), "team test only", "test", 0, null);
        var a = login("组队房主"); var b = login("组队成员");
        String aid = (String) a.get("userId"), bid = (String) b.get("userId");
        try (WsClient wa = new WsClient(port, (String) a.get("token")); WsClient wb = new WsClient(port, (String) b.get("token"))) {
            wa.awaitType("HELLO"); wb.awaitType("HELLO");
            String code = wa.call(wa.msg("CREATE_ROOM", "team-create")).path("roomCode").asText();
            assertThat(wb.call(wb.msg("JOIN_ROOM", "team-join").put("roomCode", code)).path("ok").asBoolean()).isTrue();
            var room = rooms.byCode(Integer.parseInt(code)).orElseThrow();
            assertThat(wa.call(wa.msg("CONFIGURE_TEAMS", "team-config").put("teamSize", 2).put("actor", bid)).path("ok").asBoolean()).isTrue();
            assertThat(wa.call(wa.msg("CONFIGURE_TEAMS", "team-invalid-size").put("teamSize", 2.5)).path("ok").asBoolean()).isFalse();
            assertThat(wa.call(wa.msg("READY", "team-no-selection").put("ready", true)).path("code").asText()).isEqualTo("TEAM_NOT_SELECTED");
            assertThat(wa.call(wa.msg("ADD_BOT", "team-bot1")).path("ok").asBoolean()).isTrue();
            assertThat(wa.call(wa.msg("ADD_BOT", "team-bot2")).path("ok").asBoolean()).isTrue();
            var bots = room.view(aid).members().stream().filter(Member::bot).toList();
            assertThat(bots).hasSize(2); assertThat(bots).allMatch(m -> !m.ready());
            assertThat(wa.call(wa.msg("ASSIGN_BOT_TEAM", "team-forged-bot").put("target", bid).put("bot", true).put("teamId", "BLUE")).path("code").asText()).isEqualTo("NOT_ALLOWED");
            var choose = wa.msg("CHOOSE_TEAM", "team-choose").put("teamId", "BLUE").put("actor", bid);
            assertThat(wa.call(choose).path("ok").asBoolean()).isTrue();
            assertThat(wa.call(choose.deepCopy().put("teamId", "RED")).path("ok").asBoolean()).isTrue();
            assertThat(room.view(aid).team().assignments().get(aid)).isEqualTo(TeamId.BLUE);
            assertThat(wb.call(wb.msg("CHOOSE_TEAM", "team-own-choice").put("teamId", "RED").put("actor", aid).put("target", aid)).path("ok").asBoolean()).isTrue();
            for (int i = 0; i < bots.size(); i++) assertThat(wa.call(wa.msg("ASSIGN_BOT_TEAM", "team-assign"+i)
                    .put("target", bots.get(i).playerId()).put("teamId", i == 0 ? "BLUE" : "RED")).path("ok").asBoolean()).isTrue();
            assertThat(wa.call(wa.msg("CONFIGURE_SERIES", "team-no-series").put("totalGames", 3)).path("code").asText()).isEqualTo("NOT_ALLOWED");
            assertThat(wa.call(wa.msg("READY", "team-ready-a").put("ready", true)).path("ok").asBoolean()).isTrue();
            assertThat(wb.call(wb.msg("READY", "team-ready-b").put("ready", true)).path("ok").asBoolean()).isTrue();
            assertThat(wa.call(wa.msg("START_GAME", "team-start")).path("ok").asBoolean()).isTrue();
            var frozen = room.view(aid).team(); assertThat(frozen.locked()).isTrue();
            assertThat(room.view(aid).game().team()).isEqualTo(frozen);
            assertThat(room.view(aid).game().players()).allMatch(p -> p.cash() == 3000);
            assertThat(wb.call(wb.msg("CHOOSE_TEAM", "team-in-game").put("teamId", "BLUE")).path("code").asText()).isEqualTo("GAME_IN_PROGRESS");
            try (WsClient reconnect = new WsClient(port, (String) b.get("token"))) {
                reconnect.awaitType("HELLO");
                var snapshot = reconnect.awaitType("UPDATE");
                assertThat(snapshot.path("view").path("team").path("assignments").path(bid).asText()).isEqualTo("RED");
                assertThat(snapshot.path("view").path("game").path("team").path("lockedGameNo").asLong()).isEqualTo(frozen.lockedGameNo());
                assertThat(snapshot.path("view").has("teamSource")).isFalse();
            }
            long gameNo = room.gameNo().orElseThrow();
            String redBot = bots.get(1).playerId();
            assertThat(room.submitClient(redBot, "team-bot-surrender", new GameCommand.Surrender(redBot, gameNo)).ok()).isTrue();
            assertThat(room.gameNo()).isPresent();
            assertThat(room.submitClient(bid, "team-human-surrender", new GameCommand.Surrender(bid, gameNo)).ok()).isTrue();
            assertThat(room.gameNo()).isEmpty();
            var result = room.view(aid).lastResult();
            assertThat(result.reason()).isEqualTo("LAST_TEAM");
            assertThat(result.teamStandings()).hasSize(2);
            assertThat(result.teamStandings().getFirst().teamId()).isEqualTo(TeamId.BLUE);
            assertThat(result.teamStandings().getFirst().survivors()).containsExactlyInAnyOrder(aid, bots.getFirst().playerId());
            assertThat(result.teamStandings().getFirst().netWorth()).isEqualTo(6000);
            assertThat(result.standings().stream().filter(s -> s.playerId().equals(aid)).findFirst().orElseThrow().cash()).isEqualTo(3000);
            try (WsClient reconnect = new WsClient(port, (String) a.get("token"))) {
                reconnect.awaitType("HELLO");
                var snapshot = reconnect.awaitType("UPDATE");
                assertThat(snapshot.path("view").path("lastResult").path("teamStandings").size()).isEqualTo(2);
                assertThat(snapshot.path("view").path("lastResult").path("reason").asText()).isEqualTo("LAST_TEAM");
            }
            assertThat(room.view(aid).team().locked()).isFalse();
            assertThat(room.view(aid).team().assignments()).isEqualTo(frozen.assignments());
            assertThat(room.view(aid).members().stream().filter(m -> !m.bot())).allMatch(m -> !m.ready());
        }
    }
}
