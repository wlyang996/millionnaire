package com.millionnaire.engine.core.engine;

import static com.millionnaire.engine.testkit.Table.dice;
import static com.millionnaire.engine.testkit.Table.order;
import static com.millionnaire.engine.testkit.Table.script;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.ScriptedRandom;
import com.millionnaire.engine.testkit.Table;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** 第 10 轮评审 T1–T10 的反例回归（修复前失败，见 m1c-report）。 */
class Round10ReviewTest {

    // ------------------------------------------------------------ T3：事件重建核对规则派生值

    /** 起点奖励 + 监狱 + 付费出狱的脚本局：p1 先 6×5 落起点，再 2+6 进狱，付费出狱。 */
    private static Table rewardJailBail() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2),
                dice(DrawPoint.MOVE_DIE, 3, 1, 6, 1, 6, 1, 6, 1, 6, 1, 6, 1, 2, 1, 5, 1))), 1).start(2);
        // p1: 3,6,6,6,6 → 27；第 6 次 6 → 33 → 3（越过起点）；再 2 → 5，再 5 → 10（不是监狱）
        return t;
    }

    private static List<Event> tamper(List<Event> log, UnaryOperator<Event> f) {
        return log.stream().map(f).toList();
    }

    @Test
    void t3TamperedMoveIsRejected() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 3))), 1).start(2);
        t.roll();
        assertDoesNotThrow(() -> t.engine.rebuild(t.log));
        List<Event> moved = tamper(t.log, e -> e instanceof GameEvent.PlayerMoved m
                ? new GameEvent.PlayerMoved(m.playerId(), m.from(), 4, 99) : e);
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(moved));
        List<Event> landed = tamper(t.log, e -> e instanceof GameEvent.Landed l
                ? new GameEvent.Landed(l.playerId(), 5, l.type(), l.placeholder()) : e);
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(landed));
        List<Event> bound = tamper(t.log, e -> e instanceof KernelEvent.RandomDrawn d && d.point() == DrawPoint.MOVE_DIE
                ? new KernelEvent.RandomDrawn(d.protocol(), d.point(), 7, d.value(), d.after()) : e);
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(bound));
    }

    @Test
    void t3TamperedStartRewardIsRejected() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 6, 1, 6, 1, 6, 1, 6, 1, 6))), 1)
                .start(2);
        for (int i = 0; i < 9; i++) {
            t.roll();
        }
        assertDoesNotThrow(() -> t.engine.rebuild(t.log));
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(tamper(t.log, e -> e instanceof GameEvent.StartRewardPaid r
                ? new GameEvent.StartRewardPaid(r.playerId(), 999, r.turnNo()) : e)));
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(tamper(t.log, e -> e instanceof GameEvent.StartRewardPaid r
                ? new GameEvent.StartRewardPaid(r.playerId(), r.amount(), r.turnNo() + 2) : e)));
    }

    @Test
    void t3TamperedBailAndReleaseReasonAreRejected() {
        Table t = new Table(new ScriptedRandom(script(order(90, 10), Table.deal(2), dice(DrawPoint.MOVE_DIE, 2, 1, 6, 1, 5))), 1).start(2);
        for (int i = 0; i < 4; i++) {
            t.roll();
        }
        t.send(t.window().window().opensAt() + 100,
                new com.millionnaire.engine.core.command.GameCommand.PayBail("p1", t.windowId()));
        t.roll();
        assertDoesNotThrow(() -> t.engine.rebuild(t.log));
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(tamper(t.log, e -> e instanceof GameEvent.BailPaid b
                ? new GameEvent.BailPaid(b.playerId(), 1) : e)));
        assertThrows(StateValidationException.class, () -> t.engine.rebuild(tamper(t.log, e -> e instanceof GameEvent.JailReleased r
                ? new GameEvent.JailReleased(r.playerId(), GameEvent.ReleaseReason.EVEN_ROLL) : e)));
        assertDoesNotThrow(() -> rewardJailBail());
    }
}
