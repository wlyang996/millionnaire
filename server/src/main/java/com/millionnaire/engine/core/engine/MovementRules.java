package com.millionnaire.engine.core.engine;
import com.millionnaire.engine.core.state.MoveChain;
import com.millionnaire.engine.core.state.MoveKind;
import com.millionnaire.engine.core.state.MoveSegment;
import java.util.ArrayList;
import java.util.List;
/** 决定和重建共用的几何与起点规则；玩法模块提供核验来源与实际距离。 */
final class MovementRules {
    private MovementRules() { }
    static MoveSegment segment(int number, MoveKind kind, int from, int distance, int size) {
        if (number < 1 || kind == null || size < 1 || from < 0 || from >= size || distance < 1 || distance > size) {
            throw new IllegalArgumentException("invalid movement geometry");
        }
        if (kind == MoveKind.TO_JAIL) { throw new IllegalArgumentException("use jailJump with the board jail tile"); }
        boolean backward = kind == MoveKind.EVENT_BACKWARD;
        int to = Math.floorMod((long) from + (backward ? -distance : distance), size);
        List<Integer> walked = new ArrayList<>();
        if (kind != MoveKind.TARGETED) {
            for (int i = 1; i <= distance; i++) { walked.add(Math.floorMod((long) from + (backward ? -i : i), size)); }
        }
        boolean eligible = !backward && (kind == MoveKind.TARGETED ? to == 0 : walked.contains(0));
        return new MoveSegment(number, kind, from, to, distance, walked, eligible);
    }
    static MoveSegment jailJump(int number, int from, int jail, int size) {
        if (number < 1 || from < 0 || from >= size || jail < 0 || jail >= size) {
            throw new IllegalArgumentException("invalid jail jump");
        }
        return new MoveSegment(number, MoveKind.TO_JAIL, from, jail, 0, List.of(), false);
    }
    /** 逐格强停只取已进入路径的前缀；跳转不受逐格拦截。路障归属/消费由 M3c 核验。 */
    static MoveSegment stopAt(MoveSegment planned, int enteredCount, int size) {
        if (planned.kind() == MoveKind.TARGETED || planned.kind() == MoveKind.TO_JAIL) { return planned; }
        if (enteredCount < 1 || enteredCount > planned.walked().size()) {
            throw new IllegalArgumentException("forced stop must be on an entered tile");
        }
        return segment(planned.number(), planned.kind(), planned.from(), enteredCount, size);
    }

    /** 几何及累积规则不限定玩法来源；来源授权由事件演化另行核对。 */
    static boolean valid(MoveChain chain, int size) {
        if (chain.segments().isEmpty()) { return false; }
        int from = chain.origin();
        int walked = 0;
        boolean eligible = false;
        for (int i = 0; i < chain.segments().size(); i++) {
            MoveSegment actual = chain.segments().get(i);
            if (actual == null || actual.from() != from) { return false; }
            MoveSegment expected = actual.kind() == MoveKind.TO_JAIL
                    ? jailJump(i + 1, from, actual.to(), size)
                    : segment(i + 1, actual.kind(), from, actual.distance(), size);
            if (!expected.equals(actual)) { return false; }
            walked = Math.addExact(walked, actual.walked().size());
            eligible |= actual.startEligible();
            from = actual.to();
        }
        return walked == chain.walkedSteps() && eligible == chain.startRewardGiven();
    }

}
