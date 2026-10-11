import { GameView, TeamId } from './Models';
import { ctx } from '../ui/Ctx';
import { GLOBAL_RULES } from './Rules';
export function teamName(id?: TeamId): string { return id === 'BLUE' ? '蓝队' : id === 'RED' ? '红队' : '未分队'; }
export function teamColor(id?: TeamId): string { return id === 'RED' ? '#E84256' : '#168AFE'; }
export function sameTeam(a: string, b: string): boolean {
    const t = ctx.store.game.team;
    return !!t?.assignments[a] && t.assignments[a] === t.assignments[b];
}
export function teamTotal(g: GameView, id: TeamId): number {
    return g.players.filter(p => p.life === 'ALIVE' && g.team?.assignments[p.playerId] === id)
        .reduce((sum, p) => sum + ctx.store.netWorthOf(p.playerId), 0);
}
export function activeAidAvailable(): boolean {
    const st=ctx.store, g=st.game, cfg=GLOBAL_RULES.team;
    const usage=g.team?.aidUsages?.[st.myId];
    const window=st.online?.myWindow('TURN');
    return !!cfg?.aidEnabled && (!usage || usage.turnNo !== g.turnNo || usage.count < cfg.aidPerTurn)
        && !!st.online && !!window && st.online.isOpen(window) && !!g.team && g.currentPlayer === st.myId && !g.debt
        && ['PRE_ROLL','JAIL_DECISION'].includes(g.stage) && g.windows.length === 1;
}
