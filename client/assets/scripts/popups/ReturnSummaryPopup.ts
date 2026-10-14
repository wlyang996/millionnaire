import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { softButton } from '../ui/Buttons';
import { text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';
export interface ReturnSummary{lines:string[];truncated:boolean;current:string}
export class ReturnSummaryPopup extends Popup{
    constructor(private readonly summary:ReturnSummary){super('return-summary','返回后的变化',660,840,0,true);}
    protected buildBody(p:Node,w:number):void{
        text(p,'当前：'+this.summary.current,26,94,w-52,46,29,Theme.c.navy,{bold:true,align:'l'});
        text(p,'倒计时照常进行；这里只显示公开结果。',26,144,w-52,40,23,Theme.c.noteGray);
        const list=new ScrollList(p,26,200,w-52,500);
        this.summary.lines.forEach((line,i)=>text(list.content,line,10,i*94,w-72,86,27,Theme.c.navy,{align:'l',wrap:true,lineHeight:34}));
        list.setContentHeight(this.summary.lines.length*94);
        if(this.summary.truncated)text(p,'较早变化已折叠，展示最近记录。',26,708,w-52,36,23,Theme.c.noteGray);
        softButton(p,'继续对局',80,764,w-160,62,()=>this.close(),30);
    }
}
