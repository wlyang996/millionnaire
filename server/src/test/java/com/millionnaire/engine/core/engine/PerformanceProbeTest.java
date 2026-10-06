package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.ledger.TrustedLedgers;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 性能探针（只测量、只打印，不对耗时做断言；数字随机器负载波动，报告如实记录一次运行的结果）。账本预置 N 笔分录，测：
 * <ul>
 *   <li>空 Tick（无资金变化，账本对象原样命中可信缓存——这是最好情况，不代表经济步）；</li>
 *   <li>真实记账步：买地（BuyProperty）、缴租（投骰落在他人地产上，含 RentPaid）、银行抵押（BankMortgage）——
 *       每步都会复制整个分录列表、按对象同一性扫描可信前缀，再增量重放新增分录；</li>
 *   <li>冷缓存：每次买地前清空可信记忆，入口校验退回完整重放；</li>
 *   <li>超过缓存容量（64）的活跃账本：66 个各自恢复的房间轮流买地，旧账本被挤出后同样退回完整重放；</li>
 *   <li>快照、状态哈希、恢复。</li>
 * </ul>
 */
class PerformanceProbeTest {
    private static final int RUNS = 15;

    private static double median(long[] nanos) {
        long[] c = nanos.clone();
        Arrays.sort(c);
        return c[c.length / 2] / 1_000_000.0;
    }

    private static double time(Runnable before, Supplier<?> action) {
        long[] ns = new long[RUNS];
        for (int warm = 0; warm < 3; warm++) {
            before.run();
            action.get();
        }
        for (int i = 0; i < RUNS; i++) {
            before.run();
            long t0 = System.nanoTime();
            action.get();
            ns[i] = System.nanoTime() - t0;
        }
        return median(ns);
    }

    /** 三人局：p1 投 1 → 1 号低价地（买 / 放弃窗口）；p2 持有 3 号地；p3 持有 4 号车站；账本补到 entries 笔。 */
    private static Table prepared(int entries, int... moves) {
        Table t = new Table(new ScriptedRandom(script(order(90, 80, 70), Table.deal(3), dice(DrawPoint.MOVE_DIE, moves))), 1)
                .start(3);
        GameState g = t.game();
        Ledger l = g.ledger();
        for (int i = l.journal().size(); i < entries; i++) {
            l = i % 2 == 0 ? l.transfer("p2", "p3", 1, "PERF", "perf-" + i) : l.transfer("p3", "p2", 1, "PERF", "perf-" + i);
        }
        GameState crafted = g.withLedger(l).withBoard(g.board().with(new OwnableState(3, "p2", 0, false, 0, null)));
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(crafted))));
        assertEquals(entries, t.game().ledger().journal().size());
        return t;
    }

    @Test
    void measureStepSnapshotAndHashAgainstLedgerSize() {
        for (int entries : new int[] {100, 1000, 10_000}) {
            // 空 Tick
            Table base = prepared(entries, 1);
            EngineState s0 = base.state;
            Input tick = new Input(base.seq + 1, base.now + 1, new Tick());
            double emptyTick = time(() -> { }, () -> base.engine.step(s0, tick));
            // 买地：先投骰进入买 / 放弃窗口，再测 BuyProperty
            base.rollOnly();
            EngineState buyState = base.state;
            Input buy = new Input(base.seq + 1, base.window().window().opensAt() + 10,
                    new GameCommand.BuyProperty("p1", base.window().windowId()));
            assertEquals(StepResult.Outcome.ACCEPTED, base.engine.step(buyState, buy).outcome());
            double buyWarm = time(() -> { }, () -> base.engine.step(buyState, buy));
            double buyCold = time(TrustedLedgers::forget, () -> base.engine.step(buyState, buy));
            // 缴租：p1 投 3 → p2 的 3 号地
            Table rentTable = prepared(entries, 3);
            EngineState rentState = rentTable.state;
            Input roll = new Input(rentTable.seq + 1, rentTable.window().window().opensAt() + 10,
                    new GameCommand.RollDice("p1", rentTable.window().windowId()));
            assertEquals(1, rentTable.engine.step(rentState, roll).events().stream().filter(e -> e instanceof GameEvent.RentPaid).count());
            double rent = time(() -> { }, () -> rentTable.engine.step(rentState, roll));
            // 快照、哈希、恢复
            String text = base.engine.snapshot(buyState);
            double snapshot = time(() -> { }, () -> base.engine.snapshot(buyState));
            double hash = time(() -> { }, () -> base.engine.stateHash(buyState));
            double restore = time(() -> { }, () -> base.engine.restore(text));
            // 超过缓存容量的活跃账本：66 个房间各自恢复（各自完整校验并进入缓存），轮流买地
            double manyRooms = Double.NaN;
            if (entries >= 1000) {
                List<EngineState> rooms = new ArrayList<>();
                for (int i = 0; i < 66; i++) {
                    rooms.add(base.engine.restore(text));
                }
                long[] ns = new long[rooms.size()];
                for (int i = 0; i < rooms.size(); i++) {
                    EngineState room = rooms.get(i);
                    long t0 = System.nanoTime();
                    base.engine.step(room, buy);
                    ns[i] = System.nanoTime() - t0;
                }
                manyRooms = median(ns);
            }
            System.out.printf("PERF entries=%d snapshotBytes=%d emptyTick=%.3fms buy=%.3fms rent=%.3fms buyColdCache=%.3fms "
                            + "buyWith66Rooms=%.3fms snapshot=%.3fms hash=%.3fms restore=%.3fms%n",
                    entries, text.length(), emptyTick, buyWarm, rent, buyCold, manyRooms, snapshot, hash, restore);
        }
    }

    @Test
    void measureBankMortgageStep() {
        Table t = prepared(10_000, 1);
        // p1 持有 1 号低价地并站在银行格前一格，投 1 → 银行
        GameState g = t.game();
        t.state = t.engine.restore(t.engine.snapshot(t.state.withDomain(t.session().withGame(
                g.withBoard(g.board().with(new OwnableState(1, "p1", 0, false, 0, null)))
                        .withPlayer(g.player("p1").orElseThrow().at(10))))));
        t.rollOnly();
        EngineState bank = t.state;
        Input mortgage = new Input(t.seq + 1, t.window().window().opensAt() + 10,
                new GameCommand.BankMortgage("p1", t.window().windowId(), 1));
        assertEquals(StepResult.Outcome.ACCEPTED, t.engine.step(bank, mortgage).outcome());
        System.out.printf("PERF entries=10000 bankMortgage=%.3fms%n", time(() -> { }, () -> t.engine.step(bank, mortgage)));
    }
}
