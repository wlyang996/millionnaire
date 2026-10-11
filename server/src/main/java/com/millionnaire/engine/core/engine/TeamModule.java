package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.command.SessionCommand.TeamCommand;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.event.RoomEvent;
import com.millionnaire.engine.core.event.TeamEvent;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.core.state.TeamState.TeamId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

final class TeamModule {
    private TeamModule() { }
    static boolean rentWaived(RuleConfig rules, GameState g, String payer, String owner) {
        return g.team() != null && rules.team().allyRentWaiver() && g.team().sameTeam(payer, owner);
    }
    static long survivingTeams(GameState g) {
        return g.alive().stream().map(p -> g.team().assignments().get(p.playerId())).distinct().count();
    }
    static List<TeamStanding> standings(GameState g, RuleConfig rules) {
        if (g.team() == null) return List.of();
        var rows = new ArrayList<TeamStanding>();
        for (var id : TeamId.values()) {
            var members = g.team().assignments().entrySet().stream().filter(e -> e.getValue() == id)
                    .map(java.util.Map.Entry::getKey).toList();
            var survivors = members.stream().filter(pid -> g.player(pid).orElseThrow().alive()).toList();
            long total = 0;
            for (String pid : survivors) total = Math.addExact(total, EconomyModule.netWorth(rules, g, pid));
            rows.add(new TeamStanding(id, 0, total, members, survivors));
        }
        rows.sort(java.util.Comparator.comparing((TeamStanding row) -> row.survivors().isEmpty())
                .thenComparing(java.util.Comparator.comparingLong(TeamStanding::netWorth).reversed()));
        var result = new ArrayList<TeamStanding>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int rank = i + 1;
            if (i > 0 && row.survivors().isEmpty() == rows.get(i - 1).survivors().isEmpty()
                    && row.netWorth() == rows.get(i - 1).netWorth()) rank = result.get(i - 1).rank();
            result.add(new TeamStanding(row.teamId(), rank, row.netWorth(), row.members(), row.survivors()));
        }
        return List.copyOf(result);
    }
    static RejectionCode decide(DecisionContext<SessionState> ctx, TeamCommand c) {
        var rejected = rejection(ctx.state(), c, ctx.config());
        if (rejected != null) return rejected;
        ctx.emit(fact(c));
        return null;
    }
    private static RejectionCode rejection(SessionState s, TeamCommand c, RuleConfig rules) {
        if (s.inGame()) return RejectionCode.GAME_IN_PROGRESS;
        if (!s.lobby().isMember(c.actor())) return RejectionCode.NOT_MEMBER;
        var team = s.team();
        if (c instanceof SessionCommand.ConfigureTeams x) {
            if (!s.lobby().isHost(x.actor())) return RejectionCode.NOT_HOST;
            if (x.teamSize() != 0 && (!rules.team().enabled() || !rules.team().teamSizeOptions().contains(x.teamSize())))
                return RejectionCode.INVALID_SETTINGS;
            if (x.teamSize() == 0 && team == null || team != null && team.teamSize() == x.teamSize()) return RejectionCode.UNCHANGED;
            if (x.teamSize() > 0) {
                if (s.series() != null && !s.series().finished()) return RejectionCode.NOT_ALLOWED;
                if (!rules.team().allowBots() && s.lobby().members().stream().anyMatch(Member::bot)) return RejectionCode.NOT_ALLOWED;
                if (!fits(rules, s.lobby().settings(), x.teamSize()) || s.lobby().members().size() > x.teamSize() * 2)
                    return RejectionCode.CAPACITY_EXCEEDED;
            }
            return null;
        }
        if (team == null) return RejectionCode.NOT_ALLOWED;
        String target = target(c);
        var member = s.lobby().member(target).orElse(null);
        if (member == null) return RejectionCode.NOT_MEMBER;
        if (c instanceof SessionCommand.AssignBotTeam) {
            if (!s.lobby().isHost(c.actor())) return RejectionCode.NOT_HOST;
            if (!member.bot() || !rules.team().allowBots()) return RejectionCode.NOT_ALLOWED;
        } else if (member.bot()) return RejectionCode.NOT_ALLOWED;
        TeamId selected = selected(c);
        if (selected == null) return RejectionCode.INVALID_ARGUMENT;
        if (team.assignments().get(target) == selected) return RejectionCode.UNCHANGED;
        if (team.assignments().values().stream().filter(id -> id == selected).count() >= team.teamSize()) return RejectionCode.TEAM_FULL;
        return null;
    }
    private static String target(TeamCommand c) {
        return c instanceof SessionCommand.AssignBotTeam x ? x.target() : c.actor();
    }
    private static TeamId selected(TeamCommand c) {
        return switch (c) {
            case SessionCommand.ChooseTeam x -> x.teamId();
            case SessionCommand.AssignBotTeam x -> x.teamId();
            case SessionCommand.ConfigureTeams ignored -> null;
        };
    }
    private static TeamEvent fact(TeamCommand c) {
        return c instanceof SessionCommand.ConfigureTeams x ? new TeamEvent.TeamsConfigured(x.teamSize())
                : new TeamEvent.TeamSelected(target(c), selected(c));
    }
    static SessionState evolve(SessionState s, TeamEvent e, RuleConfig rules) {
        var c = s.teamSource() instanceof TeamCommand source ? source : null;
        LobbyModule.check(c != null && rejection(s, c, rules) == null && fact(c).equals(e),
                "team event requires its matching accepted command");
        TeamState next;
        if (e instanceof TeamEvent.TeamsConfigured x)
            next = x.teamSize() == 0 ? null : new TeamState(x.teamSize(), new TreeMap<>(), 0);
        else {
            var x = (TeamEvent.TeamSelected) e;
            var assignments = new TreeMap<>(s.team().assignments()); assignments.put(x.playerId(), x.teamId());
            next = new TeamState(s.team().teamSize(), assignments, 0);
        }
        var l = s.lobby();
        return s.withTeamSource(null).withTeam(next).withLobby(new RoomState(l.status(), l.hostId(), LobbyModule.unready(l), l.settings()));
    }
    static boolean fits(RuleConfig rules, RoomSettings settings, int size) {
        var b = LobbyModule.board(rules, settings);
        return size * 2 >= b.minPlayers() && size * 2 <= b.maxPlayers();
    }
    static RejectionCode beforeStart(SessionState s, RuleConfig rules) {
        var t = s.team(); if (t == null) return null;
        if (!rules.team().enabled() || s.series() != null && !s.series().finished()) return RejectionCode.NOT_ALLOWED;
        if (!rules.team().allowBots() && s.lobby().members().stream().anyMatch(Member::bot)) return RejectionCode.NOT_ALLOWED;
        if (!fits(rules, s.lobby().settings(), t.teamSize())) return RejectionCode.CAPACITY_EXCEEDED;
        if (s.lobby().members().size() != t.teamSize() * 2 || t.assignments().size() != t.teamSize() * 2
                || t.assignments().values().stream().filter(id -> id == TeamId.BLUE).count() != t.teamSize()
                || t.assignments().values().stream().filter(id -> id == TeamId.RED).count() != t.teamSize())
            return RejectionCode.TEAM_INCOMPLETE;
        return null;
    }
    static SessionState roomChanged(SessionState s, RoomEvent e) {
        var t = s.team(); if (t == null || s.inGame()) return s;
        if (!(e instanceof RoomEvent.PlayerJoined || e instanceof RoomEvent.PlayerLeft || e instanceof RoomEvent.SettingsChanged)) return s;
        var ids = s.lobby().members().stream().map(Member::playerId).toList();
        var assignments = new TreeMap<>(t.assignments()); assignments.keySet().retainAll(ids);
        var l = s.lobby();
        return s.withTeam(new TeamState(t.teamSize(), assignments, 0))
                .withLobby(new RoomState(l.status(), l.hostId(), LobbyModule.unready(l), l.settings()));
    }
    static TeamState unlock(TeamState t, RoomState lobby) {
        if (t == null) return null;
        var ids = lobby.members().stream().map(Member::playerId).toList();
        var assignments = new TreeMap<>(t.assignments()); assignments.keySet().retainAll(ids);
        return new TeamState(t.teamSize(), assignments, 0);
    }
    static List<String> turnOrder(GameState g) {
        var drawn = TurnOrder.order(g.orderDraws());
        if (g.team() == null || drawn.isEmpty()) return drawn;
        TeamId first = g.team().assignments().get(drawn.getFirst());
        var a = drawn.stream().filter(id -> g.team().assignments().get(id) == first).toList();
        var b = drawn.stream().filter(id -> g.team().assignments().get(id) != first).toList();
        LobbyModule.check(a.size() == b.size(), "unequal teams in fixed order");
        var out = new ArrayList<String>();
        for (int i = 0; i < a.size(); i++) { out.add(a.get(i)); out.add(b.get(i)); }
        return List.copyOf(out);
    }
    static void validate(SessionState s, RuleConfig rules) {
        LobbyModule.expect(s.teamSource() == null, "unconsumed team command source");
        var t = s.team();
        if (t == null) { LobbyModule.expect(!s.inGame() || s.game().team() == null, "game team missing in session"); return; }
        LobbyModule.expect(rules.team().enabled() && rules.team().teamSizeOptions().contains(t.teamSize())
                && t.assignments() != null && fits(rules, s.lobby().settings(), t.teamSize())
                && t.locked() == s.inGame(), "invalid team setup/lock");
        var ids = s.inGame() ? s.game().players().stream().map(PlayerState::playerId).toList()
                : s.lobby().members().stream().map(Member::playerId).toList();
        LobbyModule.expect(ids.containsAll(t.assignments().keySet()) && ids.size() <= t.teamSize() * 2
                && t.assignments().values().stream().allMatch(java.util.Objects::nonNull), "invalid team assignments");
        for (var id : TeamId.values()) LobbyModule.expect(t.assignments().values().stream().filter(v -> v == id).count() <= t.teamSize(), "team over capacity");
        LobbyModule.expect(rules.team().allowBots() || s.lobby().members().stream().noneMatch(Member::bot), "team bots disabled");
        LobbyModule.expect(s.series() == null || s.series().finished(), "team and individual series are mutually exclusive");
        if (!s.inGame()) LobbyModule.expect(s.lobby().members().stream().allMatch(m -> !m.ready() || t.assignments().containsKey(m.playerId())), "ready member has not selected a team");
        if (s.inGame()) LobbyModule.expect(t.equals(s.game().team()) && t.lockedGameNo() == s.game().gameNo()
                && t.assignments().keySet().equals(new TreeSet<>(ids)) && ids.size() == t.teamSize() * 2, "frozen team roster mismatch");
        LobbyModule.expect(t.aidUsages().entrySet().stream().allMatch(e -> s.inGame() && ids.contains(e.getKey())
                && e.getValue().turnNo() > 0 && e.getValue().turnNo() <= s.game().turn().turnNo()
                && e.getValue().count() > 0 && e.getValue().count() <= rules.team().aidPerTurn()), "invalid aid usage");
    }
}
