package com.millionnaire.engine.replay;

import com.millionnaire.engine.core.engine.StepResult;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 场景运行结果。
 *
 * @param events     本次运行产生的事件（从创世开始运行时含创世事件）
 * @param stepHashes 每个输入处理后的状态哈希
 * @param outcomes   每个输入的处理结论
 * @param finalHash  最终状态哈希
 */
public record RunResult(EngineState state, List<Event> events, List<String> stepHashes,
                        List<StepResult.Outcome> outcomes, String finalHash) {
    public RunResult {
        events = Immutable.list(events);
        stepHashes = Immutable.list(stepHashes);
        outcomes = Immutable.list(outcomes);
    }
}
