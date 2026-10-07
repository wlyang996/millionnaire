package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.testkit.TestBoards;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 道具（requirements 第 14 节、已采纳默认值 #9 / #10 / #17、O4、O9）：正式配置（道具开启）。
 * 30 格测试棋盘：0 S、1 L、3 L、4 T（车站）、5 M、8 J（监狱）、9 L。p1 抽 90 先行动，p2 抽 10。
 */
class CardTest {
    private static final RuleConfig RULES = RuleConfigs.v1(TestBoards.LEGACY_30, TestBoards.LEGACY_50, true);

    private static Table table(int... moves) {
        return new Table(RULES, ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2),
                dice(DrawPoint.MOVE_DIE, moves))), 1).start(2);
    }

    private static void craft(Table t, UnaryOperator<GameState> f) {
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(f.apply(t.game())))));
    }

    private static UnaryOperator<GameState> hand(String player, CardType... cards) {
        return g -> g.withPlayer(g.player(player).orElseThrow().withHand(List.of(cards)));
    }

    private static UnaryOperator<GameState> own(int tile, String owner, int level) {
        return g -> g.withBoard(g.board().with(g.board().ownable(tile).orElseThrow().owned(owner).level(level)));
    }

    private static UnaryOperator<GameState> at(String player, int tile) {
        return g -> g.withPlayer(g.player(player).orElseThrow().at(tile));
    }

    private static StepResult use(Table t, CardType card, String target, int steps) {
        return t.act(w -> new GameCommand.UseCard(t.current(), w, card, target, steps));
    }

    private static StepResult use(Table t, CardType card) {
        return use(t, card, null, 0);
    }

    private static <T extends Event> List<T> events(List<Event> log, Class<T> type) {
        return log.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static OwnableState tile(Table t, int i) {
        return t.game().board().ownable(i).orElseThrow();
    }

    /** 这些场景直接改写了状态（不经事件），因此只核对校验与快照往返；事件重放由 {@link CardLongGameTest} 覆盖。 */
    private static void consistent(Table t) {
        t.engine.validate(t.state);
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    // ------------------------------------------------------------ 主动卡：机会、手牌、可见性

    @Test
    void queryShowsTheTargetsHandOnlyToTheUserAndUsesTheTurnsOneChance() {
        Table t = table(1);
        craft(t, hand("p1", CardType.QUERY, CardType.ROADBLOCK));
        craft(t, hand("p2", CardType.BUILD, CardType.RENT_WAIVER));
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        StepResult r = use(t, CardType.QUERY, "p2", 0);
        assertNull(r.rejection());
        GameEvent.QueryRevealed q = events(r.events(), GameEvent.QueryRevealed.class).getFirst();
        assertEquals(List.of(CardType.BUILD, CardType.RENT_WAIVER), q.cards());
        assertTrue(EventProjector.visibleTo(q, "p1"));
        assertFalse(EventProjector.visibleTo(q, "p2"), "only the user sees the snapshot");
        assertEquals(List.of(CardType.ROADBLOCK), t.game().player("p1").orElseThrow().hand());
        assertTrue(t.game().cards().used("p1"));
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage(), "the roll window stays open after a pre-roll card");
        assertEquals(RejectionCode.CARD_USED, use(t, CardType.ROADBLOCK).rejection(), "two phases share one chance");
        assertEquals(List.of("p1"), SessionDomain.INSTANCE.project(t.state, "p2").game().cards().chanceUsed());
        consistent(t);
    }

    @Test
    void conditionsThatFailDoNotConsumeTheCardOrTheChance() {
        Table t = table(1);
        craft(t, hand("p1", CardType.QUERY, CardType.BUILD, CardType.RENT_WAIVER));
        assertEquals(RejectionCode.NO_CARD, use(t, CardType.DOWNGRADE).rejection());
        assertEquals(RejectionCode.INVALID_ARGUMENT, use(t, CardType.QUERY, "p1", 0).rejection(), "cannot query yourself");
        assertEquals(RejectionCode.NO_TARGET, use(t, CardType.BUILD).rejection(), "the start tile is not my property");
        assertEquals(RejectionCode.NOT_ALLOWED, use(t, CardType.RENT_WAIVER).rejection(), "response cards are not active cards");
        assertEquals(RejectionCode.NOT_AVAILABLE, use(t, CardType.AUCTION).rejection());
        assertEquals(RejectionCode.NOT_YOUR_TURN, t.send(t.now + 10, new GameCommand.UseCard("p2", t.windowId(), CardType.QUERY, "p1", 0)).rejection());
        assertEquals(3, t.game().player("p1").orElseThrow().hand().size());
        assertFalse(t.game().cards().used("p1"));
        consistent(t);
    }

    @Test
    void cardsAreUnavailableWhenTheConfigTurnsThemOff() {
        Table t = new Table(ScriptedRandom.withEventCards(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 1))), 1).start(2);
        assertEquals(RejectionCode.NOT_AVAILABLE, t.act(w -> new GameCommand.UseCard("p1", w, CardType.ROADBLOCK, null, 0)).rejection());
    }

    // ------------------------------------------------------------ 移动类

    @Test
    void roadblockIsPlacedOnTheCurrentTileBeforeRolling() {
        Table t = table(1);
        craft(t, at("p1", 5));
        assertNull(use(t, CardType.ROADBLOCK).rejection());
        assertEquals(5, t.game().board().roadblock(5).orElseThrow().tile());
        assertEquals(1, t.game().player("p1").orElseThrow().hand().size());
        consistent(t);
    }

    @Test
    void fixedMoveReplacesTheRollAndChecksTheDistance() {
        Table t = table();
        craft(t, hand("p1", CardType.FIXED_MOVE));
        assertEquals(RejectionCode.INVALID_ARGUMENT, use(t, CardType.FIXED_MOVE, null, 7).rejection());
        assertEquals(RejectionCode.INVALID_ARGUMENT, use(t, CardType.FIXED_MOVE, null, 0).rejection());
        assertNull(use(t, CardType.FIXED_MOVE, null, 3).rejection());
        assertEquals(3, t.position("p1"));
        assertTrue(events(t.log, GameEvent.DiceRolled.class).isEmpty(), "no die is rolled");
        assertTrue(t.game().player("p1").orElseThrow().hand().isEmpty());
        consistent(t);
    }

    @Test
    void jailReleaseInTheJailDecisionWindow() {
        // p1 投 6 → 6（L*），p2 投 2 → 2（E）... 用脚本让 p1 第 2 回合在狱中开始
        Table t = table(1, 1);
        craft(t, hand("p1", CardType.JAIL_RELEASE, CardType.FIXED_MOVE));
        craft(t, g -> g.withPlayer(g.player("p1").orElseThrow().at(7)));
        t.rollOnly();                                                          // p1 7 → 8 监狱：入狱，回合结束
        assertTrue(t.game().player("p1").orElseThrow().inJail());
        assertEquals("p2", t.current());
        t.rollOnly();                                                          // p2 → 1
        while (t.game().turn().stage() == TurnStage.LANDING) { t.pass(); }
        assertEquals("p1", t.current());
        assertEquals(TurnStage.JAIL_DECISION, t.game().turn().stage());
        assertEquals(RejectionCode.WRONG_STAGE, use(t, CardType.FIXED_MOVE, null, 2).rejection(), "fixed move cannot escape jail");
        assertNull(use(t, CardType.JAIL_RELEASE).rejection());
        assertFalse(t.game().player("p1").orElseThrow().inJail());
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        assertEquals(GameEvent.ReleaseReason.CARD, events(t.log, GameEvent.JailReleased.class).getLast().reason());
        assertEquals(RejectionCode.CARD_USED, use(t, CardType.FIXED_MOVE, null, 2).rejection(), "the jail card takes this turn's chance");
        consistent(t);
    }

    // ------------------------------------------------------------ 地产类

    @Test
    void buildUpgradesMyPropertyForFree() {
        Table t = table(1);
        craft(t, at("p1", 1));
        craft(t, own(1, "p1", 1));
        craft(t, hand("p1", CardType.BUILD));
        long cash = t.cash("p1");
        assertNull(use(t, CardType.BUILD).rejection());
        assertEquals(2, tile(t, 1).level());
        assertEquals(cash, t.cash("p1"), "free");
        consistent(t);
    }

    @Test
    void thereIsNoCardPhaseAfterLanding() {
        // 用户 2026-10-07 裁决：落点后没有用卡阶段，落点结算完直接结束回合；主动卡只在投骰前用
        Table t = table(1, 1);
        craft(t, own(3, "p2", 2));
        craft(t, hand("p1", CardType.BUILD, CardType.QUERY, CardType.ROADBLOCK));
        craft(t, at("p1", 2));
        long p1 = t.cash("p1");
        t.rollOnly();                                                          // p1 → 3：缴租 450，回合随即结束
        assertEquals(p1 - 450, t.cash("p1"));
        assertEquals("p2", t.current(), "the turn ends right after the landing");
        assertEquals(RejectionCode.NOT_YOUR_TURN, t.send(t.now + 10,
                new GameCommand.UseCard("p1", t.window().windowId(), CardType.QUERY, "p2", 0)).rejection());
        t.rollOnly();                                                          // p2 → 1（无主）：买下，回合随即结束
        t.act(w -> new GameCommand.BuyProperty("p2", w));
        assertEquals("p1", t.current());
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        assertEquals(3, t.game().player("p1").orElseThrow().hand().size());
        consistent(t);
    }

    @Test
    void downgradeWithoutProtectionAppliesImmediately() {
        Table t = table(1);
        craft(t, at("p1", 1));
        craft(t, own(1, "p2", 2));
        craft(t, hand("p1", CardType.DOWNGRADE));
        assertNull(use(t, CardType.DOWNGRADE).rejection());
        assertEquals(1, tile(t, 1).level());
        assertEquals("p2", tile(t, 1).owner());
        consistent(t);
    }

    @Test
    void attacksNeedAnUnmortgagedOtherPlayersPropertyWithLevels() {
        Table t = table(1);
        craft(t, at("p1", 1));
        craft(t, own(1, "p2", 0));
        craft(t, hand("p1", CardType.DOWNGRADE, CardType.DEMOLISH, CardType.CLEAR_LAND));
        assertEquals(RejectionCode.NO_TARGET, use(t, CardType.DOWNGRADE).rejection(), "level 0 cannot be downgraded");
        assertEquals(RejectionCode.NO_TARGET, use(t, CardType.DEMOLISH).rejection());
        craft(t, g -> g.withBoard(g.board().with(g.board().ownable(1).orElseThrow().mortgage(400))));
        assertEquals(RejectionCode.NO_TARGET, use(t, CardType.CLEAR_LAND).rejection(), "mortgaged assets are immune");
        craft(t, own(4, "p2", 0));
        craft(t, at("p1", 4));
        assertEquals(RejectionCode.NO_TARGET, use(t, CardType.CLEAR_LAND).rejection(), "stations cannot be cleared");
        assertEquals(3, t.game().player("p1").orElseThrow().hand().size());
    }

    @Test
    void demolishAndClearLand() {
        Table t = table(1);
        craft(t, at("p1", 1));
        craft(t, own(1, "p2", 3));
        craft(t, hand("p1", CardType.DEMOLISH));
        assertNull(use(t, CardType.DEMOLISH).rejection());
        assertEquals(0, tile(t, 1).level());
        assertEquals("p2", tile(t, 1).owner(), "ownership is kept");
        Table c = table(1);
        craft(c, at("p1", 1));
        craft(c, own(1, "p2", 2));
        craft(c, hand("p1", CardType.CLEAR_LAND));
        assertNull(use(c, CardType.CLEAR_LAND).rejection());
        assertNull(tile(c, 1).owner());
        assertEquals(0, tile(c, 1).level());
        consistent(t);
        consistent(c);
    }

    @Test
    void forcedPurchasePaysOneAndAHalfStandardValueToTheOwner() {
        Table t = table(1);
        craft(t, at("p1", 4));
        craft(t, own(4, "p2", 0));                                            // 车站，标准价值 1000
        craft(t, hand("p1", CardType.FORCED_PURCHASE));
        long p1 = t.cash("p1");
        long p2 = t.cash("p2");
        assertNull(use(t, CardType.FORCED_PURCHASE).rejection());
        assertEquals("p1", tile(t, 4).owner());
        assertEquals(p1 - 1500, t.cash("p1"));
        assertEquals(p2 + 1500, t.cash("p2"));
        Table poor = table(1);
        craft(poor, at("p1", 5));
        craft(poor, own(5, "p2", 2));                                         // 中价 2 级：1000 + 600 = 1600 → 2400
        craft(poor, hand("p1", CardType.FORCED_PURCHASE));
        craft(poor, g -> g.withLedger(g.ledger().transfer("p1", com.millionnaire.engine.ledger.Ledger.SYSTEM, 1000, "TEST", null)));
        assertEquals(RejectionCode.INSUFFICIENT_CASH, use(poor, CardType.FORCED_PURCHASE).rejection());
        assertEquals(1, poor.game().player("p1").orElseThrow().hand().size(), "a failed condition keeps the card");
        consistent(t);
    }

    // ------------------------------------------------------------ 响应

    @Test
    void manualOwnerMayBlockWithHouseProtectionAndBothCardsAreSpent() {
        Table t = table(1);
        craft(t, at("p1", 1));
        craft(t, own(1, "p2", 2));
        craft(t, hand("p1", CardType.DOWNGRADE));
        craft(t, hand("p2", CardType.HOUSE_PROTECTION));
        long rollWindow = t.windowId();
        assertNull(use(t, CardType.DOWNGRADE).rejection());
        var top = t.window();
        assertEquals(FlowKind.RESPONSE, top.kind());
        assertEquals("p2", top.owner());
        assertEquals(t.config.timing().responseWindowMs(), top.window().deadline() - top.window().opensAt());
        assertTrue(t.game().flow().frame(rollWindow).orElseThrow().window().paused(), "the roll window pauses");
        var pending = SessionDomain.INSTANCE.project(t.state, "p1").game().cards().response();
        assertEquals(CardType.HOUSE_PROTECTION, pending.response());
        assertEquals(top.windowId(), pending.windowId());
        consistent(t);
        assertEquals(RejectionCode.NOT_YOUR_WINDOW, t.send(t.now + 10, new GameCommand.RespondCard("p1", top.windowId(), true)).rejection());
        assertNull(t.send(t.now + 10, new GameCommand.RespondCard("p2", top.windowId(), true)).rejection());
        assertEquals(2, tile(t, 1).level(), "blocked");
        assertTrue(t.game().player("p1").orElseThrow().hand().isEmpty());
        assertTrue(t.game().player("p2").orElseThrow().hand().isEmpty());
        assertEquals(1, events(t.log, GameEvent.AttackBlocked.class).size());
        assertEquals(rollWindow, t.window().windowId(), "the roll window resumes");
        assertFalse(t.window().window().paused());
        assertNull(t.game().cards().effect());
        consistent(t);
    }

    @Test
    void ownerDecliningOrTimingOutLetsTheAttackThrough() {
        Table t = table(1);
        craft(t, at("p1", 1));
        craft(t, own(1, "p2", 2));
        craft(t, hand("p1", CardType.DOWNGRADE));
        craft(t, hand("p2", CardType.HOUSE_PROTECTION));
        use(t, CardType.DOWNGRADE);
        t.send(t.now + 10, new GameCommand.RespondCard("p2", t.window().windowId(), false));
        assertEquals(1, tile(t, 1).level());
        assertEquals(List.of(CardType.HOUSE_PROTECTION), t.game().player("p2").orElseThrow().hand(), "declining keeps the card");

        Table x = table(1);
        craft(x, at("p1", 4));
        craft(x, own(4, "p2", 0));
        craft(x, hand("p1", CardType.FORCED_PURCHASE));
        craft(x, hand("p2", CardType.REFUSE_PURCHASE));
        use(x, CardType.FORCED_PURCHASE);
        assertEquals(FlowKind.RESPONSE, x.window().kind());
        x.tick(x.window().window().deadline());                             // 超时不使用
        assertEquals("p1", tile(x, 4).owner());
        assertTrue(events(x.log, GameEvent.ResponseDeclined.class).getLast().auto());
        consistent(t);
        consistent(x);
    }

    @Test
    void automatedOwnersUseTheirResponseCardImmediately() {
        Table t = table(1);
        craft(t, at("p1", 4));
        craft(t, own(4, "p2", 0));
        craft(t, hand("p1", CardType.FORCED_PURCHASE));
        craft(t, hand("p2", CardType.REFUSE_PURCHASE));
        t.send(t.now + 1, new GameCommand.SetControl(t.game().gameNo(), "p2", ControlMode.HOSTED));
        assertNull(use(t, CardType.FORCED_PURCHASE).rejection());
        assertEquals("p2", tile(t, 4).owner(), "blocked without a window");
        assertEquals(1, t.game().flow().frames().size());
        assertTrue(events(t.log, GameEvent.AttackBlocked.class).getLast().auto());
        consistent(t);
    }

    @Test
    void ownerSurrenderDuringTheResponseIsDeferredUntilItEnds() {
        Table t = table(1);
        craft(t, at("p1", 1));
        craft(t, own(1, "p2", 2));
        craft(t, hand("p1", CardType.DOWNGRADE));
        craft(t, hand("p2", CardType.HOUSE_PROTECTION));
        use(t, CardType.DOWNGRADE);
        assertNull(t.send(t.now + 10, new GameCommand.Surrender("p2", t.game().gameNo())).rejection());
        assertTrue(t.game().pendingSurrenders().contains("p2"));
        t.tick(t.window().window().deadline());
        assertFalse(t.session().inGame(), "the deferred surrender ends a two-player game");
    }

    // ------------------------------------------------------------ 免租

    @Test
    void rentWaiverAsksTheManualPayerBeforeTheRentIsCharged() {
        Table t = table(1);
        craft(t, own(1, "p2", 1));
        craft(t, hand("p1", CardType.RENT_WAIVER));
        long p1 = t.cash("p1");
        t.rollOnly();                                                          // p1 → 1（p2 的 L 1 级，租 250）
        assertEquals(LandingStep.RESPONSE, t.game().turn().landing().step());
        assertEquals(t.config.timing().responseWindowMs(), t.window().window().deadline() - t.window().window().opensAt());
        assertTrue(events(t.log, GameEvent.RentCharged.class).isEmpty(), "the response comes before the charge");
        consistent(t);
        assertNull(t.act(w -> new GameCommand.RespondCard("p1", w, true)).rejection());
        assertEquals(p1, t.cash("p1"));
        assertTrue(events(t.log, GameEvent.RentWaived.class).getLast().owner().equals("p2"));
        assertTrue(t.game().player("p1").orElseThrow().hand().isEmpty());
        assertFalse(t.game().cards().used("p1"), "response cards do not take the active chance");
        consistent(t);
    }

    @Test
    void decliningOrTimingOutTheWaiverPaysTheRent() {
        Table t = table(1);
        craft(t, own(1, "p2", 1));
        craft(t, hand("p1", CardType.RENT_WAIVER));
        long p1 = t.cash("p1");
        t.rollOnly();
        t.tick(t.window().window().deadline());
        assertEquals(p1 - 250, t.cash("p1"));
        assertTrue(events(t.log, GameEvent.RentWaiverDeclined.class).getLast().auto());
        assertEquals(List.of(CardType.RENT_WAIVER), t.game().player("p1").orElseThrow().hand());
        consistent(t);
    }

    @Test
    void hostedPayersWaiveRentImmediately() {
        Table t = table(1);
        craft(t, own(1, "p2", 1));
        craft(t, hand("p1", CardType.RENT_WAIVER));
        long p1 = t.cash("p1");
        t.send(t.now + 1, new GameCommand.SetControl(t.game().gameNo(), "p1", ControlMode.HOSTED));
        long due = t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt();
        t.tick(due);                                                           // 托管自动投骰 → 落到 1：立即免租，不开窗口
        assertEquals(p1, t.cash("p1"));
        assertTrue(events(t.log, GameEvent.RentWaived.class).getLast().auto());
        assertEquals("p2", t.current());
        consistent(t);
    }

    @Test
    void hostedPlayersNeverUseActiveCards() {
        Table t = table(1);
        craft(t, hand("p1", CardType.ROADBLOCK, CardType.QUERY));
        t.send(t.now + 1, new GameCommand.SetControl(t.game().gameNo(), "p1", ControlMode.HOSTED));
        long due = t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt();
        t.tick(due);                                                           // 托管：自动投骰，足额就买
        while ("p1".equals(t.current()) && t.game().turn().stage() == TurnStage.LANDING) {
            assertNotNull(t.game().turn().landing());
            t.pass();
        }
        assertEquals("p2", t.current());
        assertEquals(2, t.game().player("p1").orElseThrow().hand().size(), "hosted players never use active cards");
    }

    // ------------------------------------------------------------ 不改写状态的完整脚本：响应与重放

    @Test
    void scriptedAttacksAndResponsesReplayFromTheLog() {
        // 发牌（权重区间）：p1 = 强制购房 950、清地 985；p2 = 拒绝购买 800、房屋保护 850
        Table t = new Table(RULES, ScriptedRandom.withEventCards(script(order(90, 10),
                Table.steps(DrawPoint.INITIAL_CARD, 1000, 950, 985, 800, 850),
                dice(DrawPoint.MOVE_DIE, 1, 3, 2, 2, 2, 4, 4))), 1).start(2);
        assertEquals(List.of(CardType.FORCED_PURCHASE, CardType.CLEAR_LAND), t.game().player("p1").orElseThrow().hand());
        t.rollOnly();                                                          // p1 → 1：放弃
        t.act(w -> new GameCommand.DeclinePurchase("p1", w));
        assertEquals("p2", t.current());
        t.rollOnly();                                                          // p2 → 3：买下
        t.act(w -> new GameCommand.BuyProperty("p2", w));
        assertEquals("p1", t.current());
        long p1 = t.cash("p1");
        t.rollOnly();                                                          // p1 → 3：缴租 100，回合结束（落点后没有用卡阶段）
        assertEquals(p1 - 100, t.cash("p1"));
        assertEquals("p2", t.current());
        t.rollOnly();                                                          // p2 → 5：买下
        t.act(w -> new GameCommand.BuyProperty("p2", w));
        assertEquals("p1", t.current());
        assertNull(use(t, CardType.FORCED_PURCHASE).rejection());              // 投骰前：p1 站在 p2 的 3 上；p2 持拒绝购买 → 响应窗
        assertEquals(FlowKind.RESPONSE, t.window().kind());
        t.send(t.now + 10, new GameCommand.RespondCard("p2", t.window().windowId(), true));
        assertEquals("p2", tile(t, 3).owner());
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage(), "the pre-roll window resumes after the response");
        assertEquals(RejectionCode.CARD_USED, use(t, CardType.CLEAR_LAND).rejection(), "one active card per turn");
        t.rollOnly();                                                          // p1 → 5：缴租 200
        assertEquals("p2", t.current());
        t.rollOnly();                                                          // p2 → 9：放弃
        t.act(w -> new GameCommand.DeclinePurchase("p2", w));
        assertEquals("p1", t.current());
        assertNull(use(t, CardType.CLEAR_LAND).rejection());                   // 投骰前：p1 站在 p2 的 5 上
        t.send(t.now + 10, new GameCommand.RespondCard("p2", t.window().windowId(), false));
        assertNull(tile(t, 5).owner(), "cleared after the owner declined to protect");
        assertEquals(List.of(CardType.HOUSE_PROTECTION), t.game().player("p2").orElseThrow().hand());
        assertEquals(TurnStage.PRE_ROLL, t.game().turn().stage());
        t.rollOnly();                                                          // p1 照常投骰
        assertEquals(9, t.position("p1"));
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
        assertEquals(List.of(GameEvent.AttackBlocked.class, GameEvent.ResponseDeclined.class),
                t.log.stream().filter(e -> e instanceof GameEvent.AttackBlocked || e instanceof GameEvent.ResponseDeclined)
                        .map(Object::getClass).toList());
    }
}
