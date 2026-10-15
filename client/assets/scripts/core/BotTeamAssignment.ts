import type { TeamId, TeamState } from './Models';

/** Unassigned bots fill the blue team first, then the red team, within the room's capacity. */
export function botTeamDestination(team: Pick<TeamState, 'teamSize' | 'assignments'>, playerId: string): TeamId | null {
    if (team.assignments[playerId]) return null;
    for (const id of ['BLUE', 'RED'] as const) {
        if (Object.values(team.assignments).filter(value => value === id).length < team.teamSize) return id;
    }
    return null;
}
