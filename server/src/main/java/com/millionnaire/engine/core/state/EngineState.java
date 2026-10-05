package com.millionnaire.engine.core.state;

import com.millionnaire.engine.random.Draw;
import com.millionnaire.engine.random.RngState;
import com.millionnaire.engine.serialize.Immutable;
import com.millionnaire.engine.time.TimerQueue;
import java.util.List;

/**
 * 服务端权威状态：内核字段（输入游标、两条时间线、定时队列、随机状态、版本绑定）+ 领域状态。不可变。
 * <ul>
 *   <li>{@code now}：业务时间（任务演化时间），由被接受的输入或到期任务推进；</li>
 *   <li>{@code lastReceivedAt}：输入接收水位，被拒输入也推进，后续输入时间不得低于它；
 *       不变量 now ≤ lastReceivedAt，且所有挂起任务 dueAt &gt; lastReceivedAt；</li>
 *   <li>{@code lastInputDigest}：最后处理输入的规范摘要，用于区分"同序号重复投递"与"同序号不同内容"；</li>
 *   <li>{@code pendingDraws}：本步已抽取、尚未被领域事件消费的随机结果，步与步之间必须为空。</li>
 * </ul>
 * 只能经由引擎演化得到；从持久化恢复时由引擎完整校验。
 */
public record EngineState(
        String roomId,
        String configHash,
        String engineVersion,
        String domainId,
        String rngProtocol,
        long now,
        long lastSeq,
        long lastReceivedAt,
        String lastInputDigest,
        long eventCount,
        TimerQueue timers,
        long nextTaskId,
        RngState rng,
        List<Draw> pendingDraws,
        DomainState domain) {

    public EngineState {
        pendingDraws = Immutable.list(pendingDraws);
    }

    public EngineState withNow(long value) {
        return new EngineState(roomId, configHash, engineVersion, domainId, rngProtocol, value, lastSeq,
                lastReceivedAt, lastInputDigest, eventCount, timers, nextTaskId, rng, pendingDraws, domain);
    }

    public EngineState withInputCursor(long seq, long receivedAt, String digest) {
        return new EngineState(roomId, configHash, engineVersion, domainId, rngProtocol, now, seq,
                receivedAt, digest, eventCount, timers, nextTaskId, rng, pendingDraws, domain);
    }

    public EngineState withEventCount(long value) {
        return new EngineState(roomId, configHash, engineVersion, domainId, rngProtocol, now, lastSeq,
                lastReceivedAt, lastInputDigest, value, timers, nextTaskId, rng, pendingDraws, domain);
    }

    public EngineState withTimers(TimerQueue queue, long nextId) {
        return new EngineState(roomId, configHash, engineVersion, domainId, rngProtocol, now, lastSeq,
                lastReceivedAt, lastInputDigest, eventCount, queue, nextId, rng, pendingDraws, domain);
    }

    public EngineState withRandom(RngState state, List<Draw> pending) {
        return new EngineState(roomId, configHash, engineVersion, domainId, rngProtocol, now, lastSeq,
                lastReceivedAt, lastInputDigest, eventCount, timers, nextTaskId, state, pending, domain);
    }

    public EngineState withDomain(DomainState value) {
        return new EngineState(roomId, configHash, engineVersion, domainId, rngProtocol, now, lastSeq,
                lastReceivedAt, lastInputDigest, eventCount, timers, nextTaskId, rng, pendingDraws, value);
    }
}
