package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.CityEventConfig;
import com.millionnaire.engine.config.RoundRewardConfig.IncomeSource;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Map;

/** Persistent public progress. Cash remains exclusively in Ledger. */
public record GameProgressState(Map<String, Metrics> metrics, Map<String, Integer> observedRanks,
                                boolean rewardGranted, long boundaryRound, CityEvent cityEvent,
                                List<Notice> notices, long noticeTaskId, long nextNoticeId, boolean cityDrawPending) {
    public static final GameProgressState EMPTY = new GameProgressState(java.util.Collections.emptyMap(), java.util.Collections.emptyMap(), false, 0, null, List.of(), 0, 1, false);
    public GameProgressState {
        metrics = Immutable.sortedMap(metrics); observedRanks = Immutable.sortedMap(observedRanks);
        notices = Immutable.list(notices);
    }
    public record Metrics(Map<IncomeSource, Long> income, int peakProperties, int peakStations) {
        public static final Metrics EMPTY = new Metrics(java.util.Collections.emptyMap(), 0, 0);
        public Metrics { income = Immutable.sortedMap(income); }
        public long amount(IncomeSource source) { return income.getOrDefault(source, 0L); }
    }
    public record Award(String playerId, long income, long reward) { }
    public record CityEvent(long id, CityEventConfig.Spec spec, long startRound, long endRound) {
        public boolean active(long round) { return round >= startRound && round < endRound; }
    }
    public record Notice(long id, String kind, long opensAt, long endsAt, long completedRound,
                         List<Award> awards, CityEvent event) {
        public Notice { awards = Immutable.list(awards); }
    }
    public record TitleAward(String kind, String name, String playerId, long value, int previousRank, int finalRank) { }
}
