import { SEvent } from '../net/Protocol';
export interface PublicChange extends SEvent {id:number;gameNo:number}
/** No old animation replay. First join/new game establishes a baseline without a summary. */
export class ReturnChanges{
    private key='';private latest=0;private baseline=0;private away=false;private awaiting=false;
    private changes:PublicChange[]=[];private ready=false;
    get suppressAnimations():boolean{return this.away||this.awaiting;}
    leave():void{if(this.key&&!this.away){this.baseline=this.latest;this.away=true;}}
    resume():boolean{if(!this.away)return false;this.away=false;this.awaiting=true;return true;}
    update(key:string,raw:PublicChange[],gameNo:number):void{
        const changes=raw.filter(v=>Number.isSafeInteger(v.id)&&v.id>0&&v.gameNo===gameNo);
        const latest=changes.length?changes[changes.length-1].id:this.latest;
        if(key!==this.key){this.key=key;this.latest=latest;this.baseline=latest;this.away=false;this.awaiting=false;this.ready=false;this.changes=[];return;}
        this.changes=changes;this.latest=latest;
        if(this.awaiting){this.awaiting=false;this.ready=true;}
        else if(!this.away&&!this.ready)this.baseline=this.latest;
    }
    take():{changes:PublicChange[];truncated:boolean}|null{
        if(!this.ready)return null;this.ready=false;
        const missed=this.changes.filter(c=>c.id>this.baseline);
        const result={changes:missed,truncated:missed.length>0&&missed[0].id>this.baseline+1};
        this.baseline=this.latest;return result;
    }
    clear():void{this.key='';this.latest=0;this.baseline=0;this.away=false;this.awaiting=false;this.ready=false;this.changes=[];}
}
