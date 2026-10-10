package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 对局结算摘要：回大厅后保留在会话中，供大厅视图与结算期间重连。 */
public record GameResult(long gameNo, String reason, List<Standing> standings,
                         List<GameProgressState.TitleAward> titles,
                         java.util.Map<String, GameProgressState.Metrics> metrics) {
    public GameResult(long gameNo, String reason, List<Standing> standings) {
        this(gameNo, reason, standings, List.of(), java.util.Collections.emptyMap());
    }
    public GameResult {
        standings = Immutable.list(standings);
        titles = Immutable.list(titles == null ? List.of() : titles);
        metrics = Immutable.sortedMap(metrics == null ? java.util.Collections.emptyMap() : metrics);
    }
}
