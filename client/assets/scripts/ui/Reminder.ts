import { game, Game, sys } from 'cc';
import { TurnReminder } from '../core/TurnReminder';
const KEY='millionnaire-reminders-v1';
const latch=new TurnReminder();
let visible=true;
game.on(Game.EVENT_HIDE,()=>visible=false);game.on(Game.EVENT_SHOW,()=>visible=true);
export const Reminders = {
    enabled:true, sound:true, haptic:false, until:0,
    take(key:string, actionable:boolean):boolean {
        return latch.take(key, actionable, visible && (typeof document==='undefined'||!document.hidden));
    },
    save():void {
        try{sys.localStorage.setItem(KEY,JSON.stringify({enabled:this.enabled,sound:this.sound,haptic:this.haptic}));}catch{}
    },
};
try{const v=JSON.parse(sys.localStorage.getItem(KEY)??'{}');for(const k of ['enabled','sound','haptic'] as const)if(typeof v[k]==='boolean')Reminders[k]=v[k];}catch{}
