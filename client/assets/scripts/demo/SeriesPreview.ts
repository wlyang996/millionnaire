/** UI33 offline fixtures only; this code never awards money or sends series commands. */
import { SeriesState } from '../core/Models';
import { GLOBAL_RULES } from '../core/Rules';
import { ctx } from '../ui/Ctx';
import { ScreenId } from '../ui/Screen';

export function seriesPreview(kind: string): ScreenId | null {
    const st = ctx.store;
    if (st.online || !/^series-(setup|points|final|result)[48]$/.test(kind)) return null;
    const n = kind.endsWith('8') ? 8 : 4;
    st.patchScenario({ players: n, boardSize: n === 8 ? 50 : 30 });
    st.autoplay = false; st.clock.pause(); st.previewResult = null;
    const rules = { enabled: true, gameOptions: [3,5,7], defaultGames: 3,
        rankPoints: [10,6,4,3,2,1,0,0], tieMode: 'SHARED_RANK' as const, allowBots: true, minPlayers: 2 };
    GLOBAL_RULES.series = rules;
    st.session.hostId = st.myId;
    st.session.members.forEach(m => m.ready = true);
    const setup = kind.includes('setup'), final = kind.includes('final');
    const z: SeriesState = { seriesNo: 1, totalGames: 3, status: setup ? 'SETUP' : final ? 'FINISHED' : 'BETWEEN_GAMES',
        roster: setup ? [] : st.session.members.map(m => ({ ...m })), settings: setup ? null : { ...st.session.settings },
        rules, games: [], points: {}, withdrawn: [] };
    if (!setup) {
        const add = [[10,6,4,3,2,1,0,0], [6,10,4,3,2,1,0,0], n === 8 ? [10,10,4,3,2,1,0,0] : [10,6,4,3,2,1,0,0]];
        for (let game = 0; game < (final ? 3 : 2); game++) {
            z.games.push({ gameNo: game + 1, index: game + 1, reason: 'TIME_UP', scores: z.roster.map((m, i) => ({ playerId: m.playerId,
                rank: game === 1 && i < 2 ? 2 - i : game === 2 && n === 8 && i < 2 ? 1 : i + 1, points: add[game][i] })) });
            z.roster.forEach((m, i) => z.points[m.playerId] = (z.points[m.playerId] ?? 0) + add[game][i]);
        }
        const result = st.buildResult(); result.gameNo = z.games.length;
        st.previewResult = result; st.session.lastResult = result;
    }
    st.session.series = z;
    return setup ? 'series' : 'result';
}
