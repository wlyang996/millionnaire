/**
 * 页面 5：虎口拔牙。牙齿数 = 参与人数 × 2；每颗牙独立可点，已按压牙变暗；危险牙从外观上无法识别
 * （演示里危险牙由客户端随机，真实由服务端固定且结束前不公开）。每次选择 10 秒（截止时间驱动），
 * 超时由系统随机代选；触发闭合者输，其余玩家各获 500，输家不扣钱。
 */
import { Label, Node } from 'cc';
import { Countdown } from '../core/Clock';
import { ME } from '../core/MockStore';
import { PlayerView } from '../core/Models';
import { MINIGAME_REWARD, SECONDS, toothCount } from '../core/Rules';
import { Theme } from '../core/Theme';
import { IconButton } from '../ui/Buttons';
import { ctx } from '../ui/Ctx';
import { drawBack, drawChat, drawCoin, drawMic } from '../ui/Icons';
import { fillCircle, fillRR, gfx, mk, onTap, setText, text } from '../ui/Kit';
import { ChatPopup } from '../popups/ChatPopup';
import { Screen } from '../ui/Screen';
import { Toast } from '../ui/Toast';
import { avatar, CountdownBadge } from '../ui/Widgets';

type Phase = 'picking' | 'closed';

export class TeethScreen extends Screen {
    readonly id = 'teeth' as const;
    readonly title = '虎口拔牙';
    private participants: PlayerView[] = [];
    private pressed: boolean[] = [];
    private danger = 0;
    private turn = 0;
    private phase: Phase = 'picking';
    private cd = new Countdown(ctx.clock);
    private ring: CountdownBadge | null = null;
    private turnLabel: Label | null = null;
    private nextAutoAt = 0;
    private endAt = 0;
    private inited = false;
    private teethNodes: Node[] = [];
    private loser: PlayerView | null = null;

    onShow(): void {
        this.inited = false;
    }

    private init(): void {
        const st = ctx.store;
        this.participants = st.game.players.filter((p) => p.life === 'ALIVE');
        // 从触发者（当前回合玩家）起按顺序轮流
        const start = Math.max(0, this.participants.findIndex((p) => p.playerId === st.game.currentPlayer));
        this.participants = this.participants.slice(start).concat(this.participants.slice(0, start));
        const n = toothCount(this.participants.length);
        this.pressed = new Array<boolean>(n).fill(false);
        this.danger = Math.floor(Math.random() * n);
        this.turn = 0;
        this.phase = 'picking';
        this.loser = null;
        this.cd.start(SECONDS.tooth);
        this.nextAutoAt = 0;
        this.inited = true;
    }

    protected build(): void {
        if (!this.inited) this.init();
        this.backdrop('sky');
        new IconButton(this.root, 80, 24, 60, '', () => ctx.screens.back('board'), Theme.c.ivory, Theme.c.ink, (g, s) => drawBack(g, s / 2, s / 2, s * 0.6, Theme.c.ink));
        const hd = mk(this.root, 'Header', 150, 24, 360, 60);
        text(hd, '虎口拔牙', 0, 0, 360, 60, Theme.font.lg, Theme.c.ink, { bold: true });

        // 参与者
        this.participants.forEach((p, i) => {
            const cx = 20 + (i % 4) * 172;
            const cy = 100 + Math.floor(i / 4) * 124;
            const cur = i === this.turn && this.phase === 'picking';
            const cell = mk(this.root, 'Pl' + i, cx, cy, 160, 116);
            avatar(cell, 40, 0, 80, p.avatar, p.nickname, { ring: cur ? Theme.c.yellow : undefined });
            text(cell, p.nickname, 0, 84, 160, 28, Theme.font.sm, Theme.c.ink, { bold: true });
        });

        // 轮到谁 + 倒计时
        const bar = mk(this.root, 'Turn', 120, 358, 480, 76);
        fillRR(gfx(bar), 0, 4, 480, 72, 36, Theme.c.shadow);
        fillRR(gfx(bar), 0, 0, 480, 72, 36, Theme.c.ivory);
        this.turnLabel = text(bar, '', 24, 0, 330, 72, Theme.font.lg, Theme.c.ink, { bold: true, align: 'l' });
        this.ring = new CountdownBadge(bar, 480 - 150, 12, 130, 48);

        this.drawCroc();

        // 提示面板
        const info = mk(this.root, 'Info', 100, 1050, 520, 112);
        fillRR(gfx(info), 0, 4, 520, 108, 26, Theme.c.shadow);
        fillRR(gfx(info), 0, 0, 520, 108, 26, Theme.c.ivory);
        text(info, '请选择一颗牙齿', 0, 8, 520, 52, Theme.font.lg, Theme.c.ink, { bold: true });
        text(info, '其余玩家各获得', 120, 58, 190, 40, Theme.font.sm, Theme.c.inkSoft, { align: 'r' });
        drawCoin(gfx(mk(info, 'C', 320, 60, 36, 36)), 18, 18, 15);
        text(info, String(MINIGAME_REWARD), 362, 58, 120, 40, Theme.font.lg, Theme.c.yellowDark, { bold: true, align: 'l' });

        // 语音 / 聊天
        new IconButton(this.root, 24, 1190, 72, '', () => Toast.show('麦克风已开启（演示）'), Theme.c.ivory, Theme.c.blueDark, (g, s) => drawMic(g, s / 2, s / 2, s * 0.6, Theme.c.blueDark));
        new IconButton(this.root, 624, 1190, 72, '', () => ctx.popups.open(new ChatPopup()), Theme.c.ivory, Theme.c.blueDark, (g, s) => drawChat(g, s / 2, s / 2, s * 0.62, Theme.c.blueDark));
        this.tickTexts();
    }

    private drawCroc(): void {
        const wrap = mk(this.root, 'Croc', 0, 450, Theme.W, 580);
        const g = gfx(wrap);
        // 头部
        fillRR(g, 40, 30, 640, 520, 240, '#4FAE45');
        fillRR(g, 56, 40, 608, 500, 230, '#63C957');
        // 眼睛
        for (const ex of [170, 550]) {
            fillCircle(g, ex, 40, 62, '#4FAE45');
            fillCircle(g, ex, 36, 50, '#FFFFFF');
            fillCircle(g, ex + (ex < 360 ? 10 : -10), 44, 22, '#2D3B4A');
            fillCircle(g, ex + (ex < 360 ? 16 : -4), 36, 7, '#FFFFFF');
        }
        // 鼻孔
        fillCircle(g, 300, 110, 9, '#3F8E39');
        fillCircle(g, 420, 110, 9, '#3F8E39');
        // 口腔
        fillRR(g, 78, 150, 564, 360, 150, '#7A2432');
        fillRR(g, 130, 270, 460, 210, 100, '#E56B7A');
        fillRR(g, 150, 290, 420, 120, 60, '#F28A98');
        this.drawTeeth(wrap);
    }

    private drawTeeth(wrap: Node): void {
        const n = this.pressed.length;
        const top = Math.ceil(n / 2);
        const bottom = n - top;
        this.teethNodes = [];
        const place = (idx: number, count: number, isTop: boolean, k: number) => {
            const x0 = 130;
            const x1 = 590;
            const gap = (x1 - x0) / Math.max(1, count - 1 || 1);
            const cx = count === 1 ? 360 : x0 + k * gap;
            const tw = Math.min(54, gap - 8 || 54);
            const th = 66;
            const pressed = this.pressed[idx];
            const dx = (cx - 360) / 230;
            const baseY = isTop ? 160 + dx * dx * 22 : 500 - th - dx * dx * 22;
            const y = pressed ? baseY + (isTop ? -14 : 14) : baseY;
            const t = mk(wrap, 'Tooth' + idx, cx - tw / 2, y, tw, th);
            const g = gfx(t);
            fillRR(g, 0, 4, tw, th, 14, '#00000030');
            fillRR(g, 0, 0, tw, th, 14, pressed ? '#9AA3AD' : '#FFFFFF');
            fillRR(g, 5, 5, tw - 10, th * 0.35, 8, pressed ? '#868F99' : '#F1EEE2');
            this.teethNodes[idx] = t;
            onTap(t, () => this.pick(idx, ME), false);
        };
        for (let i = 0; i < top; i++) place(i, top, true, i);
        for (let j = 0; j < bottom; j++) place(top + j, bottom, false, j);
    }

    private pick(idx: number, by: string): void {
        if (this.phase !== 'picking') return;
        const cur = this.participants[this.turn];
        if (cur.playerId !== by) return Toast.show('还没轮到你');
        if (this.pressed[idx]) return Toast.show('这颗牙已经被按过了');
        this.pressed[idx] = true;
        if (idx === this.danger) {
            this.phase = 'closed';
            this.loser = cur;
            Toast.show('咔嚓！' + cur.nickname + ' 触发闭合，其余玩家各获 ' + MINIGAME_REWARD);
            for (const p of this.participants) if (p.playerId !== cur.playerId) p.cash += MINIGAME_REWARD;
            this.endAt = Date.now() + 2600;
        } else {
            this.turn = (this.turn + 1) % this.participants.length;
            this.cd.start(SECONDS.tooth);
            this.nextAutoAt = Date.now() + 1300;
        }
        this.rebuild();
    }

    private randomFree(): number {
        const free = this.pressed.map((v, i) => (v ? -1 : i)).filter((i) => i >= 0);
        return free[Math.floor(Math.random() * free.length)];
    }

    private tickTexts(): void {
        if (!this.turnLabel) return;
        const cur = this.participants[this.turn];
        if (this.phase === 'closed') setText(this.turnLabel, (this.loser ? this.loser.nickname : '') + ' 触发闭合！', Theme.c.red);
        else setText(this.turnLabel, '轮到' + (cur.playerId === ME ? '我' : cur.nickname), Theme.c.ink);
        this.ring?.update(this.cd);
    }

    tick(_dt: number): void {
        if (!this.inited) return;
        this.tickTexts();
        const now = Date.now();
        if (this.phase === 'closed') {
            if (now >= this.endAt) {
                this.inited = false;
                ctx.store.emit();
                ctx.screens.back('board');
            }
            return;
        }
        const cur = this.participants[this.turn];
        // 他人回合：模拟其选择；超时（含我自己）由系统随机代选
        if (cur.playerId !== ME && this.nextAutoAt && now >= this.nextAutoAt && ctx.store.autoplay !== false) {
            this.nextAutoAt = 0;
            this.pick(this.randomFree(), cur.playerId);
            return;
        }
        if (cur.playerId !== ME && !this.nextAutoAt) this.nextAutoAt = now + 1500;
        if (this.cd.consumeExpire()) {
            Toast.show(cur.nickname + ' 超时，系统随机代选');
            this.pick(this.randomFree(), cur.playerId);
        }
    }

    refresh(): void {
        // 对局数据变化不重置小游戏进度
        this.rebuild();
    }
}
