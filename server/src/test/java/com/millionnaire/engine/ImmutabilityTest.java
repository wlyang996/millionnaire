package com.millionnaire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.TierPricing;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import com.millionnaire.engine.time.TimerQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 配置与状态的集合不可变：构造后修改源集合不影响对象，getter 返回的集合不可修改（含反序列化得到的对象）。 */
class ImmutabilityTest {

    @Test
    void configCopiesItsInputsAndExposesUnmodifiableViews() {
        RuleConfig base = RuleConfigs.defaultV1();
        List<TierPricing> tiers = new ArrayList<>(base.tiers());
        Map<CardType, Integer> cards = new TreeMap<>(base.cardWeights());
        RuleConfig c = new RuleConfig(base.ruleVersion(), base.boards(), tiers, base.station(), base.economy(),
                base.ratios(), cards, base.eventWeights(), base.timing(), base.room());
        String hash = c.contentHash();
        tiers.clear();
        cards.put(CardType.QUERY, 999);
        assertEquals(hash, c.contentHash());
        assertThrows(UnsupportedOperationException.class, () -> c.tiers().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.cardWeights().put(CardType.QUERY, 1));
        assertThrows(UnsupportedOperationException.class, () -> c.boards().get(0).tiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.tiers().get(0).rents().set(0, 1L));
        assertThrows(UnsupportedOperationException.class, () -> c.timing().rollSecondsOptions().add(90));
        assertThrows(UnsupportedOperationException.class, () -> c.room().initialCashOptions().add(1L));
    }

    @Test
    void stateCollectionsAreUnmodifiableIncludingAfterRestore() {
        Engine<SessionState> engine = new Engine<>(RuleConfigs.defaultV1(), SessionDomain.INSTANCE);
        EngineState s = engine.step(engine.create("r", 1, 0).state(), new Input(1, 1, new RoomCommand.Join("a", "A"))).state();
        for (EngineState st : List.of(s, engine.restore(engine.snapshot(s)))) {
            RoomState room = ((SessionState) st.domain()).lobby();
            assertThrows(UnsupportedOperationException.class, () -> room.members().add(new Member("x", "X", false)));
            assertThrows(UnsupportedOperationException.class, () -> st.timers().tasks().clear());
            assertThrows(UnsupportedOperationException.class, () -> st.pendingDraws().clear());
        }
        List<Member> source = new ArrayList<>(List.of(new Member("a", "A", false)));
        RoomState lobby = ((SessionState) s.domain()).lobby();
        RoomState r = new RoomState(lobby.status(), "a", source, lobby.settings());
        source.clear();
        assertEquals(1, r.members().size());
    }

    @Test
    void timerQueueAndLedgerAreUnmodifiable() {
        List<ScheduledTask> tasks = new ArrayList<>(List.of(new ScheduledTask(1, 10, TaskKind.FLOW, 0)));
        TimerQueue q = new TimerQueue(tasks);
        tasks.clear();
        assertEquals(1, q.tasks().size());
        assertThrows(UnsupportedOperationException.class, () -> q.tasks().clear());
        Ledger l = Ledger.open(new TreeMap<>(Map.of("a", 10L)));
        assertThrows(UnsupportedOperationException.class, () -> l.cash().put("a", 1_000L));
        assertThrows(UnsupportedOperationException.class, () -> l.journal().clear());
    }
}
