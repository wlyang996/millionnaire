package com.millionnaire.gateway.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.core.state.GameProgressState;
import com.millionnaire.engine.core.state.GameResult;
import java.util.List;
import java.util.Map;

/** Immutable end-of-game projection; independent of public-event log retention. */
public record ResultSnapshot(List<GameProgressState.TitleAward> titles,
                             Map<String, GameProgressState.Metrics> metrics,
                             com.millionnaire.engine.core.state.SeriesState series,
                             List<com.millionnaire.engine.core.state.TeamStanding> teamStandings) {
    public ResultSnapshot { teamStandings = teamStandings == null ? List.of() : List.copyOf(teamStandings); }
    public ResultSnapshot(List<GameProgressState.TitleAward> titles, Map<String, GameProgressState.Metrics> metrics) { this(titles, metrics, null, List.of()); }
    public ResultSnapshot(List<GameProgressState.TitleAward> titles, Map<String, GameProgressState.Metrics> metrics,
                          com.millionnaire.engine.core.state.SeriesState series) { this(titles,metrics,series,List.of()); }
    private static final ObjectMapper JSON = new ObjectMapper();
    public static String encode(GameResult r) {
        return encode(r, null);
    }
    public static String encode(GameResult r, com.millionnaire.engine.core.state.SeriesState series) {
        if (series == null || series.games().isEmpty() || series.games().getLast().gameNo() != r.gameNo()) series = null;
        try { return JSON.writeValueAsString(new ResultSnapshot(r.titles(), r.metrics(), series, r.teamStandings())); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot encode game result", e); }
    }
    public static ResultSnapshot decode(String text) {
        if (text == null || text.isBlank()) return new ResultSnapshot(List.of(), Map.of());
        try { return JSON.readValue(text, ResultSnapshot.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot decode game result", e); }
    }
}
