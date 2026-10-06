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

    /**
     * 纯函数演化领域事件；消费随机结果须通过 draws，不一致时抛 {@link IllegalStateException}。
     * rules 为本局绑定的不可变规则配置（其内容哈希写在创世事件里），用于核对事件中的金额、上界等派生值。
     */
    S evolve(S state, Event event, Draws draws, RuleConfig rules);

    /**
     * 步边界的轻量检查（C4）：在新输入或新任务开始、在线决策完成时调用，领域在此断言"步内衔接状态已清空"等。
     * 不一致时抛 {@link IllegalStateException}（决策中视为内核故障，重建中视为日志损坏）。
     */
    default void checkBoundary(S state) {
    }

    /**
     * 一致性校验；不一致时抛 {@link StateValidationException}。full 为 true 表示恢复、重建、创世入口，false 表示每步入口与出口；
     * 领域可借此区分检查强度。生产会话领域目前两种情况都完整重放账本（正确优先，增量校验待后续优化）。
     */
    void validate(EngineState engine, S state, RuleConfig config, boolean full);
}
