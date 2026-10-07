package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 进行中的虎口拔牙（requirements 第 13 节、已采纳默认值 #15）。由游戏区落点的 MINIGAME 任务启动，以落点编号为小游戏编号，并绑定任务游标。
 * participants：开始时全部存活玩家，从触发者起按行动顺序排列；teeth = 人数 × 2；danger 为危险牙（<b>保密</b>，只在结束事件中公开）；
 * picks 为已按下的牙（按先后），第 i 次由 participants[i % 人数] 选。期间认输一律延后到小游戏结束（按托管代选），因此参与者全程存活。
 */
public record MinigameState(long landingId, int cursor, String trigger, List<String> participants,
                            int teeth, int danger, List<Integer> picks) {
    public MinigameState {
        participants = Immutable.list(participants);
        picks = Immutable.list(picks);
    }

    /** 本次应选牙的玩家。 */
    public String picker() {
        return participants.get(picks.size() % participants.size());
    }

    /** 尚未按下的牙（升序）。 */
    public List<Integer> remaining() {
        return java.util.stream.IntStream.range(0, teeth).filter(i -> !picks.contains(i)).boxed().toList();
    }

    public MinigameState picked(int tooth) {
        return new MinigameState(landingId, cursor, trigger, participants, teeth, danger, Immutable.append(picks, tooth));
    }
}
