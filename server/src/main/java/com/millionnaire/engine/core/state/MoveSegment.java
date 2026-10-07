package com.millionnaire.engine.core.state;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
/** 已完成位移；walked 为按顺序进入的格子，含终点、不含出发格，跳转为空。 */
public record MoveSegment(int number, MoveKind kind, int from, int to, int distance, List<Integer> walked,
                          boolean startEligible, int plannedDistance, Roadblock stoppedBy) {
    public MoveSegment { walked = Immutable.list(walked); }
    public MoveSegment(int number, MoveKind kind, int from, int to, int distance, List<Integer> walked, boolean startEligible) {
        this(number, kind, from, to, distance, walked, startEligible, distance, null);
    }
}
