package com.millionnaire.engine.core.event;

import com.millionnaire.engine.core.state.TeamState.TeamId;

/** Configuration/selection facts require a matching authenticated accepted command during replay. */
public sealed interface TeamEvent extends PublicEvent {
    record TeamsConfigured(int teamSize) implements TeamEvent { }
    record TeamSelected(String playerId, TeamId teamId) implements TeamEvent { }
}
