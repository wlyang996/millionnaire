package com.millionnaire.engine.config;

import com.millionnaire.engine.testkit.TestBoards;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.RandomSource;
import com.millionnaire.engine.random.RandomSourceFactory;
import com.millionnaire.engine.random.RngState;
import com.millionnaire.engine.random.XoshiroLemireV1;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * T1：校验通过的配置必须能真正开局到第一回合。用"抽取次数上限"判定死循环（不用墙钟）。
 */
class ConfigStartPropertyTest {
    /** 一局开局最多需要的抽取次数远小于此上限；超过即视为不终止。 */
    private static final int DRAW_CAP = 10_000;

    /** 计数包装：超过上限抛异常，用于发现开局死循环。 */
    static final class CappedRandom implements RandomSourceFactory {
        private int draws;

        @Override
        public String protocolId() {
            return XoshiroLemireV1.PROTOCOL_ID;
        }

        @Override
        public RngState seed(long seed) {
            return XoshiroLemireV1.INSTANCE.seed(seed);
        }

        @Override
        public RandomSource open(RngState state) {
            RandomSource inner = XoshiroLemireV1.INSTANCE.open(state);
            return new RandomSource() {
                @Override
                public int nextInt(DrawPoint point, int bound) {
                    if (++draws > DRAW_CAP) {
                        throw new IllegalStateException("draw cap exceeded: start never terminates");
                    }
                    return inner.nextInt(point, bound);
                }

                @Override
                public RngState state() {
                    return inner.state();
                }
            };
        }
    }

    private static RuleConfig withEconomy(RuleConfig b, EconomyConfig e) {
        return new RuleConfig(b.ruleVersion(), b.boards(), b.tiers(), b.station(), e, b.ratios(), b.cardWeights(),
                b.eventWeights(), b.timing(), b.room());
    }

    private static EconomyConfig orderMax(EconomyConfig e, int max) {
        return new EconomyConfig(e.startReward(), e.miniGameWinReward(), e.bailCost(), e.eventCashMin(), e.eventCashMax(),
                e.eventCashStep(), e.eventMoveMinSteps(), e.eventMoveMaxSteps(), e.dieFaces(), e.maxLevel(), e.handLimit(),
                e.initialHandSize(), max, e.offerUnaffordablePurchase(), e.upgradeAfterPurchase(), e.cardsEnabled());
    }

    @Test
    void orderNumberMaxOfOneIsRejected() {
        RuleConfig b = TestBoards.legacyV1();
        List<String> errors = ConfigValidator.validate(withEconomy(b, orderMax(b.economy(), 1)));
        assertFalse(errors.isEmpty(), "orderNumberMax = 1 can never separate two players");
        assertTrue(errors.stream().anyMatch(x -> x.contains("orderNumberMax")), errors.toString());
    }

    @Test
    void everyValidatedConfigActuallyStartsAGame() {
        SplittableRandom rnd = new SplittableRandom(1005);
        RuleConfig b = TestBoards.legacyV1();
        int started = 0;
        for (int i = 0; i < 60; i++) {
            EconomyConfig e = b.economy();
            EconomyConfig m = new EconomyConfig(e.startReward(), e.miniGameWinReward(), e.bailCost(), e.eventCashMin(),
                    e.eventCashMax(), e.eventCashStep(), e.eventMoveMinSteps(), e.eventMoveMaxSteps(),
                    1 + rnd.nextInt(8), e.maxLevel(), 2 + rnd.nextInt(8), 1 + rnd.nextInt(2),
                    rnd.nextInt(4) == 0 ? 100 : 1 + rnd.nextInt(120), e.offerUnaffordablePurchase(), e.upgradeAfterPurchase(), e.cardsEnabled());
            RuleConfig c = withEconomy(b, m);
            if (!ConfigValidator.validate(c).isEmpty()) {
                continue;
            }
            Engine<SessionState> engine = new Engine<>(c, SessionDomain.INSTANCE, new CappedRandom());
            EngineState s = engine.create("r", i, 0).state();
            List<Command> cs = new ArrayList<>();
            int players = 2 + rnd.nextInt(3);
            for (int p = 1; p <= players; p++) {
                cs.add(new RoomCommand.Join("p" + p, "P" + p));
            }
            for (int p = 1; p <= players; p++) {
                cs.add(new RoomCommand.SetReady("p" + p, true));
            }
            cs.add(new SessionCommand.StartGame("p1"));
            long n = 0;
            for (Command cmd : cs) {
                n++;
                EngineState cur = s;
                Input in = new Input(n, n * 10, cmd);
                s = assertDoesNotThrow(() -> engine.step(cur, in).state());
            }
            assertEquals(1, ((SessionState) s.domain()).game().turn().turnNo(), "reached the first turn");
            started++;
        }
        assertTrue(started > 5, "some mutated configs must pass validation: " + started);
    }
}
