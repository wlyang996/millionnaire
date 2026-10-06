package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.command.RoomCommand.Join;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.testkit.Table;
import org.junit.jupiter.api.Test;

/** M2 P0：产品负责人在 M1 收尾时的两项决定（open-decisions 末节）。修复前失败。 */
class M2P0DecisionsTest {

    // ------------------------------------------------------------ 1. 自动控制期间本人投骰 = 恢复并投骰

    @Test
    void anAwayPlayerRollingResumesManualControlAndRolls() {
        Table t = Table.production(1).start(2);
        String p = t.current();
        t.send(t.now + 10, new GameCommand.SetControl(1, p, ControlMode.AWAY));
        long w = t.windowId();
        StepResult r = t.send(t.window().window().opensAt() + 100, new GameCommand.RollDice(p, w));
        assertEquals(Outcome.ACCEPTED, r.outcome());
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.ControlChanged c && c.playerId().equals(p)
                && c.mode() == ControlMode.MANUAL), "manual control is restored");
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.DiceRolled d && d.playerId().equals(p) && !d.auto()),
                "and the roll is a manual roll");
        assertEquals(ControlMode.MANUAL, t.game().player(p).orElseThrow().control());
    }

    @Test
    void aHostedPlayerRollingAlsoResumes() {
        Table t = Table.production(2).start(2);
        String p = t.current();
        t.send(t.now + 10, new GameCommand.SetControl(1, p, ControlMode.HOSTED));
        assertEquals(Outcome.ACCEPTED, t.send(t.window().window().opensAt() + 100,
                new GameCommand.RollDice(p, t.windowId())).outcome());
        assertEquals(ControlMode.MANUAL, t.game().player(p).orElseThrow().control());
    }

    @Test
    void aConfirmedOfflinePlayerIsStillRejectedUntilATrustedReconnect() {
        Table t = Table.production(3).start(2);
        String p = t.current();
        t.send(t.now + 10, new GameCommand.ConnectionSuspected(1, p, 1));
        t.send(t.now + 10, new GameCommand.ConnectionConfirmed(1, p, 2));
        long w = t.windowId();
        assertEquals(RejectionCode.CONTROL_NOT_MANUAL, t.send(t.now + 10, new GameCommand.RollDice(p, w)).rejection());
        t.send(t.now + 10, new GameCommand.Reconnected(1, p, 3));
        assertEquals(Outcome.ACCEPTED, t.send(t.now + 10, new GameCommand.RollDice(p, w)).outcome());
    }

    @Test
    void aDueAutoActionStillRunsFirst() {
        Table t = Table.production(4).start(2);
        String p = t.current();
        t.send(t.now + 10, new GameCommand.SetControl(1, p, ControlMode.AWAY));
        long w = t.windowId();
        long due = t.state.timers().find(t.game().turn().autoTaskId()).orElseThrow().dueAt();
        StepResult r = t.send(due + 5, new GameCommand.RollDice(p, w));
        assertTrue(r.events().stream().anyMatch(e -> e instanceof GameEvent.DiceRolled d && d.auto()), "the auto roll fires first");
        assertEquals(Outcome.REJECTED, r.outcome(), "the late manual roll then hits a closed window");
        assertEquals(ControlMode.AWAY, t.game().player(p).orElseThrow().control(), "a rejected roll does not resume");
    }

    // ------------------------------------------------------------ 2. 昵称

    private static RejectionCode join(String nickname) {
        Table t = Table.production(5);
        t.send(new Join("p1", "Host"));
        StepResult r = t.send(new Join("p2", nickname));
        return r.rejection();
    }

    @Test
    void zeroWidthBidiAndInvisibleNicknamesAreRejected() {
        for (String bad : new String[] {"​", "a​b", "a‌b", "a‍b", "a⁠b", "a﻿b",
                "a‮b", "a‪b", "a⁦b", "a⁩b", "a‎b", "a‏b", "ㅤ", "​ ​",
                "‍😀", "😀‍", "   "}) {
            assertEquals(RejectionCode.INVALID_NICKNAME, join(bad), "should reject " + escape(bad));
        }
    }

    @Test
    void emojiJoinSequencesAndOrdinaryNamesAreAccepted() {
        for (String ok : new String[] {"Alice", "小明", "A😀",
                "👨‍👩‍👧",          // 家庭（ZWJ 序列）
                "🏳️‍🌈",                        // 彩虹旗（含 VS16）
                "👩🏽‍💻",                  // 带肤色的程序员
                "Bob 👍🏽"}) {
            assertEquals(null, join(ok), "should accept " + escape(ok));
        }
    }

    @Test
    void surroundingWhitespaceIsTrimmedAndLengthIsCountedAfterTrimming() {
        Table t = Table.production(6);
        t.send(new Join("p1", "  Bob  "));
        assertEquals("Bob", t.session().lobby().members().get(0).nickname());
        String max = "x".repeat(LobbyModule.MAX_NICKNAME_LENGTH);
        assertEquals(null, join("  " + max + "  "), "trimmed length is what counts");
        assertEquals(RejectionCode.INVALID_NICKNAME, join(max + "y"));
        assertFalse(LobbyModule.validNickname(" Bob"), "a stored nickname is always already trimmed");
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> b.append(String.format("U+%04X ", cp)));
        return b.toString().trim();
    }
}
