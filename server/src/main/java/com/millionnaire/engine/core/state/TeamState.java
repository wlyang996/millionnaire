package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.Map;

/** Public team membership; cash and title to land remain personal. Null means an ordinary game. */
public record TeamState(int teamSize, Map<String, TeamId> assignments, long lockedGameNo) {
    public enum TeamId { BLUE, RED }
    public TeamState { assignments = Immutable.sortedMap(assignments); }
    public boolean locked() { return lockedGameNo != 0; }
    public boolean sameTeam(String a, String b) {
        return a != null && b != null && assignments.get(a) != null && assignments.get(a) == assignments.get(b);
    }
}
