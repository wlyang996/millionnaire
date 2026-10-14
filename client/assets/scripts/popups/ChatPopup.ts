/**
 * 聊天：最近消息 + 输入框 + 快捷短语。
 * 联机时经服务端聊天通道发送（房间内所有人含观战者都能看到）；演示模式只写本地。
 */
import { EMOTES,EmoteId } from '../core/Emotes';
import { EXPERIENCE } from '../core/Rules';
import { Node } from 'cc';
import { ChatLine } from '../core/Models';
import { Theme } from '../core/Theme';
import { primaryButton } from '../ui/Buttons';
import { EditField } from '../ui/EditField';
import { avatar } from '../ui/Widgets';
import { ctx } from '../ui/Ctx';
import { destroyChildren, fillRR, gfx, mk, onTap, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { Toast } from '../ui/Toast';

const PHRASES = ['好～', '稳住！', '哈哈', '加油', '手下留情', '借我点钱', '你太强了', '再来一局'];
const MAX_CHARS = 40;

export class ChatPopup extends Popup {
    private sending = false;
    private emoteSending=false;private nextEmoteAt=0;
    private lines!: Node;
    private field: EditField | null = null;
    /** 上次画的是哪份列表、多少条（联机时每条推送都是新数组；满 30 条后条数不变，所以两个都比）。 */
    private shownList: ChatLine[] | null = null;
    private shown = -1;
    private readonly watch = () => this.refresh();

    constructor() {
        super('chat', '聊天', 640, 1000, 0, true);
    }

    protected buildTitle(): void {
        text(this.panel, this.title, 80, 20, this.pw - 160, 64, 38, Theme.c.ink, { bold: true });
    }

    protected buildBody(p: Node, w: number, h: number): void {
        void h;
        const close = mk(p, 'Close', w - 76, 20, 56, 56);
        text(close, '❌', 0, 0, 56, 56, 30, Theme.c.red, { bold: true });
        onTap(close, () => this.close(), false);
        this.lines = mk(p, 'Lines', 28, 96, w - 56, 290);
        this.shownList = null;
        this.shown = -1;
        this.refresh();

        // 输入框 + 发送
        const sendW = 132;
        const field = new EditField(p, 28, 402, w - 56 - sendW - 12, 60, '说点什么…（回车发送）', MAX_CHARS, () => undefined);
        field.onEnter = () => this.send(field.value, true);
        this.field = field;
        primaryButton(p, '发送', w - 28 - sendW, 402, sendW, 60, () => this.send(field.value, true), Theme.font.md);

        text(p, '快捷短语（破产观战时同样可用）', 28, 478, w - 56, 36, Theme.font.sm, Theme.c.inkSoft, { align: 'l' });
        PHRASES.forEach((s, i) => {
            const bw = (w - 56 - 12) / 2;
            const b = mk(p, 'Phrase', 28 + (i % 2) * (bw + 12), 520 + Math.floor(i / 2) * 66, bw, 54);
            fillRR(gfx(b), 0, 0, bw, 54, 27, Theme.c.blueSoft);
            text(b, s, 0, 0, bw, 54, Theme.font.sm, Theme.c.blueDark, { bold: true });
            onTap(b, () => this.send(s, false));
        });
        if(EXPERIENCE.emotesEnabled){
            text(p,'快捷表情 · 头像气泡',28,790,w-56,38,26,Theme.c.navy,{bold:true,align:'l'});
            Object.entries(EMOTES).forEach(([id,label],i)=>{
                const bw=(w-80)/3,n=mk(p,'Emote:'+id,28+i%3*(bw+12),842+Math.floor(i/3)*66,bw,54);
                fillRR(gfx(n),0,0,bw,54,18,'#FFF0BE');text(n,label,0,0,bw,54,26,Theme.c.navy,{bold:true});
                onTap(n,()=>this.emote(id as EmoteId));
            });
        }
        ctx.store.offChange(this.watch);
        ctx.store.onChange(this.watch);
    }

    close(): void {
        ctx.store.offChange(this.watch);
        this.field?.blur();
        super.close();
    }

    private emote(id:EmoteId):void{
        const st=ctx.store;
        if(this.emoteSending||Date.now()<this.nextEmoteAt){Toast.show('表情发送太快，请稍后再试');return;}
        if(st.isWatcher()){Toast.show('观战者可查看表情');return;}
        this.emoteSending=true;
        if(st.online)void st.online.emote(id).then(r=>{if(r.ok)this.nextEmoteAt=Date.now()+EXPERIENCE.emoteCooldownMs;})
            .finally(()=>this.emoteSending=false).catch(()=>Toast.show('表情未发送，请重试'));
        else{
            this.nextEmoteAt=Date.now()+EXPERIENCE.emoteCooldownMs;this.emoteSending=false;
            st.emotes.receive({playerId:st.myId,id,sequence:Date.now(),endsAt:Date.now()+EXPERIENCE.emoteVisibleMs},Date.now());st.emit();
        }
    }
    private send(raw: string, fromField: boolean): void {
        const s = raw.trim();
        if (!s) return;
        const st = ctx.store;
        if (st.online) {
            if (this.sending) return;
            this.sending=true;
            void st.online.say(s).then(result=>{
                if(result.ok && fromField && this.field?.value.trim()===s) this.field.value='';
            }).catch(()=>Toast.show('消息未发送，请稍后重试')).finally(()=>{this.sending=false;});
            return; // 服务端推回完整列表后刷新
        }
        if (fromField && this.field) this.field.value = '';
        st.game.chat.push({ from: st.me().nickname, text: s });
        st.emit();
    }

    /** 只重画消息区（输入框保持不动，正在输入的文字不丢）。 */
    private refresh(): void {
        if (this.closed || !this.lines?.isValid) return;
        const st = ctx.store;
        const all = st.session.game ? st.game.chat : st.roomChat;
        if (all === this.shownList && all.length === this.shown) return;
        this.shownList = all;
        this.shown = all.length;
        const box = this.lines;
        const w = this.pw;
        destroyChildren(box);
        const g = gfx(box);
        g.clear();
        fillRR(g, 0, 0, w - 56, 290, 18, '#FFFFFFAA');
        const lines = all.slice(-4);
        const colors = ['#FFE9EB', Theme.c.blueSoft, '#FFF3D5', Theme.c.greenSoft];
        lines.forEach((l, i) => {
            const player = st.session.game ? st.game.players.find((pl) => pl.nickname === l.from) : undefined;
            const member = st.session.members.find((m) => m.nickname === l.from);
            avatar(box, 14, 12 + i * 68, 52, player?.avatar ?? member?.avatar ?? 0, l.from);
            text(box, l.from + '：', 78, 12 + i * 68, 126, 52, 23, Theme.c.ink, { bold: true, align: 'l' });
            const bubble = mk(box, 'Message', 208, 14 + i * 68, w - 284, 48);
            fillRR(gfx(bubble), 0, 0, w - 284, 48, 16, colors[i]);
            text(bubble, l.text, 12, 0, w - 308, 48, 23, Theme.c.ink, { bold: true, align: 'l' });
        });
        if (lines.length === 0) text(box, '还没有消息', 0, 0, w - 56, 290, Theme.font.sm, Theme.c.inkFaint);
    }
}
