package com.millionnaire.gateway.record;

import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.state.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("db")
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:series;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.placeholders.table_options="})
class SeriesDbTest {
    @Autowired GameRecords records;
    @Autowired JdbcTemplate jdbc;
    private SeriesState fixture() {
        return new SeriesState(1, 3, SeriesState.Status.BETWEEN_GAMES,
                List.of(new Member("9101", "可可", false), new Member("9102", "机器人1", false, true)),
                RoomSettings.defaults(RuleConfigs.defaultV1()), SeriesConfig.DEFAULT,
                List.of(new SeriesState.ScoredGame(1, 1, "TIME_UP", List.of(new SeriesState.Score("9101", 1, 10), new SeriesState.Score("9102", 2, 6)))),
                Map.of("9101", 10, "9102", 6), List.of());
    }
    @Test void gameAndPointsCommitTogetherAndDuplicateLateRetriesCannotRewindTotals() {
        jdbc.execute("ALTER TABLE game_record DROP CONSTRAINT ck_game_record_values");
        jdbc.execute("ALTER TABLE game_record ADD CONSTRAINT ck_game_record_values CHECK (game_no >= 1 AND player_count >= 1 AND player_count <= 8 AND initial_cash >= 0 AND ended_at >= started_at AND OCTET_LENGTH(draft_sha256) = 32)");
        jdbc.update("INSERT INTO app_user (user_id, nickname, avatar_id, status, created_at, updated_at, last_login_at) VALUES (9101, '可可', 1, 'ACTIVE', 1, 1, 1)");
        var s = fixture();
        var series = new GameRecords.SeriesDraft(8101, 12, "rules", s);
        var result = new GameResult(1, "TIME_UP", List.of(new Standing("9101", 1, 3000, 3000), new Standing("9102", 2, 2000, 2000)));
        var draft = new GameRecords.Draft(8101, 1, "TIME_UP", "TIME_LIMIT", 30, "classic-30", 3000, 1, 10,
                "rules", List.of(new GameRecords.Seat(0, 9101, 1, 3000L, 3000L, "ALIVE")), ResultSnapshot.encode(result, s), series);
        records.save(draft); records.save(draft);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_record WHERE room_id=8101", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM friend_series_score WHERE room_id=8101", Integer.class)).isEqualTo(2);
        assertThat(records.series(9101, 8101, 1)).contains(s);
        assertThat(records.series(9110, 8101, 1)).isEmpty();
        var detail = records.detail(9101, 8101, 1).orElseThrow();
        assertThat(ResultSnapshot.decode(detail.resultJson()).series()).isEqualTo(s);
        assertThat(ResultSnapshot.decode("{\"titles\":[],\"metrics\":{}}").series()).isNull();
        var ended = new SeriesState(s.seriesNo(), s.totalGames(), SeriesState.Status.ENDED, s.roster(), s.settings(), s.rules(), s.games(), s.points(), List.of("9102"));
        records.saveSeries(new GameRecords.SeriesDraft(8101, 20, "rules", ended));
        records.saveSeries(series);
        assertThat(records.series(9101, 8101, 1)).contains(ended);
        var conflict = new SeriesState(s.seriesNo(), s.totalGames(), SeriesState.Status.BETWEEN_GAMES, s.roster(), s.settings(), s.rules(),
                List.of(new SeriesState.ScoredGame(1, 1, "TIME_UP", List.of(new SeriesState.Score("9101", 1, 11), new SeriesState.Score("9102", 2, 6)))), Map.of("9101", 11, "9102", 6), List.of());
        var bad = new GameRecords.Draft(8101, 2, "TIME_UP", "TIME_LIMIT", 30, "classic-30", 3000, 11, 20, "rules", draft.seats(), null, new GameRecords.SeriesDraft(8101, 25, "rules", conflict));
        assertThatThrownBy(() -> records.save(bad)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_record WHERE room_id=8101", Integer.class)).isEqualTo(1);
        assertThat(records.series(9101, 8101, 1)).contains(ended);
    }
    @Test void memoryDuplicateGameIsNotCountedTwice() {
        var records = GameRecords.inMemory(java.time.Clock.systemUTC());
        var s = fixture();
        var d = new GameRecords.Draft(8201, 1, "TIME_UP", "TIME_LIMIT", 30, "classic-30", 3000, 1, 10, "rules",
                List.of(new GameRecords.Seat(0, 9101, 1, 3000L, 3000L, "ALIVE")), null, new GameRecords.SeriesDraft(8201, 12, "rules", s));
        records.save(d); records.save(d);
        assertThat(records.recent(9101)).hasSize(1);
        assertThat(records.series(9101, 8201, 1)).contains(s);
    }
}
