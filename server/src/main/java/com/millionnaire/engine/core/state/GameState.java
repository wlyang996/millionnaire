package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 对局状态（M1 占位）：目前只有开局时固定的参与者与设置快照。
 * M1 在此加入回合、玩家资产、地产、流程等子状态，并由普通模块（回合、拍卖、债务、小游戏）处理，不做热切换领域。
 */
public record GameState(long gameNo, List<String> seats, RoomSettings settings, long startedAt) {
    public GameState {
        seats = Immutable.list(seats);
    }
}
