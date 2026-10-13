/** Approved sound-settings v1: two toggles/sliders, local auto-save and restore defaults. */
import { BlockInputEvents, Node, UITransform, Vec3 } from 'cc';
import { Sound } from '../audio/Sound';
import { AudioPreferences } from '../core/AudioState';
import { Theme } from '../core/Theme';
import { art } from '../ui/Art';
import { fillCircle, fillRR, gfx, mk, onTap, paintPanel, setText, strokeRR, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';

export class SoundSettingsPopup extends Popup {
    protected dimBackground = true;
    private readonly refresh = () => { if (!this.closed) this.rebuildBody(); };
    constructor() { super('sound-settings','声音设置',620,740,0,true); void Sound.refresh(); }
    mount(layer: Node): void {
        super.mount(layer);
        paintPanel(this.panel,this.pw,this.ph,{fill:'#FFF9EB',r:42,stroke:'#DCAE5D',strokeW:4,shadow:5,shadowColor:'#B77D34'});
        strokeRR(gfx(this.panel),7,7,this.pw-14,this.ph-14,36,'#F3D499',2);
    }
    protected buildTitle(): void { text(this.panel,'声音设置',90,28,this.pw-180,70,44,'#101A50',{bold:true}); }
    protected buildBody(p: Node,w: number): void {
        const close = mk(p,'CloseSound',w-80,24,56,56);text(close,'×',0,0,56,56,48,'#101A50',{bold:true});onTap(close,()=>this.close(),false);
        this.row(p,28,126,w-56,'music','背景音乐','♫');
        this.row(p,28,342,w-56,'effects','游戏音效','♪');
        text(p,'本机设置自动保存',40,576,w-80,42,27,'#A06D33');
        const restore = mk(p,'RestoreSound',100,642,w-200,74);art(restore,'button_flat_blue',0,0,w-200,74,'capsule');
        text(restore,'恢复默认',0,0,w-200,68,34,'#FFFFFF',{bold:true});onTap(restore,()=>Sound.reset());
        Sound.watch(this.refresh);
    }
    private row(p: Node,x: number,y: number,w: number,kind: 'music'|'effects',caption: string,glyph: string): void {
        const s=Sound.state, config=s?.catalog?.settings, values=s?.values;
        const available=kind==='music'?!!config?.musicEnabled:!!config?.effectsEnabled && Object.values(config.effects).some(Boolean);
        const enabled=available && (kind==='music'?!!values?.musicEnabled:!!values?.effectsEnabled);
        const volume=kind==='music'?(values?.musicVolume??0):(values?.effectsVolume??0);
        const row=mk(p,'Sound:'+kind,x,y,w,194);paintPanel(row,w,194,{fill:'#FFFBF0',r:28,stroke:'#F0E4CB',strokeW:2});
        const icon=mk(row,'SoundIcon',24,26,70,70);fillCircle(gfx(icon),35,35,35,available?'#FFE3A1':'#E8E2D6');
        text(icon,glyph,0,0,70,70,40,available?'#101A50':'#9E9A92',{bold:true});
        text(row,caption,112,24,w-260,72,32,available?'#101A50':'#9E9A92',{bold:true,align:'l'});
        const toggle=mk(row,'Toggle',w-126,30,100,58),tg=gfx(toggle);
        fillRR(tg,0,0,100,58,29,enabled?'#2298FF':'#CBC5BA');fillCircle(tg,enabled?71:29,29,24,'#FFFEFA');
        onTap(toggle,()=>{if(available)Sound.setPreferences(kind==='music'?{musicEnabled:!enabled}:{effectsEnabled:!enabled});});
        const sliderW=w-180, slider=mk(row,'Volume',32,114,sliderW,64),sg=gfx(slider);
        const amount=text(row,available?volume+'%':'暂未开放',w-132,114,118,64,available?28:23,enabled?'#101A50':'#A6A094',{bold:true});
        const draw=(value: number)=>{
            sg.clear();fillRR(sg,0,24,sliderW,16,8,'#DED7CA');if(enabled&&value>0)fillRR(sg,0,24,sliderW*value/100,16,8,'#2298FF');
            const cx=sliderW*value/100;fillCircle(sg,cx,34,21,'#D6CAB5');fillCircle(sg,cx,31,21,'#FFFEFA');
            if(available)setText(amount,value+'%');
        };
        draw(volume);
        // A single gesture updates the local value immediately; saving never waits for a network request.
        let selected=volume;
        const update=(event: {getUILocation():{x:number;y:number}})=>{
            if(!enabled)return;
            const pointer=event.getUILocation();
            // getUILocation starts at the design viewport's lower-left; our Canvas world origin is its centre.
            const pos=slider.getComponent(UITransform)!.convertToNodeSpaceAR(new Vec3(pointer.x-Theme.W/2,pointer.y-Theme.H/2,0));
            selected=Math.round(Math.max(0,Math.min(100,pos.x/sliderW*100)));draw(selected);
            Sound.setPreferences(kind==='music'?{musicVolume:selected}:{effectsVolume:selected},false);
        };
        slider.on(Node.EventType.TOUCH_START,update);slider.on(Node.EventType.TOUCH_MOVE,update);
        const save=()=>{if(enabled){const value:AudioPreferences=kind==='music'?{musicVolume:selected}:{effectsVolume:selected};Sound.setPreferences(value);}};
        slider.on(Node.EventType.TOUCH_END,save);slider.on(Node.EventType.TOUCH_CANCEL,save);
        slider.addComponent(BlockInputEvents);
    }
    close(): void { Sound.unwatch(this.refresh);super.close(); }
}
