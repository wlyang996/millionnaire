package com.millionnaire.engine.testkit;

import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 演示领域状态：玩家按入座先后排列，首位为房主。 */
public record DemoState(String hostId, List<String> players, DemoRound round, long nextWindowId, List<Integer> rolls)
        implements DomainState {
    public DemoState {
        players = Immutable.list(players);
        rolls = Immutable.list(rolls);
    }

    public DemoState with(List<String> newPlayers, DemoRound newRound, long newNextWindowId, List<Integer> newRolls) {
        return new DemoState(newPlayers.isEmpty() ? null : newPlayers.get(0), newPlayers, newRound, newNextWindowId, newRolls);
    }
}
