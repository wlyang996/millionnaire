/** UI32 review fixtures. Called only by the offline ?demo=1 mode; never sends or settles gameplay. */
import { GameResult } from '../core/Models';
import { GLOBAL_RULES } from '../core/Rules';
import { ctx } from '../ui/Ctx';
import { ScreenId } from '../ui/Screen';

export function globalPreview(kind: string): ScreenId | null {
    const st = ctx.store;
    if (st.online || !/^(reward|city|badge|titles)[48]$/.test(kind)) return null;
    const count = kind.endsWith('8') ? 8 : 4;
    st.patchScenario({ players: count, boardSize: count === 8 ? 50 : 30 });
    st.autoplay = false; st.clock.pause();
    const g = st.game, now = st.clock.now();
    GLOBAL_RULES.roundReward = { enabled: true, name: '月度荣耀奖励', timeLimitRewardRound: 5,
        bankruptcyRewardRound: 5, rewardPercent: 5, perPlayerCap: 1000, roundingUnit: 10,
        incomeSources: ['RENT', 'START', 'EVENT', 'MINIGAME', 'COMMISSION'], presentationMs: 3000 };
    const city = { id: 2, startRound: 6, endRound: 8, spec: { kind: 'UPGRADE_DISCOUNT' as const,
        enabled: true, name: '建设节', weight: 1, multiplierPercent: 80, durationRounds: 2, artworkKey: 'city_construction' } };
    const income = [8000, 6000, 4000, 3000, 2800, 2400, 1800, 1000];
    const awards = g.players.map((p, i) => ({ playerId: p.playerId, income: income[i], reward: Math.floor(income[i] * .05 / 10) * 10 }));
    g.round = 6;
    g.progress = { metrics: {}, observedRanks: {}, rewardGranted: true,
        cityEvent: kind.startsWith('reward') || kind.startsWith('titles') ? null : city,
        notices: kind.startsWith('badge') || kind.startsWith('titles') ? [] : [{ id: 1,
            kind: kind.startsWith('reward') ? 'ROUND_REWARD' : 'CITY_ANNOUNCED', opensAt: now,
            endsAt: now + 3000, completedRound: 5, awards: kind.startsWith('reward') ? awards : [],
            event: kind.startsWith('reward') ? null : city }] };
    if (kind.startsWith('titles')) {
        const result: GameResult = st.buildResult();
        const kinds = ['RENT_KING', 'PROPERTY_TYCOON', 'LUCKY_STAR', 'COMEBACK'];
        const names = ['收租王', '地产大亨', '幸运之星', '逆风翻盘'];
        result.titles = kinds.map((kind, i) => ({ kind, name: names[i], playerId: g.players[i].playerId,
            value: [3200, 5, 1500, 2][i], previousRank: 4, finalRank: 2 }));
        st.previewResult = result;
        return 'result';
    }
    st.previewResult = null;
    return 'board';
}
