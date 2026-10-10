package com.millionnaire.gateway.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.core.state.SeriesState;
import org.springframework.jdbc.core.JdbcTemplate;

/** Called inside the game-record transaction; revision prevents late retries from rewinding a total. */
final class SeriesPersistence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SeriesPersistence() { }
    static String encode(SeriesState s) {
        try { return JSON.writeValueAsString(s); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    static SeriesState decode(String s) {
        try { return JSON.readValue(s, SeriesState.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    static void save(JdbcTemplate t, GameRecords.SeriesDraft d, long now) {
        var s = d.series(); if (s == null || s.roster().isEmpty()) return;
        Integer count = t.queryForObject("SELECT COUNT(*) FROM friend_series WHERE room_id = ? AND series_no = ?", Integer.class, d.roomId(), s.seriesNo());
        if (count == 0) t.update("INSERT INTO friend_series (room_id, series_no, total_games, completed_games, series_status, revision, config_hash, snapshot_json, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                d.roomId(), s.seriesNo(), s.totalGames(), s.games().size(), s.status().name(), d.revision(), d.configHash(), encode(s), now);
        else t.update("UPDATE friend_series SET completed_games = ?, series_status = ?, revision = ?, snapshot_json = ?, updated_at = ? WHERE room_id = ? AND series_no = ? AND revision < ?",
                s.games().size(), s.status().name(), d.revision(), encode(s), now, d.roomId(), s.seriesNo(), d.revision());
        for (int i = 0; i < s.roster().size(); i++) {
            var m = s.roster().get(i);
            Integer present = t.queryForObject("SELECT COUNT(*) FROM friend_series_player WHERE room_id = ? AND series_no = ? AND player_id = ?", Integer.class, d.roomId(), s.seriesNo(), m.playerId());
            if (present == 0) t.update("INSERT INTO friend_series_player (room_id, series_no, player_id, seat_no, bot) VALUES (?, ?, ?, ?, ?)", d.roomId(), s.seriesNo(), m.playerId(), i, m.bot() ? 1 : 0);
        }
        for (var g : s.games()) for (var score : g.scores()) {
            var previous = t.query("SELECT finish_rank, points FROM friend_series_score WHERE room_id = ? AND series_no = ? AND game_no = ? AND player_id = ?", (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, d.roomId(), s.seriesNo(), g.gameNo(), score.playerId());
            if (previous.isEmpty()) t.update("INSERT INTO friend_series_score (room_id, series_no, game_no, game_index, player_id, finish_rank, points) VALUES (?, ?, ?, ?, ?, ?, ?)", d.roomId(), s.seriesNo(), g.gameNo(), g.index(), score.playerId(), score.rank(), score.points());
            else if (previous.getFirst()[0] != score.rank() || previous.getFirst()[1] != score.points()) throw new IllegalStateException("conflicting series score");
        }
    }
}
