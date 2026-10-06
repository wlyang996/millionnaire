package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.DebtState;
import com.millionnaire.engine.core.state.Elimination;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameResult;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.LifeState;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.Standing;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * M2 篡改日志核对：对每类新事件只改一个目标字段（归属、阶段、金额 / 资产、顺序、任务关联），重建必须被拒；
 * 未篡改的日志可以重建（排除"被无关错误提前挡住"）。日志全部来自命令驱动的真实对局。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M2TamperTest {
    private Table game;

    private static final List<Class<? extends GameEvent>> NEEDED = List.of(GameEvent.PropertyBought.class,
            GameEvent.PurchaseDeclined.class, GameEvent.PropertyUpgraded.class, GameEvent.UpgradeSkipped.class,
            GameEvent.RentCharged.class, GameEvent.RentPaid.class, GameEvent.DebtCreated.class, GameEvent.DebtSegmentStarted.class,
            GameEvent.DebtPaid.class, GameEvent.LiquidationStarted.class, GameEvent.AssetLiquidated.class,
            GameEvent.DebtSettled.class, GameEvent.CashReclaimed.class, GameEvent.PlayerEliminated.class,
            GameEvent.AssetMortgaged.class, GameEvent.AssetRedeemed.class, GameEvent.BankFinished.class,
            GameEvent.SurrenderDeferred.class, GameEvent.AssetReclaimed.class, GameEvent.GameEnded.class,
            GameEvent.DebtContinued.class, GameEvent.SurrenderBatchStarted.class, GameEvent.SurrenderBatchEnded.class,
            GameEvent.LandingFinished.class);

    @BeforeAll
    void play() {
        for (long seed = 1; seed < 300; seed++) {
            Table t = run(seed);
            if (NEEDED.stream().allMatch(c -> t.log.stream().anyMatch(c::isInstance))) {
                game = t;
                return;
            }
        }
        throw new AssertionError("no seed covered every event type");
    }

    /** 命令驱动的 3 人局：积极买地升级；奇数债务确认破产、偶数债务应急抵押；第一笔债务时债权人认输（延后）；银行格先抵押再赎回。 */
    private static Table run(long seed) {
        Table t = Table.production(seed);
        t.send(new RoomCommand.Join("p1", "P1"));
        t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_30, 2000, EndMode.TIME_LIMIT, 15, 15)));
        for (int i = 2; i <= 3; i++) {
            t.send(new RoomCommand.Join("p" + i, "P" + i));
        }
        for (int i = 1; i <= 3; i++) {
            t.send(new RoomCommand.SetReady("p" + i, true));
        }
        t.send(new SessionCommand.StartGame("p1"));
        boolean deferred = false;
        int bankOps = 0;
        while (t.session().inGame()) {
            GameState g = t.game();
            FlowFrame w = g.flow().top().orElseThrow();
            String cur = g.turn().currentPlayer();
            long at = Math.max(t.now + 10, w.window().opensAt());
            if (w.kind() == FlowKind.DEBT) {
                if (g.debt().source().landingId() % 3 == 0 && g.debt().segment() == 1) {
                    t.tick(w.window().deadline());                  // 第一段到期 → 第二段弹窗
                    continue;
                }
                if (g.debt().segment() == 2 && !g.debt().continued()) {
                    t.send(at, new GameCommand.ContinueDebt(w.owner(), w.windowId()));
                    continue;
                }
                if (!deferred && g.debt().creditor() != null && g.alive().size() == 3) {
                    deferred = true;
                    t.send(at, new GameCommand.Surrender(g.debt().creditor(), g.gameNo()));
                } else if (g.debt().source().landingId() % 2 == 1) {
                    t.send(at, new GameCommand.DeclareBankruptcy(w.owner(), w.windowId()));
                } else {
                    OwnableState o = g.board().ownedBy(w.owner()).stream().filter(x -> !x.mortgaged()).findFirst().orElseThrow();
                    t.send(at, new GameCommand.EmergencyMortgage(w.owner(), w.windowId(), o.tile()));
                }
                continue;
            }
            switch (g.turn().stage()) {
                case JAIL_DECISION, PRE_ROLL -> t.send(at, new GameCommand.RollDice(cur, w.windowId()));
                case LANDING -> {
                    LandingStep step = g.turn().landing().step();
                    List<OwnableState> free = g.board().ownedBy(cur).stream().filter(x -> !x.mortgaged()).toList();
                    if (step == LandingStep.BANK && bankOps == 0 && !free.isEmpty()) {
                        bankOps++;
                        t.send(at, new GameCommand.BankMortgage(cur, w.windowId(), free.get(0).tile()));
                    } else if (step == LandingStep.BANK && bankOps == 1) {
                        bankOps++;
                        OwnableState m = g.board().ownedBy(cur).stream().filter(OwnableState::mortgaged).findFirst().orElse(null);
                        t.send(at, m == null ? new GameCommand.FinishBank(cur, w.windowId())
                                : new GameCommand.Redeem(cur, w.windowId(), m.tile()));
                    } else {
                        t.send(at, switch (step) {
                            case BUY -> g.turn().turnNo() % 9 == 0 ? new GameCommand.DeclinePurchase(cur, w.windowId())
                                    : new GameCommand.BuyProperty(cur, w.windowId());
                            case UPGRADE -> g.turn().turnNo() % 5 == 0 ? new GameCommand.SkipUpgrade(cur, w.windowId())
                                    : new GameCommand.UpgradeProperty(cur, w.windowId());
                            case BANK -> new GameCommand.FinishBank(cur, w.windowId());
                            case EVENT -> new GameCommand.DrawEventCard(cur, w.windowId());
                            case DISCARD -> new GameCommand.DiscardCard(cur, w.windowId(), g.turn().landing().event().newCardIndex());
                            default -> throw new IllegalStateException();
                        });
                    }
                }
                default -> t.tick(w.window().deadline());
            }
        }
        return t;
    }

    private <T extends GameEvent> void rejectsTampered(Class<T> type, Function<T, Event> tamper, String what) {
        List<Event> log = new ArrayList<>(game.log);
        int i = 0;
        while (!type.isInstance(log.get(i))) {
            i++;
        }
        Event forged = tamper.apply(type.cast(log.get(i)));
        assertTrue(!forged.equals(log.get(i)), "the tamper must change the event: " + what);
        log.set(i, forged);
        M2bReviewTest.rejectsAt(game, log, i);
    }

    private static <T extends GameEvent> int first(List<Event> log, Class<T> type, int from) {
        for (int i = from; i < log.size(); i++) {
            if (type.isInstance(log.get(i))) {
                return i;
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " after " + from);
    }

    /** 复制第一个 type 事件并插在它之后：必须在插入处被拒。 */
    private <T extends GameEvent> void rejectsDuplicate(Class<T> type) {
        List<Event> log = new ArrayList<>(game.log);
        int i = first(log, type, 0);
        log.add(i + 1, log.get(i));
        M2bReviewTest.rejectsAt(game, log, i + 1);
    }

    /** 删除第一个 type 事件：必须在其后第一个依赖它的事件（expectedAt 之一，取最早者）处被拒。 */
    @SafeVarargs
    private <T extends GameEvent> void rejectsDeletion(Class<T> type, Class<? extends GameEvent>... expectedAt) {
        List<Event> log = new ArrayList<>(game.log);
        int i = first(log, type, 0);
        log.remove(i);
        int at = Integer.MAX_VALUE;
        for (Class<? extends GameEvent> c : expectedAt) {
            try {
                at = Math.min(at, first(log, c, i));
            } catch (AssertionError none) {
                // 该类事件之后不再出现
            }
        }
        M2bReviewTest.rejectsAt(game, log, at);
    }

    @Test
    void deletedDuplicatedAndInsertedEventsAreRejectedWhereTheyBreakTheRules() {
        rejectsDuplicate(GameEvent.PropertyBought.class);
        rejectsDuplicate(GameEvent.PurchaseDeclined.class);
        rejectsDuplicate(GameEvent.PropertyUpgraded.class);
        rejectsDuplicate(GameEvent.RentPaid.class);
        rejectsDuplicate(GameEvent.DebtContinued.class);
        rejectsDuplicate(GameEvent.BankFinished.class);
        rejectsDuplicate(GameEvent.SurrenderBatchStarted.class);
        rejectsDeletion(GameEvent.PurchaseDeclined.class, GameEvent.LandingFinished.class);
        rejectsDeletion(GameEvent.UpgradeSkipped.class, GameEvent.LandingFinished.class);
        rejectsDeletion(GameEvent.DebtPaid.class, GameEvent.LandingFinished.class, GameEvent.SurrenderBatchStarted.class);
        rejectsDeletion(GameEvent.SurrenderBatchStarted.class, GameEvent.LiquidationStarted.class);
    }

    @Test
    void landingFinishContinueAndBatchFieldsAreChecked() {
        rejectsTampered(GameEvent.LandingFinished.class, e -> new GameEvent.LandingFinished(e.landingId() + 1), "landing id");
        rejectsTampered(GameEvent.DebtContinued.class, e -> new GameEvent.DebtContinued(e.debtId() + 1), "continued debt id");
        rejectsTampered(GameEvent.SurrenderBatchStarted.class, e -> new GameEvent.SurrenderBatchStarted(e.batch() + 1),
                "batch number");
        rejectsTampered(GameEvent.SurrenderBatchEnded.class, e -> new GameEvent.SurrenderBatchEnded(e.batch() + 1),
                "batch end number");
    }

    private static String other(String p) {
        return p.equals("p1") ? "p2" : "p1";
    }

    @Test
    void theUntamperedLogRebuilds() {
        assertEquals(game.state, game.engine.rebuild(game.log));
        System.out.println("TAMPER BASE seed=" + game.seed + " events=" + game.log.size());
    }

    @Test
    void ownershipAndAmountsOfPurchasesAndUpgradesAreChecked() {
        rejectsTampered(GameEvent.PropertyBought.class, e -> new GameEvent.PropertyBought(e.playerId(), e.tile(), e.price() + 1), "price");
        rejectsTampered(GameEvent.PropertyBought.class, e -> new GameEvent.PropertyBought(other(e.playerId()), e.tile(), e.price()),
                "buyer");
        rejectsTampered(GameEvent.PropertyBought.class, e -> new GameEvent.PropertyBought(e.playerId(), e.tile() + 1, e.price()),
                "tile");
        rejectsTampered(GameEvent.PurchaseDeclined.class, e -> new GameEvent.PurchaseDeclined(other(e.playerId()), e.tile(), e.auto()),
                "decliner");
        rejectsTampered(GameEvent.PropertyUpgraded.class,
                e -> new GameEvent.PropertyUpgraded(e.playerId(), e.tile(), e.level() + 1, e.cost()), "level");
        rejectsTampered(GameEvent.PropertyUpgraded.class,
                e -> new GameEvent.PropertyUpgraded(e.playerId(), e.tile(), e.level(), e.cost() - 1), "upgrade cost");
        rejectsTampered(GameEvent.UpgradeSkipped.class, e -> new GameEvent.UpgradeSkipped(e.playerId(), e.tile() + 1, e.auto()),
                "skip tile");
    }

    @Test
    void rentDebtAndMortgageAmountsAreChecked() {
        rejectsTampered(GameEvent.RentCharged.class, e -> new GameEvent.RentCharged(e.payer(), e.owner(), e.tile(), e.amount() + 50),
                "rent amount");
        rejectsTampered(GameEvent.RentCharged.class, e -> new GameEvent.RentCharged(e.payer(), e.payer(), e.tile(), e.amount()),
                "rent creditor");
        rejectsTampered(GameEvent.RentPaid.class, e -> new GameEvent.RentPaid(e.payer(), e.owner(), e.tile(), e.amount() - 1),
                "rent paid");
        rejectsTampered(GameEvent.DebtCreated.class, e -> {
            DebtState d = e.debt();
            return new GameEvent.DebtCreated(new DebtState(d.debtId(), d.debtor(), d.creditor(), d.amount() + 1, d.cause(),
                    d.segment(), d.continued(), d.windowId(), d.source(), d.path()));
        }, "debt amount");
        rejectsTampered(GameEvent.DebtSegmentStarted.class,
                e -> new GameEvent.DebtSegmentStarted(e.debtId(), e.segment(), e.windowId() + 1), "debt window association");
        rejectsTampered(GameEvent.DebtSegmentStarted.class,
                e -> new GameEvent.DebtSegmentStarted(e.debtId(), e.segment() + 1, e.windowId()), "debt segment order");
        rejectsTampered(GameEvent.DebtPaid.class, e -> new GameEvent.DebtPaid(e.debtId(), e.amount() - 1), "debt payment");
        rejectsTampered(GameEvent.AssetMortgaged.class,
                e -> new GameEvent.AssetMortgaged(e.playerId(), e.tile(), e.principal() + 1, e.emergency()), "principal");
        rejectsTampered(GameEvent.AssetMortgaged.class,
                e -> new GameEvent.AssetMortgaged(e.playerId(), e.tile(), e.principal(), !e.emergency()), "mortgage kind");
        rejectsTampered(GameEvent.AssetRedeemed.class,
                e -> new GameEvent.AssetRedeemed(e.playerId(), e.tile(), e.principal(), e.fee() + 1), "redeem fee");
        rejectsTampered(GameEvent.BankFinished.class, e -> new GameEvent.BankFinished(other(e.playerId()), e.auto()), "bank owner");
    }

    @Test
    void liquidationEliminationAndSurrenderAreChecked() {
        rejectsTampered(GameEvent.LiquidationStarted.class,
                e -> new GameEvent.LiquidationStarted(e.playerId(), e.debtId(),
                        e.outcome() == LifeState.BANKRUPT ? LifeState.SURRENDERED : LifeState.BANKRUPT), "outcome");
        rejectsTampered(GameEvent.AssetLiquidated.class,
                e -> new GameEvent.AssetLiquidated(e.playerId(), e.tile(), e.proceeds() + 1), "liquidation proceeds");
        rejectsTampered(GameEvent.AssetReclaimed.class, e -> new GameEvent.AssetReclaimed(e.playerId(), e.tile() + 1), "reclaimed tile");
        rejectsTampered(GameEvent.DebtSettled.class, e -> new GameEvent.DebtSettled(e.debtId(), e.creditor(), e.paid() + 1),
                "settlement above the cash or the debt");
        rejectsTampered(GameEvent.CashReclaimed.class, e -> new GameEvent.CashReclaimed(e.playerId(), e.amount() + 1), "residual cash");
        rejectsTampered(GameEvent.PlayerEliminated.class, e -> new GameEvent.PlayerEliminated(e.playerId(), e.life(),
                new Elimination(e.elimination().seq() + 1, e.elimination().batch(), e.elimination().netWorthBefore())), "seq");
        rejectsTampered(GameEvent.PlayerEliminated.class, e -> new GameEvent.PlayerEliminated(e.playerId(), e.life(),
                new Elimination(e.elimination().seq(), e.elimination().batch(), e.elimination().netWorthBefore() + 1)),
                "net worth snapshot");
        rejectsTampered(GameEvent.SurrenderDeferred.class, e -> new GameEvent.SurrenderDeferred(other(e.playerId())),
                "only flow participants can defer");
        rejectsTampered(GameEvent.GameEnded.class, e -> {
            List<Standing> s = new ArrayList<>(e.result().standings());
            Standing a = s.get(0);
            s.set(0, new Standing(a.playerId(), a.rank(), a.netWorth() + 1, a.cash()));
            return new GameEvent.GameEnded(e.gameNo(), e.reason(), new GameResult(e.result().gameNo(), e.result().reason(), s));
        }, "standings");
    }

    @Test
    void landingOrderIsChecked() {
        rejectsTampered(GameEvent.LandingStarted.class, e -> new GameEvent.LandingStarted(e.landingId() + 1, e.playerId(), e.tile(), e.chainId()),
                "landing number");
        rejectsTampered(GameEvent.LandingStepEntered.class, e -> new GameEvent.LandingStepEntered(e.landingId(),
                e.step() == LandingStep.BUY ? LandingStep.BANK : LandingStep.BUY, e.payment(), e.cursor()), "landing step");
        // 顺序：把第一笔 RentPaid 挪到它的 RentCharged 之前
        List<Event> log = new ArrayList<>(game.log);
        int paid = 0;
        while (!(log.get(paid) instanceof GameEvent.RentPaid)) {
            paid++;
        }
        Event moved = log.remove(paid);
        log.add(paid - 1, moved);
        M2bReviewTest.rejectsAt(game, log, paid - 1);
    }
}
