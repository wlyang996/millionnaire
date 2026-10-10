package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.SeriesConfig;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Map;

/** Fixed roster and authoritative per-game scoring; no game cash is carried across games. */
public record SeriesState(long seriesNo, int totalGames, Status status, List<Member> roster,
                          RoomSettings settings, SeriesConfig rules, List<ScoredGame> games,
                          Map<String, Integer> points, List<String> withdrawn) {
    public enum Status { SETUP, PLAYING, BETWEEN_GAMES, FINISHED, ENDED }
    public record Score(String playerId, int rank, int points) { }
    public record ScoredGame(long gameNo, int index, String reason, List<Score> scores) {
        public ScoredGame { scores = Immutable.list(scores); }
    }
    public record Entry(String playerId, String nickname, boolean bot, int rank, int points, boolean withdrawn) { }
    public SeriesState {
        roster = Immutable.list(roster); games = Immutable.list(games);
        points = Immutable.sortedMap(points); withdrawn = Immutable.list(withdrawn);
    }
    public boolean locked() { return status == Status.PLAYING || status == Status.BETWEEN_GAMES; }
    public boolean finished() { return status == Status.FINISHED || status == Status.ENDED; }
    public List<Entry> standings() {
        var sorted = roster.stream().sorted(java.util.Comparator.<Member>comparingInt(m -> points.getOrDefault(m.playerId(), 0)).reversed()
                .thenComparingInt(roster::indexOf)).toList();
        var out = new java.util.ArrayList<Entry>();
        int rank = 0, previous = -1;
        for (int i = 0; i < sorted.size(); i++) {
            var m = sorted.get(i); int p = points.getOrDefault(m.playerId(), 0);
            if (i == 0 || p != previous) rank = i + 1;
            out.add(new Entry(m.playerId(), m.nickname(), m.bot(), rank, p, withdrawn.contains(m.playerId())));
            previous = p;
        }
        return List.copyOf(out);
    }
}
