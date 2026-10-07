package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.testkit.TestBoards;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 正式规则（道具、虎口拔牙、免租等开启）的随机长局：4 人 30 格，随机客户端会在投骰前 / 狱中 / 落点后随机用卡（含非法目标），
 * 随机响应或放任超时、随机切托管。全程走生产随机协议与真实命令（不改写状态），每一步核对视图隔离，定期核对事件重放与快照往返。
 */
class CardLongGameTest {
    private static final RuleConfig RULES = RuleConfigs.v1(TestBoards.LEGACY_30, TestBoards.LEGACY_50, true);

    private static Table start(long seed) {
        Table t = new Table(RULES, XoshiroLemireV1.INSTANCE, seed);
        t.send(new RoomCommand.Join("p1", "P1"));
        t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings("classic-30", 2000, com.millionnaire.engine.config.EndMode.TIME_LIMIT, 15, 15)));
        for (int i = 2; i <= 4; i++) {
            t.send(new RoomCommand.Join("p" + i, "P" + i));
        }
        for (int i = 1; i <= 4; i++) {
            t.send(new RoomCommand.SetReady("p" + i, true));
        }
        t.send(new SessionCommand.StartGame("p1"));
        return t;
    }

    private static long at(Table t, FlowFrame w, SplittableRandom rnd) {
        return Math.min(Math.max(t.now + 1, w.window().opensAt()) + rnd.nextInt(3000), w.window().deadline() - 1);
    }

    /** 随机挑一张手牌用掉（目标随机，可能不合法：被拒的命令不改变状态）。 */
    private static Command randomCard(GameState g, String cur, long window, SplittableRandom rnd) {
        var hand = g.player(cur).orElseThrow().hand();
        if (hand.isEmpty()) {
            return null;
        }
        CardType card = hand.get(rnd.nextInt(hand.size()));
        String target = g.players().get(rnd.nextInt(g.players().size())).playerId();
        if (rnd.nextInt(10) < 7) {
            // 多数时候挑一张此刻能用的卡、合法的查询对象
            boolean post = CardModule.postLandingWindow(g);
            var usable = hand.stream().distinct().filter(c -> CardModule.usable(RULES, g, cur, c, post)).toList();
            if (!usable.isEmpty()) {
                card = usable.get(rnd.nextInt(usable.size()));
            }
            var others = g.alive().stream().map(p -> p.playerId()).filter(p -> !p.equals(cur)).toList();
            if (!others.isEmpty()) {
                target = others.get(rnd.nextInt(others.size()));
            }
        }
        return new GameCommand.UseCard(cur, window, card, target, 1 + rnd.nextInt(6));
    }

    private static void play(Table t, SplittableRandom rnd) {
        for (int step = 0; step < 1500 && t.session().inGame(); step++) {
            GameState g = t.game();
            FlowFrame w = g.flow().top().orElseThrow();
            String cur = g.turn().currentPlayer();
            int r = rnd.nextInt(100);
            if (r < 3) {
                String someone = g.alive().get(rnd.nextInt(g.alive().size())).playerId();
                ControlMode m = ControlMode.values()[rnd.nextInt(3)];
                t.send(t.now + 1, m == ControlMode.MANUAL ? new GameCommand.ResumeControl(someone, g.gameNo())
                        : new GameCommand.SetControl(g.gameNo(), someone, m));
            } else if (r < 6) {
                // 随时申请拍卖卡（多半不合法，被拒不改变状态）
                String someone = g.alive().get(rnd.nextInt(g.alive().size())).playerId();
                var own = g.board().ownedBy(someone);
                int tile = own.isEmpty() ? 1 : own.get(rnd.nextInt(own.size())).tile();
                t.send(t.now + 1, new GameCommand.RequestAuction(someone, tile));
            } else if (w.kind() == FlowKind.AUCTION || w.kind() == FlowKind.LAND_AUCTION) {
                var a = g.auction();
                if (a != null && r < 75) {
                    var bidders = g.alive().stream().map(p -> p.playerId()).filter(p -> !p.equals(a.host())).toList();
                    String bidder = bidders.get(rnd.nextInt(bidders.size()));
                    long amount = r < 10 ? a.cap() : Math.min(a.cap(), a.minimumBid() + a.minRaise() * rnd.nextInt(3));
                    t.send(at(t, w, rnd), new GameCommand.Bid(bidder, w.windowId(), amount));
                } else {
                    t.tick(Math.max(t.now, w.window().deadline()));
                }
            } else if (w.kind() == FlowKind.DEBT) {
                var assets = g.board().ownedBy(w.owner()).stream().filter(o -> !o.mortgaged()).toList();
                if (!assets.isEmpty() && r < 70) {
                    t.send(at(t, w, rnd), new GameCommand.EmergencyMortgage(w.owner(), w.windowId(), assets.get(rnd.nextInt(assets.size())).tile()));
                } else {
                    t.tick(Math.max(t.now, w.window().deadline()));
                }
            } else if (w.kind() == FlowKind.MINIGAME) {
                var m = g.minigame();
                if (r < 60) {
                    t.send(at(t, w, rnd), new GameCommand.PickTooth(w.owner(), w.windowId(), m.remaining().get(rnd.nextInt(m.remaining().size()))));
                } else {
                    t.tick(Math.max(t.now, w.window().deadline()));
                }
            } else if (w.kind() == FlowKind.RESPONSE) {
                if (r < 70) {
                    t.send(at(t, w, rnd), new GameCommand.RespondCard(w.owner(), w.windowId(), rnd.nextBoolean()));
                } else {
                    t.tick(Math.max(t.now, w.window().deadline()));
                }
            } else if (g.player(cur).orElseThrow().automated()) {
                long auto = g.turn().autoTaskId();
                t.tick(Math.max(t.now, auto != 0 ? t.state.timers().find(auto).orElseThrow().dueAt() : w.window().deadline()));
            } else if (r < 8) {
                t.tick(Math.max(t.now, w.window().deadline()));
            } else {
                var l = g.turn().landing();
                Command c;
                if (g.turn().stage() == TurnStage.PRE_ROLL || g.turn().stage() == TurnStage.JAIL_DECISION) {
                    c = r < 40 ? randomCard(g, cur, w.windowId(), rnd) : null;
                    if (c == null) {
                        c = new GameCommand.RollDice(cur, w.windowId());
                    }
                } else if (l == null) {
                    c = r < 60 ? randomCard(g, cur, w.windowId(), rnd) : null;
                    if (c == null) {
                        c = new GameCommand.FinishTurn(cur, w.windowId());
                    }
                } else {
                    c = switch (l.step()) {
                        case BUY -> r < 25 ? new GameCommand.StartLandAuction(cur, w.windowId())
                                : r < 60 ? new GameCommand.BuyProperty(cur, w.windowId()) : new GameCommand.DeclinePurchase(cur, w.windowId());
                        case UPGRADE -> r < 60 ? new GameCommand.UpgradeProperty(cur, w.windowId()) : new GameCommand.SkipUpgrade(cur, w.windowId());
                        case BANK -> new GameCommand.FinishBank(cur, w.windowId());
                        case EVENT -> new GameCommand.DrawEventCard(cur, w.windowId());
                        case DISCARD -> new GameCommand.DiscardCard(cur, w.windowId(), rnd.nextInt(g.player(cur).orElseThrow().hand().size()));
                        case RESPONSE -> new GameCommand.RespondCard(cur, w.windowId(), r < 70);
                        default -> new Tick();
                    };
                }
                if (c instanceof Tick) {
                    t.tick(Math.max(t.now, w.window().deadline()));
                } else {
                    t.send(at(t, w, rnd), c);
                }
            }
            if (t.session().inGame()) {
                checkViews(t);
            }
            if (step % 150 == 0) {
                assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
            }
        }
        if (t.session().inGame()) {
            t.tick(Math.max(t.now, t.game().clock().endsAt()));
        }
        for (int i = 0; i < 400 && t.session().inGame(); i++) {
            var w = t.game().flow().top().orElseThrow();
            t.tick(Math.max(t.now, w.window().deadline()));
        }
        assertFalse(t.session().inGame(), "the game drains after its global end");
    }

    /** 每个客户端只看到自己的手牌；除手牌外的公开部分完全一致；危险牙与查询结果不进视图。 */
    private static void checkViews(Table t) {
        com.millionnaire.engine.core.state.GameView reference = null;
        for (var p : t.game().players()) {
            var v = SessionDomain.INSTANCE.project(t.state, p.playerId()).game();
            assertEquals(p.hand(), v.myHand());
            var pub = new com.millionnaire.engine.core.state.GameView(v.gameNo(), v.phase(), v.players(), v.orderDraws(), v.board(),
                    v.turnNo(), v.currentPlayer(), v.stage(), v.globalEndsAt(), v.windows(), v.landing(), v.debt(), List.of(),
                    v.minigame(), v.cards());
            if (reference == null) {
                reference = pub;
            }
            assertEquals(reference, pub);
        }
    }

    private static long count(List<Event> log, java.util.function.Predicate<Event> p) {
        return log.stream().filter(p).count();
    }

    @Test
    void randomCardGamesReplayAndResumeExactly() {
        TreeMap<String, Long> all = new TreeMap<>();
        for (long seed : new long[] {7L, 77L, 777L, 7777L}) {
            Table t = start(seed);
            play(t, new SplittableRandom(seed));
            assertEquals(t.state, t.engine.rebuild(t.log), "seed " + seed + " replays exactly");
            TreeMap<String, Long> c = new TreeMap<>();
            for (CardType card : CardType.values()) {
                c.put("use." + card, count(t.log, e -> e instanceof GameEvent.CardUsed u && u.card() == card));
            }
            c.put("blocked", count(t.log, e -> e instanceof GameEvent.AttackBlocked));
            c.put("offered", count(t.log, e -> e instanceof GameEvent.ResponseOffered));
            c.put("declined", count(t.log, e -> e instanceof GameEvent.ResponseDeclined));
            c.put("waived", count(t.log, e -> e instanceof GameEvent.RentWaived));
            c.put("waiverDeclined", count(t.log, e -> e instanceof GameEvent.RentWaiverDeclined));
            c.put("query", count(t.log, e -> e instanceof GameEvent.QueryRevealed));
            c.put("forced", count(t.log, e -> e instanceof GameEvent.PropertyForceBought));
            c.put("built", count(t.log, e -> e instanceof GameEvent.PropertyBuilt));
            c.put("minigame", count(t.log, e -> e instanceof GameEvent.MinigameEnded));
            c.put("auctionLand", count(t.log, e -> e instanceof GameEvent.AuctionStarted s && s.auction().kind() == com.millionnaire.engine.core.state.AuctionState.Kind.LAND));
            c.put("auctionCard", count(t.log, e -> e instanceof GameEvent.AuctionStarted s && s.auction().kind() == com.millionnaire.engine.core.state.AuctionState.Kind.CARD));
            c.put("auctionSold", count(t.log, e -> e instanceof GameEvent.AuctionSettled));
            c.put("auctionPassed", count(t.log, e -> e instanceof GameEvent.AuctionPassed));
            c.put("bids", count(t.log, e -> e instanceof GameEvent.BidPlaced));
            c.put("rejected", count(t.log, e -> e instanceof com.millionnaire.engine.core.event.KernelEvent.InputRejected));
            System.out.println("CARD_COVERAGE seed=" + seed + " events=" + t.log.size() + " " + c);
            c.forEach((k, v) -> all.merge(k, v, Long::sum));
        }
        for (String path : List.of("use.ROADBLOCK", "use.QUERY", "use.FIXED_MOVE", "use.RENT_WAIVER", "query", "waived", "waiverDeclined",
                "minigame", "auctionLand", "auctionSold", "bids")) {
            assertTrue(all.get(path) > 0, "random card games cover " + path + " " + all);
        }
    }
}
