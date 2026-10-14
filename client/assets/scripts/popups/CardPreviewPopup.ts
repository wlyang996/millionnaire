import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { primaryButton,softButton } from '../ui/Buttons';
import { fillRR,gfx,mk,text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';
/** A preview is advisory. The original server command remains the only authority. */
export class CardPreviewPopup extends Popup {
    private snapshot:string;
    constructor(title:string,private readonly describe:()=>string[],private readonly fingerprint:()=>string,
        private readonly usable:()=>boolean,private readonly submit:()=>void){
        super('card-preview',title+' · 效果预览',660,680,0,false);
        this.snapshot=fingerprint();
    }
    protected buildBody(p:Node,w:number):void{
        const rows=this.describe();
        rows.forEach((s,i)=>{const n=mk(p,'Preview'+i,26,94+i*72,w-52,64);fillRR(gfx(n),0,0,w-52,64,16,Theme.c.boxBeige);
            text(n,s,16,0,w-84,64,25,Theme.c.navy,{bold:i===0,align:'l',wrap:true,lineHeight:30});});
        text(p,'以服务端实际结算为准；取消不会消耗道具。',26,536,w-52,40,23,Theme.c.noteGray);
        softButton(p,'取消',26,588,240,70,()=>this.close(),30);
        primaryButton(p,'确认使用',282,588,w-308,70,()=>{
            if(!this.usable()){this.close();Toast.show('使用时机已结束或目标已不可用');return;}
            if(this.snapshot!==this.fingerprint()){this.snapshot=this.fingerprint();this.rebuildBody();Toast.show('状态已变化，请重新查看后确认');return;}
            this.close();this.submit();
        },30);
    }
    tick():void{if(!this.closed&&!this.usable()){this.close();Toast.show('道具预览已失效，请重新选择');}}
}
