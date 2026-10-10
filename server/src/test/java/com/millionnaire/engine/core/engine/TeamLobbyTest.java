package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.core.state.TeamState.TeamId;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.Table;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamLobbyTest {
    private RuleConfig rules(boolean bots) {
        var c = RuleConfigs.defaultV1(); var t = TeamConfig.DEFAULT;
        return new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing(), c.room(), c.rentInflation(), c.setBonus(),
                c.startPick(), c.roundReward(), c.funTitles(), c.cityEvents(), c.series(),
                new TeamConfig(true, t.teamSizeOptions(), t.defaultTeamSize(), bots, t.allyRentWaiver(),
                        t.allyRoadblockPass(), t.aidEnabled(), t.aidMaxAmount(), t.aidPerTurn(), t.aidQuickAmounts(), t.debtAidEnabled(), t.debtAidWindowMs()));
    }
    private Table lobby(int count, boolean bot, boolean botsAllowed) {
        var t = new Table(rules(botsAllowed), XoshiroLemireV1.INSTANCE, 38);
        t.send(new RoomCommand.Join("p1", "P1"));
        if (count > 4) t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_50, 3000, EndMode.TIME_LIMIT, 30, 15, 0)));
        for (int i = 2; i <= count; i++) assertNull(t.send(bot && i == count ? new RoomCommand.JoinBot("p"+i, "机器人") : new RoomCommand.Join("p"+i, "P"+i)).rejection());
        return t;
    }
    private void choose(Table t, int count) {
        for (int i = 1; i <= count; i++) {
            String id = "p"+i; TeamId team = i <= count/2 ? TeamId.BLUE : TeamId.RED;
            if (t.session().team().assignments().get(id) == team) continue;
            assertNull(t.send(t.session().lobby().member(id).orElseThrow().bot()
                    ? new SessionCommand.AssignBotTeam("p1", id, team) : new SessionCommand.ChooseTeam(id, team)).rejection());
        }
    }
    private void ready(Table t) { for (var m : t.session().lobby().members()) assertNull(t.send(new RoomCommand.SetReady(m.playerId(), true)).rejection()); }
    private void assertReplay(Table t) {
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        assertNull(t.session().teamSource());
    }

    @Test void disabledByDefaultAndOnlyHostCanConfigureOrAssignRealBots() {
        var ordinary = new Table(RuleConfigs.defaultV1(), XoshiroLemireV1.INSTANCE, 4);
        ordinary.send(new RoomCommand.Join("p1", "P1"));
        assertEquals(RejectionCode.INVALID_SETTINGS, ordinary.send(new SessionCommand.ConfigureTeams("p1", 2)).rejection());
        var t = lobby(4, true, true);
        assertEquals(RejectionCode.NOT_HOST, t.send(new SessionCommand.ConfigureTeams("p2", 2)).rejection());
        assertNull(t.send(new SessionCommand.ConfigureTeams("p1", 2)).rejection());
        assertEquals(RejectionCode.NOT_HOST, t.send(new SessionCommand.AssignBotTeam("p2", "p4", TeamId.BLUE)).rejection());
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new SessionCommand.AssignBotTeam("p1", "p2", TeamId.BLUE)).rejection());
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new SessionCommand.ChooseTeam("p4", TeamId.BLUE)).rejection());
        assertEquals(RejectionCode.NOT_MEMBER, t.send(new SessionCommand.ChooseTeam("outsider", TeamId.BLUE)).rejection());
        assertEquals(RejectionCode.INVALID_ARGUMENT, t.send(new SessionCommand.ChooseTeam("p2", null)).rejection());
        assertNull(t.send(new SessionCommand.AssignBotTeam("p1", "p4", TeamId.RED)).rejection());
        assertEquals(Map.of("p4", TeamId.RED), t.session().team().assignments());
        assertReplay(t);
    }
    @Test void selectionCapacityAndRosterChangesClearPreparation() {
        var t = lobby(3, false, true); t.send(new SessionCommand.ConfigureTeams("p1", 2));
        assertEquals(RejectionCode.TEAM_NOT_SELECTED, t.send(new RoomCommand.SetReady("p1", true)).rejection());
        t.send(new SessionCommand.ChooseTeam("p1", TeamId.BLUE)); t.send(new SessionCommand.ChooseTeam("p2", TeamId.BLUE));
        assertEquals(RejectionCode.TEAM_FULL, t.send(new SessionCommand.ChooseTeam("p3", TeamId.BLUE)).rejection());
        t.send(new SessionCommand.ChooseTeam("p3", TeamId.RED)); ready(t);
        assertEquals(RejectionCode.UNCHANGED, t.send(new SessionCommand.ChooseTeam("p3", TeamId.RED)).rejection());
        assertTrue(t.session().lobby().members().stream().allMatch(Member::ready));
        assertNull(t.send(new SessionCommand.ChooseTeam("p2", TeamId.RED)).rejection());
        assertTrue(t.session().lobby().members().stream().noneMatch(Member::ready));
        ready(t); t.send(new RoomCommand.Join("p4", "P4"));
        assertTrue(t.session().lobby().members().stream().noneMatch(Member::ready));
        t.send(new RoomCommand.Leave("p2"));
        assertFalse(t.session().team().assignments().containsKey("p2"));
        t.send(new RoomCommand.Leave("p1"));
        assertEquals("p3", t.session().lobby().hostId());
        assertNull(t.send(new SessionCommand.ConfigureTeams("p3", 0)).rejection());
        assertNull(t.session().team()); assertReplay(t);
    }
    @Test void fourSixEightPlayersLockAlternatingTeamsAndKeepPersonalAccountsAndPrivateHands() {
        for (int n : List.of(4,6,8)) {
            var t = lobby(n, true, true); t.send(new SessionCommand.ConfigureTeams("p1", n/2)); choose(t,n); ready(t);
            assertNull(t.send(new SessionCommand.StartGame("p1")).rejection());
            var team = t.session().team(); assertTrue(team.locked()); assertEquals(t.game().gameNo(), team.lockedGameNo());
            assertEquals(team, t.game().team());
            var players = t.game().players();
            for (int i = 1; i < n; i++) assertFalse(team.sameTeam(players.get(i-1).playerId(), players.get(i).playerId()));
            assertTrue(players.stream().allMatch(p -> t.game().ledger().cash(p.playerId()) == 3000));
            assertEquals(RejectionCode.GAME_IN_PROGRESS, t.send(new SessionCommand.ChooseTeam("p1", TeamId.RED)).rejection());
            assertEquals(RejectionCode.GAME_IN_PROGRESS, t.send(new SessionCommand.ConfigureTeams("p1", 0)).rejection());
            assertEquals(RejectionCode.GAME_IN_PROGRESS, t.send(new SessionCommand.AssignBotTeam("p1", "p"+n, TeamId.BLUE)).rejection());
            var viewer = SessionDomain.INSTANCE.project(t.state, "watcher");
            assertEquals(team, viewer.team()); assertEquals(team, viewer.game().team()); assertTrue(viewer.game().myHand().isEmpty());
            assertReplay(t);
            assertNull(t.send(new SessionCommand.EndGame(t.game().gameNo(), "TEST")).rejection());
            assertFalse(t.session().team().locked()); assertTrue(t.session().lobby().members().stream().noneMatch(Member::ready));
            ready(t); assertNull(t.send(new SessionCommand.StartGame("p1")).rejection());
            assertEquals(2, t.session().team().lockedGameNo()); assertReplay(t);
        }
    }
    @Test void mapAndExactRosterAreCheckedAndTeamSettingsChangesClearReadiness() {
        var t = lobby(3, false, true); t.send(new SessionCommand.ConfigureTeams("p1", 2));
        choose(t,2); t.send(new SessionCommand.ChooseTeam("p3", TeamId.RED)); ready(t);
        assertEquals(RejectionCode.TEAM_INCOMPLETE, t.send(new SessionCommand.StartGame("p1")).rejection());
        assertEquals(RejectionCode.CAPACITY_EXCEEDED, t.send(new SessionCommand.ConfigureTeams("p1", 3)).rejection());
        t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_50, 3000, EndMode.TIME_LIMIT, 30, 15, 0)));
        assertTrue(t.session().lobby().members().stream().noneMatch(Member::ready));
        assertNull(t.send(new SessionCommand.ConfigureTeams("p1", 3)).rejection());
        assertTrue(t.session().team().assignments().isEmpty());
        assertEquals(RejectionCode.CAPACITY_EXCEEDED, t.send(new RoomCommand.ChangeSettings("p1", RoomSettings.defaults(t.config))).rejection());
        assertEquals(RejectionCode.INVALID_SETTINGS, t.send(new SessionCommand.ConfigureTeams("p1", -1)).rejection());
        assertReplay(t);
    }
    @Test void individualSeriesAndTeamsCannotBeCombinedAndBotSwitchIsEnforced() {
        var t = lobby(4, false, true); t.send(new SessionCommand.ConfigureTeams("p1", 2));
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new SessionCommand.ConfigureSeries("p1", 3)).rejection());
        t.send(new SessionCommand.ConfigureTeams("p1", 0)); t.send(new SessionCommand.ConfigureSeries("p1", 3));
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new SessionCommand.ConfigureTeams("p1", 2)).rejection());
        t.send(new SessionCommand.EndSeries("p1", t.session().series().seriesNo()));
        assertNull(t.send(new SessionCommand.ConfigureTeams("p1", 2)).rejection()); assertReplay(t);
        var disabledBots = lobby(2, true, false);
        assertEquals(RejectionCode.NOT_ALLOWED, disabledBots.send(new SessionCommand.ConfigureTeams("p1", 2)).rejection());
        disabledBots.send(new RoomCommand.Leave("p2")); disabledBots.send(new SessionCommand.ConfigureTeams("p1", 2));
        assertEquals(RejectionCode.NOT_ALLOWED, disabledBots.send(new RoomCommand.JoinBot("p2", "P2")).rejection()); assertReplay(disabledBots);
    }
    @Test void forgedReplaySelectionAndRestoredUnconsumedCredentialsAreRejected() {
        var t = lobby(4, false, true); t.send(new SessionCommand.ConfigureTeams("p1", 2));
        t.send(new SessionCommand.ChooseTeam("p1", TeamId.BLUE));
        var forged = new ArrayList<Event>(t.log);
        int pos = 0; while (!(forged.get(pos) instanceof TeamEvent.TeamSelected)) pos++;
        forged.set(pos, new TeamEvent.TeamSelected("p2", TeamId.BLUE));
        assertThrows(RuntimeException.class, () -> t.engine.rebuild(forged));
        var pending = t.state.withDomain(t.session().withTeamSource(new SessionCommand.ChooseTeam("p2", TeamId.RED)));
        assertThrows(RuntimeException.class, () -> t.engine.restore(t.engine.snapshot(pending)));
        choose(t,4); ready(t); t.send(new SessionCommand.StartGame("p1"));
        var mismatched = t.state.withDomain(t.session().withTeam(new TeamState(2, t.session().team().assignments(), 2)));
        assertThrows(RuntimeException.class, () -> t.engine.restore(t.engine.snapshot(mismatched)));
        assertThrows(UnsupportedOperationException.class, () -> t.session().team().assignments().put("p1", TeamId.RED)); assertReplay(t);
    }
}
