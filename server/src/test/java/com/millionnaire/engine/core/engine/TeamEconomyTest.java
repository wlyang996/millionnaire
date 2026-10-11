package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.core.state.TeamState.TeamId;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamEconomyTest {
    static RuleConfig rules(boolean production, boolean waiver) {
        var c = production ? RuleConfigs.defaultV1() : RuleConfigs.v1(TestBoards.LEGACY_30, TestBoards.LEGACY_50, true);
        var t = TeamConfig.DEFAULT;
        return new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing(), c.room(), c.rentInflation(), c.setBonus(),
                c.startPick(), c.roundReward(), c.funTitles(), c.cityEvents(), c.series(),
                new TeamConfig(true, t.teamSizeOptions(), t.defaultTeamSize(), true, waiver, t.allyRoadblockPass(),
                        t.aidEnabled(), t.aidMaxAmount(), t.aidPerTurn(), t.aidQuickAmounts(), t.debtAidEnabled(), t.debtAidWindowMs()));
    }
    @SafeVarargs
    static Table table(boolean production, boolean waiver, List<ScriptedRandom.Step>... steps) {
        var all = new ArrayList<>(Table.order(90, 80, 70, 60));
        for (var part : steps) all.addAll(part);
        var t = new Table(rules(production, waiver), new ScriptedRandom(all), 1);
        t.send(new RoomCommand.Join("p1", "P1"));
        t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_30, 3000, EndMode.TIME_LIMIT, 30, 15, 0)));
        for (int i = 2; i <= 4; i++) t.send(new RoomCommand.Join("p"+i, "P"+i));
        t.send(new SessionCommand.ConfigureTeams("p1", 2));
        for (int i = 1; i <= 4; i++) t.send(new SessionCommand.ChooseTeam("p"+i, i <= 2 ? TeamId.BLUE : TeamId.RED));
        for (int i = 1; i <= 4; i++) t.send(new RoomCommand.SetReady("p"+i, true));
        assertNull(t.send(new SessionCommand.StartGame("p1")).rejection());
        assertEquals("p1", t.current());
        return t;
    }
    static void craft(Table t, UnaryOperator<GameState> f) {
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(f.apply(t.game())))));
    }
    static GameState own(GameState g, int tile, String owner, int level) {
        return g.withBoard(g.board().with(new OwnableState(tile, owner, level, false, 0, null)));
    }
    private static void restore(Table t) { assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state))); }
    private static void replay(Table t) { restore(t); assertEquals(t.state, t.engine.rebuild(t.log)); }
    /** Move the global deadline ahead of the roll timeout, retaining the production clock invariants. */
    private static void expire(Table t) {
        var g = t.game(); long at = t.now + 1, task = g.clock().taskId();
        var moved = new GameState(g.gameNo(), at - (g.clock().endsAt() - g.startedAt()), g.settings(), g.phase(),
                g.players(), g.orderDraws(), g.board(), g.turn(), g.flow(), g.ledger(), new GameClock(at, task), g.debt(),
                g.pendingSurrenders(), g.minigame(), g.cards(), g.auction(), g.trade(), g.progress(), g.team());
        var state = t.state.withTimers(t.state.timers().cancel(task).schedule(new com.millionnaire.engine.time.ScheduledTask(
                task, at, com.millionnaire.engine.time.TaskKind.GLOBAL_END, g.gameNo())), t.state.nextTaskId());
        t.state = t.engine.restore(t.engine.snapshot(state.withDomain(t.session().withGame(moved))));
        t.tick(at);
    }
    private static void surrender(Table t, String pid) { assertNull(t.send(new GameCommand.Surrender(pid, 1)).rejection()); }
    private static TeamStanding row(GameResult r, TeamId id) {
        return r.teamStandings().stream().filter(x -> x.teamId() == id).findFirst().orElseThrow();
    }
    private static GameEvent.GameEnded ended(Table t) {
        return t.log.stream().filter(GameEvent.GameEnded.class::isInstance).map(GameEvent.GameEnded.class::cast).findFirst().orElseThrow();
    }
    @Test void allyLandAndStationWaiveBeforeResponsesWithoutSpendingACardOrOpeningDebt() {
        for (int tile : List.of(1, 4)) {
            var t = table(false, true, Table.dice(DrawPoint.MOVE_DIE, tile));
            craft(t, g -> own(g, tile, "p2", tile == 1 ? 3 : 0)
                    .withPlayer(g.player("p1").orElseThrow().withHand(List.of(CardType.RENT_WAIVER))));
            t.rollOnly();
            assertEquals(3000, t.cash("p1")); assertEquals(3000, t.cash("p2"));
            assertEquals(List.of(CardType.RENT_WAIVER), t.game().player("p1").orElseThrow().hand());
            assertNull(t.game().debt());
            assertFalse(t.log.stream().anyMatch(e -> e instanceof GameEvent.RentCharged || e instanceof GameEvent.RentWaived
                    || e instanceof GameEvent.RentWaiverDeclined));
            assertEquals("p3", t.current()); restore(t);
        }
    }
    @Test void enemyRentAndConfiguredNonWaiverRetainTheActualPersonalOwner() {
        for (boolean waiver : List.of(true, false)) {
            var t = table(false, waiver, Table.dice(DrawPoint.MOVE_DIE, 1));
            String owner = waiver ? "p3" : "p2";
            craft(t, g -> own(g, 1, owner, 2));
            t.rollOnly();
            assertEquals(2550, t.cash("p1")); assertEquals(3450, t.cash(owner));
            assertTrue(t.log.contains(new GameEvent.RentPaid("p1", owner, 1, 450))); restore(t);
        }
        var t = table(false, false, Table.dice(DrawPoint.MOVE_DIE, 1));
        craft(t, g -> own(g, 1, "p2", 2).withPlayer(g.player("p1").orElseThrow().withHand(List.of(CardType.RENT_WAIVER))));
        t.rollOnly(); assertEquals(LandingStep.RESPONSE, t.game().turn().landing().step()); restore(t);
    }
    @Test void fixedMoveUsesTheSameAllyAndEnemySettlement() {
        for (String owner : List.of("p2", "p3")) {
            var t = table(false, true);
            craft(t, g -> own(g, 1, owner, 0).withPlayer(g.player("p1").orElseThrow().withHand(List.of(CardType.FIXED_MOVE))));
            assertNull(t.act(w -> new GameCommand.UseCard("p1", w, CardType.FIXED_MOVE, null, 1)).rejection());
            assertEquals(1, t.position("p1"));
            assertEquals(owner.equals("p2") ? 3000 : 2900, t.cash("p1")); restore(t);
        }
    }
    @Test void eventForwardAndBackwardBothResolveAllyAndEnemyLand() {
        for (int direction : List.of(0, 1)) for (String owner : List.of("p2", "p3")) {
            int tile = direction == 0 ? 3 : 1;
            var t = table(false, true, Table.dice(DrawPoint.MOVE_DIE, 2),
                    Table.steps(DrawPoint.EVENT_KIND, 100, 80), Table.steps(DrawPoint.EVENT_MOVE_DIRECTION, 2, direction),
                    Table.steps(DrawPoint.EVENT_MOVE_DISTANCE, 3, 0));
            craft(t, g -> own(g, tile, owner, 0));
            t.rollOnly(); assertEquals(LandingStep.EVENT, t.game().turn().landing().step()); t.pass();
            assertEquals(tile, t.position("p1")); assertEquals(owner.equals("p2") ? 3000 : 2900, t.cash("p1")); restore(t);
        }
    }
    @Test void expressSettlesTheDestinationStationWithoutCombiningTeammatesStations() {
        for (String owner : List.of("p2", "p3")) {
            var t = table(true, true, Table.dice(DrawPoint.MOVE_DIE, 1), Table.steps(DrawPoint.LUCKY_EVENT, 100, 60),
                    Table.steps(DrawPoint.EVENT_STATION, 4, 1));
            craft(t, g -> own(own(g, 11, owner, 0), 4, owner.equals("p2") ? "p1" : "p4", 0)
                    .withPlayer(g.player("p1").orElseThrow().at(8)));
            t.rollOnly(); assertEquals(11, t.position("p1"));
            assertEquals(owner.equals("p2") ? 3000 : 2800, t.cash("p1"));
            assertEquals(owner.equals("p2") ? 3000 : 3200, t.cash(owner)); restore(t);
        }
    }
    @Test void teammateOwnershipDoesNotCompleteAPropertyGroupAndSystemFinesStillApply() {
        var t = table(true, true);
        var c = t.config; var board = c.board(RuleConfigs.BOARD_30).orElseThrow();
        var group = c.setBonus().members(board.id(), 1);
        assertTrue(group.size() > 1);
        var g = t.game();
        for (int tile : group) g = own(g, tile, tile == 1 ? "p3" : "p4", 0);
        assertFalse(EconomyModule.setComplete(c, board, g, g.board().ownable(1).orElseThrow()));
        assertEquals(100, EconomyModule.rent(c, board, g, g.board().ownable(1).orElseThrow()));
        var fine = table(true, true, Table.dice(DrawPoint.MOVE_DIE, 1), Table.steps(DrawPoint.LUCKY_EVENT, 100, 10));
        craft(fine, x -> x.withPlayer(x.player("p1").orElseThrow().at(23)));
        fine.rollOnly(); assertEquals(2800, fine.cash("p1")); restore(fine);
    }
    @Test void onePlayerBankruptsButTheirTeamContinuesAndAssetsAreNotDonated() {
        var t = table(false, true, Table.dice(DrawPoint.MOVE_DIE, 1));
        craft(t, g -> own(own(g, 1, "p3", 3), 5, "p1", 0).withLedger(g.ledger().transfer("p1", Ledger.SYSTEM, 2950, "TEST", null)));
        t.rollOnly(); assertNotNull(t.game().debt());
        assertNull(t.act(w -> new GameCommand.DeclareBankruptcy("p1", w)).rejection());
        assertTrue(t.session().inGame()); assertFalse(t.game().player("p1").orElseThrow().alive());
        assertTrue(t.game().player("p2").orElseThrow().alive());
        assertNull(t.game().board().ownable(5).orElseThrow().owner());
        assertEquals(3000, t.cash("p2")); assertEquals("p3", t.current()); restore(t);
    }
    @Test void aWholeTeamEliminatedEndsWithTwoLivingWinnersAndKeepsFrozenMembership() {
        var t = table(false, true);
        surrender(t, "p3"); assertTrue(t.session().inGame());
        surrender(t, "p4"); assertFalse(t.session().inGame());
        var r = t.session().lastResult(); assertEquals("LAST_TEAM", r.reason());
        assertEquals(1, row(r, TeamId.BLUE).rank()); assertEquals(6000, row(r, TeamId.BLUE).netWorth());
        assertEquals(List.of("p1", "p2"), row(r, TeamId.BLUE).survivors());
        assertEquals(2, row(r, TeamId.RED).rank()); assertEquals(0, row(r, TeamId.RED).netWorth());
        assertEquals(List.of("p3", "p4"), row(r, TeamId.RED).members());
        assertTrue(row(r, TeamId.RED).survivors().isEmpty());
        assertEquals(3000, r.standings().stream().filter(s -> s.playerId().equals("p1")).findFirst().orElseThrow().cash());
        replay(t);
    }
    @Test void timeLimitTeamTiesIgnoreIndividualCashTieBreakAndCountMortgagePrincipalOnce() {
        var t = table(false, true);
        craft(t, g -> g.withLedger(g.ledger().transfer("p1", "p2", 500, "TEST", null)));
        expire(t);
        assertEquals("TIME_UP", t.session().lastResult().reason());
        assertTrue(t.session().lastResult().teamStandings().stream().allMatch(s -> s.rank() == 1 && s.netWorth() == 6000)); restore(t);
        var mortgage = table(false, true);
        craft(mortgage, g -> g.withBoard(g.board().with(new OwnableState(1, "p1", 2, true, 500, null))));
        expire(mortgage);
        assertEquals(6300, row(mortgage.session().lastResult(), TeamId.BLUE).netWorth()); restore(mortgage);
    }
    @Test void eliminatedPlayerDoesNotContributeToTimedTeamTotalAndEarlySurrenderDoesNotEndGame() {
        var t = table(false, true); surrender(t, "p1");
        assertTrue(t.session().inGame()); assertEquals("p3", t.current());
        expire(t);
        var r = t.session().lastResult();
        assertEquals(6000, row(r, TeamId.RED).netWorth()); assertEquals(1, row(r, TeamId.RED).rank());
        assertEquals(3000, row(r, TeamId.BLUE).netWorth()); assertEquals(2, row(r, TeamId.BLUE).rank()); restore(t);
    }
    @Test void teamResultTamperingCannotBeReplayed() {
        var t = table(false, true); surrender(t, "p3"); surrender(t, "p4");
        var e = ended(t); var r = e.result();
        var bad = new GameResult(r.gameNo(), r.reason(), r.standings(), r.titles(), r.metrics(), List.of());
        var log = new ArrayList<>(t.log); log.set(log.indexOf(e), new GameEvent.GameEnded(e.gameNo(), e.reason(), bad));
        assertThrows(RuntimeException.class, () -> t.engine.rebuild(log));
    }
    @Test void rentFeeCannotBeRestoredAsDebtBetweenRentExemptAllies() {
        var t = table(false, true, Table.dice(DrawPoint.MOVE_DIE, 1));
        craft(t, g -> own(own(g, 1, "p3", 3), 5, "p1", 0)
                .withLedger(g.ledger().transfer("p1", Ledger.SYSTEM, 2950, "TEST", null)));
        t.rollOnly(); var g = t.game(); var debt = g.debt(); assertNotNull(debt);
        assertTrue(FeeRules.valid(t.config, g, g.turn().landing(), debt.source()));
        var assignments = new TreeMap<>(g.team().assignments());
        assignments.put("p2", TeamId.RED); assignments.put("p3", TeamId.BLUE);
        var ally = new TeamState(2, assignments, g.gameNo()); var changed = g.withTeam(ally);
        assertFalse(FeeRules.valid(t.config, changed, changed.turn().landing(), debt.source()));
        var state = t.state.withDomain(t.session().withTeam(ally).withGame(changed));
        assertThrows(RuntimeException.class, () -> t.engine.restore(t.engine.snapshot(state)));
    }
    @Test void acceptedEnemySurrenderWaitsForTheCardResponseBeforeTeamVictory() {
        var t = table(false, true);
        craft(t, g -> own(g, 1, "p3", 2)
                .withPlayer(g.player("p1").orElseThrow().at(1).withHand(List.of(CardType.DOWNGRADE)))
                .withPlayer(g.player("p3").orElseThrow().withHand(List.of(CardType.HOUSE_PROTECTION))));
        assertNull(t.act(w -> new GameCommand.UseCard("p1", w, CardType.DOWNGRADE, null, 0)).rejection());
        surrender(t, "p3"); surrender(t, "p4");
        assertTrue(t.session().inGame()); assertEquals(List.of("p3"), t.game().pendingSurrenders());
        assertNotNull(t.game().cards().effect());
        t.tick(t.window().window().deadline());
        assertFalse(t.session().inGame()); assertEquals("LAST_TEAM", t.session().lastResult().reason());
        assertTrue(t.state.timers().tasks().isEmpty()); restore(t);
    }
    @Test void sixAndEightPlayerGamesEndWhenTheEnemyTeamIsGoneInsteadOfWaitingForOnePerson() {
        for (int n : List.of(6, 8)) {
            var t = new Table(rules(true, true), com.millionnaire.engine.random.XoshiroLemireV1.INSTANCE, 38);
            t.send(new RoomCommand.Join("p1", "P1"));
            t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_50, 3000, EndMode.BANKRUPTCY, 30, 15, 0)));
            for (int i = 2; i <= n; i++) t.send(new RoomCommand.Join("p"+i, "P"+i));
            t.send(new SessionCommand.ConfigureTeams("p1", n/2));
            for (int i = 1; i <= n; i++) t.send(new SessionCommand.ChooseTeam("p"+i, i <= n/2 ? TeamId.BLUE : TeamId.RED));
            for (int i = 1; i <= n; i++) t.send(new RoomCommand.SetReady("p"+i, true));
            assertNull(t.send(new SessionCommand.StartGame("p1")).rejection());
            for (int i = n/2 + 1; i <= n; i++) {
                surrender(t, "p"+i); assertEquals(i != n, t.session().inGame());
            }
            var blue = row(t.session().lastResult(), TeamId.BLUE);
            assertEquals(n/2, blue.survivors().size()); assertEquals(n/2 * 3000L, blue.netWorth());
            assertEquals(1, blue.rank()); replay(t);
        }
    }
}
