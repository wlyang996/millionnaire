package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.SeriesConfig;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.event.SeriesEvent;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.serialize.Immutable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Collections;

/** Series accounting uses only verified terminal standings, never public logs or client totals. */
final class SeriesModule {
    private SeriesModule() { }
    static RejectionCode configure(DecisionContext<SessionState> ctx, SessionCommand.ConfigureSeries c) {
        var s = ctx.state();
        if (s.inGame()) return RejectionCode.GAME_IN_PROGRESS;
        if (!s.lobby().isHost(c.actor())) return RejectionCode.NOT_HOST;
        if (c.totalGames() > 0 && s.team() != null) return RejectionCode.NOT_ALLOWED;
        if (s.series() != null && s.series().locked()) return RejectionCode.NOT_ALLOWED;
        var r = ctx.config().series();
        if (c.totalGames() != 0 && (!r.enabled() || !r.gameOptions().contains(c.totalGames()))) return RejectionCode.INVALID_SETTINGS;
        if (c.totalGames() > 0 && !r.allowBots() && s.lobby().members().stream().anyMatch(Member::bot)) return RejectionCode.NOT_ALLOWED;
        var old = s.series();
        if (old != null && old.status() == SeriesState.Status.SETUP && old.totalGames() == c.totalGames()) return RejectionCode.UNCHANGED;
        long no = old == null ? 1 : old.status() == SeriesState.Status.SETUP ? old.seriesNo() : Math.addExact(old.seriesNo(), 1);
        ctx.emit(new SeriesEvent.SeriesConfigured(no, c.totalGames()));
        return null;
    }
    static RejectionCode end(DecisionContext<SessionState> ctx, SessionCommand.EndSeries c) {
        var s = ctx.state();
        if (s.inGame()) return RejectionCode.GAME_IN_PROGRESS;
        if (!s.lobby().isHost(c.actor())) return RejectionCode.NOT_HOST;
        if (s.series() == null || s.series().seriesNo() != c.seriesNo() || s.series().finished()) return RejectionCode.INVALID_TRANSITION;
        ctx.emit(new SeriesEvent.SeriesEnded(c.seriesNo())); return null;
    }
    static RejectionCode beforeStart(SessionState s, RuleConfig config) {
        var z = s.series();
        if (z == null || z.finished()) return null;
        if (s.lobby().members().size() < Math.max(config.room().minPlayersToStart(), z.rules().minPlayers())) return RejectionCode.NOT_ENOUGH_PLAYERS;
        if (!z.rules().allowBots() && s.lobby().members().stream().anyMatch(Member::bot)) return RejectionCode.NOT_ALLOWED;
        if (z.locked() && (!z.settings().equals(s.lobby().settings()) || !participants(z).equals(s.lobby().members().stream().map(Member::playerId).toList()))) return RejectionCode.INVALID_TRANSITION;
        return null;
    }
    static List<String> participants(SeriesState z) { return z.roster().stream().map(Member::playerId).filter(id -> !z.withdrawn().contains(id)).toList(); }
    static SessionState evolve(SessionState s, SeriesEvent e, RuleConfig config) {
        var old = s.series();
        return switch (e) {
            case SeriesEvent.SeriesConfigured x -> {
                LobbyModule.check(!s.inGame() && (old == null || !old.locked()), "series configuration locked");
                LobbyModule.check(x.totalGames() == 0 || s.team() == null, "series cannot be combined with teams");
                long expected = old == null ? 1 : old.status() == SeriesState.Status.SETUP ? old.seriesNo() : old.seriesNo() + 1;
                LobbyModule.check(expected == x.seriesNo() && (x.totalGames() == 0 || config.series().enabled() && config.series().gameOptions().contains(x.totalGames())), "invalid series configuration");
                var l = s.lobby();
                yield s.withLobby(new RoomState(l.status(), l.hostId(), LobbyModule.unready(l), l.settings())).withSeries(
                        new SeriesState(x.seriesNo(), x.totalGames(), x.totalGames() == 0 ? SeriesState.Status.ENDED : SeriesState.Status.SETUP,
                                List.of(), null, config.series(), List.of(), Collections.emptyMap(), List.of()));
            }
            case SeriesEvent.SeriesStarted x -> {
                LobbyModule.check(!s.inGame() && old != null && old.status() == SeriesState.Status.SETUP
                        && old.seriesNo() == x.seriesNo() && x.roster().equals(s.lobby().members())
                        && x.settings().equals(s.lobby().settings()) && beforeStart(s, config) == null, "invalid series start");
                var points = new TreeMap<String, Integer>(); x.roster().forEach(m -> points.put(m.playerId(), 0));
                yield s.withSeries(new SeriesState(old.seriesNo(), old.totalGames(), SeriesState.Status.BETWEEN_GAMES,
                        x.roster(), x.settings(), old.rules(), List.of(), points, List.of()));
            }
            case SeriesEvent.SeriesGameScored x -> {
                LobbyModule.check(old != null && old.seriesNo() == x.seriesNo() && !old.games().isEmpty()
                        && x.game().equals(old.games().getLast()) && s.lastResult() != null
                        && x.game().gameNo() == s.lastResult().gameNo(), "series scoring fact mismatch");
                yield s;
            }
            case SeriesEvent.SeriesEnded x -> {
                LobbyModule.check(!s.inGame() && old != null && !old.finished() && old.seriesNo() == x.seriesNo(), "invalid series end");
                yield s.withSeries(copy(old, SeriesState.Status.ENDED, old.games(), old.points(), old.withdrawn()));
            }
        };
    }
    static SeriesState started(SeriesState z) {
        return z != null && z.locked() ? copy(z, SeriesState.Status.PLAYING, z.games(), z.points(), z.withdrawn()) : z;
    }
    static SeriesState ended(SeriesState z, GameResult result) {
        if (z == null || z.status() != SeriesState.Status.PLAYING) return z;
        if (result == null) return copy(z, SeriesState.Status.BETWEEN_GAMES, z.games(), z.points(), z.withdrawn());
        LobbyModule.check(z.games().stream().noneMatch(g -> g.gameNo() == result.gameNo()), "duplicate series game");
        var scores = new ArrayList<SeriesState.Score>();
        for (var r : result.standings()) {
            LobbyModule.check(z.points().containsKey(r.playerId()), "series result not in roster");
            int p = z.rules().rankPoints().get(r.rank() - 1);
            if (z.rules().tieMode() == SeriesConfig.TieMode.SPLIT_OCCUPIED) {
                long count = result.standings().stream().filter(other -> other.rank() == r.rank()).count();
                int sum = 0; for (int i = 0; i < count; i++) sum = Math.addExact(sum, z.rules().rankPoints().get(r.rank() - 1 + i));
                p = sum / (int) count;
            }
            scores.add(new SeriesState.Score(r.playerId(), r.rank(), p));
        }
        var points = new TreeMap<>(z.points()); scores.forEach(r -> points.merge(r.playerId(), r.points(), Math::addExact));
        var games = Immutable.append(z.games(), new SeriesState.ScoredGame(result.gameNo(), z.games().size() + 1, result.reason(), scores));
        return copy(z, games.size() == z.totalGames() ? SeriesState.Status.FINISHED : SeriesState.Status.BETWEEN_GAMES, games, points, z.withdrawn());
    }
    static SessionState memberLeft(SessionState s, String id) {
        var z = s.series(); if (z == null || !z.locked()) return s;
        var withdrawn = new TreeSet<>(z.withdrawn()); withdrawn.add(id);
        return s.withSeries(copy(z, s.lobby().members().isEmpty() && !s.inGame() ? SeriesState.Status.ENDED : z.status(), z.games(), z.points(), List.copyOf(withdrawn)));
    }
    private static SeriesState copy(SeriesState z, SeriesState.Status status, List<SeriesState.ScoredGame> games, Map<String, Integer> points, List<String> withdrawn) {
        return new SeriesState(z.seriesNo(), z.totalGames(), status, z.roster(), z.settings(), z.rules(), games, points, withdrawn);
    }
    static void validate(SessionState s, RuleConfig config) {
        var z = s.series(); if (z == null) return;
        LobbyModule.expect(z.seriesNo() > 0 && z.seriesNo() < Long.MAX_VALUE && z.status() != null
                && SeriesConfig.validate(z.rules()).isEmpty() && z.roster() != null && z.games() != null
                && z.points() != null && z.withdrawn() != null, "invalid series state");
        LobbyModule.expect(z.rules().equals(config.series()), "series rules must be room bound");
        if (z.roster().isEmpty()) {
            LobbyModule.expect(z.games().isEmpty() && z.points().isEmpty() && z.withdrawn().isEmpty()
                    && (z.status() == SeriesState.Status.SETUP || z.status() == SeriesState.Status.ENDED), "unstarted series invalid");
            return;
        }
        var ids = z.roster().stream().map(Member::playerId).toList();
        LobbyModule.expect(ids.size() == new TreeSet<>(ids).size() && ids.size() >= z.rules().minPlayers()
                && ids.size() <= 8 && z.points().keySet().equals(new TreeSet<>(ids))
                && ids.containsAll(z.withdrawn()) && z.games().size() <= z.totalGames()
                && z.rules().gameOptions().contains(z.totalGames()), "series roster/count invalid");
        LobbyModule.expect(z.withdrawn().size() == new TreeSet<>(z.withdrawn()).size()
                && z.settings() != null && LobbyModule.validSettings(config, z.settings())
                && (z.rules().allowBots() || z.roster().stream().noneMatch(Member::bot)), "series frozen settings invalid");
        var sums = new TreeMap<String, Integer>(); ids.forEach(id -> sums.put(id, 0));
        long previous = 0;
        for (int i = 0; i < z.games().size(); i++) {
            var g = z.games().get(i);
            LobbyModule.expect(g.index() == i + 1 && g.gameNo() > previous && g.gameNo() <= s.gamesPlayed(), "series game ordering invalid");
            var seen = new TreeSet<String>();
            var ranks = g.scores().stream().map(SeriesState.Score::rank).sorted().toList();
            int priorRank = 0;
            for (int seat = 0; seat < ranks.size(); seat++) {
                int rank = ranks.get(seat);
                LobbyModule.expect(rank == priorRank || rank == seat + 1, "series tied rank sequence invalid");
                priorRank = rank;
            }
            for (var r : g.scores()) {
                LobbyModule.expect(ids.contains(r.playerId()) && seen.add(r.playerId()) && r.points() >= 0 && r.points() <= 1000 && r.rank() >= 1 && r.rank() <= 8, "invalid series score");
                sums.merge(r.playerId(), r.points(), Math::addExact);
                int expected = z.rules().rankPoints().get(r.rank() - 1);
                if (z.rules().tieMode() == SeriesConfig.TieMode.SPLIT_OCCUPIED) {
                    int count = (int) g.scores().stream().filter(p -> p.rank() == r.rank()).count();
                    int total = 0; for (int occupied = r.rank() - 1; occupied < r.rank() - 1 + count; occupied++) total += z.rules().rankPoints().get(occupied);
                    expected = total / count;
                }
                LobbyModule.expect(r.points() == expected, "series score differs from frozen rules");
            }
            previous = g.gameNo();
        }
        LobbyModule.expect(sums.equals(z.points()), "series point total mismatch");
        if (z.locked()) LobbyModule.expect(z.settings().equals(s.lobby().settings())
                && participants(z).equals(s.lobby().members().stream().map(Member::playerId).toList())
                && (z.status() == SeriesState.Status.PLAYING) == s.inGame(), "series lock/status mismatch");
        if (z.status() == SeriesState.Status.FINISHED) LobbyModule.expect(z.games().size() == z.totalGames(), "series prematurely finished");
    }
}
