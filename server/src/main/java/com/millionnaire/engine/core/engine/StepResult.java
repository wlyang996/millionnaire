package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.EngineState;
import java.util.List;

/**
 * 处理一个输入的结果。events 包含输入前推进的到期任务事件，以及输入本身的事件。
 *
 * @param rejection outcome 为 REJECTED 时的原因，否则为 null
 */
public record StepResult(EngineState state, List<Event> events, Outcome outcome, RejectionCode rejection) {
    public StepResult {
        events = List.copyOf(events);
    }

    /** 输入处理结论。 */
    public enum Outcome {
        ACCEPTED,
        REJECTED,
        /** 与最后处理的输入序号相同且内容相同：重复投递，完全忽略。 */
        DUPLICATE,
        /** 序号小于最后处理的序号：过期重放，完全忽略。 */
        STALE
    }
}
