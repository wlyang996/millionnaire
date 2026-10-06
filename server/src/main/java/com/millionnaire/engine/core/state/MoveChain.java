package com.millionnaire.engine.core.state;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
/** 行动移动链，跨落点保留事件已抽、回合奖励及逐格路径。 */
public record MoveChain(long chainId, long turnNo, String playerId, int origin, boolean eventDrawn,
                        boolean startRewardGiven, List<MoveSegment> segments, int walkedSteps) {
    public MoveChain { segments = Immutable.list(segments); }
    public MoveChain append(MoveSegment segment) {
        int expectedFrom = segments.isEmpty() ? origin : segments.getLast().to();
        if (segment == null || segment.number() != segments.size() + 1 || segment.from() != expectedFrom) {
            throw new IllegalStateException("movement segment is repeated, reordered or disconnected");
        }
        return new MoveChain(chainId, turnNo, playerId, origin, eventDrawn, startRewardGiven,
                Immutable.append(segments, segment), Math.addExact(walkedSteps, segment.walked().size()));
    }
    public boolean canReward(MoveSegment segment) { return !startRewardGiven && segment.startEligible(); }
    public MoveChain rewarded() {
        if (startRewardGiven || segments.isEmpty() || !segments.getLast().startEligible()) {
            throw new IllegalStateException("start reward is repeated or not earned by the last segment");
        }
        return new MoveChain(chainId, turnNo, playerId, origin, eventDrawn, true, segments, walkedSteps);
    }
    /** 第一个事件格消费抽取机会，后续事件格不可再次消费。 */
    public MoveChain drewEvent() {
        if (eventDrawn) { throw new IllegalStateException("an event was already drawn in this chain"); }
        return new MoveChain(chainId, turnNo, playerId, origin, true, startRewardGiven, segments, walkedSteps);
    }
}
