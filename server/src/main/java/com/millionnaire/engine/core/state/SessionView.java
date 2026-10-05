package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 下发给客户端的会话视图：只含公开字段与观察者本人的私有字段；不含随机状态、计时内部细节、输入游标，
 * 也不含原始 {@link GameState}（对局部分为按观察者投影的 {@link GameView}）。lastResult 为上一局结算摘要。
 */
public record SessionView(String roomId, RoomStatus status, String hostId, List<Member> members, RoomSettings settings,
                          GameView game, long gamesPlayed, GameResult lastResult) {
    public SessionView {
        members = Immutable.list(members);
    }
}
