import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { GLOBAL_RULES, INITIAL_CASH_OPTIONS, TIME_LIMIT_OPTIONS, ROLL_SECONDS_OPTIONS, MAX_HAND, ROOM_ANIMATION } from '../core/Rules';
import { TeamId } from '../core/Models';
import { teamColor, teamName } from '../core/Teams';
import { art, informationCharacterKey } from '../ui/Art';
import { primaryButton, secondaryButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { text, onTap } from '../ui/Kit';
import { avatar, chip, Segmented } from '../ui/Widgets';
import { shareRoom } from '../net/Wx';
import { InformationPage, informationCard } from '../popups/InformationPage';
import { ScrollList } from '../ui/ScrollList';
import { ConfirmPopup } from '../popups/ConfirmPopup';

/** Screen34: two colored team panels; the settings link preserves all existing room controls. */
export function buildTeamRoom(root: Node): void {
    const st=ctx.store,s=st.session,t=s.team!,cfg=GLOBAL_RULES.team!,host=s.hostId===st.myId;
    art(root,'information_background',0,0,Theme.W,Theme.H,'stretch');
    text(root,'组队房间',160,100,400,70,44,Theme.c.navy,{bold:true});
    secondaryButton(root,'返回',32,110,100,58,()=>ctx.popups.open(new ConfirmPopup({title:'离开房间？',message:'离开后需要重新加入并选择队伍。',onConfirm:()=>{void st.online?.leave().then(r=>{if(r.ok)ctx.screens.go('lobby');});}})),24);
    const code=informationCard(root,24,188,672,112);
    text(code,'房间号',22,8,150,34,24,Theme.c.inkSoft,{align:'l'});
    text(code,s.roomId,20,42,320,62,56,Theme.c.navy,{bold:true,align:'l'});
    secondaryButton(code,'分享邀请',400,22,246,72,()=>void shareRoom(s.roomId,st.profile.nickname),28);
    const mode=new Segmented(root,24,312,672,56,[{label:'个人对战',value:0},{label:'组队合作',value:t.teamSize}],t.teamSize,v=>void st.online?.configureTeams(v as number));
    mode.locked=!host;
    const size=new Segmented(root,210,378,480,52,cfg.teamSizeOptions.map(n=>({label:n+'v'+n,value:n,disabled:s.settings.boardId==='classic-30'&&n>2,note:'请先在房间设置切换50格地图'})),t.teamSize,v=>void st.online?.configureTeams(v as number));
    text(root,'对战形式',32,378,170,52,28,Theme.c.navy,{bold:true,align:'l'}); size.locked=!host;
    (['BLUE','RED'] as TeamId[]).forEach((id,index)=>{
        const members=s.members.filter(m=>t.assignments[m.playerId]===id);
        const panel=informationCard(root,24,444+index*246,672,232,id==='BLUE'?'#E2F4FF':'#FFE8E9');
        text(panel,teamName(id)+' · '+members.length+'/'+t.teamSize,20,8,420,44,32,teamColor(id),{bold:true,align:'l'});
        const choose=secondaryButton(panel,t.assignments[st.myId]===id?'本队':'加入'+teamName(id),476,8,176,46,()=>void st.online?.chooseTeam(id),23);
        choose.setEnabled(t.assignments[st.myId]!==id && members.length<t.teamSize,t.assignments[st.myId]===id?'你已在此队':'队伍已满');
        const count=Math.max(t.teamSize,1), cw=632/count;
        for(let i=0;i<count;i++){
            const m=members[i], x=20+i*cw, cell=informationCard(panel,x,60,cw-10,162,'#FFFEFA',18,false);
            if(!m){text(cell,'等待加入',0,50,cw-10,44,23,Theme.c.inkSoft);continue;}
            if(m.bot)avatar(cell,(cw-86)/2,4,76,m.avatar,m.nickname);
            else art(cell,informationCharacterKey(m.avatar),(cw-102)/2,0,92,88);
            if(m.playerId===s.hostId)chip(cell,4,2,'房主','#FFB437','#FFFFFF',16,24);
            text(cell,m.nickname,0,90,cw-10,30,23,Theme.c.navy,{bold:true});
            text(cell,m.ready?'✓ 已准备':'未准备',0,122,cw-10,32,22,m.ready?Theme.c.greenDark:Theme.c.inkSoft);
            if(host && m.bot)onTap(cell,()=>void st.online?.assignBotTeam(m.playerId,id==='BLUE'?'RED':'BLUE'));
            else if(host && m.playerId!==st.myId)onTap(cell,()=>ctx.popups.open(new ConfirmPopup({title:'移除 '+m.nickname+'？',message:'成员移除后，全员需要重新准备。',onConfirm:()=>void st.online?.kick(m.playerId)})));
        }
    });
    const waiting=s.members.filter(m=>!t.assignments[m.playerId]);
    const notes=informationCard(root,24,948,672,150);
    text(notes,'各自资金与土地 · 队友'+(cfg.allyRentWaiver?'免租':'按配置付租'),16,8,640,38,27,Theme.c.navy,{bold:true});
    text(notes,waiting.length?'未分队：'+waiting.map(m=>m.nickname).join('、'):'开局后队伍固定，不能更换队友',16,48,640,32,21,Theme.c.inkSoft);
    const bots=waiting.filter(m=>m.bot);
    const botList=bots.length ? new ScrollList(notes,20,86,432,48) : null;
    bots.forEach((m,i)=>{
        const n=secondaryButton(botList!.content,m.nickname+' 分队',(i%2)*216,Math.floor(i/2)*50,208,46,()=>void st.online?.assignBotTeam(m.playerId,'BLUE'),20);
        n.setEnabled(host,'仅房主可分配机器人');
    });
    botList?.setContentHeight(Math.ceil(bots.length/2)*50);
    if(!waiting.some(m=>m.bot)) secondaryButton(notes,'地图 / 金额 / 计时设置',24,88,420,46,()=>ctx.popups.open(new TeamRoomSettingsPage()),23);
    else secondaryButton(root,'房间设置',24,1104,252,40,()=>ctx.popups.open(new TeamRoomSettingsPage()),20);
    if(host && cfg.allowBots && s.members.length<t.teamSize*2)secondaryButton(notes,'加机器人',470,88,180,46,()=>void st.online?.addBot(),22);
    const me=s.members.find(m=>m.playerId===st.myId),full=waiting.length===0 && s.members.length===t.teamSize*2;
    const start=primaryButton(root,host?'开始组队对局':me?.ready?'已准备，等待开局':'准备',24,1150,448,94,()=>{
        if(host)void st.online?.start(); else st.setReady(st.myId,true);
    },36);
    start.setEnabled(host?full&&st.allReady():!!t.assignments[st.myId]&&!me?.ready,host?'需要两队满员、选队并准备':'请先选队');
    secondaryButton(root,me?.ready?'取消准备':'准备',488,1150,208,94,()=>st.setReady(st.myId,!me?.ready),30);
}

class TeamRoomSettingsPage extends InformationPage {
    constructor(){super('team-settings','组队房间设置');}
    protected buildBody(p: Node): void {
        const st=ctx.store,s=st.session,host=s.hostId===st.myId;
        const panel=informationCard(p,24,218,672,876);
        const list=new ScrollList(panel,16,12,640,850);
        const rows:[string,{label:string;value:string|number;disabled?:boolean;note?:string}[],string|number,(v:unknown)=>void][]=[
            ['地图',[{label:'30格',value:'classic-30',disabled:s.members.length>4 || (s.team?.teamSize ?? 0)>2,note:'当前组队人数需50格地图'},{label:'50格',value:'classic-50'}],s.settings.boardId,v=>st.setSetting({boardId:v as 'classic-30'|'classic-50'})],
            ['初始资金',INITIAL_CASH_OPTIONS.map(n=>({label:String(n),value:n})),s.settings.initialCash,v=>st.setSetting({initialCash:v as number})],
            ['结束模式',[{label:'限时',value:'TIME_LIMIT'},{label:'破产',value:'BANKRUPTCY'}],s.settings.endMode,v=>st.setSetting({endMode:v as 'TIME_LIMIT'|'BANKRUPTCY'})],
            ['游戏时长',TIME_LIMIT_OPTIONS.map(n=>({label:n+'分钟',value:n,disabled:s.settings.endMode!=='TIME_LIMIT'})),s.settings.timeLimitMinutes,v=>st.setSetting({timeLimitMinutes:v as number})],
            ['投骰时间',ROLL_SECONDS_OPTIONS.map(n=>({label:n+'秒',value:n})),s.settings.rollSeconds,v=>st.setSetting({rollSeconds:v as number})],
            ['开局道具',Array.from({length:MAX_HAND+1},(_,n)=>({label:n?String(n):'无',value:n})),s.settings.initialCards,v=>st.setSetting({initialCards:v as number})],
            ['动画模式',[{label:'普通',value:'normal'},{label:'快速',value:'fast',disabled:!ROOM_ANIMATION.enabled}],s.settings.fastMode?'fast':'normal',v=>st.setSetting({fastMode:v==='fast'})],
        ];
        rows.forEach(([title,items,value,change],i)=>{
            text(list.content,title,8,i*116,624,44,28,Theme.c.navy,{bold:true,align:'l'});
            const sg=new Segmented(list.content,8,i*116+46,624,54,items,value,change,6,22);sg.locked=!host;
        });
        list.setContentHeight(rows.length*116);
        secondaryButton(p,'返回分队',40,1140,640,90,()=>this.close(),36);
    }
}
