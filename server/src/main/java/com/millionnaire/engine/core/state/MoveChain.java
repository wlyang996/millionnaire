package com.millionnaire.engine.core.state;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
/** 行动移动链，跨落点保留事件已抽、回合奖励及逐格路径。 */
public record MoveChain(long chainId, long turnNo, String playerId, int origin, boolean eventDrawn,
                        boolean startRewardGiven, List<MoveSegment> segments, int walkedSteps, List<MovePlan> plans) {
    public MoveChain { segments = Immutable.list(segments); plans = Immutable.list(plans); }
    /** Geometry-only fixtures; production supplies plans from the committed dice/effect/task sources. */
    public MoveChain(long chainId, long turnNo, String playerId, int origin, boolean eventDrawn,
                     boolean startRewardGiven, List<MoveSegment> segments, int walkedSteps) {
        this(chainId, turnNo, playerId, origin, eventDrawn, startRewardGiven, segments, walkedSteps,
                segments.stream().map(s -> new MovePlan(s.kind(), s.plannedDistance(), 0, -1)).toList());
    }
    public MoveChain authorize(MovePlan plan) {
        if (plans.size() != segments.size()) { throw new IllegalStateException("unconsumed movement plan"); }
        return new MoveChain(chainId, turnNo, playerId, origin, eventDrawn, startRewardGiven, segments, walkedSteps,
                Immutable.append(plans, plan));
    }
    public MoveChain append(MoveSegment segment) {
        int expectedFrom = segments.isEmpty() ? origin : segments.getLast().to();
        if (segment == null || segment.number() != segments.size() + 1 || segment.from() != expectedFrom) {
            throw new IllegalStateException("movement segment is repeated, reordered or disconnected");
        }
        List<MovePlan> authorized = plans.size() == segments.size()
                ? Immutable.append(plans, new MovePlan(segment.kind(), segment.plannedDistance(), 0, -1)) : plans;
        MovePlan plan = authorized.getLast();
        if (authorized.size() != segments.size() + 1 || plan.kind() != segment.kind() || plan.distance() != segment.plannedDistance()) {
            throw new IllegalStateException("segment does not consume the authorized movement plan");
        }
        return new MoveChain(chainId, turnNo, playerId, origin, eventDrawn, startRewardGiven,
                Immutable.append(segments, segment), Math.addExact(walkedSteps, segment.walked().size()), authorized);
    }
    public boolean canReward(MoveSegment segment) { return !startRewardGiven && segment.startEligible(); }
    public MoveChain rewarded() {
        if (startRewardGiven || segments.isEmpty() || !segments.getLast().startEligible()) {
            throw new IllegalStateException("start reward is repeated or not earned by the last segment");
        }
        return new MoveChain(chainId, turnNo, playerId, origin, eventDrawn, true, segments, walkedSteps, plans);
    }
    /** 第一个事件格消费抽取机会，后续事件格不可再次消费。 */
    public MoveChain drewEvent() {
        if (eventDrawn) { throw new IllegalStateException("an event was already drawn in this chain"); }
        return new MoveChain(chainId, turnNo, playerId, origin, true, startRewardGiven, segments, walkedSteps, plans);
    }
}
