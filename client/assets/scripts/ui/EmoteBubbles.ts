import { Node } from 'cc';
import { EMOTES } from '../core/Emotes';
import { EXPERIENCE } from '../core/Rules';
import { ctx } from './Ctx';
import { fillRR,gfx,mk,text } from './Kit';
const bubbles=new Map<Node,number>();
export function drawEmoteBubble(parent:Node,id:string,x:number,y:number,w=140):void{
    if(!EXPERIENCE.emotesEnabled)return;
    const now=ctx.store.online?.now()??Date.now(),v=ctx.store.emotes.get(id,now);
    if(!v)return;
    const n=mk(parent,'EmoteBubble',x,y,w,42);fillRR(gfx(n),0,0,w,42,16,'#FFF3C1');
    text(n,EMOTES[v.id],0,0,w,42,23,'#101A50',{bold:true});bubbles.set(n,v.endsAt);
}
export function tickEmoteBubbles():void{
    const now=ctx.store.online?.now()??Date.now();
    for(const [node,end]of bubbles){
        if(!node.isValid){bubbles.delete(node);continue;}
        if(now>=end){node.active=false;bubbles.delete(node);}
    }
}
