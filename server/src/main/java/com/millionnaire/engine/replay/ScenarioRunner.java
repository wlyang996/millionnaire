package com.millionnaire.engine.replay;

import com.millionnaire.engine.core.engine.Domain;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.StepResult;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.random.RandomSourceFactory;
import com.millionnaire.engine.random.XoshiroLemireV1;
import java.util.ArrayList;
import java.util.List;

/** 场景回放器：命令流回放、快照续跑、事件流重建。三者对同一场景必须得到逐字节一致的状态。 */
public final class ScenarioRunner<S extends DomainState> {
    private final Scenario scenario;
    private final Engine<S> engine;

    public ScenarioRunner(Scenario scenario, Domain<S> domain) {
        this(scenario, domain, XoshiroLemireV1.INSTANCE);
    }

    public ScenarioRunner(Scenario scenario, Domain<S> domain, RandomSourceFactory random) {
        this.scenario = scenario;
        this.engine = new Engine<>(scenario.config(), domain, random);
    }

    public Engine<S> engine() {
        return engine;
    }

    /** 从创世开始完整运行。 */
    public RunResult run() {
        return runRange(null, 0, scenario.inputs().size());
    }

    /** 运行前 count 个输入。 */
    public RunResult runPrefix(int count) {
        return runRange(null, 0, count);
    }

    /** 从（已校验的）快照状态出发，处理第 fromIndex 个及之后的输入。 */
    public RunResult resume(EngineState snapshot, int fromIndex) {
        return runRange(snapshot, fromIndex, scenario.inputs().size());
    }

    private RunResult runRange(EngineState start, int from, int to) {
        List<Event> events = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        List<StepResult.Outcome> outcomes = new ArrayList<>();
        EngineState s = start;
        if (s == null) {
            StepResult genesis = engine.create(scenario.roomId(), scenario.seed(), scenario.createdAt());
            events.addAll(genesis.events());
            s = genesis.state();
        }
        for (int i = from; i < to; i++) {
            StepResult r = engine.step(s, scenario.inputs().get(i));
            events.addAll(r.events());
            outcomes.add(r.outcome());
            s = r.state();
            hashes.add(engine.stateHash(s));
        }
        return new RunResult(s, events, hashes, outcomes, engine.stateHash(s));
    }
}
