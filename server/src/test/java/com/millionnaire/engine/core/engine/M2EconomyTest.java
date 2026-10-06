package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.Tier;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.LifeState;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.Standing;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * M2 经济规则的精确断言（30 格地图：1 L、3 L、4 车站、5 M、6 L*、7 M、8 监狱、9 L、11 银行、12 车站、13 M*、14 H、
 * 19 车站、20 H、27 车站）。p1 抽 90、p2 抽 10，p1 先行动；初始现金 3000。
 */
class M2EconomyTest {

    private static Table table(int players, int... moves) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            order.add(90 - i * 10);
        }
        Table t = new Table(new ScriptedRandom(script(order(order.stream().mapToInt(Integer::intValue).toArray()),
                Table.deal(players), dice(DrawPoint.MOVE_DIE, moves))), 1).start(players);
        return t;
    }

    /** 改写对局状态（经恢复入口完整校验）；之后该桌不再用于"从事件重建"的断言。 */
    private static void craft(Table t, UnaryOperator<GameState> f) {
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(f.apply(t.game())))));
    }

    private static UnaryOperator<GameState> own(int tile, String owner, int level) {
        return g -> g.withBoard(g.board().with(new OwnableState(tile, owner, level, false, 0, null)));
    }

    private static UnaryOperator<GameState> mortgaged(int tile, String owner, long principal) {
        return g -> g.withBoard(g.board().with(new OwnableState(tile, owner, 0, true, principal, null)));
    }

    /** 让玩家只剩 cash 现金（多余部分转给系统，账本照常记账）。 */
    private static UnaryOperator<GameState> cash(String player, long cash) {
        return g -> g.withLedger(g.ledger().transfer(player, Ledger.SYSTEM, g.ledger().cash(player) - cash, "TEST", null));
    }

    private static <T extends Event> List<T> events(List<Event> log, Class<T> type) {
        return log.stream().filter(type::isInstance).map(type::cast).toList();
    }

    // ------------------------------------------------------------ P4 购买与升级

    @Test
    void buyThenImmediateUpgradeThenRent() {
        Table t = table(2, 1, 1);
        t.rollOnly();                                                   // p1 → 1（低价）
        assertEquals(TurnStage.LANDING, t.game().turn().stage());
        assertEquals(LandingStep.BUY, t.game().turn().landing().step());
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        assertEquals(2500, t.cash("p1"));
        assertEquals("p1", t.game().board().ownable(1).orElseThrow().owner());
        assertEquals(LandingStep.UPGRADE, t.game().turn().landing().step(), "buy then immediately upgrade");
        t.act(w -> new GameCommand.UpgradeProperty("p1", w));
        assertEquals(2200, t.cash("p1"));
        assertEquals(1, t.game().board().ownable(1).orElseThrow().level());
        assertEquals("p2", t.current(), "at most one level per landing");
        t.rollOnly();                                                   // p2 → 1：租金 250（一级）
        assertEquals(List.of(new GameEvent.RentPaid("p2", "p1", 1, 250)), events(t.log, GameEvent.RentPaid.class));
        assertEquals(2450, t.cash("p1"));
        assertEquals(2750, t.cash("p2"));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void declineAndTimeoutLeaveTheLandUnowned() {
        Table t = table(2, 1, 3);
        t.rollOnly();
        t.act(w -> new GameCommand.DeclinePurchase("p1", w));
        assertNull(t.game().board().ownable(1).orElseThrow().owner());
        t.rollOnly();                                                   // p2 → 3：不操作，15 秒到时放弃
        long deadline = t.window().window().deadline();
        assertEquals(t.config.timing().decisionWindowMs(), deadline - t.window().window().opensAt());
        t.tick(deadline);
        assertNull(t.game().board().ownable(3).orElseThrow().owner());
        assertTrue(events(t.log, GameEvent.PurchaseDeclined.class).contains(new GameEvent.PurchaseDeclined("p2", 3, false)));
        assertEquals(3000, t.cash("p2"));
    }

    @Test
    void designatedAuctionLandOffersOnlyBuyOrDeclineUntilM5() {
        Table t = table(2, 6);
        t.rollOnly();                                                   // p1 → 6（指定拍卖地）
        assertEquals(RejectionCode.NOT_AVAILABLE, t.act(w -> new GameCommand.StartLandAuction("p1", w)).rejection());
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        assertEquals(2500, t.cash("p1"));
    }

    @Test
    void hostedPlayersBuyAndUpgradeWhenAffordableOfflinePlayersDoNot() {
        Table t = table(2, 1, 3);
        t.send(t.now + 5, new GameCommand.SetControl(1, "p1", ControlMode.HOSTED));
        t.tick(t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt());   // 自动投骰 → 1
        t.tick(t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt());   // 自动买
        t.tick(t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt());   // 自动升一级
        assertEquals("p1", t.game().board().ownable(1).orElseThrow().owner());
        assertEquals(1, t.game().board().ownable(1).orElseThrow().level());
        assertEquals(2200, t.cash("p1"));
        t.send(t.now + 5, new GameCommand.ConnectionSuspected(1, "p2", 1));
        t.send(t.now + 5, new GameCommand.ConnectionConfirmed(1, "p2", 2));
        t.tick(t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt());   // 掉线自动投骰 → 3
        t.tick(t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt());   // 掉线：不买
        assertNull(t.game().board().ownable(3).orElseThrow().owner());
        assertTrue(events(t.log, GameEvent.PurchaseDeclined.class).contains(new GameEvent.PurchaseDeclined("p2", 3, true)));
    }

    @Test
    void noPurchaseWindowWhenCashIsShortAndNoUpgradeWindowOnMortgagedOrMaxLevelLand() {
        Table t = table(2, 1, 5);
        craft(t, cash("p1", 400));
        t.rollOnly();                                                   // p1 → 1，付不起 500：不开窗口
        assertEquals("p2", t.current());
        craft(t, own(5, "p2", 3));
        t.rollOnly();                                                   // p2 → 5，自己的三级地产：不开升级窗口
        assertEquals("p1", t.current());
    }

    // ------------------------------------------------------------ 租金表

    @Test
    void rentFollowsTierAndLevelExactly() {
        long[][] expected = {{100, 250, 450, 700}, {200, 500, 900, 1400}, {300, 750, 1350, 2100}};
        int[] tiles = {3, 5, 14};                                   // L、M、H
        for (int tier = 0; tier < 3; tier++) {
            for (int level = 0; level <= 3; level++) {
                Table t = table(2, 1, 1);
                t.rollOnly();
                t.pass();
                int target = tiles[tier];
                craft(t, own(target, "p1", level).andThen(g -> g.withPlayer(g.player("p2").orElseThrow().at(target - 1)))::apply);
                long before = t.cash("p2");
                t.rollOnly();
                assertEquals(expected[tier][level], before - t.cash("p2"), Tier.values()[tier] + " level " + level);
            }
        }
    }

    @Test
    void stationRentCountsOnlyUnmortgagedStationsAndMortgagedLandCollectsNothing() {
        Table t = table(2, 1, 4, 1, 1);
        t.rollOnly();
        t.pass();
        craft(t, own(4, "p1", 0).andThen(own(12, "p1", 0)).andThen(mortgaged(19, "p1", 1000))::apply);
        t.rollOnly();                                                   // p2 → 4：持有未抵押车站 2 座 × 200
        assertEquals(List.of(new GameEvent.RentPaid("p2", "p1", 4, 400)), events(t.log, GameEvent.RentPaid.class));
        t.rollOnly();                                                   // p1 → 2
        craft(t, mortgaged(5, "p1", 1000));
        t.rollOnly();                                                   // p2 → 5：已抵押，不收租
        assertEquals(1, events(t.log, GameEvent.RentPaid.class).size());
        assertEquals("p1", t.current());
    }

    // ------------------------------------------------------------ 银行、抵押与赎回

    @Test
    void bankMortgagesAtFullPriceAndRedeemsFreeOffBankRedemptionCostsTenPercent() {
        Table t = table(2, 5, 1, 6, 1, 1, 1);
        t.rollOnly();                                                   // p1 → 5（中价）买下
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        t.pass();
        t.rollOnly();                                                   // p2 → 1
        t.pass();
        t.rollOnly();                                                   // p1 → 11 银行
        assertEquals(LandingStep.BANK, t.game().turn().landing().step());
        long windowEnd = t.window().window().deadline();
        t.act(w -> new GameCommand.BankMortgage("p1", w, 5));
        assertEquals(2000 + 1000, t.cash("p1"), "100% of the 1000 base price");
        assertEquals(windowEnd, t.window().window().deadline(), "operations do not refresh the bank window");
        assertEquals(RejectionCode.MORTGAGED, t.act(w -> new GameCommand.BankMortgage("p1", w, 5)).rejection());
        t.act(w -> new GameCommand.Redeem("p1", w, 5));
        assertEquals(2000, t.cash("p1"), "free redemption at the bank");
        t.act(w -> new GameCommand.BankMortgage("p1", w, 5));
        t.act(w -> new GameCommand.FinishBank("p1", w));
        t.rollOnly();                                                   // p2 → 2（事件，占位）
        // p1 的下一回合：仍站在银行格（O10 不要求落地当回合），投骰前可再操作
        t.act(w -> new GameCommand.Redeem("p1", w, 5));
        assertEquals(2000, t.cash("p1"), "still on the bank: free");
        t.act(w -> new GameCommand.BankMortgage("p1", w, 5));
        t.rollOnly();                                                   // p1 → 12（车站）
        t.pass();
        t.rollOnly();                                                   // p2 → 3
        t.pass();
        assertEquals(RejectionCode.NOT_AT_BANK, t.act(w -> new GameCommand.BankMortgage("p1", w, 5)).rejection());
        long before = t.cash("p1");
        t.act(w -> new GameCommand.Redeem("p1", w, 5));
        assertEquals(1100, before - t.cash("p1"), "off the bank: principal 1000 + 10%");
        assertFalse(t.game().board().ownable(5).orElseThrow().mortgaged());
    }

    // ------------------------------------------------------------ P5 债务

    @Test
    void rentDebtOpensTheManualWindowAndEmergencyMortgageSettlesIt() {
        Table t = table(2, 2, 1);
        t.rollOnly();                                                   // p1 → 2（事件，占位）
        craft(t, own(1, "p1", 0).andThen(own(3, "p2", 0)).andThen(cash("p2", 50))::apply);
        StepResult r = t.rollOnly();                                    // p2 → 1：租金 100，现金 50
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.DebtCreated));
        assertEquals(TurnStage.AWAITING_FLOW, t.game().turn().stage());
        assertEquals(FlowKind.DEBT, t.window().kind());
        long w = t.window().windowId();
        assertEquals(t.config.timing().debtSegmentMs(), t.window().window().deadline() - t.window().window().opensAt());
        assertEquals(RejectionCode.NOT_OWNER, t.send(t.window().window().opensAt() + 1,
                new GameCommand.EmergencyMortgage("p2", w, 1)).rejection());
        t.send(t.now + 10, new GameCommand.EmergencyMortgage("p2", w, 3));   // 低价应急 80% = 400
        assertNull(t.game().debt());
        assertEquals(50 + 400 - 100, t.cash("p2"));
        assertEquals(400, t.game().board().ownable(3).orElseThrow().principal());
        assertEquals("p1", t.current(), "the landing finished and the turn moved on");
    }

    @Test
    void insufficientAssetsMeanImmediateBankruptcyWithoutAWindow() {
        Table t = table(2, 2, 1);
        t.rollOnly();
        craft(t, own(1, "p1", 2).andThen(cash("p2", 50))::apply);
        t.rollOnly();                                                   // p2 → 1：租金 450，只有 50 现金、无资产
        GameEvent.GameEnded end = events(t.log, GameEvent.GameEnded.class).get(0);
        assertEquals("LAST_SURVIVOR", end.reason());
        assertEquals(List.of("p1", "p2"), end.result().standings().stream().map(Standing::playerId).toList());
        assertTrue(events(t.log, GameEvent.WindowOpened.class).stream().noneMatch(o -> o.frame().kind() == FlowKind.DEBT));
        assertEquals(List.of(new GameEvent.DebtSettled(2, "p1", 50)), events(t.log, GameEvent.DebtSettled.class));
    }

    @Test
    void automatedDebtorsGoBankruptDirectlyLiquidatingAtEmergencyRatios() {
        Table t = table(3, 2, 1);
        t.rollOnly();                                                   // p1 → 2
        craft(t, own(1, "p1", 0).andThen(own(14, "p2", 0)).andThen(mortgaged(4, "p2", 1000)).andThen(cash("p2", 50))::apply);
        t.send(t.now + 5, new GameCommand.SetControl(1, "p2", ControlMode.AWAY));
        long sys = t.game().ledger().systemNet();
        t.tick(t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt());   // p2 自动投骰 → 1
        assertEquals(LifeState.BANKRUPT, t.game().player("p2").orElseThrow().life());
        assertEquals(List.of(new GameEvent.AssetReclaimed("p2", 4)), events(t.log, GameEvent.AssetReclaimed.class));
        assertEquals(List.of(new GameEvent.AssetLiquidated("p2", 14, 900)), events(t.log, GameEvent.AssetLiquidated.class),
                "high tier liquidates at 60%");
        assertEquals(List.of(new GameEvent.DebtSettled(2, "p1", 100)), events(t.log, GameEvent.DebtSettled.class));
        assertEquals(List.of(new GameEvent.CashReclaimed("p2", 850)), events(t.log, GameEvent.CashReclaimed.class));
        assertEquals(sys - 900 + 850, t.game().ledger().systemNet());
        assertNull(t.game().board().ownable(14).orElseThrow().owner());
        assertEquals("p3", t.current());
    }

    @Test
    void debtSegmentsLastThirtyPlusThirtySecondsAndThenBankrupt() {
        Table t = table(3, 2, 1);
        t.rollOnly();
        craft(t, own(1, "p1", 0).andThen(own(3, "p2", 0)).andThen(cash("p2", 50))::apply);
        t.rollOnly();
        long start = t.window().window().opensAt();
        long w1 = t.window().windowId();
        assertEquals(RejectionCode.WRONG_STAGE, t.send(start + 1, new GameCommand.ContinueDebt("p2", w1)).rejection(),
                "the continue popup only exists in the second segment");
        t.tick(start + 30_000);
        assertEquals(2, t.game().debt().segment());
        long w2 = t.window().windowId();
        assertEquals(start + 60_000, t.window().window().deadline(), "the whole debt window never exceeds 60 s");
        t.send(t.now + 10, new GameCommand.ConnectionSuspected(1, "p2", 1));
        t.send(t.now + 10, new GameCommand.ConnectionConfirmed(1, "p2", 2));
        assertEquals(Outcome.ACCEPTED, t.send(t.now + 10, new GameCommand.ContinueDebt("p2", w2)).outcome(),
                "the manual path is locked: going offline does not change it");
        assertEquals(start + 60_000, t.window().window().deadline(), "continuing does not extend");
        t.tick(start + 60_000);
        assertEquals(LifeState.BANKRUPT, t.game().player("p2").orElseThrow().life());
        assertEquals("p3", t.current());
    }

    @Test
    void declaringBankruptcyIsAllowedAnyTime() {
        Table t = table(3, 2, 1);
        t.rollOnly();
        craft(t, own(1, "p1", 0).andThen(own(3, "p2", 0)).andThen(cash("p2", 50))::apply);
        t.rollOnly();
        t.send(t.window().window().opensAt() + 1, new GameCommand.DeclareBankruptcy("p2", t.window().windowId()));
        assertEquals(LifeState.BANKRUPT, t.game().player("p2").orElseThrow().life());
        assertEquals(List.of(new GameEvent.AssetLiquidated("p2", 3, 400)), events(t.log, GameEvent.AssetLiquidated.class));
        assertEquals(List.of(new GameEvent.DebtSettled(2, "p1", 100)), events(t.log, GameEvent.DebtSettled.class),
                "the creditor is paid in full from liquidation, never more than the debt");
    }

    // ------------------------------------------------------------ P6 认输、淘汰与排名

    @Test
    void surrenderReclaimsEverythingWithoutRewardingOthers() {
        Table t = table(3, 1);
        craft(t, own(5, "p3", 2));
        long sys = t.game().ledger().systemNet();
        t.send(t.now + 5, new GameCommand.Surrender("p3", 1));
        assertEquals(LifeState.SURRENDERED, t.game().player("p3").orElseThrow().life());
        assertNull(t.game().board().ownable(5).orElseThrow().owner());
        assertEquals(sys + 3000, t.game().ledger().systemNet());
        assertEquals(3000, t.cash("p1"));
        assertEquals(RejectionCode.NOT_ALIVE, t.send(t.now + 5, new GameCommand.Surrender("p3", 1)).rejection());
    }

    @Test
    void theCurrentPlayerSurrenderingEndsTheTurnAndTheLastSurvivorWins() {
        Table t = table(3, 1);
        t.send(t.now + 5, new GameCommand.Surrender("p1", 1));
        assertEquals("p2", t.current());
        assertTrue(t.session().inGame());
        t.send(t.now + 5, new GameCommand.Surrender("p3", 1));
        GameEvent.GameEnded end = events(t.log, GameEvent.GameEnded.class).get(0);
        assertEquals("LAST_SURVIVOR", end.reason());
        assertEquals(List.of("p2", "p3", "p1"), end.result().standings().stream().map(Standing::playerId).toList(),
                "survivor first, then later eliminations first");
        assertEquals(List.of(1, 2, 3), end.result().standings().stream().map(Standing::rank).toList());
    }

    @Test
    void aCreditorsSurrenderWaitsForTheDebtAndTheDebtorsSurrenderIsBankruptcy() {
        Table t = table(3, 2, 1);
        t.rollOnly();
        craft(t, own(1, "p1", 0).andThen(own(3, "p2", 0)).andThen(cash("p2", 50))::apply);
        t.rollOnly();                                                   // p2 欠 p1 100
        long w = t.window().windowId();
        t.send(t.window().window().opensAt() + 1, new GameCommand.Surrender("p1", 1));
        assertEquals(List.of("p1"), t.game().pendingSurrenders(), "the creditor's surrender is deferred");
        assertTrue(t.game().player("p1").orElseThrow().alive());
        t.send(t.now + 10, new GameCommand.EmergencyMortgage("p2", w, 3));
        // 债务付清 → 先付给债权人，再处理延后认输：p1 的现金由系统回收
        assertEquals(LifeState.SURRENDERED, t.game().player("p1").orElseThrow().life());
        assertTrue(events(t.log, GameEvent.DebtPaid.class).size() == 1);
        assertEquals(0, t.cash("p1"));
        assertTrue(t.session().inGame());
        assertEquals("p3", t.current());
    }

    @Test
    void aSuspectedDebtorStillGetsTheManualWindow() {
        Table t = table(3, 2, 1);
        t.rollOnly();
        craft(t, own(1, "p1", 0).andThen(own(3, "p2", 0)).andThen(cash("p2", 50))::apply);
        t.send(t.now + 5, new GameCommand.ConnectionSuspected(1, "p2", 1));
        t.rollOnly();
        assertEquals(FlowKind.DEBT, t.window().kind(), "suspect is not offline: manual emergency mortgage (decision 2)");
    }

    @Test
    void aBatchThatLeavesNobodyAliveIsRankedByPreBatchNetWorth() {
        Table t = table(2, 2, 1);
        t.rollOnly();
        craft(t, own(1, "p1", 0).andThen(own(3, "p2", 0)).andThen(cash("p2", 50))::apply);
        t.rollOnly();                                                   // p2 欠 p1 100
        t.send(t.window().window().opensAt() + 1, new GameCommand.Surrender("p1", 1));   // 债权人：延后
        long p1Worth = EconomyModule.netWorth(t.config, t.game(), "p1");
        t.send(t.now + 10, new GameCommand.DeclareBankruptcy("p2", t.window().windowId()));
        GameEvent.GameEnded end = events(t.log, GameEvent.GameEnded.class).get(0);
        assertEquals("ALL_ELIMINATED", end.reason());
        assertEquals(List.of(new Standing("p1", 1, p1Worth + 100, 0), new Standing("p2", 2, 0, 0)), end.result().standings(),
                "the creditor is paid in full (liquidation 400 + cash 50) before its deferred surrender; pre-batch net worth ranks it first");
    }

    @Test
    void theDebtorSurrenderingInsideTheDebtIsBankruptcy() {
        Table t = table(3, 2, 1);
        t.rollOnly();
        craft(t, own(1, "p1", 0).andThen(own(3, "p2", 0)).andThen(cash("p2", 50))::apply);
        t.rollOnly();
        t.send(t.window().window().opensAt() + 1, new GameCommand.Surrender("p2", 1));
        assertEquals(LifeState.BANKRUPT, t.game().player("p2").orElseThrow().life());
    }

    @Test
    void timeLimitRankingUsesNetWorthIncludingMortgagedAssets() {
        Table t = table(3, 1);
        craft(t, own(5, "p1", 2).andThen(mortgaged(14, "p2", 900))::apply);
        GameState g = t.game();
        // p1：3000 + 中价二级标准价值 1000 + 2×600/2 = 1600 → 4600
        // p2：3000 + 高价 0 级 1500 − 本金 900 = 3600
        assertEquals(4600, EconomyModule.netWorth(t.config, g, "p1"));
        assertEquals(3600, EconomyModule.netWorth(t.config, g, "p2"));
        List<Standing> s = TurnModule.standings(g, t.config);
        assertEquals(List.of(new Standing("p1", 1, 4600, 3000), new Standing("p2", 2, 3600, 3000),
                new Standing("p3", 3, 3000, 3000)), s);
    }

    @Test
    void eliminatedSpectatorsMayLeaveTheRoomButAliveParticipantsMayNot() {
        Table t = table(3, 1);
        assertEquals(RejectionCode.GAME_IN_PROGRESS, t.send(t.now + 5, new RoomCommand.Leave("p3")).rejection());
        t.send(t.now + 5, new GameCommand.Surrender("p3", 1));
        assertEquals(Outcome.ACCEPTED, t.send(t.now + 5, new RoomCommand.Leave("p3")).outcome());
        assertFalse(t.session().lobby().isMember("p3"));
        assertTrue(t.game().player("p3").isPresent(), "the participant roster is kept");
        t.send(t.now + 5, new GameCommand.Surrender("p2", 1));
        GameEvent.GameEnded end = events(t.log, GameEvent.GameEnded.class).get(0);
        assertEquals(3, end.result().standings().size(), "the departed spectator is still settled");
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    // ------------------------------------------------------------ 全局到时

    /** 用真实随机推进到"全局到时前一刻刚落在可买地上"的局面（固定种子中找第一个满足条件的）。 */
    static Table nearEndBuyWindow() {
        for (long seed = 1; seed < 200; seed++) {
            Table t = Table.production(seed).start(2, RuleConfigs.BOARD_30, com.millionnaire.engine.config.EndMode.TIME_LIMIT, 15);
            long endsAt = t.game().clock().endsAt();
            while (t.session().inGame() && !(t.game().turn().stage() == TurnStage.PRE_ROLL
                    && t.window().window().opensAt() > endsAt - 12_000)) {
                t.roll();
            }
            if (!t.session().inGame() || t.game().phase() != GamePhase.RUNNING) {
                continue;
            }
            t.rollOnly();
            if (t.session().inGame() && t.game().turn().stage() == TurnStage.LANDING
                    && t.game().turn().landing().step() == LandingStep.BUY && t.window().window().deadline() > endsAt) {
                return t;
            }
        }
        throw new AssertionError("no seed reached a buy window across the global end");
    }

    @Test
    void globalEndDuringABuyWindowStillAllowsTheBuyButNoBankOperations() {
        Table t = nearEndBuyWindow();
        long endsAt = t.game().clock().endsAt();
        String p = t.current();
        int tile = t.game().turn().landing().tile();
        t.tick(endsAt);
        assertEquals(GamePhase.DRAINING, t.game().phase());
        assertEquals(LandingStep.BUY, t.game().turn().landing().step(), "the landing decision survives the global end");
        t.act(w -> new GameCommand.BuyProperty(p, w));
        assertEquals(p, t.game().board().ownable(tile).orElseThrow().owner());
        while (t.session().inGame()) {
            assertEquals(TurnStage.LANDING, t.game().turn().stage(), "no new turn after the global end");
            t.pass();
        }
        GameEvent.GameEnded end = events(t.log, GameEvent.GameEnded.class).get(0);
        assertEquals("TIME_UP", end.reason());
        assertEquals(2, end.result().standings().size());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    // ------------------------------------------------------------ 过期 / 重复 / 非当前玩家

    @Test
    void staleDuplicateAndForeignCommandsHaveNoSecondEffect() {
        Table t = table(2, 1);
        t.rollOnly();
        long w = t.window().windowId();
        assertEquals(RejectionCode.NOT_YOUR_TURN, t.act(x -> new GameCommand.BuyProperty("p2", x)).rejection());
        t.act(x -> new GameCommand.BuyProperty("p1", x));
        int drawsBefore = t.log.stream().filter(e -> e instanceof com.millionnaire.engine.core.event.KernelEvent.RandomDrawn)
                .toList().size();
        StepResult again = t.send(t.now + 10, new GameCommand.BuyProperty("p1", w));
        assertEquals(Outcome.REJECTED, again.outcome());
        assertEquals(2500, t.cash("p1"), "no second purchase");
        assertEquals(RejectionCode.WRONG_STAGE, t.act(x -> new GameCommand.RollDice("p1", x)).rejection());
        assertEquals(drawsBefore, t.log.stream()
                .filter(e -> e instanceof com.millionnaire.engine.core.event.KernelEvent.RandomDrawn).toList().size());
    }
}
