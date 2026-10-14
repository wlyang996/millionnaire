import { Practice } from '../core/Practice';
import { EVENT_CASH, rentOf } from '../core/Rules';
import { ConfirmPopup } from '../popups/ConfirmPopup';
import { Theme } from '../core/Theme';
import { BoardView } from './board/BoardView';
import { ctx } from '../ui/Ctx';
import { Screen } from '../ui/Screen';
import { avatar } from '../ui/Widgets';
import { art } from '../ui/Art';
import { primaryButton,softButton } from '../ui/Buttons';
import { fillRR,gfx,mk,text } from '../ui/Kit';
import { Sound } from '../audio/Sound';
import { DiceView } from '../ui/DiceView';
import { EventOverlay } from './board/EventOverlay';
import { EventDrawState } from '../core/EventDraw';
/** Reuses the production board art while retaining a separate, local model. */
export class PracticeScreen extends Screen {
    readonly id='practice' as const;readonly title='新手练习';
    private lesson=new Practice(ctx.store.profile,EVENT_CASH.min);
    private view:BoardView|null=null;
    private working=false;
    private dice:DiceView|null=null;
    private eventOverlay:EventOverlay|null=null;
    private flippingAt=0;
    refresh():void{} // Real room updates must not reset a lesson or destroy its animation.
    protected build():void{
        this.backdrop('sky');this.header('新手练习',()=>ctx.screens.back('lobby'));
        text(this.root,'独立练习 · 可反复进入 · 不计入战绩',30,104,660,40,25,Theme.c.navy,{bold:true});
        this.lesson.game.players.forEach((p,i)=>{
            const n=mk(this.root,'PracticePlayer'+i,40+i*330,154,310,92);fillRR(gfx(n),0,0,310,92,22,Theme.c.ivory);
            avatar(n,12,12,68,p.avatar,p.nickname);text(n,p.nickname,94,8,206,36,25,Theme.c.navy,{bold:true,align:'l'});
            text(n,'现金 '+p.cash,94,48,206,36,25,Theme.c.navy,{bold:true,align:'l'});
        });
        const model=this.lesson,g=model.game,tile=g.tiles[model.land];
        this.view=new BoardView(this.root,24,266,672,704,null);this.view.render(g,model.me.playerId,model.me.nickname,'fan',model.me.position);
        this.dice=null;
        this.eventOverlay=null;
        if(model.step==='ROLL'){this.dice=new DiceView(this.root,306,580);this.dice.setReady(true);}
        if(model.step==='EVENT'||model.step==='EVENT_RESULT'){
            const state:EventDrawState={phase:model.step==='EVENT_RESULT'?'RESULT':this.flippingAt?'FLIPPING':'WAITING',
                actor:model.me.playerId,since:this.flippingAt||Date.now(),settled:model.step==='EVENT_RESULT'?1:0,
                result:this.flippingAt||model.step==='EVENT_RESULT'?{kind:'CASH_REWARD',amount:EVENT_CASH.min,card:null,steps:0,label:'现金奖励'}:null};
            this.eventOverlay=new EventOverlay(this.root);
            this.eventOverlay.build(state,()=>this.act(),model.step==='EVENT',model.me.nickname);
        }
        const descriptions={
            ROLL:['1 / 4 · 投骰与买地','练习骰子将带你到 '+tile.name+'。','投骰到达'],
            BUY:['1 / 4 · 买下第一块地',tile.name+'：购买价 '+model.price+'。买地后现金减少，土地归你。','购买 '+model.price],
            RENT:['2 / 4 · 收取租金','练习伙伴来到你的土地，将向你支付租金 '+model.rent+'。','结算租金'],
            EVENT:['3 / 4 · 事件抽卡','现在来到事件格。练习事件是现金奖励 '+EVENT_CASH.min+'，点卡按钮查看结果。','翻开事件卡'],
            EVENT_RESULT:['3 / 4 · 事件结果','已获得现金 '+EVENT_CASH.min+'。接下来获得建造卡，回自己的土地练习用卡。','继续练习用卡'],
            CARD:['4 / 4 · 预览并用卡','你获得建造卡。返回自己的土地，可免费升一级；确认后消耗一张卡。','预览建造效果'],
            DONE:['练习完成','已练习买地、收租、事件和用卡。实际对局事件随机，规则以房间参数为准。','再练一次'],
        } as const;
        const [title,body,label]=descriptions[model.step];
        const panel=mk(this.root,'PracticeGuide',40,934,640,270);fillRR(gfx(panel),0,0,640,270,26,Theme.c.ivory);
        text(panel,title,22,14,596,46,32,Theme.c.navy,{bold:true,align:'l'});
        text(panel,body,22,70,596,100,27,Theme.c.navy,{align:'l',wrap:true,lineHeight:34});
        primaryButton(panel,label,22,182,596,68,()=>this.act(),30);
        softButton(this.root,'退出练习',230,1212,260,54,()=>ctx.screens.back('lobby'),25);
    }
    private act():void{
        if(this.working)return;
        if(this.lesson.step==='EVENT'){
            this.working=true;this.flippingAt=Date.now();Sound.effect('card');this.rebuild();return;
        }
        if(this.lesson.step==='EVENT_RESULT')this.flippingAt=0;
        if(this.lesson.step==='ROLL'&&this.dice){
            this.working=true;
            this.dice.play(this.lesson.land,()=>{this.working=false;this.lesson.advance();this.rebuild();});
            return;
        }
        if(this.lesson.step==='DONE'){this.lesson=new Practice(ctx.store.profile,EVENT_CASH.min);this.rebuild();return;}
        if(this.lesson.step==='CARD'){
            const m=this.lesson;const before=m.rent;
            ctx.popups.open(new ConfirmPopup({title:'练习 · 建造效果预览',
                message:m.game.tiles[m.land].name+'：未升级 → 1级\n租金 '+before+' → '+rentOf(m.game.tiles[m.land].tier??'LOW',1)+'\n免费建造；消耗1张建造卡与用卡机会。',
                confirmText:'确认建造',onConfirm:()=>{m.advance();Sound.effect('upgrade');this.rebuild();}}));
            return;
        }
        if(this.lesson.step==='BUY')Sound.effect('purchase');
        else if(this.lesson.step==='RENT')Sound.effect('cashGain');
        this.lesson.advance();this.rebuild();
    }
    tick(dt:number):void{
        this.view?.tick(dt);this.dice?.update();this.eventOverlay?.tick(Date.now());
        if(this.working&&this.flippingAt&&Date.now()-this.flippingAt>=Theme.anim.eventFlipMs){
            this.working=false;this.lesson.advance();Sound.effect('cashGain');this.rebuild();
        }
    }
}
