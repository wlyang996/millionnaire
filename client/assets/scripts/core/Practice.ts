import { buildBoard } from './BoardLayout';
import { GameView, Profile, ROBOT_AVATAR } from './Models';
import { INITIAL_CASH_OPTIONS, landPrice, rentOf, TIERS } from './Rules';
export type PracticeStep='ROLL'|'BUY'|'RENT'|'EVENT'|'CARD'|'DONE';
/** Scripted learning sandbox: no OnlineSession, persistence, history or real room references. */
export class Practice {
    step:PracticeStep='ROLL';
    readonly game:GameView;
    readonly land:number;
    readonly event:number;
    constructor(profile:Profile,private readonly eventReward:number){
        const tiles=buildBoard(30);
        this.land=tiles.findIndex(t=>t.type==='PROPERTY');
        this.event=tiles.findIndex(t=>t.type==='EVENT');
        const price=landPrice(false,tiles[this.land].tier);
        const player=(id:string,nickname:string,avatar:number)=>({playerId:id,nickname,avatar,position:0,
            cash:Math.max(price,...INITIAL_CASH_OPTIONS),frozen:0,handCount:0,life:'ALIVE' as const,inJail:false,jailFailures:0,control:'MANUAL' as const,conn:'ONLINE' as const});
        this.game={gameNo:0,phase:'PLAYING',boardId:'classic-30',turnNo:1,currentPlayer:'practice-me',stage:'PRE_ROLL',
            players:[player('practice-me',profile.nickname||'我',profile.avatar),player('practice-bot','练习伙伴',ROBOT_AVATAR)],
            orderDraws:[],globalEndsAt:Number.MAX_SAFE_INTEGER,windows:[],myHand:[],tiles,lastDice:this.land,chat:[],
            properties:tiles.filter(t=>t.type==='PROPERTY'||t.type==='STATION').map(t=>({tileIndex:t.index,owner:null,level:0,mortgaged:false,mortgagePaid:0,upgradeSpent:0}))};
    }
    get me(){return this.game.players[0];}
    get bot(){return this.game.players[1];}
    get property(){return this.game.properties.find(p=>p.tileIndex===this.land)!;}
    get price(){return landPrice(false,this.game.tiles[this.land].tier);}
    get rent(){return rentOf(this.game.tiles[this.land].tier??'LOW',this.property.level);}
    advance():boolean{
        switch(this.step){
            case 'ROLL':this.me.position=this.land;this.step='BUY';break;
            case 'BUY':
                if(this.me.cash<this.price)return false;
                this.me.cash-=this.price;this.property.owner=this.me.playerId;this.step='RENT';break;
            case 'RENT':
                this.bot.position=this.land;this.bot.cash-=this.rent;this.me.cash+=this.rent;this.me.position=this.event;this.step='EVENT';break;
            case 'EVENT':
                this.me.cash+=this.eventReward;this.game.myHand=[{id:'practice-build',type:'BUILD'}];this.me.handCount=1;
                this.me.position=this.land;this.step='CARD';break;
            case 'CARD':
                this.property.level++;this.property.upgradeSpent=TIERS[this.game.tiles[this.land].tier??'LOW'].upgrade;
                this.game.myHand=[];this.me.handCount=0;this.step='DONE';break;
            default:return false;
        }
        return true;
    }
}
