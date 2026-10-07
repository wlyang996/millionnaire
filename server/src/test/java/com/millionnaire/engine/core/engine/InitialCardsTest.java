package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.testkit.Table;
import com.millionnaire.engine.testkit.TestBoards;
import org.junit.jupiter.api.Test;

/** 开局道具数（房间设置，用户 2026-10-07）：正式配置默认不发；房主可选 1～6 张；旧语义（-1）沿用配置的 initialHandSize。 */
class InitialCardsTest {
    private static final RuleConfig RULES = RuleConfigs.v1(TestBoards.LEGACY_30, TestBoards.LEGACY_50, true);

    private static Table lobby(long seed) {
        Table t = new Table(RULES, XoshiroLemireV1.INSTANCE, seed);
        t.send(new RoomCommand.Join("p1", "P1"));
        t.send(new RoomCommand.Join("p2", "P2"));
        t.send(new RoomCommand.Join("p3", "P3"));
        return t;
    }

    private static void start(Table t) {
        for (String p : new String[] {"p1", "p2", "p3"}) {
            t.send(new RoomCommand.SetReady(p, true));
        }
        assertNull(t.send(new SessionCommand.StartGame("p1")).rejection());
    }

    @Test
    void productionRoomsDealNoCardsByDefault() {
        Table t = lobby(3);
        assertEquals(0, t.session().lobby().settings().initialCards());
        start(t);
        assertTrue(t.game().players().stream().allMatch(p -> p.hand().isEmpty()));
        assertEquals(t.state, t.engine.rebuild(t.log));
    }

    @Test
    void theHostChoosesHowManyCardsEachPlayerStartsWith() {
        Table t = lobby(5);
        RoomSettings four = t.session().lobby().settings().withInitialCards(4);
        assertNull(t.send(new RoomCommand.ChangeSettings("p1", four)).rejection());
        start(t);
        for (PlayerState p : t.game().players()) {
            assertEquals(4, p.hand().size(), p.playerId());
        }
        assertEquals(t.state, t.engine.rebuild(t.log));
        assertEquals(t.state, t.engine.restore(t.engine.snapshot(t.state)));
    }

    @Test
    void moreThanTheHandLimitIsRejected() {
        Table t = lobby(7);
        RoomSettings s = t.session().lobby().settings();
        assertEquals(RejectionCode.INVALID_SETTINGS, t.send(new RoomCommand.ChangeSettings("p1", s.withInitialCards(7))).rejection());
        assertNull(t.send(new RoomCommand.ChangeSettings("p1", s.withInitialCards(6))).rejection());
        assertEquals(RejectionCode.INVALID_SETTINGS, t.send(new RoomCommand.ChangeSettings("p1", s.withInitialCards(-2))).rejection());
    }

    @Test
    void legacySettingsKeepTheConfiguredHandSize() {
        Table t = lobby(9);
        t.send(new RoomCommand.ChangeSettings("p1", new RoomSettings(RuleConfigs.BOARD_30, 3000, EndMode.TIME_LIMIT, 15, 15)));
        start(t);
        for (PlayerState p : t.game().players()) {
            assertEquals(RULES.economy().initialHandSize(), p.hand().size());
        }
    }
}
