import { Node } from 'cc';
import { GLOBAL_RULES } from '../core/Rules';
import { Theme } from '../core/Theme';
import { sameTeam, activeAidAvailable, teamColor, teamName } from '../core/Teams';
import { art } from '../ui/Art';
import { teamPose } from '../ui/TeamPoses';
import { Button, primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { text } from '../ui/Kit';
import { Keypad } from '../ui/Keypad';
import { avatar, chip } from '../ui/Widgets';
import { InformationPage, informationCard } from './InformationPage';
import { DebtPopup } from './DebtPopup';
import { ConfirmPopup } from './ConfirmPopup';

/** Approved screens35–37; independent dynamic character poses, all money is live. */
export class TeamAidPage extends InformationPage {
    private amount = '';
    private busy = false;
    private stateKey = '';
    private readonly gameNo: number;
    private readonly windowId: number;
    private readonly debtId: number;
    constructor(private readonly recipient: string, private readonly debtMode = false) {
        super('team-aid', debtMode ? recipient === ctx.store.myId ? '处理欠款' : '队友求助' : '援助队友');
        const g=ctx.store.game;
        this.gameNo=g.gameNo; this.debtId=debtMode ? g.debt?.debtId ?? 0 : 0;
        this.windowId=debtMode ? g.debt?.windowId ?? 0 : ctx.store.online?.myWindow('TURN')?.windowId
            ?? g.windows.find(w=>w.kind==='TURN' && w.owner===ctx.store.myId)?.windowId ?? 0;
    }
    protected buildBody(p: Node, w: number): void {
        const st=ctx.store, g=st.game, me=st.me(), target=st.player(this.recipient), cfg=GLOBAL_RULES.team;
        if (!target || !cfg) return;
        const debtor=this.debtMode && this.recipient===st.myId;
        const d=g.debt, win=g.windows.find(x=>x.windowId===this.windowId);
        const remaining=win ? Math.max(0,Math.ceil((win.deadline-(st.online?.now() ?? ctx.clock.now()))/1000)) : 0;
        const available=Math.max(0,me.cash-me.frozen), gap=this.debtMode ? d?.gap ?? 0 : 0;
        const max=Math.min(cfg.aidMaxAmount,available,this.debtMode ? gap : cfg.aidMaxAmount);
        const value=Number(this.amount || '0');
        if(this.debtMode){
            teamPose(p,'worry',target.avatar,196,170,328,268);
            art(p,'team_debt_thought',460,158,138,140);
        }
        else {
            teamPose(p,'give',me.avatar,46,172,330,270);
            teamPose(p,'receive',target.avatar,285,172,330,270);
        }
        const team=g.team?.assignments[target.playerId];
        chip(p,232,398,teamName(team)+' · '+target.nickname,teamColor(team),'#FFFFFF',24,44);
        if (this.debtMode) {
            const summary=informationCard(p,32,462,w-64,206,'#FFFCF1');
            [['应付',d?.amount ?? 0],['可用资金',Math.max(0,target.cash-target.frozen)],['缺口',gap]].forEach((row,i)=>{
                text(summary,String(row[0]),i*212,14,212,42,26,Theme.c.navy,{bold:true});
                art(summary,'info_cash',i*212+24,65,38,42);
                text(summary,String(row[1]),i*212+68,58,140,58,40,i===2?Theme.c.redDark:Theme.c.navy,{bold:true,align:'l'});
            });
            text(summary,'剩余 '+remaining+'秒 · 求助不延长计时',0,136,w-64,52,27,Theme.c.redDark,{bold:true});
        }
        if (debtor) {
            const list=informationCard(p,32,684,w-64,274);
            text(list,'可求助的队友',0,8,w-64,48,34,Theme.c.navy,{bold:true});
            const allies=g.players.filter(x=>x.playerId!==st.myId && x.life==='ALIVE' && sameTeam(st.myId,x.playerId));
            allies.forEach((a,i)=>{
                const x=16+i*(620/Math.max(1,allies.length));
                avatar(list,x,66,84,a.avatar,a.nickname);
                text(list,a.nickname,x,158,190,40,24,Theme.c.navy,{bold:true,align:'l'});
                text(list,'可用 '+Math.max(0,a.cash-a.frozen),x,198,190,38,23,Theme.c.navy,{align:'l'});
            });
            const ask=primaryButton(p,d?.aidRequested?'已向队友求助':'向队友求助',40,976,w-80,86,()=>{
                if(this.busy)return; this.busy=true;
                void st.online?.act('RequestTeamAid',{gameNo:this.gameNo,windowId:this.windowId,debtId:this.debtId}).finally(()=>{this.busy=false;this.rebuildBody();});
            },36);
            ask.setEnabled(!d?.aidRequested && remaining>0,'求助已发出或窗口已结束');
            const mortgage=secondaryButton(p,'应急抵押',40,1076,308,86,()=>{
                const debt=g.debt; if(!debt || !win)return;
                this.close(); ctx.popups.open(new DebtPopup(debt.amount,debt.creditor,1,0,{debtId:debt.debtId,windowId:win.windowId,deadline:win.deadline}));
            },32);
            mortgage.setEnabled(d?.mortgageAvailable!==false,'本次债务不能通过抵押补足，只能等待援助或清算');
            secondaryButton(p,'宣告破产',364,1076,316,86,()=>ctx.popups.open(new ConfirmPopup({title:'确认破产？',message:'队友还可继续游戏，本人的土地会按规则清算。',onConfirm:()=>{void st.online?.act('DeclareBankruptcy',{windowId:this.windowId});}})),30);
            text(p,'队友自愿出资 · 欠款处理结束前不开始下一回合',30,1180,w-60,48,23,Theme.c.navy,{bold:true});
            return;
        }
        const top=this.debtMode?684:466;
        const panel=informationCard(p,32,top,w-64,this.debtMode?410:610);
        avatar(panel,20,16,82,me.avatar,me.nickname); avatar(panel,340,16,82,target.avatar,target.nickname);
        text(panel,'我的可用资金',114,16,208,36,23,Theme.c.navy,{bold:true,align:'l'});
        text(panel,String(available),114,54,208,44,34,Theme.c.navy,{bold:true,align:'l'});
        text(panel,'队友现金',434,16,190,36,23,Theme.c.navy,{bold:true,align:'l'});
        text(panel,String(target.cash),434,54,190,44,34,Theme.c.navy,{bold:true,align:'l'});
        const amount=informationCard(panel,20,112,w-104,72,'#F4FBFF',20,false);
        text(amount,'援助金额',16,0,180,72,29,Theme.c.navy,{bold:true,align:'l'});
        text(amount,this.amount || '0',206,0,360,72,44,Theme.c.navy,{bold:true});
        const choices=cfg.aidQuickAmounts.filter(x=>x<=max);
        // Always expose the permitted maximum: full gap or available partial aid, even with six presets.
        if(this.debtMode && max>0 && !choices.includes(max)){
            if(choices.length>=6)choices.pop();
            choices.push(max);
        }
        choices.slice(0,6).forEach((n,i)=>new Button(panel,this.debtMode&&n===gap?'补足'+n:String(n),20+i*((w-104)/Math.max(1,choices.length)),196,(w-116)/Math.max(1,choices.length),56,value===n?'secondary':'soft',()=>{this.amount=String(n);this.rebuildBody();},26));
        if(!this.debtMode)new Keypad(panel,20,264,w-104,52,key=>{
            if(key==='清空')this.amount=''; else if(key==='⌫')this.amount=this.amount.slice(0,-1);
            else if(this.amount.length<7)this.amount=String(Number(this.amount+key)); this.rebuildBody();
        });
        if(this.debtMode) {
            text(panel,'单笔最多 '+max+' · 最多补足当前缺口',20,270,w-104,52,25,Theme.c.navy,{bold:true});
            text(panel,'队友到账后继续处理欠款，不延长计时',20,330,w-104,48,23,Theme.c.inkSoft);
        }
        const after=informationCard(p,32,this.debtMode?1094:1078,w-64,62,'#E2F3FF',22,false);
        text(after,this.debtMode?'援助后我的可用资金 '+(available-value):'转出后可用 '+(available-value)+' · 队友现金 '+(target.cash+value),0,0,w-64,31,24,Theme.c.navy,{bold:true});
        text(after,'自愿援助，不计月度奖励',0,31,w-64,31,20,Theme.c.navy);
        new Button(p,this.debtMode?'暂不援助':'取消',40,1168,252,86,'ghost',()=>this.close(),34);
        const valid=this.debtMode ? !!d?.aidRequested && d.debtId===this.debtId && d.segment===0 && sameTeam(st.myId,this.recipient) : activeAidAvailable();
        const send=primaryButton(p,'确认援助 '+value,310,1168,370,86,()=>{
            if(this.busy)return;this.busy=true;
            void st.online?.act('TeamAid',{gameNo:this.gameNo,windowId:this.windowId,recipient:this.recipient,amount:value,debtId:this.debtId})
                .then(r=>{if(r.ok)this.close();}).finally(()=>{this.busy=false;if(!this.closed)this.rebuildBody();});
        },32);
        send.setEnabled(valid && value>0 && value<=max && !this.busy && (!this.debtMode || remaining>0 && !win?.paused),'金额须在可用资金、单笔上限与当前缺口内，且援助窗口有效');
    }
    tick(): void {
        if(this.closed)return;
        const st=ctx.store,g=st.game;
        if(g.gameNo!==this.gameNo || !g.windows.some(w=>w.windowId===this.windowId)
            || this.debtMode && (!g.debt || g.debt.debtId!==this.debtId || g.debt.segment!==0)) {this.close();return;}
        const key=JSON.stringify([Math.floor((st.online?.now() ?? ctx.clock.now())/1000),g.debt,g.players.map(p=>[p.cash,p.frozen])]);
        if(key!==this.stateKey){this.stateKey=key;this.rebuildBody();}
    }
}
