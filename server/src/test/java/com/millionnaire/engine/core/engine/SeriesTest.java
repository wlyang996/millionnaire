package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.Table;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeriesTest {
    private Table lobby(int count, boolean bot) {
        var t = new Table(RuleConfigs.defaultV1(), XoshiroLemireV1.INSTANCE, 371);
        t.send(new RoomCommand.Join("p1", "P1"));
        t.send(new RoomCommand.ChangeSettings("p1", RoomSettings.defaults(t.config).withInitialCards(0)));
        if (count > 4) t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_50, 3000, EndMode.TIME_LIMIT, 30, 15, 0)));
        for (int i = 2; i <= count; i++) t.send(bot && i == count ? new RoomCommand.JoinBot("p" + i, "P" + i) : new RoomCommand.Join("p" + i, "P" + i));
        assertNull(t.send(new SessionCommand.ConfigureSeries("p1", 3)).rejection());
        return t;
    }
    private void start(Table t) {
        for (var m : t.session().lobby().members()) t.send(new RoomCommand.SetReady(m.playerId(), true));
        assertNull(t.send(new SessionCommand.StartGame(t.session().lobby().hostId())).rejection());
        assertEquals(SeriesState.Status.PLAYING, t.session().series().status());
    }
    private void finish(Table t) {
        for (var p : t.game().players()) t.send(new GameCommand.SetControl(t.game().gameNo(), p.playerId(), ControlMode.HOSTED));
        int count = 0;
        while (t.session().inGame() && count++ < 10000)
            t.tick(t.state.timers().tasks().stream().mapToLong(task -> task.dueAt()).min().orElseThrow());
        assertTrue(count < 10000);
        assertNotNull(t.session().lastResult());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }
    @Test void timerDrivenTwoFourAndEightPlayerSeriesResetAssetsAndScoreEveryGameOnce() {
        for (int n : List.of(2, 4, 8)) {
            var t = lobby(n, true);
            var sum = new TreeMap<String, Integer>();
            for (int game = 1; game <= 3; game++) {
                start(t);
                assertTrue(t.game().players().stream().allMatch(p -> t.game().ledger().cash(p.playerId()) == 3000));
                assertTrue(t.game().board().ownables().stream().allMatch(o -> o.owner() == null));
                assertFalse(t.game().progress().rewardGranted());
                finish(t);
                var z = t.session().series();
                assertEquals(game, z.games().size());
                var r = t.session().lastResult();
                for (var row : r.standings()) sum.merge(row.playerId(), z.rules().rankPoints().get(row.rank() - 1), Integer::sum);
                assertEquals(sum, z.points());
                assertEquals(game, t.log.stream().filter(e -> e instanceof SeriesEvent.SeriesGameScored).count());
                long before = t.session().gamesPlayed(); t.tick(t.now + 1);
                assertEquals(before, t.session().gamesPlayed());
            }
            assertEquals(SeriesState.Status.FINISHED, t.session().series().status());
            assertEquals(3, t.session().series().games().size());
            assertTrue(t.session().series().roster().getLast().bot());
            assertNull(t.send(new SessionCommand.ConfigureSeries("p1", 5)).rejection());
            assertEquals(2, t.session().series().seriesNo());
        }
    }
    @Test void locksReadyRulesAndRosterButAllowsBetweenGameWithdrawalWithPastPointsRetained() {
        var t = lobby(4, false);
        assertTrue(t.session().lobby().members().stream().noneMatch(Member::ready));
        assertEquals(RejectionCode.NOT_HOST, t.send(new SessionCommand.ConfigureSeries("p2", 5)).rejection());
        assertEquals(RejectionCode.INVALID_SETTINGS, t.send(new SessionCommand.ConfigureSeries("p1", 4)).rejection());
        start(t); finish(t);
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new RoomCommand.ChangeSettings("p1", t.session().lobby().settings().withFastMode(true))).rejection());
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new SessionCommand.ConfigureSeries("p1", 5)).rejection());
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new RoomCommand.Join("new", "new")).rejection());
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new RoomCommand.Kick("p1", "p2")).rejection());
        var points = t.session().series().points().get("p4");
        assertNull(t.send(new RoomCommand.Leave("p4")).rejection());
        start(t); finish(t);
        assertEquals(points, t.session().series().points().get("p4"));
        assertEquals(List.of("p4"), t.session().series().withdrawn());
        assertEquals(3, t.session().series().games().getLast().scores().size());
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }
    @Test void adminAbortKeepsSeriesNumberAndPointsAndReplayStartsTheSameSeriesGameIndex() {
        var t = lobby(2, false); start(t);
        t.send(new GameCommand.ConnectionSuspected(t.game().gameNo(), "p2", 1));
        t.send(new GameCommand.Reconnected(t.game().gameNo(), "p2", 2));
        assertNull(t.send(new SessionCommand.EndGame(t.game().gameNo(), "ADMIN_STOP")).rejection());
        assertEquals(0, t.session().series().games().size());
        assertEquals(SeriesState.Status.BETWEEN_GAMES, t.session().series().status());
        start(t); finish(t);
        assertEquals(1, t.session().series().games().getFirst().index());
        assertEquals(2, t.session().series().games().getFirst().gameNo());
        t.send(new RoomCommand.Leave("p2"));
        t.send(new RoomCommand.SetReady("p1", true));
        assertEquals(RejectionCode.NOT_ENOUGH_PLAYERS, t.send(new SessionCommand.StartGame("p1")).rejection());
        assertEquals(RejectionCode.NOT_HOST, t.send(new SessionCommand.EndSeries("p2", 1)).rejection());
        assertNull(t.send(new SessionCommand.EndSeries("p1", 1)).rejection());
        assertEquals(SeriesState.Status.ENDED, t.session().series().status());
        assertEquals(1, t.session().series().games().size());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void sharedRanksAndOccupiedRankAverageGiveCorrectScoresAndJointChampions() {
        var members = List.of(new Member("a", "A", true), new Member("b", "B", true), new Member("c", "C", true));
        var result = new GameResult(1, "TIME_UP", List.of(new Standing("a", 1, 3000, 3000), new Standing("b", 1, 3000, 3000), new Standing("c", 3, 2000, 2000)));
        for (var tie : SeriesConfig.TieMode.values()) {
            var rules = new SeriesConfig(true, List.of(3), 3, SeriesConfig.DEFAULT.rankPoints(), tie, true, 2);
            var z = new SeriesState(1, 3, SeriesState.Status.PLAYING, members, null, rules, List.of(), Map.of("a", 0, "b", 0, "c", 0), List.of());
            var out = SeriesModule.ended(z, result);
            assertEquals(tie == SeriesConfig.TieMode.SHARED_RANK ? 10 : 8, out.points().get("a"));
            assertEquals(out.points().get("a"), out.points().get("b"));
            assertEquals(4, out.points().get("c"));
            assertEquals(List.of(1, 1, 3), out.standings().stream().map(SeriesState.Entry::rank).toList());
            assertThrows(IllegalStateException.class, () -> SeriesModule.ended(new SeriesState(out.seriesNo(), out.totalGames(), SeriesState.Status.PLAYING, out.roster(), out.settings(), out.rules(), out.games(), out.points(), out.withdrawn()), result));
        }
    }
    @Test void disabledSeriesBotsMinimumAndInvalidPointsAreValidated() {
        var c = RuleConfigs.defaultV1();
        var rules = new SeriesConfig(true, List.of(3), 3, SeriesConfig.DEFAULT.rankPoints(), SeriesConfig.TieMode.SHARED_RANK, false, 3);
        var copy = new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(), c.cardWeights(), c.eventWeights(), c.timing(), c.room(), c.rentInflation(), c.setBonus(), c.startPick(), c.roundReward(), c.funTitles(), c.cityEvents(), rules);
        var t = new Table(copy, XoshiroLemireV1.INSTANCE, 4);
        t.send(new RoomCommand.Join("p1", "P1"));
        t.send(new RoomCommand.JoinBot("p2", "P2"));
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new SessionCommand.ConfigureSeries("p1", 3)).rejection());
        t.send(new RoomCommand.Leave("p2")); t.send(new SessionCommand.ConfigureSeries("p1", 3));
        assertEquals(RejectionCode.NOT_ALLOWED, t.send(new RoomCommand.JoinBot("p2", "P2")).rejection());
        assertFalse(SeriesConfig.validate(new SeriesConfig(true, List.of(1), 1, List.of(0, 10), null, true, 1)).isEmpty());
        var old = new Table(XoshiroLemireV1.INSTANCE, 6); old.send(new RoomCommand.Join("p1", "P1"));
        assertEquals(RejectionCode.INVALID_SETTINGS, old.send(new SessionCommand.ConfigureSeries("p1", 3)).rejection());
    }
}
