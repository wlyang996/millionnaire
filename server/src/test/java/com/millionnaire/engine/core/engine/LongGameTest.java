package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ConnState;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.GameView;
import com.millionnaire.engine.core.state.LifeState;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.SessionView;
import com.millionnaire.engine.core.state.Standing;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.replay.RandomAudit;
import com.millionnaire.engine.replay.RunResult;
import com.millionnaire.engine.replay.Scenario;
import com.millionnaire.engine.replay.ScenarioRunner;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * M2 完整经济长局（生产随机协议）：一局 8 人 50 格的固定策略客户端（初始现金 2000，积极买地升级），加三局以固定种子驱动的
 * 随机客户端（4 人 30 格）。客户端会买 / 放弃 / 升级 / 跳过、银行抵押与赎回、在债务窗口应急抵押 / 继续 / 确认破产、
 * 让窗口超时、付费出狱、切换暂离 / 托管 / 恢复、发送合法与非法的连接判定、认输（含债权人延后认输）、被淘汰者离房。
 * 输入全部记录，可复现。每一步核对账本守恒、账本不变量与各客户端视图一致；结束后做快照续跑比对。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LongGameTest {
    private static final long[] RANDOM_SEEDS = {101L, 202L, 303L};
    private final List<Table> games = new ArrayList<>();

    @BeforeAll
    void play() {
        Table fixed = Table.production(20261005L);
        start(fixed, 8, RuleConfigs.BOARD_50, 2000, 30);
        playFixed(fixed);
        games.add(fixed);
        for (long seed : RANDOM_SEEDS) {
            Table t = Table.production(seed);
            start(t, 4, RuleConfigs.BOARD_30, 3000, 30);
            playRandom(t, new SplittableRandom(seed));
            games.add(t);
        }
        // Retain all four historical scenarios; add a long-lived no-purchase game to reach the hand cap.
        Table passive = Table.production(404L);
        start(passive, 2, RuleConfigs.BOARD_50, 3000, 15);
        while (passive.session().inGame()) {
            var g = passive.game();
            if (g.debt() != null) { passive.tick(passive.window().window().deadline()); }
            else if (g.turn().stage() == TurnStage.LANDING) {
                if (g.turn().landing().step() == com.millionnaire.engine.core.state.LandingStep.EVENT && g.turn().turnNo() % 3 == 0) {
                    passive.tick(passive.window().window().deadline());
                } else { passive.pass(); }
            } else { passive.rollOnly(); }
            checkStep(passive);
        }
        games.add(passive);
    }

    private static void start(Table t, int players, String board, long cash, int minutes) {
        t.send(new RoomCommand.Join("p1", "P1"));
        t.send(new RoomCommand.ChangeSettings("p1", new com.millionnaire.engine.core.state.RoomSettings(board, cash,
                EndMode.TIME_LIMIT, minutes, 15)));
        for (int i = 2; i <= players; i++) {
            t.send(new RoomCommand.Join("p" + i, "P" + i));
        }
        for (int i = 1; i <= players; i++) {
            t.send(new RoomCommand.SetReady("p" + i, true));
        }
        t.send(new com.millionnaire.engine.core.command.SessionCommand.StartGame("p1"));
    }

    // ------------------------------------------------------------ 共用

    private static FlowFrame top(Table t) {
        return t.game().flow().top().orElseThrow();
    }

    private static long at(Table t, FlowFrame w, long offset) {
        return Math.min(Math.max(t.now, w.window().opensAt()) + offset, w.window().deadline() - 1);
    }

    /** 自动玩家：推进到其自动任务（或窗口截止）。 */
    private static void waitAuto(Table t) {
        GameState g = t.game();
        long id = g.turn().autoTaskId();
        long due = id != 0 ? t.state.timers().find(id).orElseThrow().dueAt() : top(t).window().deadline();
        t.tick(Math.max(t.now, due));
    }

    private static List<OwnableState> owned(GameState g, String p, boolean mortgaged) {
        return g.board().ownedBy(p).stream().filter(o -> o.mortgaged() == mortgaged).toList();
    }

    private static boolean atBank(Table t, String p) {
        var board = t.config.board(t.game().settings().boardId()).orElseThrow();
        return board.tiles().get(t.game().player(p).orElseThrow().position()).type() == TileType.BANK;
    }

    // ------------------------------------------------------------ 固定策略

    private void playFixed(Table t) {
        long obs = 0;
        boolean creditorSurrendered = false;
        boolean p8Surrendered = false;
        while (t.session().inGame()) {
            GameState g = t.game();
            long turn = g.turn().turnNo();
            if (g.debt() != null && !creditorSurrendered && g.debt().creditor() != null && g.alive().size() > 3) {
                creditorSurrendered = true;
                t.send(t.now + 1, new GameCommand.Surrender(g.debt().creditor(), g.gameNo()));   // 延后认输
            } else if (turn >= 6 && alive(g, "p3") && g.player("p3").orElseThrow().control() == ControlMode.MANUAL && turn < 60) {
                t.send(t.now + 1, new GameCommand.SetControl(1, "p3", ControlMode.AWAY));
            } else if (turn >= 20 && turn < 45 && alive(g, "p5") && g.player("p5").orElseThrow().conn() == ConnState.ONLINE) {
                t.send(t.now + 1, new GameCommand.ConnectionSuspected(1, "p5", ++obs));
                t.send(t.now + 1, new GameCommand.ConnectionConfirmed(1, "p5", ++obs));
            } else if (turn >= 45 && alive(g, "p5") && g.player("p5").orElseThrow().conn() != ConnState.ONLINE) {
                t.send(t.now + 1, new GameCommand.Reconnected(1, "p5", ++obs));
            } else if (turn >= 60 && alive(g, "p3") && g.player("p3").orElseThrow().control() != ControlMode.MANUAL) {
                t.send(t.now + 1, new GameCommand.ResumeControl("p3", 1));
            } else if (turn >= 80 && !p8Surrendered && alive(g, "p8") && g.alive().size() > 2 && g.debt() == null) {
                p8Surrendered = true;
                t.send(t.now + 1, new GameCommand.Surrender("p8", 1));
            } else if (leaver(t) != null) {
                t.send(t.now + 1, new RoomCommand.Leave(leaver(t)));
            } else {
                driveFixed(t, g);
            }
            checkStep(t);
        }
    }

    private static boolean alive(GameState g, String p) {
        return g.player(p).map(PlayerState::alive).orElse(false);
    }

    /** 被淘汰且仍在房间里的玩家（每局让一个离房，验证名册与成员分离）。 */
    private static String leaver(Table t) {
        GameState g = t.game();
        return g.players().stream().filter(p -> !p.alive() && t.session().lobby().isMember(p.playerId()))
                .filter(p -> p.playerId().compareTo("p5") >= 0).map(PlayerState::playerId).findFirst().orElse(null);
    }

    /** 积极策略：能买就买、能升就升；银行格现金低时抵押；现金充裕时赎回；欠款时逐项应急抵押；每第 11 回合让窗口超时。 */
    private static void driveFixed(Table t, GameState g) {
        FlowFrame w = top(t);
        String cur = g.turn().currentPlayer();
        if (w.kind() == FlowKind.DEBT) {
            List<OwnableState> assets = owned(g, w.owner(), false);
            long mode = g.debt().source().landingId() % 4;
            if (mode == 0) {
                t.tick(w.window().deadline());                       // 不操作：第一段 → 第二段 → 破产
            } else if (mode == 1) {
                t.send(at(t, w, 300), new GameCommand.DeclareBankruptcy(w.owner(), w.windowId()));
            } else if (!assets.isEmpty()) {
                t.send(at(t, w, 300), new GameCommand.EmergencyMortgage(w.owner(), w.windowId(), assets.get(0).tile()));
            } else {
                t.send(at(t, w, 300), new GameCommand.DeclareBankruptcy(w.owner(), w.windowId()));
            }
            return;
        }
        if (g.player(cur).orElseThrow().automated()) {
            waitAuto(t);
            return;
        }
        if (g.turn().turnNo() % 11 == 0) {
            t.tick(w.window().deadline());
            return;
        }
        long cash = g.ledger().available(cur);
        switch (g.turn().stage()) {
            case JAIL_DECISION -> {
                if (g.turn().turnNo() % 2 == 0 && cash >= t.config.economy().bailCost()) {
                    t.send(at(t, w, 200), new GameCommand.PayBail(cur, w.windowId()));
                } else {
                    t.send(at(t, w, 100), new GameCommand.RollDice(cur, w.windowId()));
                }
            }
            case PRE_ROLL -> {
                List<OwnableState> mortgaged = owned(g, cur, true);
                if (!mortgaged.isEmpty() && cash > 1500) {
                    t.send(at(t, w, 50), new GameCommand.Redeem(cur, w.windowId(), mortgaged.get(0).tile()));
                } else {
                    t.send(at(t, w, 100), new GameCommand.RollDice(cur, w.windowId()));
                }
            }
            case LANDING -> {
                switch (g.turn().landing().step()) {
                    case EVENT -> t.send(at(t, w, 100), new GameCommand.DrawEventCard(cur, w.windowId()));
                    case DISCARD -> t.send(at(t, w, 100), new GameCommand.DiscardCard(cur, w.windowId(), g.turn().landing().event().newCardIndex()));
                    case BUY -> t.send(at(t, w, 100), new GameCommand.BuyProperty(cur, w.windowId()));
                    case UPGRADE -> t.send(at(t, w, 100), new GameCommand.UpgradeProperty(cur, w.windowId()));
                    case BANK -> {
                        List<OwnableState> free = owned(g, cur, false);
                        if (cash < 600 && !free.isEmpty()) {
                            t.send(at(t, w, 100), new GameCommand.BankMortgage(cur, w.windowId(), free.get(0).tile()));
                        } else {
                            t.send(at(t, w, 100), new GameCommand.FinishBank(cur, w.windowId()));
                        }
                    }
                    default -> t.tick(w.window().deadline());
                }
            }
            default -> t.tick(w.window().deadline());
        }
    }

    // ------------------------------------------------------------ 随机策略（固定种子）

    private void playRandom(Table t, SplittableRandom rnd) {
        assertEquals(RejectionCode.STALE_OBSERVATION,
                t.send(t.now + 1, new GameCommand.ConnectionSuspected(t.game().gameNo(), "p1", 0)).rejection());
        TreeMap<String, Long> observations = new TreeMap<>();
        while (t.session().inGame()) {
            GameState g = t.game();
            FlowFrame w = top(t);
            String cur = g.turn().currentPlayer();
            int choice = rnd.nextInt(1000);
            List<PlayerState> alive = g.alive();
            String someone = alive.get(rnd.nextInt(alive.size())).playerId();
            if (choice < 40) {
                ControlMode m = ControlMode.values()[rnd.nextInt(3)];
                Command c = m == ControlMode.MANUAL ? new GameCommand.ResumeControl(someone, g.gameNo())
                        : new GameCommand.SetControl(g.gameNo(), someone, m);
                t.send(t.now + 1 + rnd.nextInt(200), c);
            } else if (choice < 80) {
                PlayerState tp = g.player(someone).orElseThrow();
                long seen = observations.getOrDefault(someone, 0L);
                long obs = rnd.nextInt(5) == 0 ? seen : seen + 1;
                observations.put(someone, Math.max(seen, obs));
                Command c = switch (tp.conn()) {
                    case ONLINE -> new GameCommand.ConnectionSuspected(g.gameNo(), someone, obs);
                    case SUSPECT -> rnd.nextBoolean() ? new GameCommand.ConnectionConfirmed(g.gameNo(), someone, obs)
                            : new GameCommand.Reconnected(g.gameNo(), someone, obs);
                    case OFFLINE -> new GameCommand.Reconnected(g.gameNo(), someone, obs);
                };
                t.send(t.now + 1 + rnd.nextInt(200), c);
            } else if (choice < 92 && alive.size() > 2) {
                t.send(t.now + 1, new GameCommand.Surrender(someone, g.gameNo()));
            } else if (choice < 96 && leaverAny(t) != null) {
                t.send(t.now + 1, new RoomCommand.Leave(leaverAny(t)));
            } else if (choice < 104) {
                // 非法输入：别人窗口的命令、过期窗口
                t.send(t.now + 1, new GameCommand.BuyProperty(someone, w.windowId() - 1));
            } else if (w.kind() == FlowKind.DEBT) {
                randomDebt(t, rnd, g, w);
            } else if (g.player(cur).orElseThrow().automated()) {
                PlayerState p = g.player(cur).orElseThrow();
                if (p.control() != ControlMode.MANUAL && p.conn() != ConnState.OFFLINE && choice < 600) {
                    t.send(t.now + 1, new GameCommand.ResumeControl(cur, g.gameNo()));
                } else {
                    waitAuto(t);
                }
            } else if (choice < 150) {
                t.tick(Math.max(t.now, w.window().deadline()));
            } else {
                randomTurn(t, rnd, g, w, cur);
            }
            checkStep(t);
        }
    }

    private static String leaverAny(Table t) {
        GameState g = t.game();
        return g.players().stream().filter(p -> !p.alive() && t.session().lobby().isMember(p.playerId()))
                .map(PlayerState::playerId).findFirst().orElse(null);
    }

    private static void randomDebt(Table t, SplittableRandom rnd, GameState g, FlowFrame w) {
        String debtor = w.owner();
        List<OwnableState> assets = owned(g, debtor, false);
        int r = rnd.nextInt(10);
        if (r < 6 && !assets.isEmpty()) {
            t.send(at(t, w, rnd.nextInt(5000)), new GameCommand.EmergencyMortgage(debtor, w.windowId(),
                    assets.get(rnd.nextInt(assets.size())).tile()));
        } else if (r < 7) {
            t.send(at(t, w, rnd.nextInt(5000)), new GameCommand.DeclareBankruptcy(debtor, w.windowId()));
        } else if (r < 8 && g.debt().segment() == 2) {
            t.send(at(t, w, rnd.nextInt(5000)), new GameCommand.ContinueDebt(debtor, w.windowId()));
        } else if (r < 9 && g.debt().creditor() != null && g.alive().size() > 2) {
            t.send(t.now + 1, new GameCommand.Surrender(g.debt().creditor(), g.gameNo()));
        } else {
            t.tick(Math.max(t.now, w.window().deadline()));
        }
    }

    private static void randomTurn(Table t, SplittableRandom rnd, GameState g, FlowFrame w, String cur) {
        long when = at(t, w, rnd.nextInt(8000));
        long cash = g.ledger().available(cur);
        switch (g.turn().stage()) {
            case JAIL_DECISION, PRE_ROLL -> {
                List<OwnableState> mortgaged = owned(g, cur, true);
                List<OwnableState> free = owned(g, cur, false);
                int r = rnd.nextInt(10);
                if (r == 0 && !mortgaged.isEmpty()) {
                    t.send(when, new GameCommand.Redeem(cur, w.windowId(), mortgaged.get(rnd.nextInt(mortgaged.size())).tile()));
                } else if (r == 1 && !free.isEmpty() && atBank(t, cur)) {
                    t.send(when, new GameCommand.BankMortgage(cur, w.windowId(), free.get(rnd.nextInt(free.size())).tile()));
                } else if (r == 2 && g.turn().stage() == TurnStage.JAIL_DECISION && cash >= t.config.economy().bailCost()) {
                    t.send(when, new GameCommand.PayBail(cur, w.windowId()));
                } else {
                    t.send(when, new GameCommand.RollDice(cur, w.windowId()));
                }
            }
            case LANDING -> {
                int r = rnd.nextInt(10);
                Command c = switch (g.turn().landing().step()) {
                    case EVENT -> new GameCommand.DrawEventCard(cur, w.windowId());
                    case DISCARD -> new GameCommand.DiscardCard(cur, w.windowId(), rnd.nextInt(g.player(cur).orElseThrow().hand().size()));
                    case BUY -> r < 8 ? new GameCommand.BuyProperty(cur, w.windowId()) : new GameCommand.DeclinePurchase(cur, w.windowId());
                    case UPGRADE -> r < 7 ? new GameCommand.UpgradeProperty(cur, w.windowId()) : new GameCommand.SkipUpgrade(cur, w.windowId());
                    case BANK -> {
                        List<OwnableState> free = owned(g, cur, false);
                        List<OwnableState> mortgaged = owned(g, cur, true);
                        if (r < 4 && !free.isEmpty()) {
                            yield new GameCommand.BankMortgage(cur, w.windowId(), free.get(rnd.nextInt(free.size())).tile());
                        } else if (r < 6 && !mortgaged.isEmpty()) {
                            yield new GameCommand.Redeem(cur, w.windowId(), mortgaged.get(rnd.nextInt(mortgaged.size())).tile());
                        }
                        yield new GameCommand.FinishBank(cur, w.windowId());
                    }
                    default -> throw new IllegalStateException("unexpected landing step");
                };
                t.send(when, c);
            }
            default -> t.tick(Math.max(t.now, w.window().deadline()));
        }
    }

    // ------------------------------------------------------------ 每步检查

    private static void checkStep(Table t) {
        SessionState s = t.session();
        if (s.game() == null) {
            return;
        }
        GameState g = s.game();
        Ledger l = g.ledger();
        l.verifyInvariants();
        long total = l.systemNet() + l.cash().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(g.players().size() * g.settings().initialCash(), total, "cash is conserved");
        for (PlayerState p : g.players()) {
            if (!p.alive()) {
                assertEquals(0, l.cash(p.playerId()), "eliminated players hold no cash");
                assertTrue(g.board().ownedBy(p.playerId()).isEmpty(), "eliminated players hold no assets");
            }
        }
        GameView reference = null;
        for (PlayerState p : g.players()) {
            SessionView v = SessionDomain.INSTANCE.project(t.state, p.playerId());
            GameView view = v.game();
            assertEquals(p.hand(), view.myHand(), "each client sees exactly its own hand");
            GameView publicPart = new GameView(view.gameNo(), view.phase(), view.players(), view.orderDraws(), view.board(),
                    view.turnNo(), view.currentPlayer(), view.stage(), view.globalEndsAt(), view.windows(), view.landing(), view.debt(), List.of());
            if (reference == null) {
                reference = publicPart;
            }
            assertEquals(reference, publicPart, "client " + p.playerId() + " disagrees");
        }
    }

    // ------------------------------------------------------------ 覆盖

    private static long count(List<Event> log, java.util.function.Predicate<Event> p) {
        return log.stream().filter(p).count();
    }

    private static long autoRolls(List<Event> log, CloseReason reason) {
        long n = 0;
        CloseReason last = null;
        for (Event e : log) {
            if (e instanceof GameEvent.WindowClosed w) {
                last = w.reason();
            } else if (e instanceof GameEvent.DiceRolled d && d.auto() && last == reason) {
                n++;
            }
        }
        return n;
    }

    private static TreeMap<String, Long> coverage(List<Event> log) {
        TreeMap<String, Long> c = new TreeMap<>();
        c.put("turns", count(log, e -> e instanceof GameEvent.TurnStarted));
        c.put("buy", count(log, e -> e instanceof GameEvent.PropertyBought));
        c.put("decline", count(log, e -> e instanceof GameEvent.PurchaseDeclined));
        c.put("upgrade", count(log, e -> e instanceof GameEvent.PropertyUpgraded));
        c.put("rent", count(log, e -> e instanceof GameEvent.RentPaid));
        c.put("bankMortgage", count(log, e -> e instanceof GameEvent.AssetMortgaged m && !m.emergency()));
        c.put("emergencyMortgage", count(log, e -> e instanceof GameEvent.AssetMortgaged m && m.emergency()));
        c.put("redeem", count(log, e -> e instanceof GameEvent.AssetRedeemed));
        c.put("debt", count(log, e -> e instanceof GameEvent.DebtCreated));
        c.put("debtPaid", count(log, e -> e instanceof GameEvent.DebtPaid));
        c.put("debtSegment2", count(log, e -> e instanceof GameEvent.DebtSegmentStarted s && s.segment() == 2));
        c.put("bankrupt", count(log, e -> e instanceof GameEvent.PlayerEliminated x && x.life() == LifeState.BANKRUPT));
        c.put("liquidated", count(log, e -> e instanceof GameEvent.AssetLiquidated));
        c.put("surrender", count(log, e -> e instanceof GameEvent.PlayerEliminated x && x.life() == LifeState.SURRENDERED));
        c.put("surrenderDeferred", count(log, e -> e instanceof GameEvent.SurrenderDeferred));
        c.put("left", count(log, e -> e instanceof com.millionnaire.engine.core.event.RoomEvent.PlayerLeft));
        c.put("auto.byTask", autoRolls(log, CloseReason.ACTED));
        c.put("auto.byTimeout", autoRolls(log, CloseReason.EXPIRED));
        c.put("jail", count(log, e -> e instanceof GameEvent.PlayerJailed));
        c.put("bail", count(log, e -> e instanceof GameEvent.BailPaid));
        c.put("startReward", count(log, e -> e instanceof GameEvent.StartRewardPaid));
        for (var kind : com.millionnaire.engine.config.EventKind.values()) {
            c.put("event." + kind, count(log, e -> e instanceof GameEvent.EventDrawn d && d.kind() == kind));
        }
        c.put("discard", count(log, e -> e instanceof GameEvent.EventCardDiscarded));
        c.put("fineDebt", count(log, e -> e instanceof GameEvent.DebtCreated d && d.debt().source().kind() == com.millionnaire.engine.core.state.FeeSource.Kind.FINE));
        c.put("eventAuto", count(log, e -> e instanceof GameEvent.EventDrawn d && d.auto()));
        c.put("staleObservation", count(log, e -> e instanceof KernelEvent.InputRejected r
                && r.code() == RejectionCode.STALE_OBSERVATION));
        c.put("timeUp", count(log, e -> e instanceof GameEvent.GameEnded x && x.reason().equals("TIME_UP")));
        c.put("lastSurvivor", count(log, e -> e instanceof GameEvent.GameEnded x && x.reason().equals("LAST_SURVIVOR")));
        return c;
    }

    @Test
    void eachGameCoversItsOwnPaths() {
        List<String> fixedMust = List.of("buy", "upgrade", "rent", "bankMortgage", "redeem", "emergencyMortgage", "debt",
                "debtPaid", "debtSegment2", "bankrupt", "liquidated", "surrender", "surrenderDeferred", "left",
                "auto.byTask", "auto.byTimeout", "jail", "startReward");
        List<String> randomMust = List.of("buy", "upgrade", "rent", "debt", "surrender", "auto.byTimeout", "staleObservation");
        for (int i = 0; i < games.size(); i++) {
            Table t = games.get(i);
            TreeMap<String, Long> c = coverage(t.log);
            System.out.println("COVERAGE seed=" + t.seed + " inputs=" + t.inputs.size() + " " + c);
            var must = i == 0 ? fixedMust : i <= RANDOM_SEEDS.length ? randomMust
                    : List.of("decline", "discard", "eventAuto", "event.CARD", "timeUp");
            for (String path : must) {
                assertTrue(c.get(path) > 0, "seed " + t.seed + " covers " + path + " " + c);
            }
            assertEquals(1, c.get("timeUp") + c.get("lastSurvivor"), "seed " + t.seed + " ends exactly once");
        }
        TreeMap<String, Long> all = new TreeMap<>();
        games.forEach(t -> coverage(t.log).forEach((k, v) -> all.merge(k, v, Long::sum)));
        for (String path : List.of("event.CASH_REWARD", "event.CASH_FINE", "event.CARD", "event.MOVE", "event.JAIL", "eventAuto", "fineDebt", "discard")) {
            assertTrue(all.get(path) > 0, "M3b long games cover " + path + " " + all);
        }
        for (String path : List.of("decline", "bail", "debtSegment2", "bankMortgage", "redeem", "emergencyMortgage",
                "bankrupt", "surrenderDeferred", "lastSurvivor", "timeUp", "auto.byTask")) {
            assertTrue(all.get(path) > 0, "some game covers " + path + " " + all);
        }
    }

    /** 每局结算玩家集合与开局集合完全相同（含离房的被淘汰者），名次符合规则。 */
    @Test
    void eachGameSettlesExactlyItsStartingPlayers() {
        for (Table t : games) {
            List<String> seats = t.log.stream().filter(e -> e instanceof GameEvent.GameStarted)
                    .map(e -> ((GameEvent.GameStarted) e).seats()).findFirst().orElseThrow();
            GameEvent.GameEnded end = t.log.stream().filter(e -> e instanceof GameEvent.GameEnded)
                    .map(e -> (GameEvent.GameEnded) e).findFirst().orElseThrow();
            List<Standing> st = end.result().standings();
            assertEquals(new TreeSet<>(seats), new TreeSet<>(st.stream().map(Standing::playerId).toList()),
                    "seed " + t.seed + ": settled players == starting players");
            assertEquals(seats.size(), st.size());
            assertEquals(1, st.get(0).rank());
            assertNull(t.session().game());
            // 被淘汰者排在存活者之后，越晚出局越靠前
            List<String> eliminatedOrder = t.log.stream().filter(e -> e instanceof GameEvent.PlayerEliminated)
                    .map(e -> ((GameEvent.PlayerEliminated) e).playerId()).toList();
            List<String> tail = st.subList(st.size() - eliminatedOrder.size(), st.size()).stream()
                    .map(Standing::playerId).toList();
            // 存活者部分逐对核对：净资产（含地产价值）降序，相同再比现金，完全相同并列（1、1、3）
            int aliveCount = st.size() - eliminatedOrder.size();
            for (int i = 1; i < aliveCount; i++) {
                Standing prev = st.get(i - 1);
                Standing cur = st.get(i);
                assertTrue(prev.netWorth() > cur.netWorth() || prev.netWorth() == cur.netWorth() && prev.cash() >= cur.cash(),
                        "seed " + t.seed + ": net worth then cash order at " + i);
                boolean tie = prev.netWorth() == cur.netWorth() && prev.cash() == cur.cash();
                assertEquals(tie ? prev.rank() : i + 1, cur.rank(), "seed " + t.seed + ": competition ranking at " + i);
            }
            if (st.size() > eliminatedOrder.size()) {
                List<String> expectedTail = new ArrayList<>(eliminatedOrder);
                java.util.Collections.reverse(expectedTail);
                assertEquals(expectedTail, tail, "seed " + t.seed + ": later eliminations rank higher");
            }
            System.out.println("LONG GAME seed=" + t.seed + " reason=" + end.reason() + " standings=" + st);
        }
    }

    @Test
    void replayIsByteIdenticalRebuildsFromEventsAndPassesTheRandomAudit() {
        for (Table t : games) {
            Scenario scenario = t.scenario();
            ScenarioRunner<SessionState> runner = new ScenarioRunner<>(scenario, SessionDomain.INSTANCE);
            RunResult full = runner.run();
            assertEquals(t.state, full.state());
            assertEquals(runner.engine().encodeEvents(t.log), runner.engine().encodeEvents(full.events()));
            assertEquals(full.state(), runner.engine().rebuild(full.events()));
            RandomAudit.verify(full.events());
        }
    }

    @Test
    void longRunsAllocateIndependentContiguousDebtAndChainNumbers() {
        int debts = 0;
        for (Table t : games) {
            long expectedDebt = 1;
            long expectedChain = 1;
            for (Event e : t.log) {
                if (e instanceof GameEvent.MoveChainStarted c) { assertEquals(expectedChain++, c.chainId()); }
                if (e instanceof GameEvent.DebtCreated c) {
                    assertEquals(expectedDebt++, c.debt().debtId());
                    assertEquals(c.debt().amount(), c.debt().source().amount());
                    assertNotNull(c.debt().path());
                    debts++;
                }
            }
        }
        assertTrue(debts > 3, "long scenarios must exercise multiple distinct debts");
    }

    /**
     * 快照续跑：每个截点的快照都能恢复为与原状态相等的状态，并从该处续跑一步得到与原运行相同的状态与事件；
     * 另每隔 STRIDE 个截点从快照一直续跑到终局，比较最终状态与全部尾部事件。
     */
    @Test
    void snapshotResumeAtEveryCutMatchesTheUninterruptedRun() {
        final int stride = 150;
        for (Table t : games) {
            Scenario scenario = t.scenario();
            var engine = t.engine;
            int n = scenario.inputs().size();
            List<EngineState> prefixes = new ArrayList<>();
            List<List<Event>> stepEvents = new ArrayList<>();
            EngineState s = engine.create(scenario.roomId(), scenario.seed(), scenario.createdAt()).state();
            prefixes.add(s);
            for (int i = 0; i < n; i++) {
                var r = engine.step(s, scenario.inputs().get(i));
                s = r.state();
                prefixes.add(s);
                stepEvents.add(r.events());
            }
            assertEquals(t.state, s);
            var keyCuts = new LinkedHashMap<String, Integer>();
            for (int cut = 0; cut <= n; cut++) {
                var session = (SessionState) prefixes.get(cut).domain();
                if (!session.inGame()) { continue; }
                var g = session.game();
                var landing = g.turn().landing();
                if (landing != null && landing.cursor() > 0) { keyCuts.putIfAbsent("multi-step", cut); }
                if (landing != null && landing.step() == com.millionnaire.engine.core.state.LandingStep.EVENT) { keyCuts.putIfAbsent("event-window", cut); }
                if (landing != null && landing.step() == com.millionnaire.engine.core.state.LandingStep.DISCARD) { keyCuts.putIfAbsent("discard-window", cut); }
                if (g.debt() != null && g.debt().source().kind() == com.millionnaire.engine.core.state.FeeSource.Kind.FINE) { keyCuts.putIfAbsent("fine-debt", cut); }
                if (g.turn().chain() != null && g.turn().chain().segments().size() > 1) { keyCuts.putIfAbsent("event-move", cut); }
                if (g.debt() != null) { keyCuts.putIfAbsent("debt-" + g.debt().segment(), cut); }
                if (g.turn().chain() != null && g.turn().chain().startRewardGiven()) { keyCuts.putIfAbsent("reward-chain", cut); }
            }
            System.out.println("M3b full-tail cuts seed=" + t.seed + " " + keyCuts);
            for (int cut = 0; cut <= n; cut++) {
                EngineState restored = engine.restore(engine.snapshot(prefixes.get(cut)));
                assertEquals(prefixes.get(cut), restored, "seed " + t.seed + " cut " + cut);
                if (cut < n) {
                    var r = engine.step(restored, scenario.inputs().get(cut));
                    assertEquals(prefixes.get(cut + 1), r.state(), "seed " + t.seed + " step after cut " + cut);
                    assertEquals(stepEvents.get(cut), r.events(), "seed " + t.seed + " events after cut " + cut);
                }
                if (cut % stride == 0 || keyCuts.containsValue(cut)) {
                    EngineState x = restored;
                    List<Event> tail = new ArrayList<>();
                    for (int i = cut; i < n; i++) {
                        var r = engine.step(x, scenario.inputs().get(i));
                        x = r.state();
                        tail.addAll(r.events());
                    }
                    assertEquals(t.state, x, "seed " + t.seed + " final state after resuming at " + cut);
                    List<Event> expected = new ArrayList<>();
                    stepEvents.subList(cut, n).forEach(expected::addAll);
                    assertEquals(expected, tail, "seed " + t.seed + " tail events after resuming at " + cut);
                }
            }
        }
    }
}
