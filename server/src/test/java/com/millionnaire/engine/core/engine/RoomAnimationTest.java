package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.command.*;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.testkit.*;
import org.junit.jupiter.api.Test;

class RoomAnimationTest {
    private Table lobby(boolean enabled, int percent) {
        var c = TestBoards.legacyV1(); var r = c.room();
        var config = new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(), c.economy(), c.ratios(),
                c.cardWeights(), c.eventWeights(), c.timing().withPresentation(3701, 2301, 1801, 2601),
                new RoomOptions(r.minPlayersToStart(), r.initialCashOptions(), r.defaultBoardId(), r.defaultInitialCash(),
                        r.defaultEndMode(), r.defaultTimeLimitMinutes(), r.defaultRollSeconds(), enabled, false, percent),
                c.rentInflation(), c.setBonus(), c.startPick());
        var t = new Table(config, new ScriptedRandom(Table.script(Table.order(90, 10), Table.deal(2),
                Table.dice(DrawPoint.MOVE_DIE, 2), Table.steps(DrawPoint.EVENT_KIND, 100, 0),
                Table.steps(DrawPoint.EVENT_CASH, 9, 0))), 1);
        t.send(new RoomCommand.Join("p1", "P1")); t.send(new RoomCommand.Join("p2", "P2"));
        return t;
    }

    @Test void hostChoosesBeforeStartAndTheGameLocksTheSharedMode() {
        var t = lobby(true, 50);
        var fast = new RoomSettings(RuleConfigs.BOARD_30, 3000, EndMode.TIME_LIMIT, 30, 15).withFastMode(true);
        assertEquals(RejectionCode.NOT_HOST, t.send(new RoomCommand.ChangeSettings("p2", fast)).rejection());
        assertNull(t.send(new RoomCommand.ChangeSettings("p1", fast)).rejection());
        assertTrue(t.session().lobby().settings().fastMode());
        t.send(new RoomCommand.SetReady("p1", true)); t.send(new RoomCommand.SetReady("p2", true));
        assertNull(t.send(new SessionCommand.StartGame("p1")).rejection());
        assertTrue(t.game().settings().fastMode());
        assertEquals(RejectionCode.GAME_IN_PROGRESS,
                t.send(new RoomCommand.ChangeSettings("p1", fast.withFastMode(false))).rejection());
        assertTrue(t.game().settings().fastMode());
        t.rollOnly();
        assertEquals(t.now + fast.animationMs(t.config, t.config.timing().animDiceMs())
                + 2 * fast.animationMs(t.config, t.config.timing().animPerStepMs()), t.window().window().opensAt());
        t.act(w -> new GameCommand.DrawEventCard("p1", w));
        assertEquals("p2", t.current());
        assertEquals(t.now + 1151, t.window().window().opensAt());
        assertEquals(15000, t.window().window().deadline() - t.window().window().opensAt());
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test void disabledModeRejectsFastAndOldSettingsUseNormalSpeed() {
        var t = lobby(false, 40);
        var normal = new RoomSettings(RuleConfigs.BOARD_30, 3000, EndMode.TIME_LIMIT, 30, 15);
        assertFalse(normal.fastMode());
        assertEquals(2301, normal.animationMs(t.config, 2301));
        assertEquals(921, normal.withFastMode(true).animationMs(t.config, 2301));
        assertEquals(RejectionCode.INVALID_SETTINGS,
                t.send(new RoomCommand.ChangeSettings("p1", normal.withFastMode(true))).rejection());
    }
}
