/**
 * 对局记录面板（用户 2026-10-08）：本局发生的买地、租金、事件、道具、抵押、拍卖、交易、入狱、出局等。
 * 按回合分段，最新的回合在上；回合标题下按发生顺序列出。和我有关的行加粗。打开期间有新记录会自动刷新。
 */
import { Node } from 'cc';
import { Theme } from '../core/Theme';
import { LogLine } from '../net/GameLog';
import { ctx } from '../ui/Ctx';
import { fillRR, gfx, mk, onTap, text } from '../ui/Kit';
import { Popup } from '../ui/Popup';
import { ScrollList } from '../ui/ScrollList';

const W = 660;
const H = 1040;
const HEADER_H = 46;
const ROW_H = 66;

export class GameLogPopup extends Popup {
    private list: ScrollList | null = null;
    private shown: LogLine[] | null = null;
    private shownCount = -1;
    private readonly watch = () => this.refresh();

    constructor() {
        super('game-log', '对局记录', W, H, 0, true);
    }

    protected buildTitle(): void {
        text(this.panel, this.title, 80, 20, this.pw - 160, 64, 38, Theme.c.ink, { bold: true });
    }

    protected buildBody(p: Node, w: number, h: number): void {
        const close = mk(p, 'Close', w - 76, 20, 56, 56);
        text(close, '❌', 0, 0, 56, 56, 30, Theme.c.red, { bold: true });
        onTap(close, () => this.close(), false);
        this.list = new ScrollList(p, 20, 96, w - 40, h - 120);
        this.shown = null;
        this.shownCount = -1;
        this.refresh();
        ctx.store.offChange(this.watch);
        ctx.store.onChange(this.watch);
    }

    close(): void {
        ctx.store.offChange(this.watch);
        super.close();
    }

    private refresh(): void {
        const list = this.list;
        if (!list) return;
        const log = ctx.store.gameLog;
        if (log === this.shown && log.length === this.shownCount && (log.length === 0 || log[log.length - 1].seq === this.lastSeq)) return;
        this.shown = log;
        this.shownCount = log.length;
        this.lastSeq = log.length ? log[log.length - 1].seq : 0;
        list.clear();
        const lw = list.w;
        if (!log.length) {
            text(list.content, ctx.store.online ? '还没有记录，开始投骰后这里会列出每一步' : '演示模式没有对局记录', 0, 40, lw, 60,
                Theme.font.md, Theme.c.noteGray, { wrap: true });
            list.setContentHeight(140);
            return;
        }
        // 以回合标题切段，段落倒序（新回合在上），段内保持时间顺序
        const blocks: LogLine[][] = [];
        for (const l of log) {
            if (l.header || !blocks.length) blocks.push([]);
            blocks[blocks.length - 1].push(l);
        }
        const ordered = blocks.reverse().reduce<LogLine[]>((a, b) => a.concat(b), []);
        let y = 0;
        for (const l of ordered) {
            if (l.header) {
                const n = mk(list.content, 'LogHeader', 0, y + 6, lw, HEADER_H - 10);
                fillRR(gfx(n), 0, 0, lw, HEADER_H - 10, 12, Theme.c.blueSoft);
                text(n, l.text, 16, 0, lw - 32, HEADER_H - 10, 22, Theme.c.blueDark, { bold: true, align: 'l' });
                y += HEADER_H;
                continue;
            }
            const n = mk(list.content, 'LogLine', 0, y, lw, ROW_H);
            if (l.mine) fillRR(gfx(n), 0, 4, lw, ROW_H - 8, 10, '#FFF6DA');
            text(n, l.text, 16, 2, lw - 32, ROW_H - 4, 23, l.mine ? Theme.c.ink : Theme.c.inkSoft,
                { bold: !!l.mine, align: 'l', wrap: true, lineHeight: 29 });
            y += ROW_H;
        }
        list.setContentHeight(y + 12);
    }

    private lastSeq = 0;
}
