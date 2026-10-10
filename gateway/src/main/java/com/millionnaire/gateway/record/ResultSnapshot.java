package com.millionnaire.gateway.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.core.state.GameProgressState;
import com.millionnaire.engine.core.state.GameResult;
import java.util.List;
import java.util.Map;

/** Immutable end-of-game projection; independent of public-event log retention. */
public record ResultSnapshot(List<GameProgressState.TitleAward> titles,
                             Map<String, GameProgressState.Metrics> metrics) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static String encode(GameResult r) {
        try { return JSON.writeValueAsString(new ResultSnapshot(r.titles(), r.metrics())); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot encode game result", e); }
    }
    public static ResultSnapshot decode(String text) {
        if (text == null || text.isBlank()) return new ResultSnapshot(List.of(), Map.of());
        try { return JSON.readValue(text, ResultSnapshot.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot decode game result", e); }
    }
}
