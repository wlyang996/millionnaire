package com.millionnaire.engine.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.EconomyConfig;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.StateValidationException;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.serialize.Canonical;
import com.millionnaire.engine.serialize.Envelope;
import com.millionnaire.engine.testkit.DemoDomain;
import com.millionnaire.engine.testkit.DemoScenarios;
import com.millionnaire.engine.testkit.DemoState;
import com.millionnaire.engine.testkit.GoldenMain;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayTest {
    /** 黄金值：锁定引擎行为 + 规范格式 + 随机协议。有意修改时同步更新，并提升 EngineVersion。 */
    static final String GOLDEN_FINAL_HASH = "4357036b8a88fe4fb8e259bfe6f730afef6aa4b04afd948083b721a532be3310";
    /** 测试配置新增道具开关（EconomyConfig.cardsEnabled，测试配置关闭）后的配置哈希；之前为 15be5a0b…。 */
    static final String CONFIG_HASH = "dc874ba180688decc09099487a7c79aa42754ff1c271a1a4aba2c84a9f692ba9";
    static final String PRE_CARDS_CONFIG_HASH = "15be5a0b1185660540974ce2b74ac25d73bfb205f06e368cdb3c79b0ed26b668";
    private static final String GOLDEN_SNAPSHOT_RESOURCE = "/golden/demo-final.snapshot";

    private final Scenario scenario = DemoScenarios.full();
    private final ScenarioRunner<DemoState> runner = new ScenarioRunner<>(scenario, DemoDomain.INSTANCE);
    private final Engine<DemoState> engine = runner.engine();
    private final RunResult full = runner.run();

    @Test
    void scenarioProducesExpectedOutcomes() {
        assertEquals(DemoScenarios.FULL_OUTCOMES, full.outcomes());
        DemoState s = (DemoState) full.state().domain();
        assertEquals(List.of("b"), s.players());
        assertEquals(2, s.rolls().size(), "one manual roll + one timeout auto-roll");
        assertEquals(2, full.events().stream().filter(e -> e instanceof KernelEvent.RandomDrawn).count());
        assertEquals(full.events().size(), full.state().eventCount());
        assertEquals(28, full.state().lastSeq());
        assertEquals(60_000, full.state().lastReceivedAt());
    }

    @Test
    void sameInputsGiveByteIdenticalEventLogAndState() {
        RunResult again = new ScenarioRunner<>(DemoScenarios.full(), DemoDomain.INSTANCE).run();
        assertEquals(engine.encodeEvents(full.events()), engine.encodeEvents(again.events()));
        assertEquals(engine.snapshot(full.state()), engine.snapshot(again.state()));
        assertEquals(full.stepHashes(), again.stepHashes());
    }

    @Test
    void goldenFinalHash() {
        assertEquals(GOLDEN_FINAL_HASH, full.finalHash());
    }

    @Test
    void m3bGoldenSnapshotDiffersFromM3aOnlyInItsVersionBytes() {
        byte[] previous = java.util.Base64.getDecoder().decode("bWlsbGlvbm5haXJlLWVuZ2luZS8xL3NuYXBzaG90Cnsicm9vbUlkIjoicm9vbS0xIiwiY29uZmlnSGFzaCI6ImY4NTI0YTYyODgwMjc4Y2EzZTNhMGU2ODM4MTQwZTkxNmYyOGNmMWIyNDk4MTExYzUzYTI5ZGRiMzhlZjcwOWQiLCJlbmdpbmVWZXJzaW9uIjoiZW5naW5lLTAuOC4xLW0zYSIsImRvbWFpbklkIjoiZGVtby10ZXN0Iiwicm5nUHJvdG9jb2wiOiJ4b3NoaXJvMjU2c3MtbGVtaXJlMzItdjEiLCJub3ciOjYwMDAwLCJsYXN0U2VxIjoyOCwibGFzdFJlY2VpdmVkQXQiOjYwMDAwLCJsYXN0SW5wdXREaWdlc3QiOiJmZDUwMTY5NWYxYmZkYzA2MjhjNDU4MzgzMDA3NDNiOTE5NmUxYWI3NWIyN2I3MmU3YzQ4YzgzMjJiZGM0NDY2IiwiZXZlbnRDb3VudCI6NjMsInRpbWVycyI6eyJ0YXNrcyI6W119LCJuZXh0VGFza0lkIjo3LCJybmciOnsiczAiOjcwMzI4NzIxODkzMjIyMzQ3NDYsInMxIjotNzQ1MDcxMzIzNTMxNTU3OTk3LCJzMiI6MjU5MDU3Nzg4MjY5OTg0NTE1OCwiczMiOjEyNjExNjM4MDU3NDQ5Mjg1NTl9LCJwZW5kaW5nRHJhd3MiOltdLCJkb21haW4iOnsiQHR5cGUiOiJEZW1vU3RhdGUiLCJob3N0SWQiOiJiIiwicGxheWVycyI6WyJiIl0sInJvdW5kIjpudWxsLCJuZXh0V2luZG93SWQiOjUsInJvbGxzIjpbMiw1XX19");
        // 配置后来新增了正式服策略开关（EconomyConfig.offerUnaffordablePurchase / upgradeAfterPurchase、TimingConfig.endWhenAllAway），配置哈希随之改变
        byte[] normalized = engine.snapshot(full.state()).replace(CONFIG_HASH, PRE_CARDS_CONFIG_HASH).replace("engine-0.11.0-m6a", "engine-0.8.1-m3a")
                .replace("15be5a0b1185660540974ce2b74ac25d73bfb205f06e368cdb3c79b0ed26b668",
                        "f8524a62880278ca3e3a0e6838140e916f28cf1b2498111c53a29ddb38ef709d").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(previous, normalized);
    }

    @Test
    void goldenSnapshotFileMatchesByteForByte() throws IOException {
        byte[] expected;
        try (InputStream in = ReplayTest.class.getResourceAsStream(GOLDEN_SNAPSHOT_RESOURCE)) {
            assertTrue(in != null, "golden file missing: run GoldenMain write src/test/resources" + GOLDEN_SNAPSHOT_RESOURCE);
            expected = in.readAllBytes();
        }
        assertArrayEquals(expected, engine.snapshot(full.state()).getBytes(StandardCharsets.UTF_8));
        assertEquals(full.state(), engine.restore(new String(expected, StandardCharsets.UTF_8)));
    }

    @Test
    void m3cDemoGoldenDiffersFromTheCanonicalM3bBaselineOnlyInVersionBytes() throws IOException {
        byte[] previous;
        try (InputStream in = ReplayTest.class.getResourceAsStream("/golden/demo-m3b-baseline.snapshot")) {
            assertNotNull(in); previous = in.readAllBytes();
        }
        assertEquals("2520dcd5a18d6bf8bcfb6b079390315ef9d3744a4434a617a70f074b5cc9415c", Canonical.sha256Hex(previous));
        byte[] normalized = engine.snapshot(full.state()).replace(CONFIG_HASH, PRE_CARDS_CONFIG_HASH).replace("engine-0.11.0-m6a", "engine-0.9.1-m3b").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(previous, normalized);
    }

    /** 虎口拔牙接入（engine-0.11.0-m6a）不改变演示领域的行为：与 m3c 基线只差版本字节。 */
    @Test
    void m6aDemoGoldenDiffersFromTheM3cBaselineOnlyInVersionBytes() throws IOException {
        byte[] previous;
        try (InputStream in = ReplayTest.class.getResourceAsStream("/golden/demo-m3c-baseline.snapshot")) {
            assertNotNull(in); previous = in.readAllBytes();
        }
        assertEquals("c3c2624903ce3d1e0d28231b1ec29519320da9d5e3c80b85afbdf7bc75118976", Canonical.sha256Hex(previous));
        byte[] normalized = engine.snapshot(full.state()).replace(CONFIG_HASH, PRE_CARDS_CONFIG_HASH).replace("engine-0.11.0-m6a", "engine-0.10.0-m3c").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(previous, normalized);
    }

    @Test
    void separateJvmProcessWithOtherLocaleTimezoneAndEncodingAgrees(@TempDir Path dir) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        Path out = dir.resolve("stdout.txt");
        Path err = dir.resolve("stderr.txt");
        ProcessBuilder pb = new ProcessBuilder(java,
                "-Duser.language=tr", "-Duser.country=TR", "-Duser.timezone=Pacific/Kiritimati",
                "-Dfile.encoding=ISO-8859-1", "-Dsun.jnu.encoding=ISO-8859-1",
                "-cp", System.getProperty("java.class.path"), GoldenMain.class.getName())
                .redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        try {
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "child JVM timed out");
            List<String> lines = Files.readAllLines(out, StandardCharsets.US_ASCII);
            assertEquals(0, p.exitValue(), lines + " / " + Files.readString(err, StandardCharsets.ISO_8859_1));
            String env = lines.stream().filter(l -> l.startsWith("ENV ")).findFirst().orElseThrow();
            assertEquals("ENV locale=tr-TR tz=Pacific/Kiritimati charset=ISO-8859-1", env, "child settings took effect");
            String result = lines.stream().filter(l -> l.startsWith("RESULT ")).findFirst().orElseThrow();
            String expected = "RESULT " + full.finalHash() + " "
                    + Canonical.sha256Hex(engine.snapshot(full.state()).getBytes(StandardCharsets.UTF_8)) + " "
                    + Canonical.sha256Hex(engine.encodeEvents(full.events()).getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, result);
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void snapshotResumeAtEveryCutMatchesUninterruptedRun() {
        int n = scenario.inputs().size();
        for (int cut = 0; cut <= n; cut++) {
            RunResult prefix = runner.runPrefix(cut);
            EngineState restored = engine.restore(engine.snapshot(prefix.state()));
            assertEquals(prefix.state(), restored);
            RunResult rest = runner.resume(restored, cut);
            List<Event> stitched = new ArrayList<>(prefix.events());
            stitched.addAll(rest.events());
            assertEquals(full.events(), stitched, "events differ at cut " + cut);
            assertEquals(full.finalHash(), rest.finalHash(), "final hash differs at cut " + cut);
            assertEquals(full.stepHashes().subList(cut, n), rest.stepHashes(), "step hashes differ at cut " + cut);
        }
    }

    @Test
    void snapshotResumeAtSelectedInterestingCuts() {
        // 3：普通状态；8：窗口缓冲期；13：暂停中；18：两个同刻任务挂起；27：刚被时间倒退拒绝
        for (int cut : new int[] {3, 8, 13, 18, 27}) {
            EngineState restored = engine.restore(engine.snapshot(runner.runPrefix(cut).state()));
            assertEquals(full.finalHash(), runner.resume(restored, cut).finalHash());
        }
        assertEquals(2, runner.runPrefix(18).state().timers().tasks().size());
        assertEquals(14_000, ((DemoState) runner.runPrefix(13).state().domain()).round().window().pausedRemainingMs());
    }

    @Test
    void rebuildFromEventLogEqualsCommandReplayAndPassesAudit() {
        EngineState rebuilt = engine.rebuild(full.events());
        assertEquals(full.state(), rebuilt);
        List<Event> decoded = engine.decodeEvents(engine.encodeEvents(full.events()));
        assertEquals(full.events(), decoded);
        assertEquals(full.finalHash(), engine.stateHash(engine.rebuild(decoded)));
        assertDoesNotThrow(() -> RandomAudit.verify(full.events()));
    }

    @Test
    void tamperedRandomStateIsAcceptedByRebuildButCaughtByAudit() {
        List<Event> events = new ArrayList<>(full.events());
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof KernelEvent.RandomDrawn d) {
                events.set(i, new KernelEvent.RandomDrawn(d.protocol(), d.point(), d.bound(), d.value(),
                        new com.millionnaire.engine.random.RngState(1, 2, 3, 4)));
                break;
            }
        }
        assertDoesNotThrow(() -> engine.rebuild(events), "normal rebuild trusts the recorded state");
        assertThrows(IllegalStateException.class, () -> RandomAudit.verify(events));
    }

    @Test
    void drawsFromAnotherRandomProtocolAreRefused() {
        List<Event> events = full.events().stream().map(e -> e instanceof KernelEvent.RandomDrawn d
                ? new KernelEvent.RandomDrawn("xoshiro256ss-lemire64-v0", d.point(), d.bound(), d.value(), d.after()) : e).toList();
        StateValidationException e = assertThrows(StateValidationException.class, () -> engine.rebuild(events),
                "game is bound to its genesis protocol");
        assertTrue(e.getCause() instanceof IllegalStateException && e.getCause().getMessage().contains("protocol"));
        assertThrows(IllegalStateException.class, () -> RandomAudit.verify(events), "audit needs the old implementation");
        assertThrows(IllegalStateException.class, () -> RandomAudit.verify(full.events(), id -> java.util.Optional.empty()));
    }

    @Test
    void envelopeVersionIsCheckedBeforeBody() {
        String snap = engine.snapshot(full.state());
        assertTrue(snap.startsWith("millionnaire-engine/1/snapshot\n"));
        String v2 = snap.replaceFirst("/1/", "/2/") + "garbage that is not even JSON";
        Envelope.UnsupportedFormatException e = assertThrows(Envelope.UnsupportedFormatException.class,
                () -> engine.restore(v2));
        assertTrue(e.getMessage().contains("formatVersion 2"));
        assertThrows(Envelope.UnsupportedFormatException.class, () -> engine.decodeEvents(snap), "kind mismatch");
        assertThrows(Envelope.UnsupportedFormatException.class, () -> engine.restore("{}"));
    }

    @Test
    void scenarioItselfRoundTrips() {
        String text = engine.codec().encode(scenario);
        Scenario back = engine.codec().decode(text, Scenario.class);
        assertEquals(scenario, back);
        assertEquals(full.finalHash(), new ScenarioRunner<>(back, DemoDomain.INSTANCE).run().finalHash());
    }

    @Test
    void differentSeedChangesRandomOutcomeOnly() {
        Scenario other = new Scenario(scenario.config(), scenario.roomId(), scenario.seed() + 1,
                scenario.createdAt(), scenario.inputs());
        RunResult r = new ScenarioRunner<>(other, DemoDomain.INSTANCE).run();
        assertEquals(full.outcomes(), r.outcomes());
        assertNotEquals(full.finalHash(), r.finalHash());
    }

    @Test
    void stateFromAnotherConfigOrDomainIsRefused() {
        RuleConfig c = scenario.config();
        EconomyConfig e = c.economy();
        RuleConfig changed = new RuleConfig(c.ruleVersion(), c.boards(), c.tiers(), c.station(),
                new EconomyConfig(e.startReward() + 50, e.miniGameWinReward(), e.bailCost(), e.eventCashMin(),
                        e.eventCashMax(), e.eventCashStep(), e.eventMoveMinSteps(), e.eventMoveMaxSteps(), e.dieFaces(),
                        e.maxLevel(), e.handLimit(), e.initialHandSize(), e.orderNumberMax(), e.offerUnaffordablePurchase(), e.upgradeAfterPurchase(), e.cardsEnabled()),
                c.ratios(), c.cardWeights(), c.eventWeights(), c.timing(), c.room());
        EngineState s = runner.runPrefix(5).state();
        Engine<DemoState> other = new Engine<>(changed, DemoDomain.INSTANCE);
        assertThrows(StateValidationException.class, () -> other.step(s, new Input(6, 9999, new Tick())));
        assertThrows(StateValidationException.class, () -> other.restore(engine.snapshot(s)));
        assertThrows(StateValidationException.class, () -> other.rebuild(full.events()));
    }
}
