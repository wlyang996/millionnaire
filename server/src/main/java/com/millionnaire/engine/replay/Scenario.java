package com.millionnaire.engine.replay;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 可回放场景：配置 + 种子 + 创建时间 + 输入序列。可由引擎的 Codec 规范序列化。 */
public record Scenario(RuleConfig config, String roomId, long seed, long createdAt, List<Input> inputs) {
    public Scenario {
        inputs = Immutable.list(inputs);
    }
}
