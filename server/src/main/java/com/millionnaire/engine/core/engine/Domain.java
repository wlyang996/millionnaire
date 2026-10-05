package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.serialize.TypeRegistry;
import com.millionnaire.engine.time.ScheduledTask;

/**
 * 领域规则插件：内核负责输入游标、时间、定时任务、随机与版本，领域只负责规则。
 * decide/onTask 通过 {@link DecisionContext} 发出事件；evolve 是纯函数。
 */
public interface Domain<S extends DomainState> {

    /** 领域标识（写入创世事件，旧局只能由同一领域续跑）。 */
    String id();

    Class<S> stateType();

    /** 本领域的命令、事件、状态实现（登记到 Command / Event / DomainState）。 */
    TypeRegistry types();

    S initialState(RuleConfig config);

    /**
     * 判定命令。接受时经 ctx 发出事件并返回 null；拒绝时返回原因（引擎丢弃整个工作区，
     * 因此拒绝不会留下任何事件、随机消耗或 ID 分配）。应先校验、后抽随机数。
     */
    RejectionCode decide(DecisionContext<S> ctx, Command command);

    /** 定时任务到期（TaskFired 已发出）。 */
    void onTask(DecisionContext<S> ctx, ScheduledTask task);

    /** 纯函数演化领域事件；消费随机结果须通过 draws，不一致时抛 {@link IllegalStateException}。 */
    S evolve(S state, Event event, Draws draws);

    /** 恢复入口的一致性校验；不一致时抛 {@link StateValidationException}。 */
    void validate(EngineState engine, S state, RuleConfig config);
}
