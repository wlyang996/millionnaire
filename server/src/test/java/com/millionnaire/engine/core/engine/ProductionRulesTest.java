package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.testkit.TestBoards;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 正式配置特有的规则（legacy 测试配置关闭）：
 * 现金不足也开购买窗口（只能放弃）；买下后不能在同一次落点立即升级；存活玩家全部暂离 / 托管时在回合交界结束对局（ALL_AWAY）。
 * 30 格测试棋盘：1 L（500）。p1 抽 90、p2 抽 10，p1 先行动。
 */
class ProductionRulesTest {
    private static final RuleConfig PRODUCTION_RULES = RuleConfigs.v1(TestBoards.LEGACY_30, TestBoards.LEGACY_50, true);

    private static Table table(int... moves) {
        return new Table(PRODUCTION_RULES, ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2),
                dice(DrawPoint.MOVE_DIE, moves))), 1).start(2);
    }

    private static void craft(Table t, UnaryOperator<GameState> f) {
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(f.apply(t.game())))));
    }

    private static UnaryOperator<GameState> cash(String player, long cash) {
        return g -> g.withLedger(g.ledger().transfer(player, Ledger.SYSTEM, g.ledger().cash(player) - cash, "TEST", null));
    }

    @Test
    void productionConfigOffersPurchaseEvenWhenCashIsShort() {
        assertTrue(RuleConfigs.defaultV1().economy().offerUnaffordablePurchase());
        assertTrue(RuleConfigs.defaultV1().timing().endWhenAllAway());
        assertFalse(TestBoards.legacyV1().economy().offerUnaffordablePurchase());
        assertFalse(TestBoards.legacyV1().timing().endWhenAllAway());
    }

    @Test
    void unaffordableLandOpensBuyWindowThatOnlyAllowsDeclining() {
        Table t = table(1, 1);
        craft(t, cash("p1", 400));
        t.rollOnly();                                                               // p1 → 1（500），只有 400
        assertEquals("p1", t.current());
        assertEquals(LandingStep.BUY, t.game().turn().landing().step());
        assertTrue(t.game().turn().landing().decisionOpen());
        StepResult buy = t.act(w -> new GameCommand.BuyProperty("p1", w));
        assertEquals(RejectionCode.INSUFFICIENT_CASH, buy.rejection());
        assertEquals(400, t.cash("p1"));
        t.act(w -> new GameCommand.DeclinePurchase("p1", w));
        assertEquals("p2", t.current());
        assertNull(t.game().board().ownable(1).orElseThrow().owner());
    }

    @Test
    void unaffordableBuyWindowTimesOutAsDecline() {
        Table t = table(1, 1);
        craft(t, cash("p1", 400));
        t.rollOnly();
        t.tick(t.window().window().deadline());
        assertEquals("p2", t.current());
        assertEquals(400, t.cash("p1"));
    }

    @Test
    void boughtLandCannotBeUpgradedInTheSameLandingOnlyOnALaterOne() {
        assertFalse(RuleConfigs.defaultV1().economy().upgradeAfterPurchase());
        assertTrue(TestBoards.legacyV1().economy().upgradeAfterPurchase());
        Table t = table(1, 5, 1);
        t.rollOnly();                                                               // p1 → 1（L，无主）
        assertEquals(LandingStep.BUY, t.game().turn().landing().step());
        t.act(w -> new GameCommand.BuyProperty("p1", w));
        assertEquals("p1", t.game().board().ownable(1).orElseThrow().owner());
        assertNull(t.game().turn().landing(), "no upgrade window right after buying");
        assertEquals("p2", t.current());
        t.rollOnly();                                                               // p2 → 5（无主）：放弃
        while (t.session().inGame() && "p2".equals(t.current())) {
            t.pass();
        }
        craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().at(0)));
        t.rollOnly();                                                               // p1 再到 1：自己的地，可以升级
        assertEquals(LandingStep.UPGRADE, t.game().turn().landing().step());
        t.act(w -> new GameCommand.UpgradeProperty("p1", w));
        assertEquals(1, t.game().board().ownable(1).orElseThrow().level());
    }

    @Test
    void gameEndsWhenEveryLivePlayerIsAwayOrHosted() {
        Table t = table(1, 1, 1, 1, 1, 1, 1, 1);
        long gameNo = t.game().gameNo();
        t.send(new GameCommand.SetControl(gameNo, "p1", ControlMode.AWAY));
        drive(t, 6);
        assertTrue(t.session().inGame(), "one manual player keeps the game going");
        t.send(new GameCommand.SetControl(gameNo, "p2", ControlMode.HOSTED));
        drive(t, 40);
        assertFalse(t.session().inGame());
        GameEvent.GameEnded end = t.log.stream().filter(GameEvent.GameEnded.class::isInstance)
                .map(GameEvent.GameEnded.class::cast).findFirst().orElseThrow();
        assertEquals("ALL_AWAY", end.reason());
    }

    @Test
    void merelyDisconnectedPlayersDoNotEndTheGame() {
        Table t = table(1, 1, 1, 1, 1, 1, 1, 1);
        long gameNo = t.game().gameNo();
        t.send(new GameCommand.SetControl(gameNo, "p1", ControlMode.AWAY));
        t.send(new GameCommand.ConnectionSuspected(gameNo, "p2", 1));
        t.send(new GameCommand.ConnectionConfirmed(gameNo, "p2", 2));
        assertTrue(t.game().player("p2").orElseThrow().automated(), "offline player is automated");
        drive(t, 12);
        assertTrue(t.session().inGame(), "all-offline grace (open-decisions #6) is not replaced by ALL_AWAY");
    }

    /** 推进最多 n 步：人工玩家按"放弃 / 投骰"操作，自动玩家等待自动任务。 */
    private static void drive(Table t, int n) {
        for (int i = 0; i < n && t.session().inGame(); i++) {
            var p = t.game().player(t.current()).orElseThrow();
            if (p.automated() || t.game().turn().stage() == com.millionnaire.engine.core.state.TurnStage.LANDING) {
                t.pass();
            } else {
                t.rollOnly();
            }
        }
    }
}
