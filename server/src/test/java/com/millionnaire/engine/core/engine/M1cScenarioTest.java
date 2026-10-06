package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.OrderDraw;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 评审"确定性与测试质量"小节要求补强的场景。 */
class M1cScenarioTest {

    @Test
    void twoTieGroupsAtOnceAreResolvedHighestGroupFirstAndOnlyWithinEachGroup() {
        // 首抽 70/70/30/30：组 [p1,p2]（70）先重抽 10/20，组 [p3,p4]（30）再重抽 5/6
        ScriptedRandom r = new ScriptedRandom(script(order(70, 70, 30, 30), order(10, 20, 5, 6), Table.deal(4)));
        Table t = new Table(r, 1).start(4);
        assertEquals(List.of("p2", "p1", "p4", "p3"), t.game().players().stream().map(PlayerState::playerId).toList());
        assertEquals(List.of(new OrderDraw("p1", List.of(70, 10)), new OrderDraw("p2", List.of(70, 20)),
                new OrderDraw("p3", List.of(30, 5)), new OrderDraw("p4", List.of(30, 6))), t.game().orderDraws());
        r.assertExhausted(t.state);
    }

    @Test
    void threeConsecutiveJailTimeoutsReleaseOnTheThirdAndMoveImmediately() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 1, 6, 1),
                dice(DrawPoint.JAIL_DIE, 1), dice(DrawPoint.MOVE_DIE, 1), dice(DrawPoint.JAIL_DIE, 3),
                dice(DrawPoint.MOVE_DIE, 1), dice(DrawPoint.JAIL_DIE, 5), dice(DrawPoint.MOVE_DIE, 2))), 1).start(2);
        for (int i = 0; i < 4; i++) {
            t.roll();
        }
        t.tick(t.window().window().deadline());            // 超时：判定 1，失败 1
        assertEquals(1, t.game().player("p1").orElseThrow().jailFailures());
        t.roll();
        t.tick(t.window().window().deadline());            // 超时：判定 3，失败 2
        assertEquals(2, t.game().player("p1").orElseThrow().jailFailures());
        t.roll();
        long deadline = t.window().window().deadline();
        t.tick(deadline);                                   // 超时：判定 5，第三次失败 → 释放，剩余 0 → 立即移动 4
        assertFalse(t.game().player("p1").orElseThrow().inJail());
        assertEquals(10, t.position("p1"));
        assertEquals("p2", t.current());
        assertEquals(deadline + 1500 + 1500 + 2 * 250, t.window().window().opensAt());
        assertTrue(t.log.stream().anyMatch(e -> e instanceof GameEvent.JailReleased j && j.reason() == GameEvent.ReleaseReason.THIRD_FAILURE));
    }

    @Test
    void severalModeSwitchesInsideOneWindowLeaveExactlyOneAutoAction() {
        Table t = Table.production(11).start(2);
        String p = t.current();
        t.send(new GameCommand.SetControl(1, p, ControlMode.AWAY));
        long first = t.game().turn().autoTaskId();
        t.send(new GameCommand.ResumeControl(p, 1));
        t.send(new GameCommand.SetControl(1, p, ControlMode.HOSTED));
        t.send(new GameCommand.SetControl(1, p, ControlMode.AWAY));
        long last = t.game().turn().autoTaskId();
        assertTrue(first != last && t.state.timers().find(first).isEmpty(), "older auto tasks were cancelled");
        long due = t.state.timers().find(last).orElseThrow().dueAt();
        long autosBefore = t.log.stream().filter(e -> e instanceof GameEvent.DiceRolled d && d.auto()).count();
        t.tick(due);
        assertEquals(autosBefore + 1, t.log.stream().filter(e -> e instanceof GameEvent.DiceRolled d && d.auto()).count());
        assertTrue(t.log.stream().anyMatch(e -> e instanceof GameEvent.WindowClosed w && w.reason() == CloseReason.ACTED),
                "an AUTO_ACT roll closes the window as acted, not expired");
    }

    @Test
    void overlayPausedDuringTheAnimationBufferKeepsBufferAndFullWindow() {
        // M2c：买 / 升级等落点决策窗口不可被覆盖流程抢占（N3），因此落在事件格 2，让下一位的投骰窗口带动画缓冲
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2))), 1).start(2);
        t.rollOnly();                                           // 下一个窗口（落点决策或下一位投骰）带动画缓冲
        FlowFrame next = t.window();
        long pauseAt = t.now + 1;
        assertTrue(pauseAt < next.window().opensAt(), "pause inside the buffer");
        t.send(pauseAt, new com.millionnaire.engine.core.command.Tick());
        long leadLeft = next.window().opensAt() - pauseAt;
        DecisionContext<SessionState> c = new DecisionContext<>(t.state, new Evolver<>(SessionDomain.INSTANCE, t.config),
                SessionState.class, XoshiroLemireV1.INSTANCE, t.config);
        GameModule.openOverlay(c, FlowKind.ATTACK, c.state().game().turn().currentPlayer(), 0, 10_000, "x");
        t.engine.validate(c.engineState());
        t.state = c.engineState();
        long closeAt = t.window().window().deadline();
        long originalDeadline = next.window().deadline();
        t.tick(closeAt);
        FlowFrame resumed = t.window();
        assertEquals(closeAt + leadLeft, resumed.window().opensAt(), "the unused buffer is kept");
        assertEquals(15_000, resumed.window().deadline() - resumed.window().opensAt(), "the full roll time is kept");
        assertTrue(resumed.window().deadline() > originalDeadline, "the window crosses its original deadline without expiring");
    }

    @Test
    void capacityBoundariesAreEnforcedAndRejectedChangesKeepTheState() {
        Table eight = Table.production(13);
        eight.send(new RoomCommand.Join("p1", "P1"));
        RoomSettings fifty = new RoomSettings(RuleConfigs.BOARD_50, 3000, EndMode.TIME_LIMIT, 30, 15);
        eight.send(new RoomCommand.ChangeSettings("p1", fifty));
        for (int i = 2; i <= 8; i++) {
            eight.send(new RoomCommand.Join("p" + i, "P" + i));
        }
        assertEquals(RejectionCode.ROOM_FULL, eight.send(new RoomCommand.Join("p9", "P9")).rejection(), "eight-player lobby is full");
        Table five = Table.production(14);
        five.send(new RoomCommand.Join("p1", "P1"));
        five.send(new RoomCommand.ChangeSettings("p1", fifty));
        for (int i = 2; i <= 5; i++) {
            five.send(new RoomCommand.Join("p" + i, "P" + i));
        }
        five.send(new RoomCommand.SetReady("p2", true));
        var before = five.state.domain();
        RoomSettings thirty = new RoomSettings(RuleConfigs.BOARD_30, 3000, EndMode.TIME_LIMIT, 30, 15);
        assertEquals(RejectionCode.CAPACITY_EXCEEDED, five.send(new RoomCommand.ChangeSettings("p1", thirty)).rejection());
        assertEquals(before, five.state.domain(), "a rejected map change keeps settings and ready flags");
    }
}
