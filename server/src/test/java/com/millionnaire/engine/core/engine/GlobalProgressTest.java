package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.*;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.core.state.GameProgressState.*;
import com.millionnaire.engine.random.*;
import com.millionnaire.engine.testkit.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobalProgressTest {
    private RuleConfig rules(boolean city, int advance) {
        var c = TestBoards.legacyV1();
        var r = new RoundRewardConfig(true, 1, 2, 5, 1000, 10,
                List.of(RoundRewardConfig.IncomeSource.values()), 3000);
        var e = new CityEventConfig(city, List.of(EndMode.values()), 1, 1, 100, advance, 3000, 1500, 1500,
                List.of(new CityEventConfig.Spec(CityEventConfig.Kind.UPGRADE_DISCOUNT, true, "建设节", 1, 80, 2, "city_construction")));
        return new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing(), c.room(), c.rentInflation(), c.setBonus(), c.startPick(),
                r, FunTitleConfig.DEFAULT, e);
    }
    private Table table(boolean city, int advance, EndMode mode) {
        return new Table(rules(city, advance), new ScriptedRandom(Table.script(Table.order(90, 10), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 1, 1), city ? Table.steps(DrawPoint.CITY_TRIGGER, 100, 0) : List.of(),
                city ? Table.steps(DrawPoint.CITY_KIND, 1, 0) : List.of())), 1)
                .start(2, RuleConfigs.BOARD_30, mode, 30);
    }
    @Test void wholeRoundNoticeBlocksCommandsAndKeepsTheCompleteRollWindow() {
        var t = table(false, 1, EndMode.TIME_LIMIT);
        t.rollOnly(); t.pass();
        assertFalse(t.game().progress().rewardGranted());
        t.rollOnly(); t.pass();
        var p = t.game().progress();
        assertTrue(p.rewardGranted()); assertEquals(1, p.boundaryRound());
        assertEquals(2, p.notices().getFirst().awards().size());
        assertTrue(p.notices().getFirst().awards().stream().allMatch(a -> a.reward() == 0 && a.income() == 0));
        assertEquals(TurnStage.AWAITING_FLOW, t.game().turn().stage());
        assertEquals(RejectionCode.WINDOW_NOT_OPEN, t.send(new GameCommand.RollDice(t.current(), 0)).rejection());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        long ends = p.notices().getLast().endsAt();
        t.tick(ends);
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        assertEquals(ends, t.window().window().opensAt());
        assertEquals(15000, t.window().window().deadline() - ends);
        t.tick(ends + 1);
        assertEquals(1, t.log.stream().filter(e -> e instanceof GameEvent.RoundRewardGranted).count());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void independentModeRoundAndCityEffectsStartAndRestoreExactly() {
        var t = table(true, 2, EndMode.BANKRUPTCY);
        t.rollOnly(); t.pass(); t.rollOnly(); t.pass();
        assertFalse(t.game().progress().rewardGranted());
        var city = t.game().progress().cityEvent();
        assertEquals(3, city.startRound()); assertEquals(5, city.endRound());
        assertEquals(100, GameProgressModule.multiplier(t.game(), CityEventConfig.Kind.UPGRADE_DISCOUNT));
        assertEquals(t.state, t.engine.rebuild(t.log));
        t.tick(t.game().progress().notices().getLast().endsAt());
        assertEquals(0, t.game().progress().noticeTaskId());
    }
    @Test void incomeUsesOnlyActualCreditsAndTitlesKeepTiesAndMultipleHonours() {
        var t = table(false, 1, EndMode.TIME_LIMIT); var before = t.game();
        var paid = before.withLedger(before.ledger().transfer("p2", "p1", 500, "RENT", "test")
                .transfer(com.millionnaire.engine.ledger.Ledger.SYSTEM, "p1", 1000, "MORTGAGE", "loan")
                .transfer("p2", "p1", 200, "TRADE", "sale")
                .transfer(com.millionnaire.engine.ledger.Ledger.SYSTEM, "p1", 600, "EVENT_REWARD", "event"));
        var captured = GameProgressModule.capture(before, paid, null, t.config);
        assertEquals(500, captured.progress().metrics().get("p1").amount(RoundRewardConfig.IncomeSource.RENT));
        assertEquals(600, captured.progress().metrics().get("p1").amount(RoundRewardConfig.IncomeSource.EVENT));
        assertEquals(50, GameProgressModule.awards(captured, t.config.roundReward()).getFirst().reward());
        assertEquals(2, GameProgressModule.titles(captured, t.config).size());
        var m = captured.progress().metrics().get("p1");
        var both = captured.withProgress(new GameProgressState(Map.of("p1", m, "p2", m), Map.of(), false, 0, null, List.of(), 0, 1, false));
        assertEquals(4, GameProgressModule.titles(both, t.config).size());
    }
    @Test void rewardIsCappedRoundedAndOverflowSafe() {
        var r = RoundRewardConfig.DEFAULT;
        assertEquals(400, r.reward(8000)); assertEquals(300, r.reward(6000));
        assertEquals(1000, r.reward(25000)); assertEquals(1000, r.reward(Long.MAX_VALUE));
        assertEquals(0, r.reward(0)); assertEquals(0, r.reward(199));
        assertEquals(10, r.reward(200));
    }
    @Test void rentDebtLiquidationCountsOnlyWhatTheOwnerActuallyReceived() {
        var c = TestBoards.legacyV1();
        var enabled = new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing(), c.room(), c.rentInflation(), c.setBonus(), c.startPick(),
                RoundRewardConfig.NONE, FunTitleConfig.DEFAULT, CityEventConfig.NONE);
        var t = new Table(enabled, ScriptedRandom.withEventCards(Table.script(Table.order(90, 80), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 1, 5, 2, 1, 1, 1, 1))), 1).start(2);
        for (String player : List.of("p1", "p2", "p1", "p2")) {
            t.rollThenResolveEvent(); t.act(w -> new GameCommand.BuyProperty(player, w));
            t.act(w -> new GameCommand.UpgradeProperty(player, w));
        }
        t.rollThenResolveEvent(); t.act(w -> new GameCommand.BuyProperty("p1", w));
        t.rollThenResolveEvent(); t.rollThenResolveEvent();
        var before = t.game(); assertNotNull(before.debt());
        String creditor = before.debt().creditor();
        long previous = before.progress().metrics().get(creditor).amount(RoundRewardConfig.IncomeSource.RENT);
        t.act(w -> new GameCommand.DeclareBankruptcy(before.debt().debtor(), w));
        var end = t.log.stream().filter(e -> e instanceof GameEvent.DebtSettled).map(e -> (GameEvent.DebtSettled)e).findFirst().orElseThrow();
        assertTrue(end.paid() > 0);
        assertEquals(end.paid(), t.session().lastResult().metrics().get(creditor).amount(RoundRewardConfig.IncomeSource.RENT) - previous);
        assertEquals(t.state, t.engine.rebuild(t.log));
    }
    @Test void realTimerDrivenEightPlayerGameCoversRepeatedCityBoundariesAndReplay() {
        var t = new Table(rules(true, 1), XoshiroLemireV1.INSTANCE, 913).start(8, RuleConfigs.BOARD_50, EndMode.TIME_LIMIT, 30);
        int steps = 0;
        while (t.session().inGame() && t.game().turn().round() < 6 && steps++ < 2000) {
            long due = t.state.timers().tasks().stream().mapToLong(task -> task.dueAt()).min().orElseThrow();
            t.tick(due);
        }
        assertTrue(steps < 2000);
        assertTrue(t.log.stream().anyMatch(e -> e instanceof GameEvent.CityEventEnded));
        assertEquals(1, t.log.stream().filter(e -> e instanceof GameEvent.RoundRewardGranted).count());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }
    @Test void cityCostsChangeCashButNotStandardAssetValueAndCanReachZeroRent() {
        var t = table(false, 1, EndMode.TIME_LIMIT); var g = t.game();
        var tile = t.config.board(g.settings().boardId()).orElseThrow().tiles().get(1);
        var spec = new CityEventConfig.Spec(CityEventConfig.Kind.UPGRADE_DISCOUNT, true, "建设节", 1, 80, 2, "city_construction");
        var active = g.withProgress(new GameProgressState(Map.of(), Map.of(), false, 0,
                new CityEvent(1, spec, 1, 3), List.of(), 0, 2, false));
        assertEquals(t.config.tier(tile.tier()).upgradeCost() * 80 / 100, GameProgressModule.upgradeCost(t.config, active, tile));
        var off = active.withTurn(active.turn().next(active.turn().turnNo() + 1, active.turn().currentPlayer(), 3, 100));
        assertEquals(t.config.tier(tile.tier()).upgradeCost(), GameProgressModule.upgradeCost(t.config, off, tile));
        var owned = active.withBoard(active.board().with(new OwnableState(1, "p2", 1, false, 0, null)));
        var original = owned.withProgress(GameProgressState.EMPTY);
        assertEquals(EconomyModule.netWorth(t.config, original, "p2"), EconomyModule.netWorth(t.config, owned, "p2"));
        var rentSpec = new CityEventConfig.Spec(CityEventConfig.Kind.PROPERTY_RENT, true, "安居日", 1, 1, 2, "scene_property_low");
        var zeroRent = owned.withBoard(owned.board().with(new OwnableState(1, "p2", 0, false, 0, null)))
                .withProgress(new GameProgressState(Map.of(), Map.of(), false, 0, new CityEvent(1, rentSpec, 1, 3), List.of(), 0, 2, false));
        assertEquals(0, EconomyModule.rent(t.config, t.config.board(zeroRent.settings().boardId()).orElseThrow(), zeroRent,
                zeroRent.board().ownable(1).orElseThrow()));
        assertNull(EconomyModule.requiredStep(t.config, zeroRent, "p1", 1));
    }

    @Test void globalDeadlineDuringNoticeDrainsItWithoutOpeningAnotherRollWindow() {
        var t = table(false, 1, EndMode.TIME_LIMIT);
        t.rollOnly(); t.pass(); t.rollOnly(); t.pass();
        var g = t.game(); long end = g.progress().notices().getLast().endsAt();
        long at = end - 1, task = g.clock().taskId();
        var moved = new GameState(g.gameNo(), at - (g.clock().endsAt() - g.startedAt()), g.settings(), g.phase(),
                g.players(), g.orderDraws(), g.board(), g.turn(), g.flow(), g.ledger(), new GameClock(at, task), g.debt(),
                g.pendingSurrenders(), g.minigame(), g.cards(), g.auction(), g.trade(), g.progress());
        var state = t.state.withTimers(t.state.timers().cancel(task).schedule(new com.millionnaire.engine.time.ScheduledTask(
                task, at, com.millionnaire.engine.time.TaskKind.GLOBAL_END, g.gameNo())), t.state.nextTaskId());
        t.state = t.engine.restore(t.engine.snapshot(state.withDomain(t.session().withGame(moved))));
        t.tick(at); assertEquals(GamePhase.DRAINING, t.game().phase());
        t.tick(end); assertFalse(t.session().inGame());
        assertEquals("TIME_UP", t.session().lastResult().reason());
        assertTrue(t.state.timers().tasks().isEmpty());
    }
}
