package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import java.util.Optional;

/**
 * 单个房间的最小运行壳（外层的参考实现与接口约束）：
 * <ul>
 *   <li>只持有<b>最后已提交</b>状态；ACCEPTED 与 REJECTED 的结果都整体提交（含先到期任务的效果）；</li>
 *   <li>发生 {@link KernelFaultException} 时整步不提交并<b>停房</b>，之后的一切输入抛 {@link RoomHaltedException}；</li>
 *   <li>只有显式的人工处理 {@link #clearFault} 才能恢复，且默认禁止再次提交同一故障输入（不得自动重试）。</li>
 * </ul>
 * 持久化（state、events、幂等结果同事务）与停机补偿由真正的外层负责。
 */
public final class RoomRunner<S extends DomainState> {
    private final Engine<S> engine;
    private EngineState committed;
    private FaultReport fault;
    private String blockedDigest;

    public RoomRunner(Engine<S> engine, EngineState committed) {
        engine.validate(committed);
        this.engine = engine;
        this.committed = committed;
    }

    public StepResult submit(Input input) {
        if (fault != null) {
            throw new RoomHaltedException("room halted by kernel fault at seq " + fault.seq(), fault);
        }
        if (blockedDigest != null && blockedDigest.equals(engine.digest(input))) {
            throw new RoomHaltedException("the input that caused the last fault may not be retried", null);
        }
        try {
            StepResult r = engine.step(committed, input);
            committed = r.state();
            return r;
        } catch (KernelFaultException e) {
            fault = e.report();
            blockedDigest = fault == null ? null : fault.inputDigest();
            throw e;
        }
    }

    public EngineState committed() {
        return committed;
    }

    public Optional<FaultReport> fault() {
        return Optional.ofNullable(fault);
    }

    /**
     * 人工处理后解除停房：可换入修复后的状态（经完整校验）；retryFaultedInput 为 false 时继续拒绝同一故障输入。
     */
    public void clearFault(EngineState repaired, String operator, boolean retryFaultedInput) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator required");
        }
        engine.validate(repaired);
        committed = repaired;
        fault = null;
        if (retryFaultedInput) {
            blockedDigest = null;
        }
    }
}
