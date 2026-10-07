package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.state.FlowRequest;
import com.millionnaire.engine.core.state.GameState;

/** 交易卡申请的启动前核对（交易流程接入前的占位：没有交易申请时恒为有效）。 */
final class TradeRules {
    private TradeRules() {
    }

    static boolean requestStillValid(RuleConfig config, GameState g, FlowRequest r) {
        return true;
    }
}
