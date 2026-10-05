package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand.ChangeSettings;
import com.millionnaire.engine.core.command.RoomCommand.Join;
import com.millionnaire.engine.core.command.RoomCommand.Kick;
import com.millionnaire.engine.core.command.RoomCommand.Leave;
import com.millionnaire.engine.core.command.RoomCommand.SetReady;
import com.millionnaire.engine.core.command.SessionCommand.EndGame;
import com.millionnaire.engine.core.command.SessionCommand.StartGame;
import com.millionnaire.engine.core.engine.StepResult.Outcome;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.RoomStatus;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.SessionView;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionDomainTest {
    private final Engine<SessionState> engine = new Engine<>(RuleConfigs.defaultV1(), SessionDomain.INSTANCE);
    private EngineState state;
    private long seq;

    @BeforeEach
    void setUp() {
        state = engine.create("room-9", 7, 0).state();
        seq = 0;
    }

    private StepResult send(Command c) {
        StepResult r = engine.step(state, new Input(++seq, seq * 10, c));
        state = r.state();
        return r;
    }

    private SessionState session() {
        return (SessionState) state.domain();
    }

    private RoomState room() {
        return session().lobby();
    }

    private void join(String... players) {
        for (String p : players) {
            assertEquals(Outcome.ACCEPTED, send(new Join(p, p.toUpperCase())).outcome());
        }
    }

    private void readyAll() {
        room().members().stream().filter(m -> !m.ready()).map(Member::playerId).toList()
                .forEach(p -> send(new SetReady(p, true)));
    }

    // ------------------------------------------------------------ 大厅

    @Test
    void hostTransfersToEarliestJoinerAndEmptyRoomCloses() {
        join("a", "b", "c");
        assertEquals("a", room().hostId());
        send(new Leave("a"));
        assertEquals("b", room().hostId());
        send(new Leave("c"));
        send(new Leave("b"));
        assertEquals(RoomStatus.CLOSED, room().status());
        assertNull(room().hostId());
        assertEquals(RejectionCode.ROOM_CLOSED, send(new Join("d", "D")).rejection());
        assertEquals(RejectionCode.ROOM_CLOSED, send(new StartGame("d")).rejection());
    }

    @Test
    void settingsChangeResetsReadyAndRespectsCapacity() {
        join("a", "b", "c", "d");
        send(new SetReady("b", true));
        send(new SetReady("c", true));
        RoomSettings big = new RoomSettings(RuleConfigs.BOARD_50, 5000, EndMode.BANKRUPTCY, 30, 30);
        send(new ChangeSettings("a", big));
        assertTrue(room().members().stream().noneMatch(Member::ready));
        join("e");
        RoomSettings small = new RoomSettings(RuleConfigs.BOARD_30, 2000, EndMode.TIME_LIMIT, 15, 15);
        assertEquals(RejectionCode.CAPACITY_EXCEEDED, send(new ChangeSettings("a", small)).rejection());
        RoomSettings invalid = new RoomSettings(RuleConfigs.BOARD_50, 2500, EndMode.TIME_LIMIT, 15, 15);
        assertEquals(RejectionCode.INVALID_SETTINGS, send(new ChangeSettings("a", invalid)).rejection());
        assertEquals(RejectionCode.UNCHANGED, send(new ChangeSettings("a", big)).rejection());
        assertEquals(RejectionCode.UNCHANGED, send(new SetReady("b", false)).rejection());
    }

    @Test
    void joinRules() {
        join("a", "b", "c", "d");
        assertEquals(RejectionCode.ROOM_FULL, send(new Join("e", "E")).rejection(), "30-tile map holds 4");
        assertEquals(RejectionCode.ALREADY_MEMBER, send(new Join("a", "A")).rejection());
        send(new Leave("d"));
        assertEquals(RejectionCode.INVALID_NICKNAME, send(new Join("e", " ")).rejection());
        assertEquals(RejectionCode.INVALID_NICKNAME, send(new Join("e", "x\ny")).rejection(), "control char");
        assertEquals(Outcome.ACCEPTED, send(new Join("e", "A😀")).outcome(), "emoji and repeated nicknames ok");
        assertEquals(RejectionCode.NOT_HOST, send(new Kick("b", "e")).rejection());
        assertEquals(RejectionCode.CANNOT_KICK_SELF, send(new Kick("a", "a")).rejection());
        assertEquals(Outcome.ACCEPTED, send(new Kick("a", "e")).outcome());
    }

    // ------------------------------------------------------------ 开局 / 回房边界

    @Test
    void startRequiresHostEnoughPlayersAndEveryoneReady() {
        join("a");
        assertEquals(RejectionCode.NOT_ENOUGH_PLAYERS, send(new StartGame("a")).rejection());
        join("b");
        send(new SetReady("a", true));
        assertEquals(RejectionCode.NOT_ALL_READY, send(new StartGame("a")).rejection());
        send(new SetReady("b", true));
        assertEquals(RejectionCode.NOT_HOST, send(new StartGame("b")).rejection());
        StepResult started = send(new StartGame("a"));
        assertEquals(Outcome.ACCEPTED, started.outcome());
        GameEvent.GameStarted ev = started.events().stream().filter(e -> e instanceof GameEvent.GameStarted)
                .map(e -> (GameEvent.GameStarted) e).findFirst().orElseThrow();
        assertEquals(2, started.events().stream().filter(e -> e instanceof KernelEvent.RandomDrawn d
                && d.point() == com.millionnaire.engine.random.DrawPoint.ORDER_NUMBER).count(), "one order draw per seat (no tie)");
        assertEquals(4, started.events().stream().filter(e -> e instanceof KernelEvent.RandomDrawn d
                && d.point() == com.millionnaire.engine.random.DrawPoint.INITIAL_CARD).count(), "two cards per player");
        assertEquals(List.of("a", "b"), ev.seats());
        GameState g = session().game();
        assertEquals(1, g.gameNo());
        assertEquals(state.now(), g.startedAt());
        assertEquals(room().settings(), g.settings());
    }

    @Test
    void lobbyIsFrozenDuringGameAndRestoredAfterwards() {
        join("a", "b");
        readyAll();
        send(new StartGame("a"));
        for (Command c : List.of(new Join("c", "C"), new Leave("b"), new Kick("a", "b"), new SetReady("a", false),
                new ChangeSettings("a", new RoomSettings(RuleConfigs.BOARD_50, 5000, EndMode.TIME_LIMIT, 30, 15)),
                new StartGame("a"))) {
            assertEquals(RejectionCode.GAME_IN_PROGRESS, send(c).rejection(), c.toString());
        }
        assertEquals(RejectionCode.INVALID_ARGUMENT, send(new EndGame(1, " ")).rejection());
        assertEquals(Outcome.ACCEPTED, send(new EndGame(1, "admin-abort")).outcome());
        assertFalse(session().inGame());
        assertEquals(1, session().gamesPlayed());
        assertTrue(room().members().stream().noneMatch(Member::ready), "everyone re-readies after a game");
        assertEquals(List.of("a", "b"), room().members().stream().map(Member::playerId).toList(), "room kept");
        assertEquals(RejectionCode.NOT_IN_GAME, send(new EndGame(1, "again")).rejection());
        readyAll();
        send(new StartGame("a"));
        assertEquals(2, session().game().gameNo());
    }

    @Test
    void seqRandomStateAndPersistenceChainAreContinuousAcrossTheBoundary() {
        join("a", "b");
        readyAll();
        EngineState beforeStart = state;
        send(new StartGame("a"));
        EngineState restored = engine.restore(engine.snapshot(state));
        assertEquals(state, restored);
        assertNotEquals(beforeStart.rng(), state.rng(), "R1 placeholder draws advanced the shared RNG chain");
        assertEquals(beforeStart.lastSeq() + 1, state.lastSeq());
        state = restored;
        send(new EndGame(1, "done"));
        assertEquals(beforeStart.lastSeq() + 2, state.lastSeq());
        assertEquals(SessionDomain.ID, state.domainId());
    }

    // ------------------------------------------------------------ 视图与恢复校验

    @Test
    void viewOmitsKernelState() {
        join("a", "b");
        SessionView v = SessionDomain.INSTANCE.project(state, "a");
        assertEquals("room-9", v.roomId());
        assertEquals(room().members(), v.members());
        List<String> fields = Arrays.stream(SessionView.class.getRecordComponents()).map(c -> c.getName()).toList();
        assertFalse(fields.contains("rng") || fields.contains("timers") || fields.contains("lastSeq"));
    }

    @Test
    void restoreRejectsInconsistentSessionStates() {
        join("a", "b");
        RoomState r = room();
        assertEquals(state, engine.restore(engine.snapshot(state)));
        assertInvalid(new SessionState(new RoomState(r.status(), "zz", r.members(), r.settings()), null, 0, 1, null));
        assertInvalid(new SessionState(new RoomState(r.status(), "a", List.of(r.members().get(0), r.members().get(0)),
                r.settings()), null, 0, 1, null));
        assertInvalid(new SessionState(new RoomState(RoomStatus.CLOSED, "a", r.members(), r.settings()), null, 0, 1, null));
        assertInvalid(new SessionState(new RoomState(r.status(), "a", r.members(),
                new RoomSettings("nope", 3000, EndMode.TIME_LIMIT, 30, 15)), null, 0, 1, null));
        // 以真实开局为基准构造非法对局
        readyAll();
        send(new StartGame("a"));
        EngineState started = state;
        SessionState ss = session();
        GameState g = ss.game();
        assertEquals(started, engine.restore(engine.snapshot(started)), "the real table order (by draws) is valid");
        assertInvalid(ss.withGame(new GameState(5, g.startedAt(), g.settings(), g.phase(), g.players(), g.orderDraws(),
                g.board(), g.turn(), g.flow(), g.ledger(), g.clock())), "game number");
        assertInvalid(ss.withGame(g.withPlayers(List.of(g.players().get(0), PlayerState.seated("zz")))), "seat not a member");
        assertInvalid(ss.withGame(g.withPlayers(List.of(g.players().get(0)))), "not enough seats");
        assertInvalid(ss.withGame(g.withPlayers(List.of(g.players().get(1), g.players().get(0)))), "order must follow the draws");
        state = started;
        EngineState withTask = state.withTimers(state.timers().schedule(
                new ScheduledTask(state.nextTaskId(), 9_999_999, TaskKind.FLOW, 0)), state.nextTaskId() + 1);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(withTask)));
        EngineState future = state.withNow(state.lastReceivedAt() + 1);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(future)));
        EngineState badCursor = state.withInputCursor(state.eventCount(), state.lastReceivedAt(), state.lastInputDigest());
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(badCursor)));
    }

    private void assertInvalid(SessionState s) {
        assertInvalid(s, "");
    }

    private void assertInvalid(SessionState s, String why) {
        EngineState bad = state.withDomain(s);
        assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(bad)), why);
        assertThrows(StateValidationException.class, () -> engine.step(bad, new Input(seq + 1, 99_999, new com.millionnaire.engine.core.command.Tick())),
                "step entry validation: " + why);
    }
}
