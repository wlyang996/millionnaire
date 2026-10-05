package com.millionnaire.engine.core.state;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;
import java.util.Optional;

/** 大厅状态（{@link SessionState} 的一部分，跨局保留）。成员按加入先后排列。 */
public record RoomState(RoomStatus status, String hostId, List<Member> members, RoomSettings settings) {

    public RoomState {
        members = Immutable.list(members);
    }

    public static RoomState initial(RuleConfig config) {
        return new RoomState(RoomStatus.OPEN, null, List.of(), RoomSettings.defaults(config));
    }

    public Optional<Member> member(String playerId) {
        return members.stream().filter(m -> m.playerId().equals(playerId)).findFirst();
    }

    public boolean isMember(String playerId) {
        return member(playerId).isPresent();
    }

    public boolean isHost(String playerId) {
        return hostId != null && hostId.equals(playerId);
    }
}
