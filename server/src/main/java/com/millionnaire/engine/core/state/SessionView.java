package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/**
 * 下发给客户端的会话视图：只含公开字段，不含随机状态、计时内部细节与输入游标。
 * M1 接入手牌等秘密时，对局部分改为按观察者投影的 GameView，不得直接复用 GameState。
 */
public record SessionView(String roomId, RoomStatus status, String hostId, List<Member> members, RoomSettings settings,
                          GameState game, long gamesPlayed) {
    public SessionView {
        members = Immutable.list(members);
    }
}
