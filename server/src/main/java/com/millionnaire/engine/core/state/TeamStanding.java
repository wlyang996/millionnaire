package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** Team totals include only surviving personal accounts; the frozen roster includes eliminated members. */
public record TeamStanding(TeamState.TeamId teamId, int rank, long netWorth,
                           List<String> members, List<String> survivors) {
    public TeamStanding {
        members = Immutable.list(members);
        survivors = Immutable.list(survivors);
    }
}
