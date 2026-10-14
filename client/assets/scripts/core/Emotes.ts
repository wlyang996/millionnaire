export const EMOTES={CHEER:'加油！',THANKS:'谢谢',WOW:'太强了',LAUGH:'哈哈',SORRY:'抱歉',GOOD_GAME:'好局！'} as const;
export type EmoteId=keyof typeof EMOTES;
export interface EmoteBubble{playerId:string;id:EmoteId;endsAt:number;sequence:number}
/** Only fixed, public identifiers are accepted; expired/repeated bubbles never replay. */
export class EmoteState{
    private latest=new Map<string,EmoteBubble>();
    receive(raw:unknown,now:number):boolean{
        const v=raw as Partial<EmoteBubble>|null;
        if(!v||typeof v.playerId!=='string'||!v.id||!Object.prototype.hasOwnProperty.call(EMOTES,v.id)
            ||!Number.isFinite(v.endsAt)||!Number.isFinite(v.sequence)||v.endsAt!<=now)return false;
        const old=this.latest.get(v.playerId);
        if(old&&old.sequence>=v.sequence!)return false;
        this.latest.set(v.playerId,v as EmoteBubble);return true;
    }
    get(id:string,now:number):EmoteBubble|undefined{
        const v=this.latest.get(id);return v&&v.endsAt>now?v:undefined;
    }
    clear():void{this.latest.clear();}
}
