package com.millionnaire.engine.testkit;

import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.FlowState;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 流程协调集成测试的领域状态：流程状态 + 自动动作日志。 */
public record FlowTestState(FlowState flow, List<String> log) implements DomainState {
    public FlowTestState {
        log = Immutable.list(log);
    }
}
