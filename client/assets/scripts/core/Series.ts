import { SeriesState } from './Models';

/** Public totals come from the server; sorting only determines presentation and tied ranks. */
export function seriesRows(s: SeriesState) {
    const last = s.games[s.games.length - 1];
    const members = s.roster.map((m, seat) => ({ ...m, seat, points: s.points[m.playerId] ?? 0,
        added: last?.scores.find(p => p.playerId === m.playerId)?.points ?? null, withdrawn: s.withdrawn.includes(m.playerId) }));
    members.sort((a, b) => b.points - a.points || a.seat - b.seat);
    let rank = 0;
    return members.map((m, i) => { if (i === 0 || m.points !== members[i - 1].points) rank = i + 1; return { ...m, rank }; });
}
export function seriesLocked(s: SeriesState | null | undefined): boolean {
    return s?.status === 'PLAYING' || s?.status === 'BETWEEN_GAMES';
}
