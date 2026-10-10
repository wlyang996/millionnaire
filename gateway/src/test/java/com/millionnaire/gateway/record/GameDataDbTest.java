package com.millionnaire.gateway.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.SessionState;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** H2(MySQL 模式) 上验证对局事件日志与聊天落库：gzip 后可还原为引擎事件，重复提交忽略，发送者须为未注销用户。 */
@SpringBootTest
@ActiveProfiles("db")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gamedata;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.placeholders.table_options="
})
class GameDataDbTest {
    @Autowired
    GameRecords records;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void resultTitlesAndIncomeRemainReadableWithoutEventLogs() {
        // H2 LENGTH(binary) differs from MySQL's byte length; keep the original migration unchanged.
        jdbc.execute("ALTER TABLE game_record DROP CONSTRAINT ck_game_record_values");
        jdbc.execute("ALTER TABLE game_record ADD CONSTRAINT ck_game_record_values CHECK (game_no >= 1 AND player_count >= 1"
                + " AND player_count <= 8 AND initial_cash >= 0 AND ended_at >= started_at AND OCTET_LENGTH(draft_sha256) = 32)");
        jdbc.update("INSERT INTO app_user (user_id, nickname, avatar_id, status, created_at, updated_at, last_login_at)"
                + " VALUES (9002, '可可', 1, 'ACTIVE', 1, 1, 1)");
        var title = new com.millionnaire.engine.core.state.GameProgressState.TitleAward("RENT_KING", "收租王", "9002", 500, 2, 1);
        var metrics = new com.millionnaire.engine.core.state.GameProgressState.Metrics(
                Map.of(com.millionnaire.engine.config.RoundRewardConfig.IncomeSource.RENT, 500L), 2, 1);
        var result = new com.millionnaire.engine.core.state.GameResult(1, "TIME_UP", List.of(), List.of(title), Map.of("9002", metrics));
        var d = new GameRecords.Draft(7003, 1, "TIME_UP", "TIME_LIMIT", 30, "classic-30", 3000,
                1, 10, RuleConfigs.defaultV1().contentHash(),
                List.of(new GameRecords.Seat(0, 9002, 1, 5000L, 3500L, "ALIVE")), ResultSnapshot.encode(result));
        records.save(d); records.save(d);
        String stored = jdbc.queryForObject("SELECT result_json FROM game_record WHERE room_id=7003 AND game_no=1", String.class);
        assertThat(ResultSnapshot.decode(stored).titles()).containsExactly(title);
        assertThat(ResultSnapshot.decode(stored).metrics()).containsEntry("9002", metrics);
        assertThat(ResultSnapshot.decode(records.detail(9002, 7003, 1).orElseThrow().resultJson()).titles()).containsExactly(title);
        assertThat(records.detail(9001, 7003, 1)).isEmpty();
        assertThat(ResultSnapshot.decode(null).titles()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_record WHERE room_id=7003 AND game_no=1", Integer.class)).isEqualTo(1);
    }

    @Test
    void gameLogRoundTripsThroughGzip() throws Exception {
        Engine<SessionState> engine = new Engine<>(RuleConfigs.defaultV1(), SessionDomain.INSTANCE);
        List<Event> events = List.of(new GameEvent.GameEnded(3, "TIME_UP", null));
        String text = engine.encodeEvents(events);
        GameRecords.LogDraft d = new GameRecords.LogDraft(7001, 3, engine.configHash(), text, events.size(), 123);
        records.saveLog(d);
        records.saveLog(d); // 重复提交忽略

        Map<String, Object> row = jdbc.queryForMap("SELECT event_count, plain_len, payload FROM game_log WHERE room_id = 7001 AND game_no = 3");
        assertThat(((Number) row.get("event_count")).intValue()).isEqualTo(1);
        byte[] plain = new GZIPInputStream(new ByteArrayInputStream((byte[]) row.get("payload"))).readAllBytes();
        assertThat(plain.length).isEqualTo(((Number) row.get("plain_len")).intValue());
        assertThat(engine.decodeEvents(new String(plain, StandardCharsets.UTF_8))).isEqualTo(events);
    }

    @Test
    void chatNeedsALiveSender() {
        jdbc.update("INSERT INTO app_user (user_id, nickname, avatar_id, status, created_at, updated_at, last_login_at)"
                + " VALUES (9001, '阿杰', 0, 'ACTIVE', 1, 1, 1)");
        records.saveChat(new GameRecords.ChatDraft(7002, 1, 9001, "你好", 10));
        records.saveChat(new GameRecords.ChatDraft(7002, 1, 9001, "你好", 10)); // 重复忽略
        assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE room_id = 7002 AND msg_no = 1", String.class))
                .isEqualTo("你好");
        assertThat(jdbc.queryForObject("SELECT sec_status FROM chat_message WHERE room_id = 7002 AND msg_no = 1", String.class))
                .isEqualTo("UNCHECKED");
    }
}
