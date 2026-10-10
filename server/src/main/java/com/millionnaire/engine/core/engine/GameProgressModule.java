package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.*;
import com.millionnaire.engine.config.RoundRewardConfig.IncomeSource;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.core.state.GameProgressState.*;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.time.TaskKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Whole-round effects share the existing authoritative ledger, safe point and timer chain. */
final class GameProgressModule {
    private GameProgressModule() { }
    static GameState capture(GameState before, GameState after, GameEvent event, RuleConfig rules) {
        if (!rules.roundReward().enabled() && !rules.funTitles().enabled()) return after;
        TreeMap<String, Metrics> stats = new TreeMap<>(after.progress().metrics());
        for (var p : after.players()) {
            Metrics old = stats.getOrDefault(p.playerId(), Metrics.EMPTY);
            int properties = 0, stations = 0;
            for (var o : after.board().ownedBy(p.playerId())) {
                if (LobbyModule.board(rules, after.settings()).tiles().get(o.tile()).type() == TileType.STATION) stations++;
                else properties++;
            }
            int peakProperties = old.peakProperties(), peakStations = old.peakStations();
            if (properties + stations > peakProperties + peakStations) { peakProperties = properties; peakStations = stations; }
            TreeMap<IncomeSource, Long> income = new TreeMap<>(old.income());
            for (int i = before.ledger().journal().size(); i < after.ledger().journal().size(); i++) {
                var entry = after.ledger().journal().get(i);
                IncomeSource source = switch (entry.reason()) {
                    case "RENT" -> IncomeSource.RENT;
                    case "START_REWARD" -> IncomeSource.START;
                    case "EVENT_REWARD" -> IncomeSource.EVENT;
                    case "MINIGAME_REWARD" -> IncomeSource.MINIGAME;
                    case "AUCTION_COMMISSION" -> IncomeSource.COMMISSION;
                    case "DEBT_PAYMENT", "DEBT_SETTLEMENT" -> before.debt() != null
                            && before.debt().source().kind() == FeeSource.Kind.RENT ? IncomeSource.RENT : null;
                    default -> null;
                };
                if (source != null) for (var leg : entry.legs()) if (leg.account().equals(p.playerId()) && leg.delta() > 0)
                    income.merge(source, leg.delta(), Math::addExact);
            }
            stats.put(p.playerId(), new Metrics(income, peakProperties, peakStations));
        }
        var s = after.progress();
        return after.withProgress(copy(s, stats, s.observedRanks(), s.rewardGranted(), s.boundaryRound(), s.cityEvent(),
                s.notices(), s.noticeTaskId(), s.nextNoticeId(), s.cityDrawPending()));
    }

    static List<Award> awards(GameState g, RoundRewardConfig r) {
        return g.alive().stream().map(p -> {
            var m = g.progress().metrics().getOrDefault(p.playerId(), Metrics.EMPTY);
            long income = 0;
            for (var source : r.incomeSources()) income = Math.addExact(income, m.amount(source));
            return new Award(p.playerId(), income, r.reward(income));
        }).toList();
    }

    /** Called after TurnStarted/SafePointEntered, before any queued flow or roll window. */
    static boolean onRoundBoundary(DecisionContext<SessionState> ctx, long leadMs) {
        GameState g = ctx.state().game(); var r = ctx.config().roundReward(); var c = ctx.config().cityEvents();
        long completed = g.turn().round() - 1;
        if (completed <= g.progress().boundaryRound() || completed <= 0) return false;
        if (!r.enabled() && !ctx.config().funTitles().enabled() && !c.enabled()) return false;
        Map<String, Integer> observed = g.progress().observedRanks();
        if (completed >= r.round(g.settings().endMode()) && observed.isEmpty()) observed = ranks(g, ctx.config());
        ctx.emit(new GameEvent.RoundBoundaryProcessed(completed, observed));
        List<Notice> notices = new ArrayList<>();
        long at = Math.addExact(ctx.now(), leadMs);
        long id = g.progress().nextNoticeId();
        var city = ctx.state().game().progress().cityEvent();
        if (city != null && g.turn().round() >= city.endRound()) {
            ctx.emit(new GameEvent.CityEventEnded(city.id()));
            if (c.endMs() > 0) {
                long end = Math.addExact(at, g.settings().animationMs(ctx.config(), c.endMs()));
                notices.add(new Notice(id++, "CITY_ENDED", at, end, completed, List.of(), city)); at = end;
            }
        }
        g = ctx.state().game();
        if (r.enabled() && completed >= r.round(g.settings().endMode()) && !g.progress().rewardGranted()) {
            List<Award> awards = awards(g, r);
            ctx.emit(new GameEvent.RoundRewardGranted(completed, awards));
            long end = Math.addExact(at, g.settings().animationMs(ctx.config(), r.presentationMs()));
            notices.add(new Notice(id++, "ROUND_REWARD", at, end, completed, awards, null)); at = end;
        }
        g = ctx.state().game(); city = g.progress().cityEvent();
        if (city != null && g.turn().round() == city.startRound()) {
            ctx.emit(new GameEvent.CityEventActivated(city.id()));
            if (c.activationMs() > 0) {
                long end = Math.addExact(at, g.settings().animationMs(ctx.config(), c.activationMs()));
                notices.add(new Notice(id++, "CITY_ACTIVE", at, end, completed, List.of(), city)); at = end;
            }
        }
        if (c.enabled() && c.allowedModes().contains(g.settings().endMode()) && c.totalWeight() > 0 && city == null
                && completed >= c.firstCheckRound() && (completed - c.firstCheckRound()) % c.checkEveryRounds() == 0) {
            boolean triggered = ctx.draw(DrawPoint.CITY_TRIGGER, 100) < c.triggerPercent();
            ctx.emit(new GameEvent.CityEventChecked(completed, triggered));
            if (triggered) {
                var spec = c.pick(ctx.draw(DrawPoint.CITY_KIND, c.totalWeight()));
                long start = Math.addExact(completed, c.advanceRounds());
                city = new CityEvent(id, spec, start, Math.addExact(start, spec.durationRounds()));
                ctx.emit(new GameEvent.CityEventAnnounced(city));
                long end = Math.addExact(at, g.settings().animationMs(ctx.config(), c.announcementMs()));
                notices.add(new Notice(id++, "CITY_ANNOUNCED", at, end, completed, List.of(), city)); at = end;
                // advance=1 means the next round, which is already the fresh turn's round.
                if (city.startRound() == g.turn().round()) ctx.emit(new GameEvent.CityEventActivated(city.id()));
            }
        }
        if (notices.isEmpty()) return false;
        ctx.emit(new GameEvent.TurnStageEntered(TurnStage.AWAITING_FLOW, 0,
                new Continuation.BeginTurn(g.turn().turnNo()), at));
        long task = ctx.schedule(at, TaskKind.GLOBAL_NOTICE, g.turn().turnNo());
        ctx.emit(new GameEvent.GlobalNoticeBatchOpened(List.copyOf(notices), task));
        return true;
    }

    static void onTask(DecisionContext<SessionState> ctx, com.millionnaire.engine.time.ScheduledTask task) {
        if (!ctx.state().inGame() || ctx.state().game().progress().noticeTaskId() != task.taskId()) return;
        ctx.emit(new GameEvent.GlobalNoticeBatchClosed(task.taskId(), ctx.now(), false));
        TurnModule.continueAfterGlobalNotice(ctx);
    }

    static void cancel(DecisionContext<SessionState> ctx) {
        var p = ctx.state().game().progress();
        if (p.noticeTaskId() == 0) return;
        if (ctx.tasks().stream().anyMatch(t -> t.taskId() == p.noticeTaskId())) ctx.cancel(p.noticeTaskId());
        ctx.emit(new GameEvent.GlobalNoticeBatchClosed(p.noticeTaskId(), ctx.now(), true));
    }

    static GameState evolve(GameState g, GameEvent.ProgressEvent event, Draws draws, RuleConfig rules) {
        var p = g.progress(); var r = rules.roundReward(); var c = rules.cityEvents();
        switch (event) {
            case GameEvent.RoundBoundaryProcessed e -> {
                check(g.phase() == GamePhase.RUNNING && g.turn().stage() == TurnStage.NONE
                        && g.turn().track().safePointPhase() == 2 && g.turn().round() - 1 == e.completedRound()
                        && e.completedRound() > p.boundaryRound(), "invalid progress boundary");
                Map<String,Integer> expected = p.observedRanks();
                if (e.completedRound() >= r.round(g.settings().endMode()) && expected.isEmpty()) expected = ranks(g, rules);
                check(expected.equals(e.observedRanks()), "observation rankings differ");
                return g.withProgress(copy(p, p.metrics(), expected, p.rewardGranted(), e.completedRound(), p.cityEvent(),
                        List.of(), 0, p.nextNoticeId(), false));
            }
            case GameEvent.RoundRewardGranted e -> {
                check(r.enabled() && !p.rewardGranted() && g.phase() == GamePhase.RUNNING && g.turn().stage() == TurnStage.NONE
                        && p.boundaryRound() == e.completedRound() && e.completedRound() >= r.round(g.settings().endMode())
                        && awards(g, r).equals(e.awards()), "invalid round reward");
                var ledger = g.ledger();
                for (Award award : e.awards()) if (award.reward() > 0) ledger = ledger.transfer(Ledger.SYSTEM, award.playerId(),
                        award.reward(), "ROUND_REWARD", "round-" + e.completedRound());
                return g.withLedger(ledger).withProgress(copy(p, p.metrics(), p.observedRanks(), true, p.boundaryRound(),
                        p.cityEvent(), p.notices(), p.noticeTaskId(), p.nextNoticeId(), p.cityDrawPending()));
            }
            case GameEvent.CityEventChecked e -> {
                check(c.enabled() && c.allowedModes().contains(g.settings().endMode()) && p.cityEvent() == null
                        && p.boundaryRound() == e.completedRound() && e.completedRound() >= c.firstCheckRound()
                        && (e.completedRound() - c.firstCheckRound()) % c.checkEveryRounds() == 0 && c.totalWeight() > 0
                        && e.triggered() == (draws.take(DrawPoint.CITY_TRIGGER, 100) < c.triggerPercent()), "invalid city check");
                return g.withProgress(copy(p, p.metrics(), p.observedRanks(), p.rewardGranted(), p.boundaryRound(), null,
                        p.notices(), 0, p.nextNoticeId(), e.triggered()));
            }
            case GameEvent.CityEventAnnounced e -> {
                var city = e.event();
                check(p.cityDrawPending() && p.cityEvent() == null && city.spec().equals(c.pick(draws.take(DrawPoint.CITY_KIND, c.totalWeight())))
                        && city.startRound() == Math.addExact(p.boundaryRound(), c.advanceRounds())
                        && city.endRound() == city.startRound() + city.spec().durationRounds() && city.id() >= p.nextNoticeId(), "invalid city draw");
                return g.withProgress(copy(p, p.metrics(), p.observedRanks(), p.rewardGranted(), p.boundaryRound(), city,
                        p.notices(), 0, p.nextNoticeId(), false));
            }
            case GameEvent.CityEventActivated e -> {
                check(p.cityEvent() != null && p.cityEvent().id() == e.eventId() && p.cityEvent().startRound() == g.turn().round(),
                        "city activation not at its starting round"); return g;
            }
            case GameEvent.CityEventEnded e -> {
                check(p.cityEvent() != null && p.cityEvent().id() == e.eventId() && g.turn().round() >= p.cityEvent().endRound(),
                        "city event ended prematurely");
                return g.withProgress(copy(p, p.metrics(), p.observedRanks(), p.rewardGranted(), p.boundaryRound(), null,
                        p.notices(), 0, p.nextNoticeId(), false));
            }
            case GameEvent.GlobalNoticeBatchOpened e -> {
                check(p.noticeTaskId() == 0 && !e.notices().isEmpty() && g.turn().continuation() instanceof Continuation.BeginTurn,
                        "notice without pending turn");
                long next = p.nextNoticeId(), end = -1;
                for (var n : e.notices()) {
                    check(n.id() == next++ && n.completedRound() == p.boundaryRound() && n.endsAt() > n.opensAt()
                            && (end == -1 || n.opensAt() == end), "invalid notice sequence");
                    long duration = switch (n.kind()) {
                        case "ROUND_REWARD" -> {
                            check(p.rewardGranted() && n.event() == null && n.awards().equals(awards(g, r)), "notice reward differs");
                            yield r.presentationMs();
                        }
                        case "CITY_ANNOUNCED", "CITY_ACTIVE" -> {
                            check(n.awards().isEmpty() && n.event() != null && n.event().equals(p.cityEvent()), "notice city differs");
                            check(n.kind().equals("CITY_ANNOUNCED") || n.event().startRound() == g.turn().round(), "notice city not active");
                            yield n.kind().equals("CITY_ANNOUNCED") ? c.announcementMs() : c.activationMs();
                        }
                        case "CITY_ENDED" -> {
                            check(n.awards().isEmpty() && n.event() != null && n.event().endRound() == g.turn().round()
                                    && c.pool().contains(n.event().spec()), "notice city not ended");
                            yield c.endMs();
                        }
                        default -> throw new IllegalStateException("unknown global notice");
                    };
                    check(n.endsAt() - n.opensAt() == g.settings().animationMs(rules, duration), "notice duration differs");
                    end = n.endsAt();
                }
                check(end == g.turn().notBefore() && e.taskId() > 0, "notice time differs from pending turn");
                return g.withProgress(copy(p, p.metrics(), p.observedRanks(), p.rewardGranted(), p.boundaryRound(), p.cityEvent(),
                        e.notices(), e.taskId(), next, false));
            }
            case GameEvent.GlobalNoticeBatchClosed e -> {
                check(e.taskId() == p.noticeTaskId() && e.taskId() > 0
                        && (e.cancelled() || e.at() == p.notices().getLast().endsAt()), "notice closed at wrong time");
                var out = g.withProgress(copy(p, p.metrics(), p.observedRanks(), p.rewardGranted(), p.boundaryRound(), p.cityEvent(),
                        List.of(), 0, p.nextNoticeId(), false));
                return e.cancelled() ? out : out.withTurn(out.turn().withStage(TurnStage.NONE, 0, null, 0)
                        .withTrack(g.phase() == GamePhase.DRAINING ? TurnTrack.NONE : TurnTrack.NONE.safePoint(2)));
            }
        }
    }

    static Map<String,Integer> ranks(GameState g, RuleConfig rules) {
        Map<String,Integer> ranks = new TreeMap<>();
        for (var s : TurnModule.standings(g, rules)) ranks.put(s.playerId(), s.rank());
        return ranks;
    }
    static List<TitleAward> titles(GameState g, RuleConfig rules) {
        if (!rules.funTitles().enabled()) return List.of();
        var finalRanks = ranks(g, rules); List<TitleAward> awards = new ArrayList<>();
        for (var title : rules.funTitles().titles()) {
            if (!title.enabled()) continue;
            Map<String,Long> values = new TreeMap<>();
            for (var player : g.players()) {
                var m = g.progress().metrics().getOrDefault(player.playerId(), Metrics.EMPTY);
                long value = switch (title.kind()) {
                    case RENT_KING -> m.amount(IncomeSource.RENT);
                    case PROPERTY_TYCOON -> m.peakProperties() + m.peakStations();
                    case LUCKY_STAR -> Math.addExact(m.amount(IncomeSource.EVENT), m.amount(IncomeSource.MINIGAME));
                    case COMEBACK -> player.alive() && g.progress().observedRanks().containsKey(player.playerId())
                            ? g.progress().observedRanks().get(player.playerId()) - finalRanks.get(player.playerId()) : 0;
                };
                values.put(player.playerId(), value);
            }
            long best = values.values().stream().mapToLong(Long::longValue).max().orElse(0);
            if (best < title.minimum()) continue;
            values.forEach((id, value) -> { if (value == best) awards.add(new TitleAward(title.kind().name(), title.name(), id, value,
                    g.progress().observedRanks().getOrDefault(id, 0), finalRanks.get(id))); });
        }
        return List.copyOf(awards);
    }
    static int multiplier(GameState g, CityEventConfig.Kind kind) {
        var city = g.progress().cityEvent();
        return city != null && city.active(g.turn().round()) && city.spec().kind() == kind ? city.spec().multiplierPercent() : 100;
    }
    static long upgradeCost(RuleConfig rules, GameState g, Tile tile) {
        long base = rules.tier(tile.tier()).upgradeCost();
        return Math.max(1, Math.multiplyExact(base, multiplier(g, CityEventConfig.Kind.UPGRADE_DISCOUNT)) / 100);
    }
    private static GameProgressState copy(GameProgressState p, Map<String,Metrics> metrics, Map<String,Integer> ranks,
                                          boolean rewarded, long boundary, CityEvent city, List<Notice> notices,
                                          long task, long next, boolean pending) {
        return new GameProgressState(metrics, ranks, rewarded, boundary, city, notices, task, next, pending);
    }
    private static void check(boolean ok, String msg) { if (!ok) throw new IllegalStateException(msg); }
}
