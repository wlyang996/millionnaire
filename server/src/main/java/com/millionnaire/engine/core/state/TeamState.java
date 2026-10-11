package com.millionnaire.engine.core.state;

import com.millionnaire.engine.serialize.Immutable;
import java.util.Map;

/** Public team membership; cash and title to land remain personal. Null means an ordinary game. */
public record TeamState(int teamSize, Map<String, TeamId> assignments, long lockedGameNo, Map<String, AidUsage> aidUsages) {
    public record AidUsage(long turnNo, int count) { }
    public TeamState(int teamSize, Map<String, TeamId> assignments, long lockedGameNo) {
        this(teamSize, assignments, lockedGameNo, java.util.Collections.emptyMap());
    }
    public enum TeamId { BLUE, RED }
    public TeamState { assignments = Immutable.sortedMap(assignments); aidUsages = Immutable.sortedMap(aidUsages == null ? java.util.Collections.emptyMap() : aidUsages); }
    public int aidCount(String player, long turn) {
        var usage = aidUsages.get(player);
        return usage != null && usage.turnNo() == turn ? usage.count() : 0;
    }
    public TeamState usedAid(String player, long turn) {
        var usages = new java.util.TreeMap<>(aidUsages);
        usages.put(player, new AidUsage(turn, Math.addExact(aidCount(player, turn), 1)));
        return new TeamState(teamSize, assignments, lockedGameNo, usages);
    }
    public boolean locked() { return lockedGameNo != 0; }
    public boolean sameTeam(String a, String b) {
        return a != null && b != null && assignments.get(a) != null && assignments.get(a) == assignments.get(b);
    }
}
