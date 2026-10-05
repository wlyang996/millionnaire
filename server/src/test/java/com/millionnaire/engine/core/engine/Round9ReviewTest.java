package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.EngineVersion;
import com.millionnaire.engine.config.CardType;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand;
import com.millionnaire.engine.core.command.SessionCommand;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.event.Visibility;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.SessionView;
import com.millionnaire.engine.replay.RunResult;
import com.millionnaire.engine.replay.Scenario;
import com.millionnaire.engine.replay.ScenarioRunner;
import com.millionnaire.engine.serialize.Codec;
import com.millionnaire.engine.testkit.DemoCommand;
import com.millionnaire.engine.testkit.DemoDomain;
import com.millionnaire.engine.testkit.DemoEvent;
import com.millionnaire.engine.testkit.DemoState;
import com.millionnaire.engine.testkit.RogueDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 第 9 轮评审 J1–J6 与故障停房的回归（J1/J2/J3/J5/J6 的反例在修复前全部失败，见 m1a-report）。 */
class Round9ReviewTest {
    private final RuleConfig base = RuleConfigs.defaultV1();
    private final Engine<SessionState> engine = new Engine<>(base, SessionDomain.INSTANCE);

    static List<Input> threePlayersStart(long firstSeq) {
        List<Input> in = new ArrayList<>();
        long n = firstSeq;
        for (Command c : List.of(new RoomCommand.Join("a", "A"), new RoomCommand.Join("b", "B"), new RoomCommand.Join("c", "C"),
                new RoomCommand.SetReady("a", true), new RoomCommand.SetReady("b", true), new RoomCommand.SetReady("c", true),
                new SessionCommand.StartGame("a"))) {
            in.add(new Input(n, n * 10, c));
            n++;
        }
        return in;
    }

    private EngineState started() {
        EngineState s = engine.create("r", 1, 0).state();
        for (Input in : threePlayersStart(1)) {
            s = engine.step(s, in).state();
        }
        return s;
    }

    // ------------------------------------------------------------ J1

    @Test
    void j1IncompleteOrContradictoryGameStatesRejectedByRestoreAndStep() {
        EngineState s = started();
        SessionState ss = (SessionState) s.domain();
        GameState g = ss.game();
        GameState missingSeat = g.withPlayers(g.players().subList(0, 2));
        RoomSettings richer = new RoomSettings(g.settings().boardId(), 5000, g.settings().endMode(),
                g.settings().timeLimitMinutes(), g.settings().rollSeconds());
        GameState otherCash = new GameState(g.gameNo(), g.startedAt(), richer, g.phase(), g.players(), g.orderDraws(),
                g.board(), g.turn(), g.flow(), g.ledger(), g.clock());
        GameState future = new GameState(g.gameNo(), s.now() + 1000, g.settings(), g.phase(), g.players(), g.orderDraws(),
                g.board(), g.turn(), g.flow(), g.ledger(), g.clock());
        for (GameState bad : List.of(missingSeat, otherCash, future)) {
            EngineState st = s.withDomain(ss.withGame(bad));
            assertThrows(StateValidationException.class, () -> engine.restore(engine.snapshot(st)), bad.toString());
            assertThrows(StateValidationException.class, () -> engine.step(st, new Input(s.lastSeq() + 1, 1000, new Tick())));
        }
    }

    @Test
    void j1DomainProducingAnInvalidStateIsCaughtByExitValidation() {
        Engine<DemoState> rogue = new Engine<>(base, new RogueDomain(RogueDomain.Mode.INVALID_RESULT));
        EngineState s = rogue.step(rogue.create("r", 1, 0).state(), new Input(1, 1, new DemoCommand.Sit("a"))).state();
        KernelFaultException e = assertThrows(KernelFaultException.class,
                () -> rogue.step(s, new Input(2, 2, new DemoCommand.Peek("a"))));
        assertInstanceOf(StateValidationException.class, e.getCause());
    }

    // ------------------------------------------------------------ J2

    @Test
    void j2CreateRefusesUnencodableRoomIdAndInvalidInitialState() {
        assertThrows(InvalidInputException.class, () -> engine.create("r\uD800", 1, 0));
        assertThrows(InvalidInputException.class, () -> engine.create(" ", 1, 0));
        Engine<DemoState> bad = new Engine<>(base, new RogueDomain(RogueDomain.Mode.BAD_INITIAL));
        assertThrows(StateValidationException.class, () -> bad.create("r", 1, 0));
        assertFalse(java.lang.reflect.Modifier.isPublic(Evolver.class.getModifiers()), "Evolver is not a commit API");
    }

    // ------------------------------------------------------------ J3

    @Test
    void j3LateEndGameMustNotEndTheNextGame() {
        EngineState s = started();
        s = engine.step(s, new Input(8, 100, new SessionCommand.EndGame(1, "first"))).state();
        long n = 8;
        for (Command c : List.of(new RoomCommand.SetReady("a", true), new RoomCommand.SetReady("b", true),
                new RoomCommand.SetReady("c", true), new SessionCommand.StartGame("a"))) {
            s = engine.step(s, new Input(++n, n * 10 + 100, c)).state();
        }
        StepResult late = engine.step(s, new Input(++n, 1000, new SessionCommand.EndGame(1, "late for game 1")));
        assertEquals(RejectionCode.GAME_MISMATCH, late.rejection());
        assertEquals(2, ((SessionState) late.state().domain()).game().gameNo());
    }

    @Test
    void j3StartEndRestartRebuildsAndResumesAtEveryCut() {
        List<Input> in = new ArrayList<>(threePlayersStart(1));
        long n = 8;
        in.add(new Input(n++, 200, new SessionCommand.EndGame(1, "done")));
        for (Command c : List.of(new RoomCommand.SetReady("a", true), new RoomCommand.SetReady("b", true),
                new RoomCommand.SetReady("c", true), new SessionCommand.StartGame("a"),
                new SessionCommand.EndGame(1, "stale"), new SessionCommand.EndGame(2, "done"))) {
            in.add(new Input(n, 200 + n * 10, c));
            n++;
        }
        Scenario scenario = new Scenario(base, "r", 5, 0, in);
        ScenarioRunner<SessionState> runner = new ScenarioRunner<>(scenario, SessionDomain.INSTANCE);
        RunResult full = runner.run();
        assertEquals(2, ((SessionState) full.state().domain()).gamesPlayed());
        assertEquals(full.state(), runner.engine().rebuild(full.events()));
        for (int cut = 0; cut <= in.size(); cut++) {
            RunResult prefix = runner.runPrefix(cut);
            EngineState restored = runner.engine().restore(runner.engine().snapshot(prefix.state()));
            assertEquals(full.finalHash(), runner.resume(restored, cut).finalHash(), "cut " + cut);
        }
    }

    @Test
    void j3SystemCommandsCannotComeFromClients() {
        assertThrows(InvalidInputException.class, () -> engine.admitClient(new SessionCommand.EndGame(1, "x")));
        assertThrows(InvalidInputException.class, () -> engine.admitClient(new Tick()));
        assertThrows(InvalidInputException.class, () -> engine.admitSystem(new SessionCommand.StartGame("a")));
        engine.admitSystem(new SessionCommand.EndGame(1, "x"));
        engine.admitClient(new SessionCommand.StartGame("a"));
    }

    // ------------------------------------------------------------ J4

    @Test
    void j4RejectedInputStillCommitsEffectsOfEarlierDueTasks() {
        Engine<DemoState> demo = new Engine<>(base, DemoDomain.INSTANCE);
        EngineState s = demo.create("r", 1, 0).state();
        s = demo.step(s, new Input(1, 1, new DemoCommand.Sit("a"))).state();
        s = demo.step(s, new Input(2, 10, new DemoCommand.OpenRound("a", "a", 0))).state();   // 截止 15010
        StepResult r = demo.step(s, new Input(3, 15_010, new DemoCommand.Roll("a", 1)));
        assertEquals(StepResult.Outcome.REJECTED, r.outcome());
        List<Class<?>> kinds = r.events().stream().<Class<?>>map(Object::getClass).toList();
        assertTrue(kinds.containsAll(List.of(KernelEvent.TaskFired.class, KernelEvent.RandomDrawn.class,
                DemoEvent.DieRolled.class, KernelEvent.InputRejected.class)), kinds.toString());
        assertEquals(1, ((DemoState) r.state().domain()).rolls().size(), "the timeout auto-roll is part of the result to commit");
    }

    @Test
    void j4KernelFaultAfterADueTaskCommitsNothingOfTheStep() {
        Engine<DemoState> rogue = new Engine<>(base, new RogueDomain(RogueDomain.Mode.INCONSISTENT_EVENT));
        EngineState s = rogue.create("r", 1, 0).state();
        s = rogue.step(s, new Input(1, 1, new DemoCommand.Sit("a"))).state();
        EngineState open = rogue.step(s, new Input(2, 10, new DemoCommand.OpenRound("a", "a", 0))).state();
        assertThrows(KernelFaultException.class, () -> rogue.step(open, new Input(3, 15_010, new DemoCommand.Peek("a"))));
        assertNotNull(((DemoState) open.domain()).round(), "the auto-roll of the due task was not committed");
        assertEquals(2, open.lastSeq());
    }

    // ------------------------------------------------------------ J5

    @Test
    void j5PublicEntryPointsUseTheDocumentedExceptionTypes() {
        assertThrows(InvalidInputException.class, () -> engine.admitClient(null));
        assertThrows(InvalidInputException.class, () -> engine.admitSystem(null));
        EngineState s = engine.create("r", 1, 0).state();
        assertThrows(InvalidInputException.class, () -> engine.step(s, null));
        EngineState noDraws = s.withRandom(s.rng(), null);
        assertThrows(StateValidationException.class, () -> engine.step(noDraws, new Input(1, 1, new Tick())));
        assertThrows(StateValidationException.class, () -> engine.validate(s.withDomain(null)));
        StepResult g = engine.create("r", 1, 0);
        List<Event> log = new ArrayList<>(g.events());
        log.addAll(engine.step(g.state(), new Input(1, 1, new Tick())).events());
        log.add(g.events().get(0));
        StateValidationException e = assertThrows(StateValidationException.class, () -> engine.rebuild(log));
        assertNotNull(e.getCause(), "cause kept for diagnosis");
        assertThrows(StateValidationException.class, () -> engine.restore("millionnaire-engine/1/snapshot\n{\"bad\":1}"));
    }

    // ------------------------------------------------------------ J6

    @Test
    void j6GameEventsAreSecretUnlessDeclaredPublicOneByOne() {
        assertFalse(Arrays.stream(GameEvent.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("visibility")),
                "GameEvent root must inherit SERVER_ONLY");
        // T9：用具体样本验证实际可见性、接收者与投影结果（不做恒真的结构断言）
        Event publicSample = new GameEvent.TurnStarted(1, "a");
        assertEquals(Visibility.PUBLIC, publicSample.visibility());
        assertEquals(null, publicSample.recipient());
        for (String viewer : Arrays.asList("a", "b", null)) {
            assertEquals(List.of(publicSample), EventProjector.project(List.of(publicSample), viewer), "public reaches " + viewer);
        }
        record Forgotten(String secret) implements Event {
        }
        Event serverOnly = new GameEvent.AutoActArmed(1, 2);
        assertEquals(Visibility.SERVER_ONLY, serverOnly.visibility());
        assertEquals(Visibility.SERVER_ONLY, new Forgotten("hand").visibility());
        for (String viewer : Arrays.asList("a", "b", null)) {
            assertTrue(EventProjector.project(List.of(serverOnly, new Forgotten("hand")), viewer).isEmpty());
        }
        // 合法的私有声明：不实现 PublicEvent、直接覆盖为 PRIVATE 并给出接收者
        record Peek(String recipient, String card) implements Event {
            @Override
            public Visibility visibility() {
                return Visibility.PRIVATE;
            }
        }
        Event privateSample = new Peek("b", "QUERY");
        assertEquals(List.of(privateSample), EventProjector.project(List.of(privateSample), "b"));
        assertTrue(EventProjector.project(List.of(privateSample), "a").isEmpty());
        assertTrue(EventProjector.project(List.of(privateSample), null).isEmpty());
        record NoRecipient(String card) implements Event {
            @Override
            public Visibility visibility() {
                return Visibility.PRIVATE;
            }
        }
        assertTrue(EventProjector.project(List.of(new NoRecipient("x")), "a").isEmpty(), "private without recipient goes nowhere");
        assertFalse(Arrays.stream(SessionView.class.getRecordComponents()).anyMatch(c -> c.getType() == GameState.class),
                "SessionView must not carry raw GameState");
    }

    @Test
    void j6GameViewShowsPrivateFieldsOnlyToTheirOwner() {
        EngineState s = started();
        SessionState ss = (SessionState) s.domain();
        GameState g = ss.game();
        // 让 b 持有一种 a 手里没有的牌，便于检查牌面是否泄露
        List<CardType> aHand = g.player("a").orElseThrow().hand();
        CardType secret = java.util.Arrays.stream(CardType.values()).filter(c -> !aHand.contains(c)).findFirst().orElseThrow();
        List<PlayerState> players = g.players().stream().map(p -> p.playerId().equals("b")
                ? p.withHand(List.of(secret, secret)) : p).toList();
        EngineState dealt = engine.restore(engine.snapshot(s.withDomain(ss.withGame(g.withPlayers(players)))));
        Codec codec = engine.codec();
        SessionView forA = SessionDomain.INSTANCE.project(dealt, "a");
        SessionView forB = SessionDomain.INSTANCE.project(dealt, "b");
        SessionView forSpectator = SessionDomain.INSTANCE.project(dealt, null);
        assertEquals(aHand, forA.game().myHand(), "a sees only its own cards");
        assertEquals(List.of(secret, secret), forB.game().myHand());
        assertEquals(List.of(), forSpectator.game().myHand());
        assertEquals(2, forA.game().players().stream().filter(p -> p.playerId().equals("b")).findFirst().orElseThrow().handCount());
        assertFalse(codec.encode(forA).contains(secret.name()), "card faces of b never reach a");
        assertFalse(codec.encode(forSpectator).contains(secret.name()));
        assertTrue(codec.encode(forB).contains(secret.name()));
    }

    // ------------------------------------------------------------ 故障停房

    @Test
    void faultHaltsTheRoomWithDiagnosticsAndForbidsBlindRetry() {
        Engine<DemoState> rogue = new Engine<>(base, new RogueDomain(RogueDomain.Mode.INCONSISTENT_EVENT));
        RoomRunner<DemoState> room = new RoomRunner<>(rogue, rogue.create("r", 1, 0).state());
        room.submit(new Input(1, 1, new DemoCommand.Sit("a")));
        EngineState committed = room.committed();
        Input bad = new Input(2, 2, new DemoCommand.Peek("a"));
        KernelFaultException e = assertThrows(KernelFaultException.class, () -> room.submit(bad));
        FaultReport report = e.report();
        assertEquals(2, report.seq());
        assertEquals(EngineVersion.VALUE, report.engineVersion());
        assertEquals(rogue.configHash(), report.configHash());
        assertEquals(rogue.stateHash(committed), report.lastCommittedStateHash());
        assertEquals(rogue.digest(bad), report.inputDigest());
        assertEquals(IllegalStateException.class.getName(), report.causeType());
        assertEquals(committed, room.committed(), "nothing of the faulted step was committed");
        assertThrows(RoomHaltedException.class, () -> room.submit(new Input(2, 3, new DemoCommand.Sit("b"))));
        room.clearFault(committed, "ops", false);
        assertThrows(RoomHaltedException.class, () -> room.submit(bad), "same faulted input may not be retried");
        assertEquals(StepResult.Outcome.ACCEPTED, room.submit(new Input(2, 3, new DemoCommand.Sit("b"))).outcome());
    }


}
