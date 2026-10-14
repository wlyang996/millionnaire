import { Node } from 'cc';
import { EXPERIENCE } from '../core/Rules';
import { Theme } from '../core/Theme';
import { softButton } from '../ui/Buttons';
import { gfx,fillRR,mk,onTap,text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Reminders } from '../ui/Reminder';
export class ReminderSettingsPopup extends Popup {
    constructor(){super('reminder-settings','回合提醒',620,500,0,true);}
    protected buildBody(p:Node,w:number):void{
        const rows:[keyof Pick<typeof Reminders,'enabled'|'sound'|'haptic'>,string,boolean][]=[
            ['enabled','轮到我时提醒',EXPERIENCE.turnReminderEnabled],['sound','短提示音（遵从声音设置）',EXPERIENCE.turnSoundEnabled],
            ['haptic','轻震动（微信支持时生效）',EXPERIENCE.hapticAllowed]];
        rows.forEach(([key,label,allowed],i)=>{const n=mk(p,key,28,94+i*82,w-56,68);fillRR(gfx(n),0,0,w-56,68,18,Theme.c.boxBeige);
            text(n,label,16,0,w-180,68,26,Theme.c.navy,{align:'l',bold:true});
            text(n,allowed?(Reminders[key]?'开启':'关闭'):'未开放',w-170,0,106,68,26,allowed?Theme.c.green:Theme.c.noteGray,{bold:true});
            onTap(n,()=>{if(allowed){Reminders[key]=!Reminders[key];Reminders.save();this.rebuildBody();}});
        });
        text(p,'震动默认关闭；每回合只提示一次。',28,348,w-56,42,24,Theme.c.noteGray);
        softButton(p,'关闭',70,410,w-140,68,()=>this.close(),30);
    }
}
