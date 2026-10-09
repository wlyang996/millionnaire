package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.EventKind;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 起点三选一（用户 2026-10-08）：停在起点开窗口，选一张得现金（60%，200～1000 步长 100）或道具（40%）。
 * 30 格正式棋盘：p1 依次掷 5、6、5、6、6、2 停在 5 / 11 / 16 / 22 / 28 / 0，p2 掷 1、3、4、2、3 停在 1 / 4 / 8 / 10 / 13，途中只有买地窗口（都放弃）。
 */
class StartPickTest {
    private static final RuleConfig RULES = RuleConfigs.defaultV1();

    private static Table reachStart(List<ScriptedRandom.Step> after) {
        List<ScriptedRandom.Step> all = new ArrayList<>(Table.script(Table.order(90, 10), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 5, 1, 6, 3, 5, 4, 6, 2, 6, 3, 2)));
        all.addAll(after);
        Table t = new Table(RULES, new ScriptedRandom(all), 1).start(2, RuleConfigs.BOARD_30, EndMode.TIME_LIMIT, 30);
        for (int i = 0; i < 10; i++) {
            String who = t.current();
            t.rollOnly();
            while (who.equals(t.current()) && t.game().turn().landing() != null) {
                t.pass();
            }
        }
        long before = t.cash("p1");
        t.rollOnly();
        assertEquals(0, t.position("p1"));
        assertEquals(LandingStep.START_PICK, t.game().turn().landing().step(), "landing on start opens the pick");
        assertEquals(before + RULES.economy().startReward(), t.cash("p1"), "the start reward is paid as usual");
        return t;
    }

    @Test
    void pickingCashPaysTheDrawnAmount() {
        // 种类 10 < 60 → 现金；金额序号 3 → 200 + 300 = 500
        Table t = reachStart(Table.script(Table.steps(DrawPoint.START_PICK_KIND, 100, 10), Table.steps(DrawPoint.START_PICK_CASH, 9, 3)));
        long before = t.cash("p1");
        t.act(w -> new GameCommand.PickStartCard("p1", w, 2));
        assertEquals(before + 500, t.cash("p1"));
        var e = t.log.stream().filter(GameEvent.StartPickDrawn.class::isInstance).map(GameEvent.StartPickDrawn.class::cast).toList();
        assertEquals(1, e.size());
        assertEquals(2, e.get(0).index());
        assertEquals(EventKind.CASH_REWARD, e.get(0).kind());
        assertEquals("p2", t.current());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test
    void pickingACardAddsOneToTheHand() {
        // 种类 70 ≥ 60 → 道具；道具按事件得道具的概率抽，0 → 路障
        Table t = reachStart(Table.script(Table.steps(DrawPoint.START_PICK_KIND, 100, 70), Table.steps(DrawPoint.EVENT_CARD, 1000, 0)));
        int hand = t.game().player("p1").orElseThrow().hand().size();
        t.act(w -> new GameCommand.PickStartCard("p1", w, 0));
        assertEquals(hand + 1, t.game().player("p1").orElseThrow().hand().size());
        assertEquals(CardType.ROADBLOCK, t.game().player("p1").orElseThrow().hand().get(hand));
        assertEquals("p2", t.current());
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void outOfRangeIndexIsRejectedAndTimeoutPicksForThePlayer() {
        Table t = reachStart(Table.script(Table.steps(DrawPoint.START_PICK_KIND, 100, 0), Table.steps(DrawPoint.START_PICK_CASH, 9, 0)));
        assertNotNull(t.act(w -> new GameCommand.PickStartCard("p1", w, 3)).rejection());
        long before = t.cash("p1");
        t.tick(t.window().window().deadline());
        assertEquals(before + 200, t.cash("p1"));
        assertTrue(t.log.stream().anyMatch(e -> e instanceof GameEvent.StartPickDrawn s && s.auto()));
    }
}
