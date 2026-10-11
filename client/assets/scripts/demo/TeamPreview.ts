/** Offline visual QA fixtures for approved screens34–36. No commands, transfers or records. */
import { TeamState } from '../core/Models';
import { GLOBAL_RULES } from '../core/Rules';
import { ctx } from '../ui/Ctx';
import { ScreenId } from '../ui/Screen';
import { AssetsPopup } from '../popups/AssetsPopup';
import { TileInfoPopup } from '../popups/TileInfoPopup';
import { TeamAidPage } from '../popups/TeamAidPage';

export function teamPreview(kind: string): ScreenId | null {
    const st=ctx.store;
    if(st.online || !/^team-(room|board|assets|land|enemy|aid|debt|help|result)[468]$/.test(kind))return null;
    const n=Number(kind.slice(-1)); st.patchScenario({players:n,boardSize:n>4?50:30});
    st.autoplay=false;st.clock.pause();st.previewResult=null;
    GLOBAL_RULES.team={enabled:true,teamSizeOptions:[2,3,4],defaultTeamSize:2,allowBots:true,allyRentWaiver:true,
        allyRoadblockPass:true,aidEnabled:true,aidMaxAmount:5000,aidPerTurn:1,aidQuickAmounts:[100,300,500],debtAidEnabled:true,debtAidWindowMs:20000};
    const g=st.game, team:TeamState={teamSize:n/2,assignments:{},lockedGameNo:1,aidUsages:{}};
    g.players.forEach((p,i)=>team.assignments[p.playerId]=i<n/2?'BLUE':'RED');
    g.team=team;st.session.team=team;st.session.hostId=st.myId;st.session.members.forEach(m=>m.ready=true);
    g.windows=[{windowId:700,kind:'TURN',owner:st.myId,opensAt:st.clock.now(),deadline:st.clock.now()+20000,paused:false}];
    const ally=g.players.find(p=>p.playerId!==st.myId && team.assignments[p.playerId]===team.assignments[st.myId])!;
    const enemy=g.players.find(p=>team.assignments[p.playerId]!==team.assignments[st.myId])!;
    const props=g.properties.slice(0,4);props.forEach((p,i)=>{p.owner=i<2?ally.playerId:enemy.playerId;p.level=i%3;});
    if(kind.includes('debt') || kind.includes('help')){
        const debtor=kind.includes('debt')?st.myId:ally.playerId;
        g.players.find(p=>p.playerId===debtor)!.cash=100;
        g.debt={debtId:1,debtor,creditor:enemy.playerId,amount:500,segment:0,continued:false,continueAvailable:false,windowId:700,aidRequested:kind.includes('help'),gap:400,mortgageAvailable:true};
        g.windows=[{windowId:700,kind:'DEBT',owner:debtor,opensAt:st.clock.now(),deadline:st.clock.now()+20000,paused:false}];
    }
    if(kind.includes('result')){
        const r=st.buildResult();
        // Ordinary demo injects individual ties/elimination; team QA uses actual fixture balances instead.
        r.standings.forEach(p=>{const live=st.player(p.playerId)!;p.cash=live.cash;p.netWorth=st.netWorthOf(p.playerId);p.bankrupt=live.life!=='ALIVE';});
        r.standings.sort((a,b)=>b.netWorth-a.netWorth);
        r.standings.forEach((r,i,rows)=>r.rank=i>0 && r.netWorth===rows[i-1].netWorth ? rows[i-1].rank : i+1);
        r.teamStandings=(['BLUE','RED'] as const).map((id,i)=>({teamId:id,rank:i+1,
            netWorth:g.players.filter(p=>team.assignments[p.playerId]===id).reduce((sum,p)=>sum+st.netWorthOf(p.playerId),0),
            members:g.players.filter(p=>team.assignments[p.playerId]===id).map(p=>p.playerId),survivors:g.players.filter(p=>team.assignments[p.playerId]===id).map(p=>p.playerId)}));
        r.teamStandings.sort((a,b)=>b.netWorth-a.netWorth);
        r.teamStandings.forEach((r,i,rows)=>r.rank=i>0 && r.netWorth===rows[i-1].netWorth ? rows[i-1].rank : i+1);
        st.previewResult=r;st.session.lastResult=r;return 'result';
    }
    return kind.includes('room')?'room':'board';
}
export function teamPreviewPopup(kind: string): void {
    if(ctx.store.online || !/^team-/.test(kind))return;
    const st=ctx.store,g=st.game,t=g.team;if(!t)return;
    const ally=g.players.find(p=>p.playerId!==st.myId && t.assignments[p.playerId]===t.assignments[st.myId])!;
    if(kind.includes('assets'))ctx.popups.open(new AssetsPopup(ally.playerId));
    else if(kind.includes('enemy'))ctx.popups.open(new TileInfoPopup(g.properties[3].tileIndex));
    else if(kind.includes('land'))ctx.popups.open(new TileInfoPopup(g.properties[0].tileIndex));
    else if(kind.includes('aid'))ctx.popups.open(new TeamAidPage(ally.playerId));
    else if(kind.includes('debt'))ctx.popups.open(new TeamAidPage(st.myId,true));
    else if(kind.includes('help'))ctx.popups.open(new TeamAidPage(ally.playerId,true));
}
