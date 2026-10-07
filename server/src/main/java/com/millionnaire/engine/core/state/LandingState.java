package com.millionnaire.engine.core.state;
import com.millionnaire.engine.serialize.Immutable;
import java.util.ArrayList;
import java.util.List;
/** 任务表只追加；results 是已消费前缀，cursor 指向未消费任务。后继按结果后的状态生成。 */
public record LandingState(long landingId, long chainId, int tile, LandingStep step, long pendingPayment,
                           boolean bought, List<LandingStep> tasks, List<Integer> generatedBy, List<LandingResult> results, int cursor,
                           boolean decisionOpen, EventResolution event) {
    public LandingState(long landingId, long chainId, int tile, LandingStep step, long pendingPayment,
                        boolean bought, List<LandingStep> tasks, List<Integer> generatedBy, List<LandingResult> results,
                        int cursor, boolean decisionOpen) {
        this(landingId, chainId, tile, step, pendingPayment, bought, tasks, generatedBy, results, cursor, decisionOpen, null);
    }
    public LandingState { tasks = Immutable.list(tasks); generatedBy = Immutable.list(generatedBy); results = Immutable.list(results); }
    public LandingStep currentTask() { return cursor < tasks.size() ? tasks.get(cursor) : null; }
    public LandingStep next() {
        return decisionOpen || pendingPayment != 0 || step != null && step.awaitsFlow() ? null : currentTask();
    }
    public LandingState withStep(LandingStep value, long payment) {
        return new LandingState(landingId, chainId, tile, value, payment, bought, tasks, generatedBy, results, cursor, decisionOpen, event);
    }
    public LandingState decision(boolean open) {
        return new LandingState(landingId, chainId, tile, step, pendingPayment, bought, tasks, generatedBy, results, cursor, open, event);
    }
    /** 核验结果后消费一个任务，并追加按当前状态生成的后继。 */
    public LandingState consumed(LandingResult result, List<LandingStep> following) {
        var expanded = new ArrayList<>(tasks);
        expanded.addAll(following);
        var parents = new ArrayList<>(generatedBy);
        following.forEach(ignored -> parents.add(cursor));
        return new LandingState(landingId, chainId, tile, null, 0, bought || result == LandingResult.BOUGHT,
                expanded, parents, Immutable.append(results, result), Math.addExact(cursor, 1), false, event);
    }
    public LandingState withEvent(EventResolution value) {
        return new LandingState(landingId, chainId, tile, step, pendingPayment, bought, tasks, generatedBy, results, cursor, decisionOpen, value);
    }
}
